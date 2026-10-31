import copy
from datetime import date
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import unittest
import uuid
import contextlib
import io
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import seed

EXAMPLE = json.loads((HERE / "manifest.example.json").read_text(encoding="utf-8"))
PUBLIC_DEMO_HASH = re.search(r"\$2a\$10\$[./A-Za-z0-9]{53}", (ROOT / "auth-service/database/V2__seed_sample_data.sql").read_text(encoding="utf-8")).group()


class ManifestTest(unittest.TestCase):
    def test_fixture_requires_explicit_opt_in(self):
        with self.assertRaises(seed.SeedError):
            seed.validate(copy.deepcopy(EXAMPLE))
        seed.validate(copy.deepcopy(EXAMPLE), True)

    def test_namespace_and_invalid_inputs_rejected(self):
        for field, value in [("idBase", 1), ("idBase", 100001), ("seedKey", "bad'$$"), ("demoDate", "bad"), ("slotPrice", "NaN"), ("slotPrice", "50000.001")]:
            m = copy.deepcopy(EXAMPLE); m[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(seed.SeedError):
                seed.validate(m, True)

    def test_plaintext_password_and_invalid_target_rejected(self):
        with self.assertRaises(seed.SeedError): seed.password_hash("plaintext-password")
        with self.assertRaises(seed.SeedError): seed.DockerDatabase("name;bad")

    def test_rows_have_consistent_refs_totals_slots_and_terminal_states(self):
        m = seed.validate(copy.deepcopy(EXAMPLE), True)
        rows = seed.build_rows(m, PUBLIC_DEMO_HASH)
        bookings = rows["booking_db"]["bookings"]
        payments = {p["id"]: p for p in rows["payment_db"]["payments"]}
        slots = rows["booking_db"]["booking_slots"]
        self.assertEqual(len(bookings), 30); self.assertEqual(len(slots), 50)
        keys = {(s["court_id"], s["booking_date"], s["slot_start"]) for s in slots}
        self.assertEqual(len(keys), 50)
        for booking in bookings:
            payment = payments[booking["payment_id"]]
            self.assertEqual(payment["amount"] + payment["discount_amount"], booking["total_price"])
            actual = [s for s in slots if s["booking_id"] == booking["id"]]
            self.assertEqual(len(actual), 0 if booking["status"] == "CANCELLED" else 2)
            self.assertLessEqual(len(booking["booking_code"]), 10)

    def test_sql_quotes_and_dollar_delimiters_are_data_not_code(self):
        m = copy.deepcopy(EXAMPLE); m["branches"][0]["address"] = "O'Brien $$ ; DROP TABLE users; --"
        rows = seed.build_rows(m, PUBLIC_DEMO_HASH)
        sql = seed.sql_for("facility_db", rows["facility_db"], m, PUBLIC_DEMO_HASH, (15, 3))
        self.assertIn("O''Brien $$", sql)
        self.assertNotIn("DO $$ BEGIN IF (SELECT count(*) FROM (VALUES", sql)

    def test_cli_dry_run_never_connects_and_labels_assets_unverified(self):
        output = io.StringIO()
        with mock.patch.object(seed, "DockerDatabase", side_effect=AssertionError("unexpected connection")), contextlib.redirect_stdout(output):
            self.assertEqual(seed.main(["--manifest", str(HERE / "manifest.example.json"), "--allow-fixture-assets"]), 0)
        result = json.loads(output.getvalue())
        self.assertEqual(result["mode"], "dry-run"); self.assertFalse(result["assetsVerified"])
        self.assertEqual(result["rows"]["bookings"], 30)

    def test_cli_missing_password_fails_before_connection_without_private_fields(self):
        output = io.StringIO()
        with mock.patch.dict(seed.os.environ, {}, clear=True), mock.patch.object(seed, "DockerDatabase", side_effect=AssertionError("unexpected connection")), contextlib.redirect_stderr(output):
            self.assertEqual(seed.main(["--manifest", str(HERE / "manifest.example.json"), "--allow-fixture-assets", "--container", "fixture", "--apply"]), 2)
        self.assertNotIn("DEMO-ACCOUNT", output.getvalue())
