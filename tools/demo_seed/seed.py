"""Explicit, resumable administrative demo seeding; never overwrites existing rows."""
from __future__ import annotations

import argparse
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


class SeedError(RuntimeError):
    pass


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, Decimal)):
        return str(value)
    if isinstance(value, (date, datetime, time)):
        value = value.isoformat()
    if "\0" in str(value):
        raise SeedError("NUL is not allowed in seed fields")
    return "'" + str(value).replace("'", "''") + "'"


def json_text(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, default=lambda v: str(v))


def validate(manifest, allow_fixtures=False):
    required = {"seedKey", "idBase", "demoDate", "branches", "courtImages", "receiptImageUrl",
                "courtTypeIds", "subscriptionPlanId", "slotPrice", "demoOnly"}
    if set(manifest) != required:
        raise SeedError("Manifest must contain exactly the documented fields")
    if not re.fullmatch(r"[a-z0-9-]{1,20}", str(manifest["seedKey"])):
        raise SeedError("Invalid seedKey")
    base = manifest["idBase"]
    if type(base) is not int or not 100000 <= base <= 2000000000 or base % 10000:
        raise SeedError("idBase must reserve a free 10000-ID block, aligned at >=100000")
    try:
        day = date.fromisoformat(manifest["demoDate"])
        price = Decimal(str(manifest["slotPrice"]))
    except (ValueError, TypeError, ArithmeticError):
        raise SeedError("Invalid demoDate or slotPrice") from None
    if not price.is_finite() or price < 10000 or price > Decimal("49999999.99") or price.as_tuple().exponent < -2:
        raise SeedError("slotPrice must fit two-slot numeric(10,2) invoices")
    if not 2000 <= day.year <= 2099:
        raise SeedError("demoDate must be between 2000 and 2099")
    if type(manifest["demoOnly"]) is not bool or manifest["demoOnly"] and not allow_fixtures:
        raise SeedError("Fixture assets require explicit --allow-fixture-assets")
    if not isinstance(manifest["branches"], list) or not isinstance(manifest["courtImages"], list) or not isinstance(manifest["courtTypeIds"], dict):
        raise SeedError("Invalid manifest collection types")
    if len(manifest["branches"]) != 3 or len(manifest["courtImages"]) != 10:
        raise SeedError("Exactly 3 branches and 10 court images are required")
    branch_fields = {"name", "address", "ward", "city", "bankName", "bankAccountNumber", "bankAccountName", "bankQrImageUrl"}
    limits = {"name": 110, "address": 255, "ward": 100, "city": 100, "bankName": 100,
              "bankAccountNumber": 50, "bankAccountName": 150, "bankQrImageUrl": 2000}
    for branch in manifest["branches"]:
        if not isinstance(branch, dict) or set(branch) != branch_fields:
            raise SeedError("Invalid branch fields")
        for field, limit in limits.items():
            if not isinstance(branch[field], str) or not branch[field].strip() or len(branch[field]) > limit or "\0" in branch[field]:
                raise SeedError("Invalid branch field: " + field)
        if not manifest["demoOnly"] and not re.fullmatch(r"[0-9]{6,34}", branch["bankAccountNumber"]):
            raise SeedError("Real manifest requires numeric bank account strings, preserving leading zeros")
    assets = manifest["courtImages"] + [manifest["receiptImageUrl"]] + [b["bankQrImageUrl"] for b in manifest["branches"]]
    if any(not isinstance(url, str) or len(url) > 2000 or not re.fullmatch(r"https://res\.cloudinary\.com/[^/\s]+/image/upload/[^\s]+", url) for url in assets):
        raise SeedError("Assets must be explicit HTTPS Cloudinary image delivery URLs")
    if not manifest["demoOnly"] and any("/demo/" in url or "REPLACE" in url.upper() for url in assets):
        raise SeedError("Replace fixture assets before a live demo")
    if set(manifest["courtTypeIds"]) != {"badminton", "pickleball"} or any(type(v) is not int or v <= 0 for v in manifest["courtTypeIds"].values()):
        raise SeedError("Provide positive court type IDs")
    if type(manifest["subscriptionPlanId"]) is not int or manifest["subscriptionPlanId"] <= 0:
        raise SeedError("Provide a positive subscription plan ID")
    return manifest


def password_hash(value):
    if not value or not re.fullmatch(r"\$2[aby]\$(?:1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}", value):
        raise SeedError("Set FCOURT_DEMO_PASSWORD_HASH to a bcrypt hash (cost >=10); no plaintext passwords")
    return value


def base36(value):
    digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    result = ""
    while value:
        value, remainder = divmod(value, 36)
        result = digits[remainder] + result
    return result or "0"
