#!/bin/bash
# ---------------------------------------------------------------------------
# Declares every GameHub topic.
#
# Run once by the kafka-init container in docker-compose.yml. Idempotent, so
# repeated `docker compose up -d` is safe.
#
# Topics are declared here rather than auto-created on first produce, because
# auto-creation gives every topic the broker defaults: one partition, and
# whatever retention happens to be configured. That makes partition count an
# accident of which producer ran first, and it cannot be changed downward
# afterwards.
# ---------------------------------------------------------------------------
set -euo pipefail

BOOTSTRAP="kafka:19092"
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

# Partitions: 3, matching the listener concurrency in application.yml. A
# partition is the unit of parallelism, so a fourth consumer thread against
# three partitions would sit idle.
PARTITIONS=3

# Single broker locally, so this must be 1. Production overrides it; see
# docs/deployment.md.
REPLICATION=1

# 7 days. Long enough to replay a weekend outage from the start, short enough
# that the processed_events ledger stays bounded: the ledger only has to
# outlive the window in which a record can still be redelivered.
RETENTION_MS=604800000

create_topic() {
  local name="$1"
  echo "declaring ${name}"
  "${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${name}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION}" \
    --config retention.ms="${RETENTION_MS}" \
    --config cleanup.policy=delete
}

create_dlt() {
  local name="$1.DLT"
  echo "declaring ${name}"
  # Dead-letter topics keep their records far longer. A poison message needs a
  # person to look at it, and that person may not arrive the same week.
  "${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${name}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION}" \
    --config retention.ms=2592000000 \
    --config cleanup.policy=delete
}

echo "waiting for the broker to accept connections"
until "${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" --list > /dev/null 2>&1; do
  sleep 1
done

for topic in \
  gamehub.game.events.v1 \
  gamehub.achievement.events.v1 \
  gamehub.match.events.v1 \
  gamehub.cloudsave.events.v1
do
  create_topic "${topic}"
  create_dlt "${topic}"
done

echo "--- topics now present ---"
"${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" --list
