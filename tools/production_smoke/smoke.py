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
