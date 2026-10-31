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
