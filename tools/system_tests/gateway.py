"""Pinned-origin HTTP client for explicit, isolated cross-service test runs."""
import json
from pathlib import Path
import ssl
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Failure(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def validate_config(config, execute=False):
    if config.get("environment") not in {"local", "test", "staging"}:
        raise Failure("Integration writes require an isolated local/test/staging environment")
    origin = urllib.parse.urlsplit(config.get("baseUrl", ""))
    if not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in {"", "/"}:
        raise Failure("Configure one gateway origin without credentials/path/query")
    local = origin.hostname in {"localhost", "127.0.0.1", "::1"}
    if origin.scheme != "https" and not (origin.scheme == "http" and local):
        raise Failure("Gateway requires verified HTTPS, except loopback fixtures")
    if execute and "replace" in origin.hostname.lower():
        raise Failure("Real gateway URL has not been supplied")
    if config.get("scope") in {"fixture", "local_cluster"} and not local:
        raise Failure("Fixture scope is restricted to loopback")
    if config.get("scope") not in {"fixture", "local_cluster", "live_gateway"}:
        raise Failure("Specify fixture, local_cluster or live_gateway scope")
    if execute and config["scope"] == "live_gateway" and "REPLACE_" in json.dumps(config):
        raise Failure("Replace all gateway, venue, bank and controlled-account placeholders")
    for route in config["routes"].values():
        path = route.get("path", "")
        if not path.startswith("/") or path.startswith("//") or route.get("method") not in {"GET", "POST", "PUT"}:
            raise Failure("Invalid pinned-origin test route")
    if not 0 < config.get("pollTimeoutSeconds", 30) <= 60 or not 0 < config.get("pollIntervalSeconds", 0.5) <= 5:
        raise Failure("Poll deadlines/intervals must be bounded")
    return config


class Gateway:
    def __init__(self, config, run_id, receipt):
        self.config, self.run_id = validate_config(config, True), run_id
        self.receipt = Path(receipt).read_bytes()
        if not 0 < len(self.receipt) <= 5*1024*1024 or not (self.receipt.startswith(b"\x89PNG\r\n\x1a\n") or self.receipt.startswith(b"\xff\xd8\xff")):
            raise Failure("Provide an explicit PNG/JPEG test receipt up to 5 MiB")
        document = config.get("legalDocumentFile")
        if config["scope"] == "fixture" and (not document or "REPLACE_" in document):
            self.document = self.receipt
        else:
            try:
                self.document = (Path(receipt).parent / document).read_bytes()
            except (OSError, TypeError):
                raise Failure("Provide an explicit authorized owner test document") from None
        if not 0 < len(self.document) <= 5*1024*1024 or not self.document.startswith((b"\x89PNG\r\n\x1a\n", b"\xff\xd8\xff", b"%PDF-")):
            raise Failure("Owner document must be PNG/JPEG/PDF up to 5 MiB")
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def request(self, name, body=None, token=None, bindings=None, expected=(200,), upload=False):
        bindings = bindings or {}
        route = self.config["routes"][name]
        path = route["path"].format(**bindings)
        if not path.startswith("/") or path.startswith("//"):
            raise Failure("Resolved route left gateway scope")
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = None
        if route["method"] != "GET":
            key = uuid.uuid5(uuid.NAMESPACE_URL, self.run_id+":"+name+":"+json.dumps(bindings, sort_keys=True))
            headers["Idempotency-Key"] = str(key)
        if upload:
            boundary = "fcourt-fixture-" + uuid.uuid4().hex
            fields = []
            owner = upload == "owner"
            if owner:
                import re
                for field, value in (body or {}).items():
                    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", field): raise Failure("Invalid multipart field")
                    fields.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"\r\n\r\n{value}\r\n').encode())
            content = self.document if owner else self.receipt
            mime = "application/pdf" if content.startswith(b"%PDF-") else "image/png" if content.startswith(b"\x89PNG") else "image/jpeg"
            field = "legalDocuments" if owner else "file"
            fields.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="fixture-upload"\r\nContent-Type: {mime}\r\n\r\n').encode()+content+b"\r\n")
            data = b"".join(fields)+(f"--{boundary}--\r\n").encode()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.config["baseUrl"].rstrip("/")+path, data=data, headers=headers, method=route["method"])
        try:
            response = self.opener.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        except (urllib.error.URLError, TimeoutError, OSError):
            raise Failure(f"Gateway connection failed during {name}") from None
        with response:
            status = response.code
            raw = response.read(2*1024*1024+1)
        if status not in expected:
            raise Failure(f"{name}: HTTP {status}, expected {expected}")
        if len(raw) > 2*1024*1024:
            raise Failure(f"{name}: response exceeded test bound")
        if not raw or status >= 400:
            return {}
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError):
            raise Failure(f"{name}: invalid JSON response") from None
