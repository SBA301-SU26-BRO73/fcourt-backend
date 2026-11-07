# SCRUM-100 — EC2 deployment preparation

This is a deployment candidate, **not an executed AWS release**. No EC2, DNS,
certificate, image registry or production database has been modified. The default
`dev` checkout still lacks voucher implementation; integrate reviewed service PRs
before building all twelve images. Single-node PostgreSQL/Kafka/Redis do not
provide high availability or prove 24/7 operation.

## Prepare a release

1. Select the reviewed integrated commit, run each module's `sh mvnw verify`, then
   build/tag/push all twelve images with that immutable commit. For modules without
   a Dockerfile use `docker build -f deploy/Service.Dockerfile booking-service` from
   the repository root (change the build context for each module). Do not silently
   substitute a different voucher image or use `latest`.
2. Provision an Ubuntu EC2 host with encrypted EBS, disk/CPU alarms, a limited IAM
   role and sufficient memory. Authorize SSH only from the operator's address;
   expose only 80/443 publicly. Do not publish PostgreSQL, Redis, Kafka or gRPC.
3. Install Docker Engine and Compose v2. Copy this directory to `/opt/fcourt/deploy`.
   Copy `.env.example` to `.env`, `runtime.env.example` to `runtime.env`; chmod both
   600 and fill credentials privately. Set a unique strong `*_DB_PASSWORD` for each database role. The PostgreSQL initializer and matching application share that role-specific secret; never reuse a service password for another database. Runtime secrets are interpolated only into
   the services that consume them; the auth signing key is not injected into other
   containers. Include both env files in Compose commands. Use a persistent RSA PKCS8 signing key and
   stable key ID: an ephemeral key invalidates active sessions on every restart.
   SMTP/Cloudinary/PayOS require their real provider settings before acceptance.
4. Replace `fcourt.example` in `nginx.conf`, set PUBLIC_ORIGIN to the same HTTPS
   issuer, point DNS at EC2 and obtain a Let's Encrypt certificate. Bootstrap using
   Certbot standalone on port 80 before Nginx starts; place its configuration tree
   under `certificates/`, including archive targets referenced by `live/` symlinks.
   Configure a root-owned daily Certbot renewal job followed by
   `docker compose -f /opt/fcourt/deploy/compose.yml exec -T nginx nginx -s reload`.
5. Run `python3 check_config.py` in this directory. It renders Compose privately;
   never upload `docker compose config` output, which includes runtime secrets.
   Confirm registry access and pull the selected images before `docker compose --env-file .env --env-file runtime.env up -d`.
   Flyway migrates fresh databases; the init script does not import demo SQL.
6. gRPC counter operations are an **outstanding release gate**. Servers currently
   require TLS on non-loopback binds and are disabled by default. Supply trusted
   certificates/keys and host bindings through reviewed server configuration and
   configure staff targets/trust before accepting walk-in. Do not enable plaintext
   or remove the service's TLS check to make a smoke test pass.
7. Run actual service health and the integrated booking/payment/check-in flow.
   This gateway deliberately denies internal routes and arbitrary actuator access.
   Health probes can use `docker compose exec` on the private network or an explicitly
   authenticated operations gateway. Finish route contracts for still-unmerged
   voucher/analytics/booking PRs before declaring the public gateway complete.

The twelve service HTTP ports are unique: notification uses 8092 here because POS
uses 8091. Common HTTP dependencies point at Compose DNS, not localhost. Database
roles own only their databases; administrative credentials stay in PostgreSQL.
Redis is password protected, including payment's Spring Redis client.

## Backup, restore and rollback

Run `cd /opt/fcourt/deploy && ./backup.sh /protected/backups` with a root-owned cron
entry, for example `15 2 * * *`. The script writes mode-600 cluster dumps atomically.
Encrypt and transfer them to a restricted off-host location; define retention and
alert on failure. Dumps contain personal data and role password hashes. Redis AOF
and Kafka volumes persist broker/hold state; a PostgreSQL dump alone is not a full
point-in-time recovery of the Saga. Record coordinated recovery/replay procedures
and source offsets before a real release.

Test recovery on an isolated PostgreSQL 16 instance: inspect the dump, then feed
it to `psql -v ON_ERROR_STOP=1 -U postgres -d postgres`. Never restore over live
production without an approved recovery plan and maintenance window. Verify role
isolation, schema versions, booking totals and event replay before promotion.

For application rollback, record the previous image tag and configuration, stop
new traffic and determine whether migrations are backwards compatible. Restore
the prior tag only when compatible; otherwise use the rehearsed database recovery
plan. Never use `docker compose down -v` on production data.

## Local evidence

`python3 -m unittest discover -s deploy -v`: configuration guard tests.
`sh -n deploy/backup.sh deploy/init-databases.sh`: shell syntax.
Compose v2 rendered the 16-container topology using temporary fixture values.
These checks establish configuration validity; cloud deployment, image builds,
provider credentials, TLS renewal and a backup restore drill still need evidence.
