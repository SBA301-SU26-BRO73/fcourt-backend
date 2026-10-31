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


class PostgreSQLSeedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # No external port or user volume; only this named ephemeral test container.
        cls.name = "fcourt-seed-test-" + uuid.uuid4().hex[:10]
        result = subprocess.run(["docker", "run", "--detach", "--rm", "--network", "none", "--name", cls.name,
                                 "--label", "fcourt.seed-test=true", "--env", "POSTGRES_PASSWORD=fixture-only-password", "postgres:16-alpine"],
                                capture_output=True, text=True, check=True)
        cls.container = result.stdout.strip(); cls.db = seed.DockerDatabase(cls.container)
        try:
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                result = subprocess.run(["docker", "exec", cls.container, "pg_isready", "-U", "postgres"], capture_output=True)
                if result.returncode == 0: break
                time.sleep(0.2)
            else: raise RuntimeError("PostgreSQL test container did not become ready")
            mapping = {"auth_db":"auth-service", "subscription_db":"subscription-service", "facility_db":"facility-service",
                       "staff_db":"staff-operations-service", "voucher_db":"voucher-service", "payment_db":"payment-service", "booking_db":"booking-service"}
            for db, service in mapping.items():
                cls.db.execute("postgres", f'CREATE DATABASE "{db}";')
                cls.db.execute(db, (ROOT / service / "database/V1__init_schema.sql").read_text(encoding="utf-8"))
            cls.db.execute("staff_db", (ROOT / "staff-operations-service/src/main/resources/db/migration/V2__staff_management.sql").read_text(encoding="utf-8"))
            for service, db in [("auth-service", "auth_db"), ("subscription-service", "subscription_db")]:
                cls.db.execute(db, (ROOT / service / "database/V2__seed_sample_data.sql").read_text(encoding="utf-8"))
        except BaseException:
            cls.cleanup(); raise

    @classmethod
    def cleanup(cls):
        if not cls.name.startswith("fcourt-seed-test-"): raise RuntimeError("Unexpected test container")
        subprocess.run(["docker", "rm", "--force", cls.container], capture_output=True, check=False)

    @classmethod
    def tearDownClass(cls): cls.cleanup()

    def manifest(self, key, base):
        m = copy.deepcopy(EXAMPLE); m["seedKey"] = key; m["idBase"] = base
        return seed.validate(m, True)

    def test_apply_and_rerun_are_idempotent_on_real_service_schemas(self):
        m = self.manifest("test-repeat", 100000)
        result = seed.apply(m, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True)
        self.assertEqual(result["branches"], 3); self.assertEqual(result["courts"], 10)
        self.assertEqual(result["time_slot_templates"], 2240); self.assertEqual(result["bookings"], 30)
        self.assertEqual(seed.apply(m, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True), result)
        self.assertEqual(self.db.execute("booking_db", "SELECT count(*) FROM bookings WHERE id BETWEEN 100000 AND 109999;"), "30")
        self.assertEqual(self.db.execute("auth_db", "SELECT count(*) FROM users WHERE id BETWEEN 1 AND 15;"), "15")
        next_id = int(self.db.execute("auth_db", "SELECT nextval('users_id_seq');"))
        self.assertGreaterEqual(next_id, 110000)

    def test_partial_failure_resumes_without_duplicate_rows(self):
        m = self.manifest("test-resume", 200000)
        class Interrupted:
            def execute(_, db, sql):
                if db == "payment_db": raise seed.SeedError("simulated interruption")
                return self.db.execute(db, sql)
        with self.assertRaises(seed.SeedError): seed.apply(m, PUBLIC_DEMO_HASH, Interrupted(), allow_fixtures=True)
        result = seed.apply(m, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True)
        self.assertEqual(result["booking_slots"], 50)
        self.assertEqual(self.db.execute("auth_db", "SELECT count(*) FROM users WHERE id BETWEEN 200000 AND 209999;"), "9")

    def test_existing_non_seed_row_is_never_overwritten(self):
        m = self.manifest("test-collision", 300000)
        self.db.execute("auth_db", "INSERT INTO users(id,email,password_hash,role,status) OVERRIDING SYSTEM VALUE VALUES(300000,'unrelated@fixture.invalid','fixture','ADMIN','ACTIVE');")
        with self.assertRaises(seed.SeedError): seed.apply(m, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True)
        self.assertEqual(self.db.execute("auth_db", "SELECT email FROM users WHERE id=300000;"), "unrelated@fixture.invalid")

    def test_changed_manifest_or_changed_seed_data_fails_without_resetting_it(self):
        m = self.manifest("test-immutable", 400000); seed.apply(m, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True)
        changed = copy.deepcopy(m); changed["branches"][0]["address"] = "Changed"
        with self.assertRaises(seed.SeedError): seed.apply(changed, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True)
        self.db.execute("booking_db", "UPDATE bookings SET status='CHECKED_IN' WHERE id=400000;")
        with self.assertRaises(seed.SeedError): seed.apply(m, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True)
        self.assertEqual(self.db.execute("booking_db", "SELECT status FROM bookings WHERE id=400000;"), "CHECKED_IN")

    def test_quote_and_dollar_characters_round_trip_as_values_on_postgres(self):
        m = self.manifest("test-quotes", 500000)
        address = "O'Brien $$ ; SELECT 'literal'; --"
        m["branches"][0]["address"] = address
        seed.apply(m, PUBLIC_DEMO_HASH, self.db, allow_fixtures=True)
        self.assertEqual(self.db.execute("facility_db", "SELECT address FROM branches WHERE id=500000;"), address)


if __name__ == "__main__": unittest.main()
