"""Read-only, verified-TLS production probes; health does not imply acceptance."""
import argparse
import http.client
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

SERVICES = {"auth-service", "subscription-service", "facility-service", "reporting-service", "discovery-service",
            "voucher-service", "slot-hold-engine", "booking-service", "payment-service", "staff-operations-service",
            "pos-retail-service", "notification-service"}
EXTERNAL = {"deployed-version-inventory":"Supply deployed commit/image versions for every service",
            "business-flow-and-settlement":"Run SCRUM-91 against the authorized release environment",
            "real-vietqr":"Authorized bank-account/QR and real transfer acceptance unavailable",
            "google-oauth":"Requires configured OAuth app and authorized account flow",
            "fcm-device":"Requires test device, push token and notification service",
            "android-camera-release":"Separate Flutter repo/device validation",
            "4g-5g-device-network":"Requires physical device network evidence",
            "production-logs":"Inspect authorized logs for unhandled exceptions/correlation IDs"}


class Failure(RuntimeError): pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url): return None


def path(value):
    if not isinstance(value,str) or not value.startswith("/") or value.startswith("//") or "\\" in value or any(ord(c)<32 for c in value):
        raise Failure("Probe paths must remain relative to one origin")
    return value


def validate(config, execute=False):
    origin = urllib.parse.urlsplit(config.get("baseUrl", ""))
    if not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in {"", "/"}:
        raise Failure("Supply one production origin without credentials/path/query")
    fixture = config.get("scope") == "fixture"
    loopback = origin.hostname in {"localhost","127.0.0.1","::1"}
    if config.get("scope") not in {"fixture","production"} or fixture and not loopback:
        raise Failure("Declare production scope or a loopback-only fixture")
    if origin.scheme != "https" and not (fixture and loopback and origin.scheme == "http"):
        raise Failure("Production requires verified HTTPS")
    if execute and "replace" in origin.hostname.lower(): raise Failure("Production URL has not been supplied")
    if set(config.get("healthPaths",{})) != SERVICES: raise Failure("Map exactly all twelve service health routes")
    for value in config["healthPaths"].values(): path(value)
    if type(config.get("samples")) is not int or not 1 <= config["samples"] <= 10: raise Failure("Samples must be 1..10")
    if type(config.get("timeoutSeconds")) not in {int,float} or not 0 < config["timeoutSeconds"] <= 10: raise Failure("Timeout must be >0 and <=10 seconds")
    if type(config.get("latencyBudgetMs")) not in {int,float} or not 0 < config["latencyBudgetMs"] <= 60000: raise Failure("Set a positive latency budget <=60000ms")
    if config.get("protectedPath"): path(config["protectedPath"])
    if config.get("corsOrigin"):
        cors = urllib.parse.urlsplit(config["corsOrigin"])
        if cors.scheme not in {"http","https"} or not cors.hostname or cors.username or cors.password or cors.query or cors.fragment or cors.path not in {"","/"}:
            raise Failure("Configure a valid browser origin")
    return config


class Probes:
    def __init__(self, config, env):
        self.config = validate(config,True)
        self.token = None
        if config.get("tokenEnv"):
            self.token = env.get(config["tokenEnv"])
            if not self.token: raise Failure("Missing authorized health-probe token environment variable")
        self.opener = urllib.request.build_opener(NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def get(self, relative, authenticated=True, origin=False):
        headers = {"Accept":"application/json"}
        if authenticated and self.token: headers["Authorization"] = "Bearer "+self.token
        if origin: headers["Origin"] = self.config["corsOrigin"]
        request = urllib.request.Request(self.config["baseUrl"].rstrip("/")+path(relative), headers=headers, method="GET")
        started = time.monotonic()
        try:
            try: response = self.opener.open(request, timeout=self.config["timeoutSeconds"])
            except urllib.error.HTTPError as error: response = error
            with response:
                status, response_headers = response.code, response.headers
                raw = response.read(256*1024+1)
        except (urllib.error.URLError,TimeoutError,OSError,http.client.HTTPException): raise Failure("TLS/network/timeout failure; private details omitted") from None
        elapsed = round((time.monotonic()-started)*1000,2)
        if len(raw)>256*1024: raise Failure("Probe response exceeded bound")
        return status, response_headers, raw, elapsed

    def health(self, relative):
        latencies = []
        for _ in range(self.config["samples"]):
            status, _, raw, elapsed = self.get(relative)
            if status != 200: raise Failure("Health route returned HTTP "+str(status))
            try: body = json.loads(raw)
            except (ValueError,UnicodeError): raise Failure("Health route returned invalid JSON") from None
            if not isinstance(body,dict) or body.get("status") != "UP": raise Failure("Service health is not UP")
            latencies.append(elapsed)
        ordered = sorted(latencies)
        p95 = ordered[math.ceil(.95*len(ordered))-1]
        if p95 > self.config["latencyBudgetMs"]: raise Failure("Observed gateway p95 exceeded configured budget")
        return {"status":"PASS","samples":len(ordered),"p50Ms":ordered[math.ceil(.5*len(ordered))-1],"p95Ms":p95,"maxMs":max(ordered)}

    def run(self):
        report = initial(self.config["scope"])
        for service in sorted(SERVICES):
            try: result = self.health(self.config["healthPaths"][service])
            except Failure as error: result = {"status":"FAIL","detail":str(error)}
            report["checks"][service] = result
        if self.config.get("protectedPath"):
            try:
                status, _, _, _ = self.get(self.config["protectedPath"],authenticated=False)
                if status != 401: raise Failure("Protected route did not reject unauthenticated GET with 401")
                result = {"status":"PASS"}
            except Failure as error: result = {"status":"FAIL","detail":str(error)}
            report["checks"]["unauthenticated-access"] = result
        else: report["checks"]["unauthenticated-access"] = {"status":"NOT_RUN","detail":"No protected GET route supplied"}
        if self.config.get("corsOrigin"):
            try:
                status, headers, _, _ = self.get(self.config["healthPaths"]["auth-service"],origin=True)
                if status != 200 or headers.get("Access-Control-Allow-Origin") != self.config["corsOrigin"]:
                    raise Failure("Gateway did not return the configured browser origin")
                result = {"status":"PASS","detail":"GET response only; preflight not exercised"}
            except Failure as error: result = {"status":"FAIL","detail":str(error)}
            report["checks"]["cors-get"] = result
        else: report["checks"]["cors-get"] = {"status":"NOT_RUN","detail":"No browser origin supplied"}
        report["overall"] = "FAIL" if any(r["status"] == "FAIL" for r in report["checks"].values()) else "PARTIAL"
        report["readOnlyProbesPassed"] = all(report["checks"][s]["status"] == "PASS" for s in SERVICES)
        return report
