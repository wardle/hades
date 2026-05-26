# Running tx-benchmark

[tx-benchmark](https://github.com/HealthSamurai/tx-benchmark) from
HealthSamurai drives a suite of k6 load tests against a running FHIR
terminology server. Hades' fork — `wardle/tx-benchmark` — pins a small
patch series until it lands upstream.

When asked to "run tx-benchmark", pick a flavor:

| Flavor      | Time      | What it runs                                                            | Use when                                            |
|-------------|-----------|-------------------------------------------------------------------------|-----------------------------------------------------|
| `preflight` | ~1 min    | Correctness check across every op (no perf numbers)                     | After a code change, before quoting any numbers     |
| `quick`     | ~5 min    | Preflight + every passing test at **1 VU / 10 s** each                  | Broad regression sweep at low load; before/after    |
| `full`      | ~30+ min  | Preflight + warmup + bench at VUs 1 / 10 / 50 across all tests          | Cross-server comparison or release-note numbers     |

For ad-hoc spot-checking of a single test, run `k6` directly (see
[Spot-check one test](#spot-check-one-test) below) — that's one
command, no flavor needed.

## Don't do these

These are common ways to get the wrong answer:

- **Don't read or run scripts under `~/Dev/tx-benchmark/scripts/`
  that aren't tracked in the repo.** Anything *untracked* there
  (e.g. `bench-hades-native.sh`, `run-native.ts`, `report-native.ts`)
  is stale local scaffolding from a prior session, not part of the
  benchmark. Use only the recipes below.
- **Don't pass the whole `.hades/fhir-cache/` cache directory to
  `serve`.** The canonical FHIR fixture is the single combined FTRM
  container `.hades/fhir-tx.db`, which already holds every FHIR package
  including VSAC. The recipes serve that one file — not the unpacked
  cache, and not a separate VSAC database.
- **VSAC is served by FTRM here, like everything else.** `fhir-tx.db` is
  the **FTRM (SQLite)** provider — the same path CI preflight and the
  criterium bench (`clj -M:bench`) exercise, so EX04 latency is
  comparable across all three. (The in-memory provider over the unpacked
  cache serves the same resources but at different latency/footprint; the
  parity tests use it, the benchmark does not.)
- **Don't change the port to match `tx-benchmark/servers.json`.** That
  file is for the Docker pipeline. We pass the URL explicitly to k6,
  so port `8080` works fine.
- **Don't `Monitor` hades startup.** Startup is one-shot. Use a
  Bash `until` poll on `/fhir/metadata` (recipes below do this).

## Run a flavor

Each block is self-contained: it boots hades against the canonical
fixture set, waits for readiness, runs the chosen flavor, and shuts
hades down. Run the entire block as one shell script (or pipe through
`bash -e`); each step depends on the one before it.

Each recipe computes a unique `RUN_ID` so every run lands under its own
directory and history is preserved. The format is
`<utc-date>T<hhmm>-<hades-version>-<sha>[-dirty]`, e.g.
`2026-05-07T2015-2.0.189-e2587dc-dirty`. The `-dirty` suffix appears
whenever `src/`, `test/`, `deps.edn`, or `build.clj` carry uncommitted
changes — without it, comparison would silently lie about what code
produced the numbers.

For `full` runs, build an uberjar first and substitute
`java -Xmx6g -jar target/hades.jar serve …` for the `clj -M:run serve …`
line — better startup, lower JVM noise. (`clj -T:build uber`.)

### `preflight`

```bash
set -e
cd ~/Dev/hades
RUN_ID="$(date -u +%Y-%m-%dT%H%M)-2.0.$(git rev-list --count HEAD)-$(git rev-parse --short HEAD)$(git diff-index --quiet HEAD -- src test deps.edn build.clj || echo -dirty)"
clj -M:run serve \
  .hades/snomed-uk-monolith.db \
  .hades/loinc-2.82.db \
  .hades/fhir-tx.db \
  --port 8080 > /tmp/hades.log 2>&1 &
HADES_PID=$!
trap 'kill $HADES_PID 2>/dev/null' EXIT

until curl -fsS http://localhost:8080/fhir/metadata >/dev/null 2>&1; do sleep 1; done

cd ~/Dev/tx-benchmark
mkdir -p "results/$RUN_ID/hades" && \
k6 run \
  --env BASE_URL=http://localhost:8080/fhir \
  --env SERVER_NAME=hades \
  --env RUN_ID="$RUN_ID" \
  preflight/run.js

jq '.tests | with_entries(select(.value.status != "pass"))' \
   "results/$RUN_ID/hades/preflight.json"
```

`pass` / `skip` are both fine. `skip` means the operation is not
claimed by hades and the benchmark phase skips it. `fail` is a bug.

### `quick`

Preflight + every passing test at 1 VU / 10 s. Same shape as `full`,
but at the lowest VU level only and a short duration — broad
regression sweep across the entire benchmark surface.

```bash
set -e
cd ~/Dev/hades
RUN_ID="$(date -u +%Y-%m-%dT%H%M)-2.0.$(git rev-list --count HEAD)-$(git rev-parse --short HEAD)$(git diff-index --quiet HEAD -- src test deps.edn build.clj || echo -dirty)"
clj -M:run serve \
  .hades/snomed-uk-monolith.db \
  .hades/loinc-2.82.db \
  .hades/fhir-tx.db \
  --port 8080 > /tmp/hades.log 2>&1 &
HADES_PID=$!
trap 'kill $HADES_PID 2>/dev/null' EXIT

until curl -fsS http://localhost:8080/fhir/metadata >/dev/null 2>&1; do sleep 1; done

cd ~/Dev/tx-benchmark
mkdir -p "results/$RUN_ID/hades/benchmark"

# 1. Preflight (records which tests pass, gates the bench loop below)
k6 run \
  --env BASE_URL=http://localhost:8080/fhir \
  --env SERVER_NAME=hades \
  --env RUN_ID="$RUN_ID" \
  preflight/run.js

# 2. Per-test bench at 1 VU / 10 s for every test that passed preflight
for test in $(jq -r '.tests | to_entries[] | select(.value.status=="pass") | .key' \
                  "results/$RUN_ID/hades/preflight.json"); do
  cat="${test:0:2}"   # FS / LK / VC / EX / SS / CM
  echo "─── $test ───"
  k6 run --vus 1 --duration 10s \
    --env BASE_URL=http://localhost:8080/fhir \
    --env SERVER_NAME=hades \
    --env RUN_ID="$RUN_ID" \
    --env TEST_ID="$test" \
    --env VUS=1 \
    "k6/${cat}/${test}.js"
done
```

Per-test summaries land under `results/$RUN_ID/hades/benchmark/`. For a
cross-test comparison table, see `full` (which produces the same
layout at three VU levels).

### Spot-check one test

For iterating on a single hot path, no flavor needed — just run k6
directly against an already-running hades. Test ids: `FS01`,
`LK01`–`LK05`, `VC01`–`VC03`, `EX01`–`EX08`, `SS01`, `CM01`–`CM02`.

Spot-checks are the one place a scratch run-id is appropriate: you're
iterating, the numbers aren't comparable across iterations anyway, and
preserving each one would just litter `results/`. Pick a memorable tag
(`probe`, `lk02-async`, your branch name) so you can tell it apart from
the dated runs.

```bash
cd ~/Dev/tx-benchmark
RUN_ID=probe                         # or any short scratch label
mkdir -p "results/$RUN_ID/hades/benchmark"
k6 run --vus 10 --duration 10s \
  --env BASE_URL=http://localhost:8080/fhir \
  --env SERVER_NAME=hades \
  --env RUN_ID="$RUN_ID" \
  --env TEST_ID=EX01 \
  --env VUS=10 \
  k6/EX/EX01.js
```

### `full`

The full sweep with metrics pushed to a local Prometheus / Pushgateway
and visible in Grafana. Prerequisites: `bun`, `docker`, `k6`.

```bash
set -e
cd ~/Dev/hades
RUN_ID="$(date -u +%Y-%m-%dT%H%M)-2.0.$(git rev-list --count HEAD)-$(git rev-parse --short HEAD)$(git diff-index --quiet HEAD -- src test deps.edn build.clj || echo -dirty)"
clj -T:build uber
java -Xmx6g -jar target/hades.jar serve \
  .hades/snomed-uk-monolith.db \
  .hades/loinc-2.82.db \
  .hades/fhir-tx.db \
  --port 8080 > /tmp/hades.log 2>&1 &
HADES_PID=$!
trap 'kill $HADES_PID 2>/dev/null' EXIT

until curl -fsS http://localhost:8080/fhir/metadata >/dev/null 2>&1; do sleep 1; done

cd ~/Dev/tx-benchmark
( cd observability && docker compose up -d )
bun scripts/run.ts hades http://localhost:8080/fhir "$RUN_ID"
```

Results land in `results/$RUN_ID/hades/` (`preflight.json` + a
`benchmark/` tree, one file per test × VU level). Grafana on
[http://localhost:3000](http://localhost:3000).

`scripts/run.ts` **hard-fails if Prometheus is not reachable at
`localhost:9090`** — the `observability` compose stack must be up first.
If Docker is unavailable, use the no-Docker sweep below instead; it
produces the same per-test `benchmark/` JSON, just without Grafana.

### No-Docker comparable sweep (VUs 1 / 10 / 50)

When you need round-0-comparable numbers but can't run Docker, drive k6
directly. Same VU levels and 30s duration as round-0, same output layout
as `full`; the only thing missing is the Prometheus/Grafana stream.

```bash
set -e
cd ~/Dev/hades
RUN_ID="$(date -u +%Y-%m-%dT%H%M)-2.0.$(git rev-list --count HEAD)-$(git rev-parse --short HEAD)$(git diff-index --quiet HEAD -- src test deps.edn build.clj || echo -dirty)"
clj -M:run serve \
  .hades/snomed-uk-monolith.db \
  .hades/loinc-2.82.db \
  .hades/fhir-tx.db \
  --port 8080 > /tmp/hades.log 2>&1 &
HADES_PID=$!
trap 'kill $HADES_PID 2>/dev/null' EXIT

until curl -fsS http://localhost:8080/fhir/metadata >/dev/null 2>&1; do sleep 1; done

cd ~/Dev/tx-benchmark
mkdir -p "results/$RUN_ID/hades/benchmark"
k6 run --env BASE_URL=http://localhost:8080/fhir --env SERVER_NAME=hades \
  --env RUN_ID="$RUN_ID" preflight/run.js

for test in $(jq -r '.tests | to_entries[] | select(.value.status=="pass") | .key' \
                  "results/$RUN_ID/hades/preflight.json"); do
  cat="${test:0:2}"
  for vus in 1 10 50; do
    echo "─── $test / ${vus}vu ───"
    k6 run --vus "$vus" --duration 30s \
      --env BASE_URL=http://localhost:8080/fhir --env SERVER_NAME=hades \
      --env RUN_ID="$RUN_ID" --env TEST_ID="$test" --env VUS="$vus" \
      "k6/${cat}/${test}.js"
  done
done
```

## Compare against published round-0

The published results are the **canonical comparison baseline**. Read
them from the live site:
[`healthsamurai.github.io/tx-benchmark/results/round-0/tests/<TEST>/`](https://healthsamurai.github.io/tx-benchmark/results/round-0/)
(per-test pages, all five servers). Round 0 is the **only** published
round (`runs.json`) — there is no newer one to accidentally skip.

That site is built from a machine-readable mirror vendored at
`~/Dev/tx-benchmark/site/src/data/round-0.json`, which carries the same
numbers at full precision. Prefer the local JSON for precise values, but
confirm its `date` still matches the site before trusting it (last
matched `2026-05-19`). Per-server schema:

```
.servers[] | select(.id=="hades") | .benchmark.<TEST>.<"1"|"10"|"50">
  → { rps, p50, p95, p99, avg, min, max, errorRate }   # latency in ms
.config → { vus:[1,10,50], testDuration:"30s", tests:[…], bias:{…} }
```

Map local k6 output onto that schema:

| local `benchmark/<TEST>_vus<N>.json` | round-0 `benchmark.<TEST>.<N>` |
|--------------------------------------|--------------------------------|
| `throughput`                         | `rps`                          |
| `duration.p50` / `p95` / `p99` / `avg` / `min` / `max` | same keys (no `duration.` prefix) |
| `error_rate`                         | `errorRate`                    |

**Caveats when reading the comparison:**

- **Ignore the `version` field in `round-0.json` (`v1.4.1540`) — it is a
  stale mislabel.** The binary that produced round-0's hades numbers is
  whatever `servers/hades/Dockerfile` pinned via its `ADD …/releases/…`
  URL at the time (a recent **2.0** release — `v2.0.206` as of this
  writing). Round-0 hades is therefore a valid near-current like-for-like,
  only a handful of commits behind `HEAD`. Check the Dockerfile pin for
  the exact version.
- **Latency is host-bound.** Round 0 ran on Apple M3 8-core / 24 GB /
  Docker Desktop (20 GB). Cross-machine latency deltas are not
  meaningful; compare *ratios between servers* and your own run-to-run
  trend, not absolute milliseconds against round-0.

## Edits to tx-benchmark

CI and local dev both track the head of [`wardle/tx-benchmark`](https://github.com/wardle/tx-benchmark)
(our fork) — there is no pinned commit. To pick up upstream test or
pool-data changes, just `git -C ~/Dev/tx-benchmark pull` and re-run
`preflight`; fix any newly-failing ops in hades.

If you need to change tx-benchmark itself (a new hades-specific test, a
pool fix, an overly-strict check): make the change on a branch of the
fork, push it, open a PR to `HealthSamurai/tx-benchmark`, and merge it
into the fork's default branch so CI and local dev pick it up. Keep the
fork's default branch in a state CI can run — `preflight` gates on no
failures.
