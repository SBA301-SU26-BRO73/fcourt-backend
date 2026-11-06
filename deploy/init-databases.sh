#!/bin/sh
set -eu
# Fresh volumes only. Flyway in each application owns migrations; no demo seed.
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE auth LOGIN PASSWORD :'app_password';
CREATE DATABASE auth_db OWNER auth;
REVOKE CONNECT ON DATABASE auth_db FROM PUBLIC;
GRANT CONNECT ON DATABASE auth_db TO auth;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE subscription LOGIN PASSWORD :'app_password';
CREATE DATABASE subscription_db OWNER subscription;
REVOKE CONNECT ON DATABASE subscription_db FROM PUBLIC;
GRANT CONNECT ON DATABASE subscription_db TO subscription;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE facility LOGIN PASSWORD :'app_password';
CREATE DATABASE facility_db OWNER facility;
REVOKE CONNECT ON DATABASE facility_db FROM PUBLIC;
GRANT CONNECT ON DATABASE facility_db TO facility;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE reporting LOGIN PASSWORD :'app_password';
CREATE DATABASE reporting_db OWNER reporting;
REVOKE CONNECT ON DATABASE reporting_db FROM PUBLIC;
GRANT CONNECT ON DATABASE reporting_db TO reporting;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE discovery LOGIN PASSWORD :'app_password';
CREATE DATABASE discovery_db OWNER discovery;
REVOKE CONNECT ON DATABASE discovery_db FROM PUBLIC;
GRANT CONNECT ON DATABASE discovery_db TO discovery;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE voucher LOGIN PASSWORD :'app_password';
CREATE DATABASE voucher_db OWNER voucher;
REVOKE CONNECT ON DATABASE voucher_db FROM PUBLIC;
GRANT CONNECT ON DATABASE voucher_db TO voucher;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE booking LOGIN PASSWORD :'app_password';
CREATE DATABASE booking_db OWNER booking;
REVOKE CONNECT ON DATABASE booking_db FROM PUBLIC;
GRANT CONNECT ON DATABASE booking_db TO booking;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE payment LOGIN PASSWORD :'app_password';
CREATE DATABASE payment_db OWNER payment;
REVOKE CONNECT ON DATABASE payment_db FROM PUBLIC;
GRANT CONNECT ON DATABASE payment_db TO payment;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE staff LOGIN PASSWORD :'app_password';
CREATE DATABASE staff_db OWNER staff;
REVOKE CONNECT ON DATABASE staff_db FROM PUBLIC;
GRANT CONNECT ON DATABASE staff_db TO staff;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE pos LOGIN PASSWORD :'app_password';
CREATE DATABASE pos_retail_db OWNER pos;
REVOKE CONNECT ON DATABASE pos_retail_db FROM PUBLIC;
GRANT CONNECT ON DATABASE pos_retail_db TO pos;
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE notification LOGIN PASSWORD :'app_password';
CREATE DATABASE notification_db OWNER notification;
REVOKE CONNECT ON DATABASE notification_db FROM PUBLIC;
GRANT CONNECT ON DATABASE notification_db TO notification;
SQL
