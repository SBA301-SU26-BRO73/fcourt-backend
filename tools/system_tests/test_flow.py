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


class FlowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.receipt = Path(self.temp.name)/"receipt.png"; self.receipt.write_bytes(PNG)
        self.config = json.loads((ROOT/"config.example.json").read_text())
        self.config.update(scope="fixture", environment="test", pollTimeoutSeconds=.1, pollIntervalSeconds=.01)
        self.server = Server(("127.0.0.1",0), Handler)
        self.config["baseUrl"] = "http://127.0.0.1:"+str(self.server.server_port)
        self.server.state = {"config":self.config,"calls":[],"templates":[],"slots":["AVAILABLE","AVAILABLE"],"bookings":{},"uploaded":0}
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close); self.addCleanup(self.server.shutdown)
    def run_flow(self, env=None, now=None):
        return Flow(self.config, Gateway(self.config,"fixture-run-1",self.receipt), ENV if env is None else env,
                    now or datetime(2030,1,1,3,tzinfo=timezone.utc)).run()
    def test_complete_workflow_and_saga_over_http(self):
        report = self.run_flow()
        self.assertEqual("PASS", report["overall"], report)
        self.assertFalse(report["liveAcceptance"])
        self.assertGreaterEqual(len(report["steps"]), 28)
        self.assertEqual(2, self.server.state["uploaded"])
        self.assertEqual(["BOOKED","AVAILABLE"], self.server.state["slots"])
        self.assertEqual("CHECKED_IN", self.server.state["bookings"][501]["status"])
        calls = self.server.state["calls"]
        keys = [c[3] for c in calls if c[1] != "GET"]
        self.assertTrue(all(keys)); self.assertEqual(len(keys), len(set(keys)))
        self.assertTrue(all(c[2] is None for c in calls if c[0] in {"search","slots","registerOwner","login"}))
        destination = Path(self.temp.name)/"report.json"; write_report(report,destination)
        rendered = destination.read_text()+destination.with_suffix(".md").read_text()
        for private in list(ENV.values())+["token-secret", "private-ticket-code", "bank-private-value", "private-hold"]:
            self.assertNotIn(private, rendered)
    def test_missing_saga_compensation_fails(self):
        self.server.state["brokenSaga"] = True
        report = self.run_flow()
        self.assertEqual("FAIL",report["overall"])
        self.assertEqual("saga-slot-released",report["steps"][-1]["name"])
    def test_upstream_http_failure_is_redacted(self):
        self.server.state["error"] = "checkout"
        report = self.run_flow()
        self.assertEqual("FAIL",report["overall"])
        self.assertNotIn("bank-private-value",json.dumps(report))
        self.assertIn("HTTP 503",json.dumps(report))
    def test_redirect_never_reaches_target(self):
        self.server.state["redirect"] = "approveOwner"
        report = self.run_flow()
        self.assertEqual("FAIL",report["overall"])
        self.assertFalse(any(c[0] is None for c in self.server.state["calls"]))
    def test_missing_credentials_prevents_all_requests(self):
        self.assertEqual("FAIL",self.run_flow({})["overall"])
        self.assertEqual([],self.server.state["calls"])
    def test_no_future_slot_prevents_all_requests(self):
        report = self.run_flow(now=datetime(2030,1,1,16,tzinfo=timezone.utc))
        self.assertEqual("FAIL",report["overall"]); self.assertEqual([],self.server.state["calls"])
    def test_invalid_receipt_prevents_network(self):
        self.receipt.write_bytes(b"not an image")
        with self.assertRaises(Failure): Gateway(self.config,"fixture",self.receipt)
        self.assertEqual([],self.server.state["calls"])
    def test_invalid_configuration_rejected(self):
        for patch in [{"environment":"production"},{"baseUrl":"http://example.com"},{"baseUrl":"https://user:secret@example.com"},
                      {"baseUrl":"https://example.com/path"},{"baseUrl":"https://example.com","scope":"fixture"},
                      {"pollTimeoutSeconds":120},{"scope":"unknown"}]:
            with self.subTest(patch=patch), self.assertRaises(Failure): validate_config(dict(self.config,**patch),True)
    def test_unpinned_route_rejected(self):
        config = copy.deepcopy(self.config); config["routes"]["login"]["path"] = "//elsewhere.invalid/leak"
        with self.assertRaises(Failure): validate_config(config,True)
    def test_live_placeholders_rejected_before_network(self):
        config = dict(self.config,scope="live_gateway")
        with self.assertRaises(Failure): validate_config(config,True)
        self.assertEqual([],self.server.state["calls"])
    def test_cli_default_does_not_connect(self):
        config = Path(self.temp.name)/"config.json"; config.write_text(json.dumps(self.config))
        report = Path(self.temp.name)/"report.json"
        result = subprocess.run([sys.executable,str(ROOT/"flow.py"),"--config",str(config),"--report",str(report)],capture_output=True)
        self.assertEqual(2,result.returncode); self.assertEqual("NOT_RUN",json.loads(report.read_text())["overall"])
        self.assertEqual([],self.server.state["calls"])
    def test_cli_execute_requires_write_flag(self):
        config = Path(self.temp.name)/"config.json"; config.write_text(json.dumps(self.config))
        report = Path(self.temp.name)/"report.json"
        result = subprocess.run([sys.executable,str(ROOT/"flow.py"),"--config",str(config),"--report",str(report),"--execute"],capture_output=True)
        self.assertEqual(2,result.returncode); self.assertEqual("BLOCKED",json.loads(report.read_text())["overall"])
        self.assertEqual([],self.server.state["calls"])


if __name__ == "__main__": unittest.main()
