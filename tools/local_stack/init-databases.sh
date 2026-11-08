#!/bin/sh
set -eu
# Runs only on an empty postgres-data volume. Application migrations own schemas.
create_app_db() {
  role="$1"; database="$2"; password_var="$3"
  eval "password=\${$password_var}"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -v app_password="$password" \
    -v app_role="$role" -v app_database="$database" <<'SQL'
CREATE ROLE :app_role LOGIN PASSWORD :'app_password';
CREATE DATABASE :app_database OWNER :app_role;
REVOKE CONNECT ON DATABASE :app_database FROM PUBLIC;
GRANT CONNECT ON DATABASE :app_database TO :app_role;
SQL
}
create_app_db auth auth_db AUTH_DB_PASSWORD
create_app_db subscription subscription_db SUBSCRIPTION_DB_PASSWORD
create_app_db facility facility_db FACILITY_DB_PASSWORD
create_app_db reporting reporting_db REPORTING_DB_PASSWORD
create_app_db discovery discovery_db DISCOVERY_DB_PASSWORD
create_app_db voucher voucher_db VOUCHER_DB_PASSWORD
create_app_db booking booking_db BOOKING_DB_PASSWORD
create_app_db payment payment_db PAYMENT_DB_PASSWORD
create_app_db staff staff_db STAFF_DB_PASSWORD
create_app_db pos pos_retail_db POS_DB_PASSWORD
create_app_db notification notification_db NOTIFICATION_DB_PASSWORD
