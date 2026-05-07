# Development

How to set up a hades development environment and run the same tests,
conformance, and benchmark workloads CI runs.

## Prerequisites

- Java 21+
- Clojure CLI (`clj` / `clojure`)
- ~15 GB free disk for `.hades/` fixtures
- For SNOMED CT fixtures: an MLDS Affiliate account and/or a TRUD API key
- For LOINC: a free account at [loinc.org](https://loinc.org/downloads/)
  to download the release archive

## Test fixtures

All tests, conformance and benchmark runs read fixtures from `.hades/`
(gitignored). The same fixtures CI builds in
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) are expected to
exist locally. Fixture paths are declared in
[`test/com/eldrix/hades/fixtures.clj`](../test/com/eldrix/hades/fixtures.clj).

| Fixture                          | Path                              | Pinned to                                | Source                                   |
|----------------------------------|-----------------------------------|------------------------------------------|------------------------------------------|
| SNOMED CT International          | `.hades/snomed-intl-20250201.db`  | `ihtsdo.mlds/167@2025-02-01`             | MLDS                                     |
| SNOMED CT UK monolith            | `.hades/snomed-uk-monolith.db`    | `uk.nhs/sct-monolith` (latest)           | TRUD                                     |
| LOINC                            | `.hades/loinc-2.81.db`            | LOINC release `2.81`                     | loinc.org (free account)                 |
| FHIR packages (SQLite container) | `.hades/fhir-smoke.db`            | `hl7.fhir.r4.core@4.0.1` + 5 others      | packages.fhir.org                        |
| FHIR packages (unpacked cache)   | `.hades/fhir-packages/`           | same as above                            | packages.fhir.org                        |
| tx-ecosystem (conformance)       | `.hades/tx-ecosystem/`            | rev pinned in `conformance_test.clj`     | `HL7/fhir-tx-ecosystem-ig` (auto-cloned) |

### SNOMED CT International (conformance pin)

The conformance suite, the `^:live` test tag and the criterium benchmarks
all run against this single pinned release — pinning matters because the
IG's tx-ecosystem fixtures were authored against this exact edition.

```bash
printf '%s' "$MLDS_PASSWORD" > .hades/mlds-password.txt
chmod 600 .hades/mlds-password.txt
clj -M:run install compact .hades/snomed-intl-20250201.db \
  --dist ihtsdo.mlds/167@2025-02-01 \
  --username "$MLDS_USERNAME" \
  --password .hades/mlds-password.txt
rm -f .hades/mlds-password.txt
```

Build takes ~2 minutes; the resulting DB is ~2.7 GB.

### SNOMED CT UK monolith (UK clinical + drug extensions)

Used by the matrix tests, tx-benchmark and ad-hoc work that exercises UK
content. The TRUD distribution is rolling — CI rebuilds monthly.

```bash
printf '%s' "$TRUD_API_KEY" > .hades/trud-api-key.txt
chmod 600 .hades/trud-api-key.txt
clj -M:run install compact .hades/snomed-uk-monolith.db \
  --dist uk.nhs/sct-monolith \
  --api-key .hades/trud-api-key.txt
rm -f .hades/trud-api-key.txt
```

### LOINC

LOINC is licensed and not redistributable, so you download it yourself:
sign in at [loinc.org/downloads](https://loinc.org/downloads/) (free
account), grab the **LOINC Table File (CSV)** archive for release 2.81,
and unzip it. Then point `import` at the unzipped directory:

```bash
unzip /path/to/Loinc_2.81.zip -d /tmp/Loinc_2.81
clj -M:run import compact .hades/loinc-2.81.db /tmp/Loinc_2.81
```

`import` auto-detects the LOINC release layout (it looks for
`LoincTableCore/LoincTableCore.csv`).

### FHIR packages

```bash
clj -M:run install compact .hades/fhir-smoke.db \
  --dist hl7.fhir.r4.core@4.0.1 \
  --dist hl7.terminology.r4@7.0.1 \
  --dist hl7.fhir.us.core@6.1.0 \
  --dist hl7.fhir.uv.ips@2.0.0 \
  --dist hl7.fhir.uv.ips@1.1.0 \
  --dist fhir.tx.support.r4@0.34.0 \
  --cache-dir .hades/fhir-packages
```

This populates both the SQLite container (`fhir-smoke.db`) and the
unpacked-tarball cache (`fhir-packages/`) — the parity test runs the
in-memory provider over the cache and the SQLite provider over the
container.

### Use a modern Hermes build

The SNOMED `.db` artefact's index format reflects whichever Hermes
version built it. Older builds may lack the `concept-id` doc-values
column — Hades has a stored-fields fallback, but the doc-values path is
faster. When in doubt, rebuild with the Hermes pinned in `deps.edn`.

## Running the suite

```bash
clj -M:test                              # unit + ^:live tests
clj -M:lint/kondo                        # static analysis
clj -X:conformance                       # IG conformance suite
clj -M:bench                             # criterium benchmarks
clj -M:check                             # compilation check
```

Each entry point fails fast with a clear hint if a required fixture is
missing. See [`CLAUDE.md`](../CLAUDE.md) for project conventions and the
broader architectural map.

## REPL workflow

Start an nREPL with conformance deps so you can iterate on the test
loop without re-bootstrapping per change:

```bash
clj -M:nrepl:conformance
```

The conformance test namespace exposes `start! / stop! / restart! /
run-tests / save-baseline!` — see its docstring for the source of truth.

## Updating fixture pins

Bumping a pin (LOINC version, FHIR package version, the conformance
SNOMED edition) is a deliberate act:

