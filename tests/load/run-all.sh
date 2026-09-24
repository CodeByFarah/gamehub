#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Runs every load scenario and writes a JSON summary per scenario.
#
#   ./tests/load/run-all.sh
#   BASE_URL=http://staging.example.test ./tests/load/run-all.sh
#
# Exits non-zero if any scenario misses a threshold, so this is usable as a
# CI gate rather than as a report someone has to read.
# ---------------------------------------------------------------------------
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "${RESULTS_DIR}"

if ! command -v k6 > /dev/null 2>&1; then
  echo "k6 is not on PATH. Install it from https://k6.io/docs/get-started/installation/"
  exit 127
fi

echo "checking that the backend is reachable at ${BASE_URL}"
if ! curl -fsS "${BASE_URL}/actuator/health/readiness" > /dev/null 2>&1; then
  echo "backend is not ready at ${BASE_URL}"
  echo "start it with: docker compose up -d && (cd backend && ./gradlew bootRun)"
  exit 1
fi

echo "seeding load-test accounts"
# Seeding is a prerequisite, not a measurement, so a failure here stops the run
# rather than producing scenarios that all fail to authenticate.
if ! BASE_URL="${BASE_URL}" k6 run --quiet "${SCRIPT_DIR}/seed-users.js"; then
  echo "seeding failed, aborting"
  exit 1
fi

SCENARIOS=(
  browse-catalogue
  leaderboard-read
  matchmaking-join
  session-write
)

FAILED=()

for scenario in "${SCENARIOS[@]}"; do
  echo
  echo "=============================================================="
  echo "running ${scenario}"
  echo "=============================================================="

  summary="${RESULTS_DIR}/${scenario}-${STAMP}.json"

  # Every scenario runs even if an earlier one failed. Stopping at the first
  # failure would hide whether the problem is one endpoint or all of them.
  if BASE_URL="${BASE_URL}" k6 run \
      --summary-export="${summary}" \
      "${SCRIPT_DIR}/${scenario}.js"; then
    echo "${scenario}: thresholds met, summary at ${summary}"
  else
    echo "${scenario}: THRESHOLD MISSED, summary at ${summary}"
    FAILED+=("${scenario}")
  fi
done

echo
echo "=============================================================="
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "all scenarios met their thresholds"
  echo "results written to ${RESULTS_DIR}"
  exit 0
fi

echo "scenarios that missed a threshold: ${FAILED[*]}"
echo "results written to ${RESULTS_DIR}"
exit 1
