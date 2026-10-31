"""Real loopback HTTP fixtures validate the runner, not upstream services."""
import base64
import copy
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse

from flow import Flow, write_report
from gateway import Failure, Gateway, validate_config

ROOT = Path(__file__).parent
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII=")
ENV = {"FCOURT_E2E_SUPERADMIN_EMAIL": "super@fixture.invalid", "FCOURT_E2E_SUPERADMIN_PASSWORD": "super-secret-password",
       "FCOURT_E2E_CUSTOMER_EMAIL": "customer@fixture.invalid", "FCOURT_E2E_CUSTOMER_PASSWORD": "customer-secret-password",
       "FCOURT_E2E_CUSTOMER_PHONE": "0901234567", "FCOURT_E2E_OWNER_PASSWORD": "owner-secret-password",
       "FCOURT_E2E_STAFF_PASSWORD": "staff-secret-password"}


class Server(ThreadingHTTPServer):
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self): self.dispatch()
    def do_POST(self): self.dispatch()
    def do_PUT(self): self.dispatch()

    def dispatch(self):
        s = self.server.state
        route = None
        for name, item in s["config"]["routes"].items():
            import re
            pattern = re.sub(r"\{\w+\}", r"([^/?]+)", item["path"].split("?")[0])
            if self.command == item["method"] and re.fullmatch(pattern, self.path.split("?")[0]):
                route = name; break
        size = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(size)
        body = json.loads(raw) if raw and "application/json" in self.headers.get("Content-Type", "") else {}
        s["calls"].append((route, self.command, self.headers.get("Authorization"), self.headers.get("Idempotency-Key")))
        code, result = 200, {}
        expected_role = {"approveOwner":"super", "createBranch":"owner", "createCourt":"owner", "createSlot":"owner",
                         "createStaff":"owner", "confirm":"owner", "reject":"owner", "hold":"customer", "createBooking":"customer",
                         "booking":"customer", "checkout":"customer", "receipt":"customer", "ticket":"customer", "verify":"staff"}.get(route)
        if expected_role and self.headers.get("Authorization") != "Bearer token-secret-"+expected_role:
            code = 401
        elif s.get("redirect") == route:
            self.send_response(302); self.send_header("Location", "/credential-leak"); self.end_headers(); return
        elif s.get("error") == route:
            code, result = 503, {"private":"bank-private-value token-secret-customer"}
        elif route == "registerOwner": result, code = {"id":101}, 201
        elif route == "login":
            email = body["email"]
            role = "super" if email.startswith("super@") else "customer" if email.startswith("customer@") else "staff" if "staff" in email else "owner"
            result = {"accessToken":"token-secret-"+role}
        elif route == "createBranch": s["branch"] = body; result, code = {"id":201}, 201
        elif route == "createCourt": result, code = {"id":301}, 201
        elif route == "createSlot": s["templates"].append(body); result, code = {"id":400+len(s["templates"])}, 201
        elif route == "createStaff": result, code = {"id":601}, 201
        elif route == "search": result = {"content":[{"id":201,"courtCount":1}]}
        elif route == "slots":
            result = [{"slotId":401+i,"startTime":row["startTime"],"status":s["slots"][i]} for i,row in enumerate(s["templates"])]
        elif route == "hold":
            slot = body["slotIds"][0]; s["held"] = slot; s["slots"][slot-401] = "HOLD"
            result, code = {"holdToken":"private-hold","slotIds":[slot]}, 201
        elif route == "createBooking":
            index = s["held"]-401; bid = 501+index
            s["bookings"][bid] = {"id":bid,"status":"PENDING_PAYMENT","branchId":201,"date":body["date"]}
            s["slots"][index] = "BOOKED"; result, code = {"id":bid}, 201
        elif route == "booking": result = s["bookings"][int(self.path.rsplit("/",1)[1])]
        elif route == "checkout": result = {"bankAccountNumber":"bank-private-value","amount":50000}
        elif route == "receipt":
            bid = int(self.path.split("/")[-2]); s["bookings"][bid]["status"] = "AWAITING_CONFIRMATION"
            s["uploaded"] += b"Content-Disposition: form-data" in raw and PNG in raw
            result = {"id":bid+200}
        elif route in {"confirm","reject"}:
            bid = int(self.path.split("/")[-2])-200
            s["bookings"][bid]["status"] = "CONFIRMED" if route == "confirm" else "CANCELLED"
            if route == "reject" and not s.get("brokenSaga"): s["slots"][bid-501] = "AVAILABLE"
        elif route == "ticket": result = {"checkInCode":"private-ticket-code"}
        elif route == "verify":
            if body.get("checkInCode") != "private-ticket-code": code = 400
            elif s["bookings"][501]["status"] == "CHECKED_IN": code = 409
            else:
                s["bookings"][501]["status"] = "CHECKED_IN"
                result = {"status":"CHECKED_IN","branchId":201}
        elif route != "approveOwner": code = 404
        payload = json.dumps(result).encode()
        self.send_response(code); self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(payload)))
        self.end_headers(); self.wfile.write(payload)
