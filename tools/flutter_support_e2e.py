"""Disposable five-service contract smoke. Never targets an existing database.

Requires Windows PowerShell, Docker, Java 21, and built staff/auth/booking jars from
their support PRs. Discovery/JWKS are local fixtures; six HTTP apps and
their PostgreSQL databases are real. No production integration is claimed.
"""
import argparse
import datetime as dt
import http.server
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for service in ("staff", "auth", "booking", "facility", "hold", "pos"):
        parser.add_argument(f"--{service}-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    for service in ("staff", "auth", "booking", "facility", "hold", "pos"):
        if not getattr(args, f"{service}_jar").is_file():
            raise SystemExit(f"Missing built {service} jar")
    args.output.mkdir(parents=True, exist_ok=True)
    processes, logs = [], []
    container = "fcourt-flutter-e2e-" + secrets.token_hex(5)
    password, service_key, signing_key = (secrets.token_urlsafe(36) for _ in range(3))
    pg_port, redis_port = free_port(), free_port()
    redis_container = container + "-redis"
    grpc_ports = {name: free_port() for name in ("booking", "hold")}
    hold_key = secrets.token_urlsafe(36)
    pos_key,settlement_key=(secrets.token_urlsafe(36) for _ in range(2))
    ports = {name: free_port() for name in ("staff", "auth", "booking", "facility", "hold", "pos")}
    jwks = {}

    class Fixture(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_GET(self):
            if self.path == "/jwks":
                value = jwks
            elif self.path == "/api/admin/branches/7":
                value = {"id": 7, "adminId": 1, "status": "ACTIVE"}
            elif self.path == "/api/staff/branches/7":
                value = {"id": 7, "status": "ACTIVE"}
            elif self.path.startswith("/api/public/courts/"):
                import urllib.parse
                route=urllib.parse.urlparse(self.path)
                court=int(route.path.split("/")[4])
                date=urllib.parse.parse_qs(route.query)["date"][0]
                booked=set(sql("booking_db",f"SELECT slot_start FROM booking_slots WHERE court_id={court} AND booking_date='{date}' AND active").splitlines())
                canonical=request("facility",f"/api/internal/courts/{court}/slot-schedule?date={date}")[1]
                value=[]
                for slot in canonical["slots"]:
                    begin=slot["startTime"]
                    value.append({"slotId":slot["slotId"],"startTime":begin,"endTime":slot["endTime"],"price":slot["price"],"status":"BOOKED" if begin in booked else "AVAILABLE"})
            else:
                self.send_error(404)
                return
            body = json.dumps(value).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    fixture_url = f"http://127.0.0.1:{server.server_port}"
    bases = {name: f"http://127.0.0.1:{port}" for name, port in ports.items()}
    # Generate/sign only disposable test credentials using .NET's crypto APIs.
    # No private key is exported, no JWT is logged, and no third-party package is needed.
    signing_script = r"""
function fixtureB64([byte[]]$bytes) { [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
$fixtureRsa=[System.Security.Cryptography.RSA]::Create()
$fixtureRsa.KeySize=2048
$fixturePublic=$fixtureRsa.ExportParameters($false)
$fixtureTokens=@{}
foreach($fixturePair in @(@('1','ADMIN'),@('100','STAFF'),@('200','CUSTOMER'))) {
  $fixtureHead=fixtureB64 ([Text.Encoding]::UTF8.GetBytes((@{alg='RS256';kid='fixture'}|ConvertTo-Json -Compress)))
  $fixtureBody=@{sub=$fixturePair[0];roles=@($fixturePair[1]);iss='ISSUER';aud=@('fcourt');exp=([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+900)}|ConvertTo-Json -Compress
  $fixturePayload=$fixtureHead+'.'+(fixtureB64 ([Text.Encoding]::UTF8.GetBytes($fixtureBody)))
  $fixtureSignature=$fixtureRsa.SignData([Text.Encoding]::ASCII.GetBytes($fixturePayload),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
  $fixtureTokens[$fixturePair[0]+':'+$fixturePair[1]]=$fixturePayload+'.'+(fixtureB64 $fixtureSignature)
}
@{jwks=@{keys=@(@{kty='RSA';kid='fixture';use='sig';alg='RS256';n=(fixtureB64 $fixturePublic.Modulus);e=(fixtureB64 $fixturePublic.Exponent)})};tokens=$fixtureTokens}|ConvertTo-Json -Depth 8 -Compress
$fixtureRsa.Dispose()
""".replace("ISSUER", fixture_url)
    signed = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", signing_script],
                            check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    credentials = json.loads(signed.stdout)
    jwks.update(credentials["jwks"])

    def token(user, role):
        return credentials["tokens"][f"{user}:{role}"]

    def run(command, **kwargs):
        return subprocess.run(command, check=True, capture_output=True, text=True, **kwargs).stdout

    def sql(database, query):
        return run(["docker", "exec", container, "psql", "-U", "postgres", "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", query]).strip()

    def request(service, path, method="GET", body=None, jwt=None, command=None):
        headers = {}
        if jwt:
            headers["Authorization"] = "Bearer " + jwt
        if command:
            headers["Idempotency-Key"] = command
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(bases[service] + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                payload = response.read()
                return response.status, json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            return error.code, None

    try:
        docker_env = dict(os.environ, POSTGRES_PASSWORD=password)
        run(["docker", "run", "--rm", "-d", "--name", container, "-p", f"127.0.0.1:{pg_port}:5432", "-e", "POSTGRES_PASSWORD", "postgres:16-alpine"], env=docker_env)
        for _ in range(120):
            probe = subprocess.run(["docker", "exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"], capture_output=True)
            if probe.returncode == 0:
                break
            time.sleep(.25)
        else:
            raise AssertionError("Disposable database did not become ready")
        run(["docker","run","--rm","-d","--name",redis_container,"-p",f"127.0.0.1:{redis_port}:6379","redis:7-alpine"])
        for name in ports:
            if name != "hold":
                sql("postgres", f"CREATE DATABASE {name}_db")
            env = dict(os.environ, SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{pg_port}/{name}_db",
                       SPRING_DATASOURCE_USERNAME="postgres", SPRING_DATASOURCE_PASSWORD=password,
                       SERVER_PORT=str(ports[name]), AUTH_URL=bases["auth"], STAFF_URL=bases["staff"], BOOKING_URL=bases["booking"],
                       FACILITY_URL=bases["facility"], DISCOVERY_URL=fixture_url, HOLD_URL=bases["hold"], POS_URL=bases["pos"], AUTH_JWK_SET_URI=fixture_url + "/jwks", AUTH_JWT_ISSUER=fixture_url,
                       AUTH_JWT_AUDIENCE="fcourt", STAFF_OFFLINE_SIGNING_KEY=signing_key, STAFF_BOOKING_SERVICE_TOKEN=service_key,
                       BOOKING_HOLD_SERVICE_TOKEN=hold_key, PAYMENT_BOOKING_SERVICE_TOKEN=service_key,
                       POS_BOOKING_SERVICE_TOKEN=pos_key, POS_SETTLEMENT_SERVICE_TOKEN=settlement_key, REDIS_ADDRESS=f"redis://127.0.0.1:{redis_port}",
                       FACILITY_EVENTS_ENABLED="false", FACILITY_BOOTSTRAP_SNAPSHOTS="false",
                       COUNTER_GRPC_ENABLED="true" if name in grpc_ports else "false",
                       COUNTER_GRPC_PORT=str(grpc_ports.get(name,0)), STAFF_GRPC_PLAINTEXT="true",
                       HOLD_GRPC_TARGET=f"127.0.0.1:{grpc_ports['hold']}", BOOKING_GRPC_TARGET=f"127.0.0.1:{grpc_ports['booking']}")
            log = (args.output / f"{name}.log").open("w", encoding="utf8")
            logs.append(log)
            processes.append(subprocess.Popen(["java", "-jar", str(getattr(args, f"{name}_jar").resolve()),
                "--server.address=127.0.0.1", "--auth.events.enabled=false", "--staff.events.enabled=false",
                "--booking.events-enabled=false", "--spring.kafka.listener.auto-startup=false"], env=env,
                stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))
        for _ in range(240):
            ready = 0
            for name in ports:
                try:
                    if request(name, "/actuator/health")[0] == 200:
                        ready += 1
                except (OSError, TimeoutError):
                    pass
            if ready == len(ports):
                break
            if any(p.poll() is not None for p in processes):
                raise AssertionError("A service exited; inspect private test logs")
            time.sleep(.25)
        else:
            raise AssertionError("Five services did not become healthy")
        sql("auth_db", "TRUNCATE users CASCADE; INSERT INTO users(id,email,password_hash,full_name,phone,role,status) OVERRIDING SYSTEM VALUE VALUES (1,'owner@fixture.test','fixture-hash','Owner','0912345678','ADMIN','ACTIVE'),(100,'staff@fixture.test','fixture-hash','Staff','0912345679','STAFF','INACTIVE')")
        sql("auth_db", "SELECT setval('users_id_seq',100,true)")
        sql("facility_db", "TRUNCATE branches CASCADE; INSERT INTO branches(id,admin_id,name,address,city,open_time,close_time,status) OVERRIDING SYSTEM VALUE VALUES (7,1,'Fixture branch','Fixture address','HCM','00:00','23:59','ACTIVE'); INSERT INTO courts(id,branch_id,name,court_type_id,status) OVERRIDING SYSTEM VALUE VALUES (21,7,'Court 1',1,'ACTIVE'),(22,7,'Court 2',1,'ACTIVE'),(23,7,'Court 3',1,'ACTIVE'),(24,7,'Court 4',1,'ACTIVE')")
        sql("facility_db", "INSERT INTO time_slot_templates(court_id,start_time,end_time,price,day_of_week) SELECT c.id,('00:00'::time + make_interval(mins=>s.n*30)),('00:00'::time + make_interval(mins=>(s.n+1)*30)),100000,d.n FROM courts c CROSS JOIN generate_series(0,46) s(n) CROSS JOIN generate_series(0,6) d(n)")
        sql("staff_db", "INSERT INTO staff(id,user_id,branch_id,email,full_name,phone,deleted_at) OVERRIDING SYSTEM VALUE VALUES (1,100,7,'staff@fixture.test','Staff','0912345679',now())")
        sql("staff_db", "SELECT setval('staff_id_seq',1,true)")
        local = dt.datetime.now(dt.timezone(dt.timedelta(hours=7)))
        start = local.replace(minute=(local.minute // 30) * 30, second=0, microsecond=0)
        end = start + dt.timedelta(minutes=30)
        raw_code = secrets.token_hex(16)
        sql("booking_db", f"INSERT INTO bookings(id,court_id,guest_phone,date,status,total_price,booking_code,branch_id,court_name,customer_name,checkin_code) OVERRIDING SYSTEM VALUE VALUES (42,21,'0912345678','{local.date()}','CONFIRMED',100000,'FC89234',7,'Court 1','Guest','{raw_code}'); INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (42,21,'{local.date()}','{start:%H:%M}','{end:%H:%M}',100000)")
        owner, staff = token(1, "ADMIN"), token(100, "STAFF")
        checks = []
        assert request("staff", "/api/staff/check-in/preview", "POST", {"checkInCode": raw_code}, staff)[0] == 403
        checks.append("inactive staff denied")
        command = str(uuid.uuid4())
        for _ in range(2):
            status, restored = request("staff", "/api/admin/staff/1/reactivate", "POST", jwt=owner, command=command)
            assert status == 200 and restored["active"] is True
        assert sql("auth_db", "SELECT count(*) FROM user_account_action_logs WHERE action='STAFF_REACTIVATE'") == "1"
        checks.append("reactivation through real IAM/staff, one audit on replay")
        status, preview = request("staff", "/api/staff/check-in/preview", "POST", {"checkInCode": raw_code}, staff)
        assert status == 200 and preview["bookingId"] == 42, f"Preview returned {status}"
        assert sql("booking_db", "SELECT status FROM bookings WHERE id=42") == "CONFIRMED"
        checks.append("read-only preview reaches booking authority")
        status, roster = request("staff", "/api/staff/offline/tickets", jwt=staff)
        assert status == 200 and len(roster["tickets"]) == 1 and raw_code not in json.dumps(roster)
        checks.append("signed snapshot exposes hash rather than QR code")
        body = {"permit": roster["tickets"][0]["permit"], "occurredAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")}
        command = str(uuid.uuid4())
        status, admitted = request("staff", "/api/staff/check-in/offline", "POST", body, staff, command)
        assert status == 200, f"Offline admission returned {status}"
        assert request("staff", "/api/staff/check-in/offline", "POST", body, staff, command) == (200, admitted)
        assert sql("booking_db", "SELECT status FROM bookings WHERE id=42") == "CHECKED_IN"
        assert sql("staff_db", "SELECT count(*) FROM staff_check_in_audit WHERE channel='OFFLINE'") == "1"
        assert sql("booking_db", "SELECT count(*) FROM booking_staff_commands") == "1"
        checks.append("offline replay commits once in authority and staff audit")
        assert request("staff", "/api/staff/check-in/offline", "POST", body, staff, str(uuid.uuid4()))[0] == 409
        assert request("staff", "/api/staff/check-in/offline", "POST", dict(body, permit=body["permit"] + "x"), staff, str(uuid.uuid4()))[0] == 403
        checks.append("duplicate new command and tampered permit denied")
        second_code=secrets.token_hex(16)
        sql("booking_db",f"INSERT INTO bookings(id,court_id,guest_phone,date,status,total_price,booking_code,branch_id,court_name,customer_name,checkin_code) OVERRIDING SYSTEM VALUE VALUES (43,22,'0912345678','{local.date()}','CONFIRMED',100000,'FC89235',7,'Court 2','Guest','{second_code}'); INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (43,22,'{local.date()}','{start:%H:%M}','{end:%H:%M}',100000)")
        assert request("staff","/api/staff/check-in/preview","POST",{"checkInCode":"FC-89235"},staff)[0]==200
        manual_key=str(uuid.uuid4())
        for _ in range(2):
            status,result=request("staff","/api/staff/check-in/manual","POST",{"checkInCode":"FC-89235"},staff,manual_key)
            assert status==200 and result["bookingId"]==43,f"Manual admission returned {status}"
        assert request("staff","/api/staff/check-in/verify","POST",{"checkInCode":second_code},staff,str(uuid.uuid4()))[0]==409
        assert sql("staff_db","SELECT count(*) FROM staff_check_in_audit WHERE channel='MANUAL'")=="1"
        checks.append("printed manual code resolves in authority, QR cannot admit it again")
        # Exercise the employee lifecycle through actual IAM and staff ownership callbacks.
        employee={"branchId":7,"email":"new@fixture.test","password":"FixturePassword123","fullName":"New Staff","phone":"0912345681","shifts":[]}
        employee_key=str(uuid.uuid4())
        status,created=request("staff","/api/admin/staff","POST",employee,owner,employee_key)
        assert status==201,f"Employee creation returned {status}"
        assert request("staff","/api/admin/staff","POST",employee,owner,employee_key)==(201,created)
        employee_id=created["id"]
        status,edited=request("staff",f"/api/admin/staff/{employee_id}","PUT",{"fullName":"Edited Staff","phone":"0912345682","shifts":[]},owner,str(uuid.uuid4()))
        assert status==200 and edited["fullName"]=="Edited Staff",f"Employee update returned {status}"
        assert request("staff",f"/api/admin/staff/{employee_id}","DELETE",jwt=owner,command=str(uuid.uuid4()))[0]==200
        assert sql("auth_db",f"SELECT status FROM users WHERE id={created['userId']}")=="INACTIVE"
        assert request("staff",f"/api/admin/staff/{employee_id}/reactivate","POST",jwt=owner,command=str(uuid.uuid4()))[0]==200
        checks.append("create/edit/disable/reactivate through real IAM and owned branch")
        # Read today/week from the owning booking service, retaining empty courts.
        status,schedule=request("staff","/api/staff/schedule/today",jwt=staff)
        assert status==200 and len(schedule["courts"])==4,f"Today schedule returned {status}"
        finish=local.date()+dt.timedelta(days=6)
        status,week=request("staff",f"/api/staff/schedule?startDate={local.date()}&endDate={finish}",jwt=staff)
        assert status==200 and len(week["courts"])==4,f"Week schedule returned {status}"
        checks.append("today and weekly schedule include live facility courts and empty courts")
        # Both online holds and counter reservation use the real hold service and Redis.
        status,canonical=request("facility",f"/api/internal/courts/23/slot-schedule?date={local.date()}")
        assert status==200
        current_time=f"{start:%H:%M}:00"
        if not any(slot["startTime"]==current_time for slot in canonical["slots"]):
            current_time=canonical["slots"][-1]["startTime"]
        slot_id=next(slot["slotId"] for slot in canonical["slots"] if slot["startTime"]==current_time)
        # A held-current-slot fixture models a customer who held it before its
        # start. This also runs late at night, without requiring a future slot.
        # The real public hold endpoint is exercised below on tomorrow's slots.
        subprocess.run(["docker","exec",redis_container,"redis-cli","SET",
            f"fcourt:hold:23:{local.date()}:{slot_id}",str(uuid.uuid4()),"EX","300"],capture_output=True,check=True)
        walk={"courtId":23,"date":str(local.date()),"slotIds":[slot_id],"guestPhone":"0912345689","guestName":"Cash Guest"}
        assert request("staff","/api/staff/walk-in","POST",walk,staff,str(uuid.uuid4()))[0]==409
        walk["courtId"]=24
        canonical=request("facility",f"/api/internal/courts/24/slot-schedule?date={local.date()}")[1]
        walk["slotIds"]=[next(slot["slotId"] for slot in canonical["slots"] if slot["startTime"]==current_time)]
        cash_key=str(uuid.uuid4())
        status,cash=request("staff","/api/staff/walk-in","POST",walk,staff,cash_key)
        assert status==201,f"Cash booking returned {status}"
        assert request("staff","/api/staff/walk-in","POST",walk,staff,cash_key)==(201,cash)
        cash_id=cash["bookingId"]
        assert sql("booking_db",f"SELECT customer_name FROM bookings WHERE id={cash_id}")=="Cash Guest"
        checks.append("public-format held slot blocks walk-in; cash booking and contact persist once")
        product={"branchId":7,"name":"Water","unit":"bottle","category":"Drinks","price":10000,"stockQuantity":10}
        status,product=request("pos","/api/admin/pos/products","POST",product,owner,str(uuid.uuid4()))
        assert status==201,f"POS product returned {status}"
        sale={"paymentMethod":"BOOKING","bookingId":cash_id,"items":[{"productId":product["id"],"quantity":2}]}
        status,order=request("pos","/api/staff/pos/orders","POST",sale,staff,str(uuid.uuid4()))
        assert status==202,f"POS booking order returned {status}"
        for _ in range(100):
            status,order=request("pos",f"/api/staff/pos/orders/{order['id']}",jwt=staff)
            if order["status"]=="ON_BOOKING":break
            time.sleep(.2)
        assert order["status"]=="ON_BOOKING","POS charge did not attach"
        checks.append("real POS order attaches once to booking before handover")
        # Move only this disposable booking into overtime to exercise the server clock.
        prior=start-dt.timedelta(minutes=30)
        sql("booking_db",f"UPDATE bookings SET date='{prior.date()}',checked_in_at=now()-interval '1 hour' WHERE id={cash_id}; UPDATE booking_slots SET booking_date='{prior.date()}',slot_start='{prior:%H:%M}',slot_end='{start:%H:%M}' WHERE booking_id={cash_id}")
        status,fee=request("staff",f"/api/staff/bookings/{cash_id}/checkout-preview",jwt=staff)
        assert status==200 and fee["surchargeAmount"]==100000,f"Checkout fee returned {status}"
        path=f"/api/staff/bookings/{cash_id}/checkout"
        assert request("staff",path,"PUT",{"expectedSurcharge":0,"expectedTotal":fee["totalAmount"]},staff,str(uuid.uuid4()))[0]==409
        checkout_key=str(uuid.uuid4());body={"expectedSurcharge":fee["surchargeAmount"],"expectedTotal":fee["totalAmount"]}
        status,settled=request("staff",path,"PUT",body,staff,checkout_key)
        assert status==200 and settled["status"]=="COMPLETED",f"Checkout returned {status}"
        assert request("staff",path,"PUT",body,staff,checkout_key)==(200,settled)
        assert sql("booking_db",f"SELECT count(*) FROM booking_cash_settlements WHERE booking_id={cash_id}")=="1"
        assert fee["posAmount"]==20000 and fee["totalAmount"]==220000
        for _ in range(100):
            status,paid=request("pos",f"/api/staff/pos/orders/{order['id']}",jwt=staff)
            if paid["status"]=="PAID":break
            time.sleep(.2)
        assert paid["status"]=="PAID","POS settlement did not reconcile"
        checks.append("overtime and POS total confirmed; checkout and POS settlement commit once")
        # Exercise online creation through real IAM, facility, Redis and booking.
        sql("auth_db", "INSERT INTO users(id,email,password_hash,full_name,phone,role,status) OVERRIDING SYSTEM VALUE VALUES (200,'customer@fixture.test','fixture-hash','Customer','0901234567','CUSTOMER','ACTIVE')")
        customer=token(200,"CUSTOMER")
        tomorrow=local.date()+dt.timedelta(days=1)
        canonical=request("facility",f"/api/internal/courts/24/slot-schedule?date={tomorrow}")[1]
        selected=[s["slotId"] for s in canonical["slots"] if s["startTime"] in ("09:00:00","09:30:00")]
        assert len(selected)==2
        status,hold=request("hold","/api/bookings/hold-slots","POST",{"courtId":24,"date":str(tomorrow),"slotIds":selected,"guestPhone":"0901234567"})
        assert status==201
        online_key=str(uuid.uuid4());online_body={"courtId":24,"date":str(tomorrow),"slotIds":selected,"holdToken":hold["holdToken"]}
        status,online=request("booking","/api/bookings","POST",online_body,customer,online_key)
        assert status==201 and online["status"]=="PENDING_PAYMENT" and online["totalPrice"]==200000,f"Online create returned {status}"
        assert request("booking","/api/bookings","POST",online_body,customer,online_key)[1]["id"]==online["id"]
        assert request("booking","/api/bookings","POST",online_body,customer,str(uuid.uuid4()))[0]==409
        assert sql("booking_db",f"SELECT count(*) FROM booking_slots WHERE booking_id={online['id']} AND active")=="2"
        checks.append("online hold becomes one durable customer booking with canonical prices; retry and duplicate guards hold across real services")
        report = {"passed": True, "checks": checks, "discovery_and_jwks": "local fixtures", "services": "real auth/staff/booking/facility/hold/POS jars", "holds": "disposable Redis 7", "database": "disposable PostgreSQL 16, separate service databases"}
        (args.output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf8")
        print(json.dumps(report))
    finally:
        for process in processes:
            process.terminate()
        for process in processes:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
        for log in logs:
            log.close()
        server.shutdown()
        subprocess.run(["docker", "rm", "-f", container,redis_container], capture_output=True)


if __name__ == "__main__":
    main()
