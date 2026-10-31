"""Read-only HTTP fixture coverage; no production host is contacted."""
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest

from smoke import Failure, Probes, SERVICES, validate, write

ROOT = Path(__file__).parent


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        state = self.server.state
        state["requests"].append((self.command,self.path,self.headers.get("Authorization"),self.headers.get("Origin")))
        code, payload = 200, b'{"status":"UP","private":"secret-response-bank"}'
        if self.path == "/private": code = 401 if not self.headers.get("Authorization") else 200
        elif self.path == state.get("badPath"):
            mode = state["mode"]
            if mode == "down": payload = b'{"status":"DOWN"}'
            elif mode == "array": payload = b'[]'
            elif mode == "malformed": payload = b'not-json-secret-response-bank'
            elif mode == "oversize": payload = b'x'*(256*1024+1)
            elif mode == "error": code = 502
            elif mode == "slow": time.sleep(.025)
            elif mode == "redirect":
                self.send_response(302); self.send_header("Location","/leaked-token"); self.end_headers(); return
        self.send_response(code)
        if state.get("cors",True) and self.headers.get("Origin"): self.send_header("Access-Control-Allow-Origin",self.headers["Origin"])
        self.send_header("Content-Length",str(len(payload))); self.end_headers(); self.wfile.write(payload)


class SmokeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.server = ThreadingHTTPServer(("127.0.0.1",0),Handler); self.server.daemon_threads = True
        self.server.state = {"requests":[]}
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.addCleanup(self.server.server_close); self.addCleanup(self.server.shutdown)
        self.config = json.loads((ROOT/"config.example.json").read_text())
        self.config.update(scope="fixture",baseUrl="http://127.0.0.1:"+str(self.server.server_port),samples=2,
                           tokenEnv="PROBE_TOKEN",protectedPath="/private",corsOrigin="https://browser.fixture.invalid")
    def probes(self): return Probes(self.config,{"PROBE_TOKEN":"secret-probe-token"})
    def test_twelve_services_metrics_auth_and_cors(self):
        report = self.probes().run()
        self.assertEqual("PARTIAL",report["overall"])
        self.assertTrue(report["readOnlyProbesPassed"]); self.assertFalse(report["productionReady"])
        for name in SERVICES:
            result = report["checks"][name]
            self.assertEqual("PASS",result["status"]); self.assertEqual(2,result["samples"])
            self.assertLessEqual(result["p50Ms"],result["p95Ms"]); self.assertGreaterEqual(result["p50Ms"],0)
        self.assertTrue(all(row["status"]=="NOT_RUN" for row in report["externalGates"].values()))
        calls = self.server.state["requests"]
        self.assertEqual(26,len(calls)); self.assertTrue(all(c[0]=="GET" for c in calls))
        self.assertIsNone(next(c for c in calls if c[1]=="/private")[2])
        self.assertTrue(all(c[2]=="Bearer secret-probe-token" for c in calls if c[1]!="/private"))
        output = Path(self.temp.name)/"report.json"; write(report,output)
        rendered = output.read_text()+output.with_suffix(".md").read_text()
        self.assertNotIn("secret-probe-token",rendered); self.assertNotIn("secret-response-bank",rendered)
    def test_all_bad_health_responses_fail_and_continue(self):
        first = sorted(SERVICES)[0]; self.server.state["badPath"] = self.config["healthPaths"][first]
        for mode in ["down","array","malformed","oversize","error","redirect"]:
            with self.subTest(mode=mode):
                self.server.state["mode"] = mode
                report = self.probes().run()
                self.assertEqual("FAIL",report["overall"])
                self.assertEqual("FAIL",report["checks"][first]["status"])
                self.assertEqual(11,sum(report["checks"][s]["status"]=="PASS" for s in SERVICES))
                self.assertNotIn("secret-response-bank",json.dumps(report))
        self.assertFalse(any(c[1]=="/leaked-token" for c in self.server.state["requests"]))
    def test_latency_budget_is_enforced(self):
        self.config["latencyBudgetMs"] = 10
        self.server.state.update(badPath=self.config["healthPaths"]["auth-service"],mode="slow")
        result = self.probes().run()
        self.assertEqual("FAIL",result["checks"]["auth-service"]["status"])
        self.assertIn("budget",result["checks"]["auth-service"]["detail"])
    def test_cors_mismatch_fails(self):
        self.server.state["cors"] = False
        self.assertEqual("FAIL",self.probes().run()["checks"]["cors-get"]["status"])
    def test_missing_optional_checks_are_not_pass(self):
        self.config.update(corsOrigin=None,protectedPath=None)
        report = self.probes().run()
        self.assertEqual("NOT_RUN",report["checks"]["cors-get"]["status"])
        self.assertEqual("NOT_RUN",report["checks"]["unauthenticated-access"]["status"])
    def test_network_failure_produces_report(self):
        self.config["baseUrl"] = "http://127.0.0.1:1"
        report = self.probes().run()
        self.assertEqual("FAIL",report["overall"])
        self.assertFalse(report["readOnlyProbesPassed"])
        self.assertEqual(12,sum(report["checks"][s]["status"]=="FAIL" for s in SERVICES))
    def test_missing_token_prevents_network(self):
        with self.assertRaises(Failure): Probes(self.config,{})
        self.assertEqual([],self.server.state["requests"])
    def test_validation_rejects_unsafe_origins(self):
        for patch in [{"scope":"production"},{"baseUrl":"https://user:password@example.com"},{"baseUrl":"https://example.com","scope":"fixture"},
                      {"baseUrl":"https://example.com/path"},{"baseUrl":"https://example.com?token=secret"},{"scope":"unknown"}]:
            with self.subTest(patch=patch),self.assertRaises(Failure): validate(dict(self.config,**patch),True)
    def test_sample_timeout_budget_bounds(self):
        for patch in [{"samples":0},{"samples":11},{"samples":True},{"timeoutSeconds":0},{"timeoutSeconds":11},{"latencyBudgetMs":0}]:
            with self.subTest(patch=patch),self.assertRaises(Failure): validate(dict(self.config,**patch),True)
    def test_all_services_required(self):
        config = copy.deepcopy(self.config); del config["healthPaths"]["booking-service"]
        with self.assertRaises(Failure): validate(config,True)
    def test_invalid_relative_path(self):
        for bad in ["//attacker.invalid/leak","https://attacker.invalid","/\nsecret","/\\attacker.invalid"]:
            config = copy.deepcopy(self.config); config["healthPaths"]["booking-service"] = bad
            with self.subTest(path=bad),self.assertRaises(Failure): validate(config,True)
    def test_cli_dry_run_and_placeholder_preflight(self):
        for execute,expected in [(False,"NOT_RUN"),(True,"BLOCKED")]:
            report = Path(self.temp.name)/"report.json"
            command = [sys.executable,str(ROOT/"smoke.py"),"--config",str(ROOT/"config.example.json"),"--report",str(report)]
            if execute: command.append("--execute")
            result = subprocess.run(command,capture_output=True)
            self.assertEqual(2,result.returncode); self.assertEqual(expected,json.loads(report.read_text())["overall"])
        self.assertEqual([],self.server.state["requests"])


if __name__ == "__main__": unittest.main()
