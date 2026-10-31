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
