#!/usr/bin/env bash
#
# smoke-test.sh — end-to-end verification of the async (RabbitMQ) event flow.
#
# Prereqs:
#   - `docker compose up --build -d` is running (Postgres, RabbitMQ, all services).
#   - curl and jq are installed.
#
# This script drives each HTTP service and then inspects the `notification`
# consumer logs to confirm the corresponding "email" was produced for every leg:
#
#   leg 1 (product availability):  POST productcatalog /products/{id}/units
#   leg 2 (credential pending):    POST microcredential /microcredentials/{courseId}/create
#   leg 3 (credential granted):    PATCH microcredential /microcredentials/{id}/approve
#
# Before the legs it resets the microcredential table (`POST /create` is idempotent per
# enrollment — ON CONFLICT DO NOTHING — so re-runs over a warm stack need a clean table to
# get a fresh REQUESTED row to approve) and seeds a user alert (the product-availability
# leg only emails users who are watching that product on today's date).
#
# Exit code is non-zero if any leg fails, so it can gate CI/deployments.

set -euo pipefail

readonly MC_PORT=18085
readonly PC_PORT=18081
readonly USER_PORT=18082
readonly PRODUCT_ID=1

readonly NOTIF_SVC="notification"
readonly NOTIF="docker compose logs $NOTIF_SVC"

base() { printf 'http://localhost:%s' "$1"; }

say_fail() { echo "  FAIL $*" >&2; }

expect_http() {
  # expect_http <method> <url> <expected_http_code>
  local method="$1" url="$2" want="$3"
  local got
  got=$(curl -s -X "$method" -o /tmp/smoke-body.$$ -w '%{http_code}' "$url")
  if [[ "$got" != "$want" ]]; then
    say_fail "expected HTTP $want from $method $url, got $got: $(cat /tmp/smoke-body.$$)"
    return 1
  fi
  echo "  OK  $method $url -> $got"
}

wait_for_http() {
  # wait_for_http <url> <timeout_seconds>
  local url="$1" timeout="${2:-120}" i=0
  while ! curl -sf -o /dev/null "$url" 2>/dev/null; do
    i=$((i + 1))
    if (( i > timeout )); then
      say_fail "timed out waiting for $url"
      return 1
    fi
    sleep 1
  done
}

echo "== smoke-test: async event flow =="

echo "Waiting for services to come up..."
wait_for_http "$(base $PC_PORT)/products/1" || exit 1
wait_for_http "$(base $MC_PORT)/microcredentials/pending" || exit 1

# Capture a timestamp so we only look at emails produced by this run.
SINCE="$(date -u +%Y-%m-%dT%H:%M:%S)"
echo "Inspecting notification logs since $SINCE"

# --- state reset ------------------------------------------------------------------
# `POST /microcredentials/{courseId}/create` is idempotent per enrollment (the repo uses
# ON CONFLICT DO NOTHING), so over a warm stack a re-run cannot produce a fresh REQUESTED
# row to approve. Reset the credential table so the create -> approve legs stay re-runnable.
echo "Resetting microcredential state..."
if ! docker compose exec -T postgres psql -U demo -d credential -c "DELETE FROM microcredential" >/dev/null 2>&1; then
  say_fail "could not reset the microcredential table (is the stack up?)"
  exit 1
fi

# --- seed an alert --------------------------------------------------------------
# The product-availability leg only emits an email if *some user is watching* that product
# on today's date. Seed an alert over a wide window so the leg works on any run day.
seed_alert() {
  local user_id
  user_id=$(curl -s "http://localhost:$USER_PORT/users" | jq -r '.[0].id // empty')
  if [[ -z "$user_id" ]]; then
    say_fail "no users seeded to alert"
    return 1
  fi
  local body="{\"productId\":$PRODUCT_ID,\"userId\":$user_id,\"from\":\"2020-01-01\",\"to\":\"2099-12-31\"}"
  local code
  code=$(curl -s -X POST -H 'Content-Type: application/json' -d "$body" \
    -o /dev/null -w '%{http_code}' "http://localhost:$USER_PORT/alerts")
  if [[ "$code" != "201" ]]; then
    say_fail "POST /alerts -> $code"
    return 1
  fi
  echo "  OK  seeded alert for product $PRODUCT_ID (user id=$user_id)"
}

seed_alert || exit 1

# --- leg 1: productcatalog publishes product.unit_available -------------------
echo "Leg 1: product availability"
expect_http POST "$(base $PC_PORT)/products/$PRODUCT_ID/units" 200 || exit 1
expect_http POST "$(base $PC_PORT)/products/999999/units" 404 || exit 1
expect_http GET "$(base $PC_PORT)/v3/api-docs" 200 || exit 1
sleep 3

# --- leg 2: microcredential pending -------------------------------------------
echo "Leg 2: microcredential pending"
expect_http POST "$(base $MC_PORT)/microcredentials/1/create" 200 || exit 1
sleep 3

# --- leg 3: microcredential granted -------------------------------------------
echo "Leg 3: microcredential granted"
MC_ID=$(curl -s "$(base $MC_PORT)/microcredentials/pending" | jq -r '.[0].id // empty')
if [[ -z "$MC_ID" ]]; then
  say_fail "no pending microcredential available to approve"
  exit 1
fi
echo "  approving microcredential id=$MC_ID"
expect_http PATCH "$(base $MC_PORT)/microcredentials/$MC_ID/approve" 200 || exit 1
sleep 3

# --- checks -------------------------------------------------------------------
echo "Verifying notification logs..."
OK=1

if $NOTIF --since "$SINCE" | grep -q "Sending an email.*product"; then
  echo "  OK  product.unit_available leg detected"
else
  say_fail "product.unit_available leg NOT detected"; OK=0
fi

if $NOTIF --since "$SINCE" | grep -q "Sending an email.*pending"; then
  echo "  OK  microcredential.pending leg detected"
else
  say_fail "microcredential.pending leg NOT detected"; OK=0
fi

if $NOTIF --since "$SINCE" | grep -q "Sending an email.*granted"; then
  echo "  OK  microcredential.granted leg detected"
else
  say_fail "microcredential.granted leg NOT detected"; OK=0
fi

rm -f /tmp/smoke-body.$$

if (( OK )); then
  echo "== smoke-test PASSED =="
else
  echo "== smoke-test FAILED ==" >&2
  exit 1
fi