1. Update the pinned id everywhere it appears: this file, the matching
   path in `fixtures.clj`, the corresponding cache key in `ci.yml`.
2. Re-run conformance and update the baseline figure (see the release
   checklist in `CLAUDE.md`).
3. Bump the cache-key suffix (`-v1` → `-v2`) so CI rebuilds rather than
   restoring the previous artefact.

## Run tx-benchmark

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

### Don't do these

These are common ways to get the wrong answer:

- **Don't read or run scripts under `~/Dev/tx-benchmark/scripts/`
  unless they exist at the pinned SHA.** Anything *untracked* there
  (e.g. `bench-hades-native.sh`, `run-native.ts`, `report-native.ts`)
  is stale local scaffolding from a prior session, not part of the
  benchmark. Use only the recipes below.
- **Don't pass unpacked `.hades/fhir-packages/<id>/package` directories
  to `serve`.** The canonical FHIR fixture is `.hades/fhir-smoke.db`,
  one positional arg.
- **Don't change the port to match `tx-benchmark/servers.json`.** That
  file is for the Docker pipeline. We pass the URL explicitly to k6,
  so port `8080` works fine.
- **Don't `Monitor` hades startup.** Startup is one-shot. Use a
  Bash `until` poll on `/fhir/metadata` (recipes below do this).

### 1. Sync tx-benchmark to the pinned SHA

```bash
# First time only:
git clone https://github.com/wardle/tx-benchmark.git ~/Dev/tx-benchmark

# Every time — check out the pinned rev and verify clean:
cd ~/Dev/tx-benchmark
git fetch --all
git checkout "$(cat ~/Dev/hades/test/resources/tx-benchmark-pin.txt)"
git status   # must be clean
```

The pinned SHA lives in
[`test/resources/tx-benchmark-pin.txt`](../test/resources/tx-benchmark-pin.txt).
Local edits drift silently from CI and from the published methodology.

### 2. Run a flavor

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

#### `preflight`

```bash
set -e
cd ~/Dev/hades
RUN_ID="$(date -u +%Y-%m-%dT%H%M)-2.0.$(git rev-list --count HEAD)-$(git rev-parse --short HEAD)$(git diff-index --quiet HEAD -- src test deps.edn build.clj || echo -dirty)"
clj -M:run serve \
  .hades/snomed-uk-monolith.db \
  .hades/loinc-2.81.db \
  .hades/fhir-smoke.db \
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

#### `quick`

Preflight + every passing test at 1 VU / 10 s. Same shape as `full`,
but at the lowest VU level only and a short duration — broad
regression sweep across the entire benchmark surface.

```bash
set -e
cd ~/Dev/hades
RUN_ID="$(date -u +%Y-%m-%dT%H%M)-2.0.$(git rev-list --count HEAD)-$(git rev-parse --short HEAD)$(git diff-index --quiet HEAD -- src test deps.edn build.clj || echo -dirty)"
clj -M:run serve \
  .hades/snomed-uk-monolith.db \
  .hades/loinc-2.81.db \
  .hades/fhir-smoke.db \
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

#### Spot-check one test

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

#### `full`

The full sweep with metrics pushed to a local Prometheus / Pushgateway
and visible in Grafana. Prerequisites: `bun`, `docker`, `k6`.

```bash
set -e
cd ~/Dev/hades
RUN_ID="$(date -u +%Y-%m-%dT%H%M)-2.0.$(git rev-list --count HEAD)-$(git rev-parse --short HEAD)$(git diff-index --quiet HEAD -- src test deps.edn build.clj || echo -dirty)"
clj -T:build uber
java -Xmx6g -jar target/hades.jar serve \
  .hades/snomed-uk-monolith.db \
  .hades/loinc-2.81.db \
  .hades/fhir-smoke.db \
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

### Updating the pin

When upstream tx-benchmark adds tests or changes pool data we want to
pick up:

1. `cd ~/Dev/tx-benchmark && git fetch --all && git checkout <new-sha>`
2. Run `preflight`; fix any newly-failing tests in hades.
3. Update `test/resources/tx-benchmark-pin.txt` to the new SHA.
4. Commit. CI runs `preflight` at the new pin and gates on no failures.

### Edits to tx-benchmark — work via the fork, never against a dirty tree

Discouraged in everyday work. The pin determines what CI runs and what
gets published; a dirty working tree silently drifts from both. If you
do need to change tx-benchmark itself (a new hades-specific test, a
pool fix, an overly-strict check):

1. **Branch from the pinned SHA in `wardle/tx-benchmark`.**
   ```bash
   cd ~/Dev/tx-benchmark
   git fetch --all
   git checkout "$(cat ~/Dev/hades/test/resources/tx-benchmark-pin.txt)"
   git checkout -b <topic-branch>
   ```
2. **Make the change, commit, push to the fork.**
   ```bash
   git push -u fork <topic-branch>
   ```
3. **Open a PR upstream** (`HealthSamurai/tx-benchmark`).
4. **Bump the pin to the fork commit immediately** — don't wait for the
   PR to merge. Both CI (which checks out `wardle/tx-benchmark`) and
   local devs follow the pin file, so pinning to the fork branch's
   commit gets the fix in front of every consumer right away.
   ```
   # test/resources/tx-benchmark-pin.txt
   <fork-commit-sha>
   ```
5. **When the PR merges upstream**, fetch origin, pick the merged-into-
   master SHA, and bump the pin again.
   ```bash
   git -C ~/Dev/tx-benchmark fetch --all
   # update test/resources/tx-benchmark-pin.txt to the merged SHA
   ```

Don't force-push the topic branch on the fork while the pin references
it — that invalidates the pinned SHA for everyone (cache misses in CI,
broken local checkouts).
