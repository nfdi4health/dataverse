#!/bin/sh
set -eu

base_url=${MEILISEARCH_URL:-http://dev_meilisearch:7700}
index=${MEILISEARCH_INDEX:-zbdocs}
api_key=${MEILISEARCH_API_KEY:-dataverse-test-master-key}
settings_file=${MEILISEARCH_SETTINGS_FILE:-/etc/meilisearch/settings.json}
embedder=${MEILISEARCH_EMBEDDER:-}
embedder_url=${MEILISEARCH_EMBEDDER_URL:-}
embedder_model=${MEILISEARCH_EMBEDDER_MODEL:-}
embedder_dimensions=${MEILISEARCH_EMBEDDER_DIMENSIONS:-}

wait_for_task() {
    task_uid=$1
    attempts=0

    while [ "$attempts" -lt 120 ]; do
        task=$(curl --fail --silent --show-error \
            -H "Authorization: Bearer $api_key" \
            "$base_url/tasks/$task_uid")
        case "$task" in
            *'"status":"succeeded"'*) return 0 ;;
            *'"status":"failed"'*|*'"status":"canceled"'*)
                printf '%s\n' "$task" >&2
                return 1
                ;;
        esac
        attempts=$((attempts + 1))
        sleep 0.25
    done

    printf 'Timed out waiting for Meilisearch task %s\n' "$task_uid" >&2
    return 1
}

task_uid_from_response() {
    task_uid=$(printf '%s\n' "$1" | sed -n 's/.*"taskUid":\([0-9][0-9]*\).*/\1/p')
    if [ -z "$task_uid" ]; then
        printf 'Meilisearch response did not contain a task UID: %s\n' "$1" >&2
        return 1
    fi
    printf '%s\n' "$task_uid"
}

if ! curl --fail --silent --output /dev/null \
    -H "Authorization: Bearer $api_key" \
    "$base_url/indexes/$index"; then
    response=$(curl --fail --silent --show-error \
        -X POST \
        -H "Authorization: Bearer $api_key" \
        -H 'Content-Type: application/json' \
        --data "{\"uid\":\"$index\",\"primaryKey\":\"id\"}" \
        "$base_url/indexes")
    wait_for_task "$(task_uid_from_response "$response")"
fi

response=$(curl --fail --silent --show-error \
    -X PATCH \
    -H "Authorization: Bearer $api_key" \
    -H 'Content-Type: application/json' \
    --data-binary "@$settings_file" \
    "$base_url/indexes/$index/settings")
wait_for_task "$(task_uid_from_response "$response")"

if [ -n "$embedder" ]; then
    if [ -z "$embedder_url" ] || [ -z "$embedder_model" ] || [ -z "$embedder_dimensions" ]; then
        printf '%s\n' \
            'MEILISEARCH_EMBEDDER_URL, MEILISEARCH_EMBEDDER_MODEL, and MEILISEARCH_EMBEDDER_DIMENSIONS are required when MEILISEARCH_EMBEDDER is set.' >&2
        exit 1
    fi
    case "$embedder_dimensions" in
        *[!0-9]*|'')
            printf 'MEILISEARCH_EMBEDDER_DIMENSIONS must be a positive integer, got: %s\n' "$embedder_dimensions" >&2
            exit 1
            ;;
    esac

    embedder_settings=$(printf \
        '{"%s":{"source":"ollama","url":"%s","model":"%s","dimensions":%s,"documentTemplate":"Title: {{doc.title}}\\n\\n{{doc.text}}","documentTemplateMaxBytes":16000}}' \
        "$embedder" "$embedder_url" "$embedder_model" "$embedder_dimensions")
    response=$(curl --fail --silent --show-error \
        -X PATCH \
        -H "Authorization: Bearer $api_key" \
        -H 'Content-Type: application/json' \
        --data "$embedder_settings" \
        "$base_url/indexes/$index/settings/embedders")
    wait_for_task "$(task_uid_from_response "$response")"
fi

printf 'Meilisearch index %s is ready.\n' "$index"
