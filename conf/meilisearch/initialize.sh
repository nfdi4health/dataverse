#!/bin/sh

set -eu

: "${MEILI_URL:?MEILI_URL is required}"
: "${MEILI_MASTER_KEY:?MEILI_MASTER_KEY is required}"

MEILI_INDEX="${MEILI_INDEX:-dataverse}"
MEILI_PRIMARY_KEY="${MEILI_PRIMARY_KEY:-id}"
MEILI_SETTINGS_FILE="${MEILI_SETTINGS_FILE:-/conf/meilisearch/settings.json}"
MEILI_TASK_TIMEOUT_SECONDS="${MEILI_TASK_TIMEOUT_SECONDS:-300}"

request() {
  method="$1"
  path="$2"
  shift 2
  curl --silent --show-error --fail-with-body \
    --request "$method" \
    --header "Authorization: Bearer ${MEILI_MASTER_KEY}" \
    "$@" \
    "${MEILI_URL}${path}"
}

wait_for_task() {
  response="$1"
  task_uid=$(printf '%s' "$response" | sed -n 's/.*"taskUid":[[:space:]]*\([0-9][0-9]*\).*/\1/p')
  if [ -z "$task_uid" ]; then
    echo "Meilisearch response did not contain taskUid: ${response}" >&2
    return 1
  fi

  elapsed=0
  while [ "$elapsed" -lt "$MEILI_TASK_TIMEOUT_SECONDS" ]; do
    task=$(request GET "/tasks/${task_uid}")
    status=$(printf '%s' "$task" | sed -n 's/.*"status":[[:space:]]*"\([^"]*\)".*/\1/p')
    case "$status" in
      succeeded) return 0 ;;
      failed|canceled)
        echo "Meilisearch task ${task_uid} ${status}: ${task}" >&2
        return 1
        ;;
    esac
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "Timed out waiting for Meilisearch task ${task_uid}" >&2
  return 1
}

if request GET "/indexes/${MEILI_INDEX}" >/dev/null 2>&1; then
  echo "Meilisearch index ${MEILI_INDEX} already exists"
else
  echo "Creating Meilisearch index ${MEILI_INDEX}"
  response=$(request POST /indexes \
    --header "Content-Type: application/json" \
    --data-binary "{\"uid\":\"${MEILI_INDEX}\",\"primaryKey\":\"${MEILI_PRIMARY_KEY}\"}")
  wait_for_task "$response"
fi

echo "Applying settings to Meilisearch index ${MEILI_INDEX}"
response=$(request PATCH "/indexes/${MEILI_INDEX}/settings" \
  --header "Content-Type: application/json" \
  --data-binary "@${MEILI_SETTINGS_FILE}")
wait_for_task "$response"

if [ -n "${MEILI_EMBEDDER_NAME:-}" ]; then
  : "${MEILI_EMBEDDER_MODEL:?MEILI_EMBEDDER_MODEL is required when an embedder is enabled}"
  : "${MEILI_EMBEDDER_URL:?MEILI_EMBEDDER_URL is required when an embedder is enabled}"
  : "${MEILI_EMBEDDER_DIMENSIONS:?MEILI_EMBEDDER_DIMENSIONS is required when an embedder is enabled}"

  echo "Configuring Meilisearch embedder ${MEILI_EMBEDDER_NAME}"
  embedder=$(printf \
    '{"%s":{"source":"ollama","url":"%s","model":"%s","dimensions":%s,"documentTemplate":"{{doc.meiliSemanticText}}","documentTemplateMaxBytes":16000}}' \
    "$MEILI_EMBEDDER_NAME" "$MEILI_EMBEDDER_URL" "$MEILI_EMBEDDER_MODEL" "$MEILI_EMBEDDER_DIMENSIONS")
  response=$(request PATCH "/indexes/${MEILI_INDEX}/settings/embedders" \
    --header "Content-Type: application/json" \
    --data-binary "$embedder")
  wait_for_task "$response"
fi

echo "Meilisearch index ${MEILI_INDEX} is ready"
