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


def build_rows(m, hashed_password, plan_limits=(15, 3)):
    base, key = m["idBase"], m["seedKey"]
    day, price = date.fromisoformat(m["demoDate"]), Decimal(str(m["slotPrice"]))
    zone = timezone(timedelta(hours=7))
    stamp = datetime.combine(day, time(6), zone)
    rows = {db: {} for db in ["auth_db", "subscription_db", "facility_db", "staff_db", "voucher_db", "payment_db", "booking_db"]}
    def add(db, table, **values):
        rows[db].setdefault(table, []).append(values)
    for index, role in [(0, "ADMIN"), (1, "STAFF"), (2, "STAFF"), (3, "STAFF")] + [(i, "CUSTOMER") for i in range(10, 15)]:
        add("auth_db", "users", id=base + index, email=f"demo.{key}.{index}@demo.invalid", password_hash=hashed_password,
            phone=f"09{base:010d}{index:02d}", role=role, status="ACTIVE", full_name=f"DEMO {key} {role} {index}",
            created_at=stamp, updated_at=stamp, deleted_at=None)
    add("subscription_db", "subscriptions", id=base, admin_id=base, plan_id=m["subscriptionPlanId"], max_courts=plan_limits[0],
        max_branches=plan_limits[1], start_date=day - timedelta(days=30), end_date=day + timedelta(days=365), created_at=stamp, updated_at=stamp, deleted_at=None)
    for i, branch in enumerate(m["branches"]):
        add("facility_db", "branches", id=base+i, admin_id=base, name=f"DEMO {key} {branch['name']}", address=branch["address"],
            ward=branch["ward"], city=branch["city"], open_time=time(6), close_time=time(22), bank_name=branch["bankName"],
            bank_account_number=branch["bankAccountNumber"], bank_account_name=branch["bankAccountName"], bank_qr_image_url=branch["bankQrImageUrl"],
            status="ACTIVE", slot_duration_minutes=30, created_at=stamp, updated_at=stamp, deleted_at=None)
        add("staff_db", "staff", id=base+i+1, user_id=base+i+1, branch_id=base+i,
            email=f"demo.{key}.{i+1}@demo.invalid", full_name=f"DEMO {key} STAFF {i+1}", phone=f"09{base:010d}{i+1:02d}",
            created_at=stamp, updated_at=stamp, deleted_at=None)
    for court in range(10):
        branch_index = 0 if court < 4 else 1 if court < 7 else 2
        court_type = m["courtTypeIds"]["badminton" if court < 5 else "pickleball"]
        add("facility_db", "courts", id=base+court, branch_id=base+branch_index, name=f"DEMO {key} Court {court+1}", court_type_id=court_type,
            description="Explicit demo dataset", image_url=m["courtImages"][court], status="ACTIVE", created_at=stamp, updated_at=stamp, deleted_at=None)
        for weekday in range(7):
            for slot in range(32):
                start = datetime.combine(day, time(6)) + timedelta(minutes=30*slot)
                add("facility_db", "time_slot_templates", id=base+court*224+weekday*32+slot, court_id=base+court, day_of_week=weekday,
                    start_time=start.time(), end_time=(start+timedelta(minutes=30)).time(), price=price, is_active=True,
                    created_at=stamp, updated_at=stamp, deleted_at=None)
    for i, kind in enumerate(["FIXED", "PERCENTAGE"]):
        add("voucher_db", "vouchers", id=base+i, code=f"DEMO-{key}-{kind}", discount_type=kind,
            discount_value=Decimal(10000 if i == 0 else 10), min_order_value=Decimal(20000), max_discount_value=Decimal(10000),
            usage_limit=100, usage_count=1, start_date=stamp-timedelta(days=1), expiry_date=stamp+timedelta(days=30), active=True,
            branch_id=base, created_at=stamp, updated_at=stamp, deleted_at=None)
    statuses = ["CONFIRMED", "CHECKED_IN", "COMPLETED", "AWAITING_CONFIRMATION", "PENDING_PAYMENT", "CANCELLED"]
    for i in range(30):
        state, court = statuses[i//5], i % 10
        branch_index = 0 if court < 4 else 1 if court < 7 else 2
        booking_day = day-timedelta(days=1) if state == "COMPLETED" else day
        created = datetime.combine(booking_day, time(6), zone)
        start = datetime.combine(booking_day, time(8 + i//10*2), zone)
        total = price*2
        discount = Decimal(10000) if i == 0 else min(total*Decimal("0.10"), Decimal(10000)) if i == 1 else Decimal(0)
        confirmed = state in {"CONFIRMED", "CHECKED_IN", "COMPLETED"}
        payment_state = "CONFIRMED" if confirmed else "REJECTED" if state == "CANCELLED" else "PENDING"
        add("payment_db", "payments", id=base+i, branch_id=base+branch_index, amount=total-discount,
            status=payment_state, confirmed_at=created+timedelta(minutes=5) if confirmed else None,
            confirmed_by=base if confirmed else None, bill_image_url=m["receiptImageUrl"] if confirmed or state == "AWAITING_CONFIRMATION" else None,
            voucher_id=base+i if i < 2 else None, discount_amount=discount, created_at=created, updated_at=created+timedelta(minutes=5))
        add("booking_db", "bookings", id=base+i, court_id=base+court, customer_id=None if i % 5 == 4 else base+10+i%5,
            guest_phone=f"08{base:010d}{i:02d}" if i % 5 == 4 else None, payment_id=base+i, date=booking_day,
            status=state, total_price=total, booking_code=f"D{base36(base)}{i:02d}", checkin_code=f"DS-{base36(base)}-{i:02d}" if confirmed else None,
            checked_in_at=start if state in {"CHECKED_IN", "COMPLETED"} else None,
            completed_at=start+timedelta(hours=1) if state == "COMPLETED" else None, created_at=created,
            updated_at=start+timedelta(hours=1) if state == "COMPLETED" else start if state == "CHECKED_IN" else created+timedelta(minutes=5))
        if state != "CANCELLED":
            for slot in range(2):
                begins = start+timedelta(minutes=30*slot)
                add("booking_db", "booking_slots", id=base+i*2+slot, booking_id=base+i, court_id=base+court,
                    booking_date=booking_day, slot_start=begins.time(), slot_end=(begins+timedelta(minutes=30)).time(),
                    price_at_booking=price, created_at=created, updated_at=created)
    return rows


def sql_for(db, tables, m, hashed_password, plan_limits):
    fingerprint = hashlib.sha256(json_text([m, hashed_password, plan_limits]).encode()).hexdigest()
    base, key = m["idBase"], literal(m["seedKey"])
    sql = ["BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; SET LOCAL TIME ZONE 'UTC'; SET LOCAL standard_conforming_strings=on;",
           "SELECT pg_advisory_xact_lock(hashtextextended('fcourt-demo-bootstrap',0));",
           "CREATE TABLE IF NOT EXISTS public.fcourt_demo_seed_manifests(seed_key varchar(20) PRIMARY KEY, manifest_hash varchar(64) NOT NULL, id_base bigint UNIQUE NOT NULL, demo_date date NOT NULL);",
           f"DO $$ DECLARE saved text; BEGIN SELECT manifest_hash INTO saved FROM fcourt_demo_seed_manifests WHERE seed_key={key};",
           f"IF saved IS NOT NULL AND saved<>{literal(fingerprint)} THEN RAISE EXCEPTION 'Seed manifest changed; use a new key and ID block'; END IF;"]
    for table in tables:
        sql.append(f"IF saved IS NULL AND EXISTS(SELECT 1 FROM public.{table} WHERE id BETWEEN {base} AND {base+9999}) THEN RAISE EXCEPTION 'Seed namespace overlaps existing data'; END IF;")
    sql.extend(["END $$;", f"INSERT INTO fcourt_demo_seed_manifests VALUES({key},{literal(fingerprint)},{base},{literal(m['demoDate'])}) ON CONFLICT(seed_key) DO NOTHING;"])
    for table, values in tables.items():
        columns = list(values[0])
        if any(list(row) != columns for row in values):
            raise SeedError("Inconsistent generated columns")
        tuples = ["("+",".join(literal(row[col]) for col in columns)+")" for row in values]
        identity = "" if table == "vouchers" else " OVERRIDING SYSTEM VALUE"
        sql.append(f"INSERT INTO public.{table}({','.join(columns)}){identity} VALUES"+",".join(tuples)+" ON CONFLICT(id) DO NOTHING;")
        expected = []
        for row in values:
            semantic = {k: v.astimezone(timezone.utc).isoformat() if isinstance(v, datetime)
                        else v.isoformat() if isinstance(v, (date, time)) else float(v) if isinstance(v, Decimal) else v
                        for k, v in row.items()}
            expected.append(f"({row['id']},{literal(json.dumps(semantic, ensure_ascii=False))}::jsonb)")
        sql.append(f"SELECT 1/(CASE WHEN (SELECT count(*) FROM (VALUES {','.join(expected)}) e(id,fields) JOIN public.{table} t ON t.id=e.id AND to_jsonb(t) @> e.fields)={len(values)} THEN 1 ELSE 0 END);")
        sql.append(f"SELECT setval('public.{table}_id_seq',GREATEST((SELECT COALESCE(max(id),1) FROM public.{table}),(SELECT last_value FROM public.{table}_id_seq),{base+9999}),true);")
    return "\n".join(sql+["COMMIT;"])
