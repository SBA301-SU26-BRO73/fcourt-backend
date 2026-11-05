"""Disposable Backend API acceptance of seed data (Windows, Java 21, Docker).

No user database, provider account or production endpoint is used. JWT signing
and Cloudinary URLs are fixtures; booking/facility/payment apps and storage are real.
"""
import argparse
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

import seed

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0));return sock.getsockname()[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("booking","facility","payment"): parser.add_argument("--"+name+"-jar",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    jars={name:getattr(args,name+"_jar").resolve() for name in ("booking","facility","payment")}
    if any(not path.is_file() for path in jars.values()): raise SystemExit("Build the three Backend jars first")
    args.output.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((HERE/"manifest.example.json").read_text(encoding="utf8"))
    manifest.update(seedKey="runtime-smoke",idBase=1700000,demoDate=str(date.today()+timedelta(days=1)))
    password_hash=re.search(r"\$2a\$10\$[./A-Za-z0-9]{53}",(ROOT/"auth-service/database/V2__seed_sample_data.sql").read_text(encoding="utf8")).group()
    pg,redis_port=free_port(),free_port()
    ports={name:free_port() for name in jars}
    bases={name:f"http://127.0.0.1:{port}" for name,port in ports.items()}
    container="fcourt-seed-api-"+secrets.token_hex(6);redis_container=container+"-redis"
    password=secrets.token_urlsafe(32);processes=[];logs=[];jwks={}
    class Keys(BaseHTTPRequestHandler):
        def log_message(self,*unused): pass
        def do_GET(self):
            if self.path!="/jwks": self.send_error(404);return
            body=json.dumps(jwks).encode();self.send_response(200);self.send_header("Content-Type","application/json");self.end_headers();self.wfile.write(body)
    server=ThreadingHTTPServer(("127.0.0.1",0),Keys)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    issuer=f"http://127.0.0.1:{server.server_port}"
    signing=r'''
function fixtureB64([byte[]]$bytes) { [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
$fixtureRsa=[Security.Cryptography.RSA]::Create();$fixtureRsa.KeySize=2048
$fixturePublic=$fixtureRsa.ExportParameters($false)
$fixtureHead=fixtureB64 ([Text.Encoding]::UTF8.GetBytes((@{alg='RS256';kid='seed-fixture'}|ConvertTo-Json -Compress)))
$fixtureBody=@{sub='1700010';roles=@('CUSTOMER');iss='ISSUER';aud=@('fcourt');exp=([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+900)}|ConvertTo-Json -Compress
$fixturePayload=$fixtureHead+'.'+(fixtureB64 ([Text.Encoding]::UTF8.GetBytes($fixtureBody)))
$fixtureSignature=$fixtureRsa.SignData([Text.Encoding]::ASCII.GetBytes($fixturePayload),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
@{token=($fixturePayload+'.'+(fixtureB64 $fixtureSignature));jwks=@{keys=@(@{kty='RSA';kid='seed-fixture';use='sig';alg='RS256';n=(fixtureB64 $fixturePublic.Modulus);e=(fixtureB64 $fixturePublic.Exponent)})}}|ConvertTo-Json -Depth 8 -Compress
$fixtureRsa.Dispose()
'''.replace("ISSUER",issuer)
    def run(command,**kwargs):
        return subprocess.run(command,check=True,capture_output=True,**kwargs)
    def request(name,path,binary=False):
        query=urllib.request.Request(bases[name]+path,headers={"Authorization":"Bearer "+credentials["token"]})
        try:
            with urllib.request.urlopen(query,timeout=5) as response: raw=response.read()
        except urllib.error.HTTPError as error:
            raise AssertionError(f"{name} API returned HTTP {error.code}") from None
        return raw if binary else json.loads(raw)
    try:
        signed=run(["powershell","-NoProfile","-NonInteractive","-Command",signing],text=True,creationflags=subprocess.CREATE_NO_WINDOW)
        credentials=json.loads(signed.stdout);jwks.update(credentials["jwks"])
        run(["docker","run","--rm","-d","--name",container,"-p",f"127.0.0.1:{pg}:5432","-e","POSTGRES_PASSWORD","postgres:16-alpine"],env=dict(os.environ,POSTGRES_PASSWORD=password))
        run(["docker","run","--rm","-d","--name",redis_container,"-p",f"127.0.0.1:{redis_port}:6379","redis:7-alpine"])
        runner=seed.DockerDatabase(container)
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            if subprocess.run(["docker","exec",container,"pg_isready","-h","127.0.0.1","-U","postgres"],capture_output=True).returncode==0:break
            time.sleep(.2)
        else:raise AssertionError("Disposable PostgreSQL did not start")
        for service,db in [("auth-service","auth_db"),("subscription-service","subscription_db"),("facility-service","facility_db"),
                           ("staff-operations-service","staff_db"),("voucher-service","voucher_db"),("payment-service","payment_db"),("booking-service","booking_db")]:
            runner.execute("postgres",f'CREATE DATABASE "{db}";')
            files=sorted((ROOT/service/"src/main/resources/db/migration").glob("V*.sql"),key=lambda p:int(p.name.split("__")[0][1:]))
            for path in files or [ROOT/service/"database/V1__init_schema.sql"]: runner.execute(db,path.read_text(encoding="utf8"))
        seed.apply(manifest,password_hash,runner,allow_fixtures=True)
        for name,jar in jars.items():
            env=dict(os.environ,SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{pg}/{name}_db",SPRING_DATASOURCE_USERNAME="postgres",SPRING_DATASOURCE_PASSWORD=password,
                     SERVER_PORT=str(ports[name]),AUTH_JWK_SET_URI=issuer+"/jwks",AUTH_JWT_ISSUER=issuer,AUTH_JWT_AUDIENCE="fcourt",
                     BOOKING_URL=bases["booking"],FACILITY_URL=bases["facility"],SPRING_DATA_REDIS_HOST="127.0.0.1",SPRING_DATA_REDIS_PORT=str(redis_port))
            log=(args.output/(name+".log")).open("w",encoding="utf8");logs.append(log)
            # SQL migrations were explicitly applied above to this disposable DB;
            # disabling Flyway here avoids fabricating a migration-history baseline.
            command=["java","-Xmx192m","-jar",str(jar),"--server.address=127.0.0.1","--spring.flyway.enabled=false",
                     "--booking.events-enabled=false","--payment.events-enabled=false","--facility.events.enabled=false","--spring.kafka.listener.auto-startup=false"]
            processes.append(subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW))
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            try:
                if all(request(name,"/actuator/health").get("status")=="UP" for name in jars):break
            except (OSError,AssertionError):pass
            if any(p.poll() is not None for p in processes):raise AssertionError("A fixture service exited; inspect local logs")
            time.sleep(.25)
        else:raise AssertionError("Fixture services did not become healthy")
        base=manifest["idBase"]
        booking=request("booking",f"/api/bookings/{base}")
        ticket=request("payment",f"/api/payments/{base}/ticket")
        assert re.fullmatch(r"[0-9]{32}",ticket["checkInCode"]) and ticket["checkInCode"]==booking["checkInCode"]
        assert request("payment",f"/api/payments/{base}/ticket/qr",True).startswith(b"\x89PNG\r\n\x1a\n")
        checkout=request("payment",f"/api/payments/checkout-info/{base+20}")
        assert checkout["amount"]==100000 and checkout["bankBin"]=="000000"
        all_day=request("payment",f"/api/payments/{base+30}/ticket")
        assert len(all_day["slots"])==32 and all_day["slots"][0]["startTime"].startswith("06:00") and all_day["slots"][-1]["endTime"].startswith("22:00")
        report={"passed":True,"scope":"local_backend_seed_api","checks":["confirmed numeric ticket shared by booking/payment","real ticket PNG endpoint","pending checkout matches authoritative amount/bank format","all-day ticket contains 32 slots"],
                "realServices":["booking","facility","payment"],"externalProviders":"fixture assets and JWKS; no external calls or transfers","productionAcceptance":False}
        (args.output/"report.json").write_text(json.dumps(report,indent=2),encoding="utf8");print(json.dumps(report))
    finally:
        for process in processes:process.terminate()
        for process in processes:
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for log in logs:log.close()
        server.shutdown();server.server_close()
        for name in (redis_container,container):
            if not name.startswith("fcourt-seed-api-"):raise AssertionError("Unexpected cleanup target")
            subprocess.run(["docker","rm","-f",name],capture_output=True)


if __name__=="__main__":main()
