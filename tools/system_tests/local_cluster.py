"""Run SCRUM-91 on eight real local services, real Kafka/Redis/PostgreSQL.

External document/receipt storage and a controlled admission clock are test-only
classpath fixtures. Production jars never contain those beans or control routes.
"""
import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.request
import zlib

from flow import Flow, write_report
from gateway import Failure, Gateway

ROOT=Path(__file__).resolve().parents[2]
SERVICES={"auth":"auth-service","facility":"facility-service","discovery":"discovery-service","hold":"slot-hold-engine",
          "booking":"booking-service","payment":"payment-service","staff":"staff-operations-service","reporting":"reporting-service"}


def free_port():
    with socket.socket() as sock:sock.bind(("127.0.0.1",0));return sock.getsockname()[1]


def fixture_png():
    def chunk(kind,data):return struct.pack("!I",len(data))+kind+data+struct.pack("!I",zlib.crc32(kind+data)&0xffffffff)
    return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack("!2I5B",4,4,8,2,0,0,0))+chunk(b"IDAT",zlib.compress((b"\0"+b"\0\x80\0"*4)*4))+chunk(b"IEND",b"")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute",action="store_true")
    parser.add_argument("--output",type=Path,default=ROOT/"tools/system_tests/target/local-cluster")
    args=parser.parse_args()
    if not args.execute:print("NOT_RUN: use --execute for disposable local fixtures");return 2
    jars={name:ROOT/service/"target"/(service+"-0.0.1-SNAPSHOT.jar") for name,service in SERVICES.items()}
    classpath_file=ROOT/"staff-operations-service/target/test-classpath.txt"
    if any(not jar.is_file() for jar in jars.values()) or not classpath_file.is_file():raise SystemExit("Build all eight jars, test classes and the staff test dependency classpath first")
    args.output.mkdir(parents=True,exist_ok=True)
    png=args.output/"receipt-fixture.png";png.write_bytes(fixture_png())
    reservation_key=secrets.token_urlsafe(36);slot_key=secrets.token_urlsafe(36);control_key=secrets.token_urlsafe(36);hold_key=secrets.token_urlsafe(36);payment_key=secrets.token_urlsafe(36);staff_key=secrets.token_urlsafe(36);booking_integration_key=secrets.token_urlsafe(36)
    processes=[];logs=[];infra=None;proxy=None
    os_keys={"PATH","SYSTEMROOT","WINDIR","TEMP","TMP","JAVA_HOME","USERPROFILE","APPDATA","LOCALAPPDATA","PROGRAMDATA","COMSPEC","PATHEXT","LANG","LC_ALL","TZ","USER","LOGNAME","HOME"}
    service_environment={key:value for key,value in os.environ.items() if key.upper() in os_keys}
    ports={name:free_port() for name in SERVICES};bases={name:f"http://127.0.0.1:{port}" for name,port in ports.items()}
    def api(name,path,body=None,headers=None):
        request=urllib.request.Request(bases[name]+path,data=None if body is None else json.dumps(body).encode(),
                headers=dict({"Content-Type":"application/json"},**(headers or {})),method="GET" if body is None else "POST")
        try:
            with urllib.request.urlopen(request,timeout=5) as response:return json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:raise AssertionError(f"{name} {path}: HTTP {error.code}") from None
    try:
        classpath=os.pathsep.join([str(ROOT/"staff-operations-service/target/test-classes"),str(ROOT/"staff-operations-service/target/classes"),classpath_file.read_text().strip()])
        infra=subprocess.Popen(["java","-XX:ActiveProcessorCount=2","-Xmx192m","-cp",classpath,"com.fcourt.staff.LocalClusterInfrastructure"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0)
        with (args.output/"infrastructure.log").open("w",encoding="utf8") as log:
            for line in infra.stdout:
                if line.startswith("FCOURT_LOCAL_TEST="):info=json.loads(line.split("=",1)[1]);break
                log.write(line);log.flush()
            else:raise AssertionError("Disposable infrastructure failed; inspect local log")
        def sql(database,statement):
            result=subprocess.run(["docker","exec","-i",info["postgresContainer"],"psql","-U","postgres","-d",database,"-v","ON_ERROR_STOP=1","-At"],input=statement,text=True,capture_output=True)
            if result.returncode:raise AssertionError("Local database fixture failed")
            return result.stdout
        for name in SERVICES:
            if name!="hold":sql("postgres",f"CREATE DATABASE {name}_db;")
        for name,service in SERVICES.items():
            env=dict(service_environment,SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{info['postgresPort']}/{name}_db",SPRING_DATASOURCE_USERNAME="postgres",SPRING_DATASOURCE_PASSWORD=info["password"],
                     SERVER_PORT=str(ports[name]),AUTH_URL=bases["auth"],AUTH_JWT_ISSUER=bases["auth"],AUTH_JWT_AUDIENCE="fcourt",AUTH_JWK_SET_URI=bases["auth"]+"/.well-known/jwks.json",
                     STAFF_URL=bases["staff"],FACILITY_URL=bases["facility"],DISCOVERY_URL=bases["discovery"],BOOKING_URL=bases["booking"],HOLD_URL=bases["hold"],PAYMENT_URL=bases["payment"],
                     REDIS_HOST="127.0.0.1",REDIS_PORT=str(info["redisPort"]),REDIS_ADDRESS=f"redis://127.0.0.1:{info['redisPort']}",SPRING_DATA_REDIS_HOST="127.0.0.1",SPRING_DATA_REDIS_PORT=str(info["redisPort"]),
                     KAFKA_BOOTSTRAP_SERVERS=info["kafka"],BOOKING_HOLD_SERVICE_TOKEN=hold_key,PAYMENT_BOOKING_SERVICE_TOKEN=payment_key,
                     BOOKING_INTEGRATION_TOKEN=booking_integration_key,
                     SUBSCRIPTION_URL="",AUTH_JWT_PRIVATE_KEY="",AUTH_JWT_KEY_ID="local-fixture",AUTH_JWT_ACCESS_TTL="15m",
                     FACILITY_RESERVATION_TOKEN=reservation_key,DISCOVERY_BASE_URL=bases["discovery"],DISCOVERY_INTERNAL_SLOT_TOKEN=slot_key,BOOKING_CANCEL_CUTOFF_HOURS="0",
                     STAFF_BOOKING_SERVICE_TOKEN=staff_key,STAFF_OFFLINE_SIGNING_KEY=secrets.token_urlsafe(36),FCOURT_LOCAL_TEST="true",FCOURT_FIXTURE_CONTROL_KEY=control_key)
            log=(args.output/(name+".log")).open("w",encoding="utf8");logs.append(log)
            if name in {"auth","booking","payment","staff"}:
                package={"auth":"com.fcourt.auth.account","booking":"com.fcourt.booking","payment":"com.fcourt.payment","staff":"com.fcourt.staff"}[name]
                command=["java","-XX:ActiveProcessorCount=2","-Xmx192m","-Dloader.path="+str(ROOT/service/"target/test-classes"),"-Dloader.main="+package+".LocalFixtureApplication","-cp",str(jars[name]),"org.springframework.boot.loader.launch.PropertiesLauncher"]
            else:command=["java","-XX:ActiveProcessorCount=2","-Xmx192m","-jar",str(jars[name])]
            command += ["--server.address=127.0.0.1","--management.health.mail.enabled=false","--facility.events.topic-partitions=3"]
            processes.append(subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0))
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            try:
                if all(api(name,"/actuator/health").get("status")=="UP" for name in SERVICES):break
            except (AssertionError,OSError):pass
            if any(p.poll() is not None for p in processes):raise AssertionError("A local service exited; inspect its log")
            time.sleep(.25)
        else:raise AssertionError("Local services did not become healthy")
        run_id=secrets.token_hex(5)
        super_email=f"super-{run_id}@fixture.invalid";customer_email=f"customer-{run_id}@fixture.invalid"
        password=secrets.token_urlsafe(24)
        for email,phone in [(super_email,"0930000001"),(customer_email,"0930000002")]:
            created=api("auth","/api/auth/register/customer",{"fullName":"Local fixture","email":email,"phone":phone,"password":password})
            if email==super_email:sql("auth_db",f"UPDATE users SET role='SUPER_ADMIN' WHERE id={int(created['user']['id'])};")
        class Proxy(BaseHTTPRequestHandler):
            def log_message(self,*unused):pass
            def do_GET(self):self.forward()
            def do_POST(self):self.forward()
            def do_PUT(self):self.forward()
            def forward(self):
                path=self.path.split("?")[0]
                if path.startswith(("/api/auth/","/api/super-admin/")):name="auth"
                elif path.startswith(("/api/admin/staff","/api/staff/")):name="staff"
                elif path.startswith(("/api/payments/","/api/admin/payments/")):name="payment"
                elif path.startswith("/api/admin/dashboard/"):name="reporting"
                elif path=="/api/public/branches" or path.startswith("/api/public/courts/"):name="discovery"
                elif path.startswith("/api/bookings/hold-slots"):name="hold"
                elif path.startswith(("/api/bookings","/api/admin/bookings")):name="booking"
                elif path.startswith(("/api/admin/branches","/api/admin/courts")):name="facility"
                else:self.send_error(404);return
                size=int(self.headers.get("Content-Length","0"))
                if size>6*1024*1024:self.send_error(413);return
                body=self.rfile.read(size)
                headers={k:v for k,v in self.headers.items() if k.lower() not in {"host","connection","content-length"}}
                connection=http.client.HTTPConnection("127.0.0.1",ports[name],timeout=5)
                try:
                    connection.request(self.command,self.path,body=body or None,headers=headers);response=connection.getresponse();raw=response.read()
                    self.send_response(response.status);self.send_header("Content-Type",response.getheader("Content-Type","application/json"));self.send_header("Content-Length",str(len(raw)));self.end_headers();self.wfile.write(raw)
                finally:connection.close()
        proxy=ThreadingHTTPServer(("127.0.0.1",0),Proxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
        config=json.loads((ROOT/"tools/system_tests/config.example.json").read_text())
        config.update(scope="local_cluster",environment="local",baseUrl=f"http://127.0.0.1:{proxy.server_port}",legalDocumentFile=png.name)
        config["ownerRegistration"]["emailDomain"]="fixture.invalid"
        config["branch"].update(address="Local fixture address",city="HCM",ward="Fixture",bankName="Fixture bank",bankBin="000000",bankAccountNumber="000000000001",bankAccountName="FIXTURE")
        config["court"]["imageUrl"]="https://cdn.example.test/local-fixture/court.png"
        config["verifyReporting"]=True
        config["routes"].update(fullCourtQuote={"method":"POST","path":"/api/admin/bookings/full-court/quote"},fullCourt={"method":"POST","path":"/api/admin/bookings/full-court"},
                                fullCourtCancel={"method":"POST","path":"/api/admin/bookings/full-court/{packageId}/cancel"})
        config["routes"]["reportingStats"]={"method":"GET","path":"/api/admin/dashboard/stats?branchId={branchId}"}
        environment={"FCOURT_E2E_SUPERADMIN_EMAIL":super_email,"FCOURT_E2E_SUPERADMIN_PASSWORD":password,"FCOURT_E2E_CUSTOMER_EMAIL":customer_email,"FCOURT_E2E_CUSTOMER_PASSWORD":password,
                     "FCOURT_E2E_CUSTOMER_PHONE":"0930000002","FCOURT_E2E_OWNER_PASSWORD":password,"FCOURT_E2E_STAFF_PASSWORD":password}
        def advance(value):
            for name in ("booking","staff"):api(name,"/__local_test__/clock",{"now":value.isoformat()},{"X-Fixture-Key":control_key})
        # Future slots keep the test independent of venue hours. Advance only
        # after payments/Saga settle, using test-only clock beans for admission.
        local=datetime.now(timezone(timedelta(hours=7)))+timedelta(days=1)
        business_now=local.replace(hour=9,minute=20,second=0,microsecond=0)
        runner=Flow(config,Gateway(config,run_id,png),environment,now=business_now,before_check_in=advance)
        report=runner.run()
        if report["overall"]=="PASS":
            try:
                runner.step("reporting-check-in-event",lambda:runner.poll("reporting check-in inbox",
                    lambda:int(sql("reporting_db","SELECT count(*) FROM processed_events WHERE topic='fcourt.staff.events' AND event_type='BookingCheckedInEvent';").strip()),lambda count:count==1))
            except Failure:report["overall"]="FAIL"
        if report["overall"]=="PASS":
            try:runner.full_court()
            except Failure:report["overall"]="FAIL"
        if report["overall"]!="PASS":
            def tail(name):
                try:
                    lines=[l.rstrip()[:300] for l in (args.output/(name+".log")).read_text(encoding="utf8",errors="replace").splitlines() if any(k in l for k in ("ERROR","WARN","Exception","eservation"))]
                    return chr(10).join(lines[-30:])
                except OSError:return ""
            report["diagnostics"]={"facilityLog":tail("facility"),"bookingLog":tail("booking"),"packages":sql("booking_db","SELECT state,failure,attempt FROM full_court_packages;"),"reservations":sql("facility_db","SELECT state,cancel_cause FROM court_reservations;"),"facilityOutbox":sql("facility_db","SELECT count(*),count(published_at) FROM facility_outbox;"),
                    "discoveryRows":sql("discovery_db","SELECT count(*) FROM branch_read_model; SELECT count(*) FROM court_read_model;")}
        report.update(realServices=list(SERVICES),infrastructure="real PostgreSQL 16 / Redis 7 / Kafka",externalStorage="test-classpath fixtures",admissionClock="test-classpath controlled; JWT clock real",gateway="loopback reverse proxy; not production Nginx",productionAcceptance=False)
        write_report(report,args.output/"report.json")
        print(json.dumps({"overall":report["overall"],"steps":len(report["steps"]),"scope":report["scope"],"productionAcceptance":False}))
        return 0 if report["overall"]=="PASS" else 1
    finally:
        if proxy:proxy.shutdown();proxy.server_close()
        for process in processes:process.terminate()
        for process in processes:
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for log in logs:log.close()
        if infra:
            try:infra.communicate(input="\n",timeout=45)
            except subprocess.TimeoutExpired:infra.kill();infra.wait()


if __name__=="__main__":raise SystemExit(main())
