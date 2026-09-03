# Dataset relation listing benchmark

This opt-in benchmark measures performance of `GET /api/datasets/{identifier}/relations` with a setup mimicking realistic database state: many released source datasets each define an internal relation pointing at one released target dataset. It exercises incoming relations, latest-released-version resolution, deduplication, ordering, pagination, and the endpoint's total-count query.

It is intentionally separate from integration tests. The runner reports measurements instead of enforcing a threshold.

## Prerequisites

- A local Dataverse instance running against PostgreSQL.
- `psql` and `curl` on the host.
- An API token for a superuser, or a user who can create a collection under the chosen parent and create relation types.
- `jq` for reading native-API responses.

The seed script creates and publishes its own temporary collection and target dataset through the native API, creates a self-inverse relation type, and then clones the target dataset's database rows to make synthetic released source datasets. Use a disposable local database only. The cleanup script removes the synthetic source rows created by this benchmark.

## Seed the fixture

Seed 10,000 incoming internal relations. The wrapper creates and publishes the collection and target dataset automatically:

```sh
tests/performance/relations/seed.sh \
  -d 'postgresql://dataverse:secret@localhost:5432/dataverse' \
  -b http://localhost:8080 \
  -k '<superuser-api-token>' \
  -n 10000
```

Pass `-p parent-alias` to create the temporary collection below a collection other than `root`. The script prints the generated target PID for use by the measurement command and runs `ANALYZE` after seeding.

## Measure the API

Run a warm-up followed by 20 timed requests. The endpoint keeps its ordinary `limit=10`, so this measures the common paginated API path as well as its total-count calculation.

```sh
tests/performance/relations/measure-list.sh \
  -b http://localhost:8080 \
  -p 'doi:10.5072/FK2/EXAMPLE' \
  -k '<api-token>'
```

The runner logs each warm-up and measured request, then prints min, median, p95, max, and mean durations. It uses a 10-second connection timeout and a 120-second request timeout, so a stalled request reports a failure instead of waiting indefinitely. Run it before and after each query or index change on the same environment.

## Inspect query plans

The API endpoint executes a list query and a total-count query. Capture the actual PostgreSQL plans for the same benchmark dataset with:

```sh
tests/performance/relations/explain.sh \
  -d 'postgresql://dataverse:secret@localhost:5432/dataverse' \
  -p 'doi:10.5072/FK2/EXAMPLE'
```

The script resolves the dataset and its latest released version, then runs both queries from `SqlDirectDatasetRelationAlgorithm` with `EXPLAIN (ANALYZE, BUFFERS)`. The output contains actual execution time, row estimates versus row counts, and buffer reads/hits.

## Clean up

```sh
tests/performance/relations/cleanup.sh \
  -d 'postgresql://dataverse:secret@localhost:5432/dataverse'
```
