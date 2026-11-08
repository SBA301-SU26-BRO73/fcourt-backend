#!/bin/sh
set -eu
# Mirrors topic names/partition counts created by staff-operations-service/src/test/java/com/fcourt/staff/LocalClusterInfrastructure.java:23-37.
create_topic() {
  topic="$1"; partitions="$2"
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1
}
create_topic fcourt.auth.events 1
create_topic fcourt.staff.events 1
create_topic fcourt.payment.events 1
create_topic fcourt.booking.events 1
create_topic facility.branch-updated.v1 3
create_topic fcourt.pos.events 1
