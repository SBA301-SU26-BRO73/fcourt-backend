#!/bin/sh
set -eu
# Use on an existing PostgreSQL volume after setting distinct *_DB_PASSWORD values.
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$AUTH_DB_PASSWORD" <<'SQL'
ALTER ROLE auth WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$SUBSCRIPTION_DB_PASSWORD" <<'SQL'
ALTER ROLE subscription WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$FACILITY_DB_PASSWORD" <<'SQL'
ALTER ROLE facility WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$REPORTING_DB_PASSWORD" <<'SQL'
ALTER ROLE reporting WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$DISCOVERY_DB_PASSWORD" <<'SQL'
ALTER ROLE discovery WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$VOUCHER_DB_PASSWORD" <<'SQL'
ALTER ROLE voucher WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$BOOKING_DB_PASSWORD" <<'SQL'
ALTER ROLE booking WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$PAYMENT_DB_PASSWORD" <<'SQL'
ALTER ROLE payment WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$STAFF_DB_PASSWORD" <<'SQL'
ALTER ROLE staff WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$POS_DB_PASSWORD" <<'SQL'
ALTER ROLE pos WITH PASSWORD :'app_password';
SQL
psql -v ON_ERROR_STOP=1 -U postgres -v app_password="$NOTIFICATION_DB_PASSWORD" <<'SQL'
ALTER ROLE notification WITH PASSWORD :'app_password';
SQL
