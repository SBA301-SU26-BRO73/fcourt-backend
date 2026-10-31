"""SCRUM-91 executable gateway workflow; fixture proof is not live acceptance."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import time
import urllib.parse
import uuid

from gateway import Failure, Gateway, validate_config


def require(condition, message):
    if not condition:
        raise Failure(message)


def identifier(data):
    value = data.get("id")
    require(type(value) is int and value > 0, "Response is missing a positive resource ID")
    return value


class Flow:
    def __init__(self, config, client, environment, now=None):
        self.config, self.client, self.env = config, client, environment
        self.now = now or datetime.now(timezone.utc)
        self.tokens = {}
        self.report = {"task": "SCRUM-91", "scope": config["scope"], "runId": client.run_id,
                       "overall": "NOT_RUN", "steps": [], "ids": {}, "liveAcceptance": False}

    def secret(self, name):
        value = self.env.get(name)
        require(isinstance(value, str) and bool(value), "Missing required test credential environment variable")
        return value

    def step(self, name, action):
        begin = time.monotonic()
        try:
            result = action()
        except Failure as error:
            self.report["steps"].append({"name": name, "status": "FAIL", "detail": str(error)})
            raise
        self.report["steps"].append({"name": name, "status": "PASS", "durationMs": round((time.monotonic()-begin)*1000, 2)})
        return result

    def poll(self, name, read, predicate):
        deadline = time.monotonic()+self.config["pollTimeoutSeconds"]
        while True:
            result = read()
            if predicate(result): return result
            if time.monotonic() >= deadline: raise Failure(name + ": expected state not observed before deadline")
            time.sleep(self.config["pollIntervalSeconds"])

    def login(self, role, email, password):
        result = self.client.request("login", {"email": email, "password": password}, bindings={"role": role})
        token = result.get("accessToken")
        require(isinstance(token, str) and bool(token), "Login did not return an access token")
        self.tokens[role] = token

    def run(self):
        try:
            self._run()
            self.report["overall"] = "PASS"
            self.report["liveAcceptance"] = self.config["scope"] == "live_gateway"
        except Failure as error:
            self.report["overall"] = "FAIL"
            if not self.report["steps"] or self.report["steps"][-1]["status"] != "FAIL":
                self.report["steps"].append({"name": "contract-validation", "status": "FAIL", "detail": str(error)})
        except Exception:
            self.report["overall"] = "FAIL"
            self.report["steps"].append({"name": "configuration-or-runtime", "status": "FAIL", "detail": "Invalid configuration or response; sensitive details omitted"})
        return self.report

    def _run(self):
        c, request = self.config, self.client.request
        require(re.fullmatch(r"[a-z0-9-]{1,24}", self.client.run_id), "Invalid run ID")
        creds = c["credentials"]
        # Resolve every credential before the first write; values never enter reports.
        super_email, super_password = self.secret(creds["superAdmin"]["emailEnv"]), self.secret(creds["superAdmin"]["passwordEnv"])
        customer_email, customer_password = self.secret(creds["customer"]["emailEnv"]), self.secret(creds["customer"]["passwordEnv"])
        owner_password, staff_password = self.secret(creds["ownerPasswordEnv"]), self.secret(creds["staffPasswordEnv"])
        phone = self.secret(creds["customerPhoneEnv"])
        require(min(len(owner_password), len(staff_password)) >= 12, "Use strong disposable owner/staff passwords")
        local = self.now.astimezone(timezone(timedelta(hours=7)))
        day = local.date()
        start = local.replace(second=0, microsecond=0)+timedelta(minutes=60)
        start += timedelta(minutes=(-start.minute) % 30)
        opening = datetime.combine(day, datetime.strptime(c["branch"]["openTime"], "%H:%M").time(), local.tzinfo)
        closing = datetime.combine(day, datetime.strptime(c["branch"]["closeTime"], "%H:%M").time(), local.tzinfo)
        start = max(start, opening)
        require(start.date() == day and start+timedelta(hours=1) <= closing, "No two future slots remain today; run within venue hours")
        run_id = self.client.run_id
        domain = c["ownerRegistration"]["emailDomain"]
        owner_email, staff_email = f"e2e-owner-{run_id}@{domain}", f"e2e-staff-{run_id}@{domain}"
        owner_body = {k: v for k, v in c["ownerRegistration"].items() if k != "emailDomain"}
        phone_suffix = str(int(uuid.uuid5(uuid.NAMESPACE_URL, run_id).hex[:8], 16) % 100000000).zfill(8)
        owner_body.update(email=owner_email, password=owner_password, role="ADMIN", phone="09"+phone_suffix)
        owner = self.step("register-owner", lambda: request("registerOwner", owner_body, expected=(201,)))
        owner_id = identifier(owner); self.report["ids"]["ownerId"] = owner_id
        self.step("login-super-admin", lambda: self.login("super", super_email, super_password))
        self.step("approve-owner", lambda: request("approveOwner", {"reason": "isolated integration test"}, self.tokens["super"], {"ownerId": owner_id}))
        self.step("login-owner", lambda: self.login("owner", owner_email, owner_password))
        branch_body = dict(c["branch"]); branch_body["name"] += " " + run_id
        branch = self.step("create-branch", lambda: request("createBranch", branch_body, self.tokens["owner"], expected=(201,)))
        branch_id = identifier(branch); self.report["ids"]["branchId"] = branch_id
        court = self.step("create-court", lambda: request("createCourt", c["court"], self.tokens["owner"], {"branchId": branch_id}, expected=(201,)))
        court_id = identifier(court); self.report["ids"]["courtId"] = court_id
        for index in range(2):
            begins = start+timedelta(minutes=30*index)
            template = {"dayOfWeek": int(day.strftime("%w")), "startTime": begins.strftime("%H:%M"),
                        "endTime": (begins+timedelta(minutes=30)).strftime("%H:%M"), "price": c["slotPrice"]}
            self.step("configure-slot-"+str(index), lambda template=template, index=index: request("createSlot", template, self.tokens["owner"],
                      {"courtId": court_id, "operation": index}, expected=(200, 201)))
        staff_body = {"branchId": branch_id, "email": staff_email, "password": staff_password, "fullName": "E2E staff " + run_id,
                      "phone": "08"+phone_suffix, "shifts": []}
        self.step("provision-staff", lambda: request("createStaff", staff_body, self.tokens["owner"], expected=(201,)))
        self.step("login-staff", lambda: self.login("staff", staff_email, staff_password))
        self.step("login-customer", lambda: self.login("customer", customer_email, customer_password))
        self.step("discover-branch", lambda: self.poll("discovery projection", lambda: request("search", bindings={"query": urllib.parse.quote(branch_body["name"], safe="")}),
                  lambda result: any(row.get("id") == branch_id and row.get("courtCount", 0) >= 1 for row in result.get("content", []))))
        bindings = {"courtId": court_id, "date": day.isoformat()}
        slots = self.step("available-slots", lambda: request("slots", bindings=bindings))
        selected = []
        for i in range(2):
            begins = (start+timedelta(minutes=30*i)).strftime("%H:%M")
            matches = [s for s in slots if str(s.get("startTime", ""))[:5] == begins and s.get("status") == "AVAILABLE"]
            require(len(matches) == 1 and type(matches[0].get("slotId")) is int, "Canonical future slot is not available")
            selected.append(matches[0]["slotId"])
        require(len(set(selected)) == 2, "Slot identities are duplicated")
        def create_booking(index):
            hold = request("hold", {"courtId": court_id, "date": day.isoformat(), "slotIds": [selected[index]], "guestPhone": phone},
                           self.tokens["customer"], {"attempt": index}, expected=(201,))
            require(isinstance(hold.get("holdToken"), str) and set(hold.get("slotIds", [])) == {selected[index]}, "Invalid hold response")
            booking = request("createBooking", {"holdToken": hold["holdToken"], "courtId": court_id, "date": day.isoformat(), "slotIds": [selected[index]]},
                              self.tokens["customer"], {"attempt": index}, expected=(201,))
            return identifier(booking)
        def booking_state(booking_id, status):
            return self.poll("booking " + status, lambda: request("booking", token=self.tokens["customer"], bindings={"bookingId": booking_id}),
                             lambda result: result.get("status") == status and result.get("branchId") == branch_id and result.get("date") == day.isoformat())
        def receipt(booking_id):
            checkout = request("checkout", token=self.tokens["customer"], bindings={"bookingId": booking_id})
            require(isinstance(checkout.get("bankAccountNumber"), str) and checkout.get("amount", 0) > 0, "Invalid authoritative checkout")
            payment = request("receipt", token=self.tokens["customer"], bindings={"bookingId": booking_id}, expected=(200, 201), upload=True)
            return identifier(payment)
        first = self.step("hold-and-book", lambda: create_booking(0)); self.report["ids"]["bookingId"] = first
        self.step("pending-payment", lambda: booking_state(first, "PENDING_PAYMENT"))
        payment = self.step("upload-bill", lambda: receipt(first)); self.report["ids"]["paymentId"] = payment
        self.step("awaiting-confirmation", lambda: booking_state(first, "AWAITING_CONFIRMATION"))
        self.step("confirm-payment", lambda: request("confirm", token=self.tokens["owner"], bindings={"paymentId": payment}))
        self.step("booking-confirmed", lambda: booking_state(first, "CONFIRMED"))
        ticket = self.step("read-ticket", lambda: request("ticket", token=self.tokens["customer"], bindings={"bookingId": first}))
        code = ticket.get("checkInCode"); require(isinstance(code, str) and bool(code), "Ticket lacks check-in code")
        checked = self.step("staff-check-in", lambda: request("verify", {"checkInCode": code}, self.tokens["staff"], {"attempt": "first"}))
        require(checked.get("status") == "CHECKED_IN" and checked.get("branchId") == branch_id, "Check-in did not commit correct branch/state")
        self.step("booking-checked-in", lambda: booking_state(first, "CHECKED_IN"))
        self.step("reject-double-check-in", lambda: request("verify", {"checkInCode": code}, self.tokens["staff"], {"attempt": "double"}, expected=(409,)))
        second = self.step("saga-hold-and-book", lambda: create_booking(1)); self.report["ids"]["cancelledBookingId"] = second
        second_payment = self.step("saga-upload-bill", lambda: receipt(second))
        self.step("saga-awaiting", lambda: booking_state(second, "AWAITING_CONFIRMATION"))
        self.step("saga-reject-payment", lambda: request("reject", {"reason": "isolated integration saga rejection"}, self.tokens["owner"], {"paymentId": second_payment}))
        self.step("saga-cancelled", lambda: booking_state(second, "CANCELLED"))
        self.step("saga-slot-released", lambda: self.poll("cancelled slot release", lambda: request("slots", bindings=bindings),
                  lambda rows: any(s.get("slotId") == selected[1] and s.get("status") == "AVAILABLE" for s in rows)
                  and any(s.get("slotId") == selected[0] and s.get("status") == "BOOKED" for s in rows)))
