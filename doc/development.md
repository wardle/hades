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

| Fixture                          | Path                              | Provider exercised        | Pinned to                                | Source                                   |
|----------------------------------|-----------------------------------|---------------------------|------------------------------------------|------------------------------------------|
| SNOMED CT International          | `.hades/snomed-intl-20250201.db`  | Hermes (LMDB + Lucene)    | `ihtsdo.mlds/167@2025-02-01`             | MLDS                                     |
| SNOMED CT UK monolith            | `.hades/snomed-uk-monolith.db`    | Hermes (LMDB + Lucene)    | `uk.nhs/sct-monolith` (latest)           | TRUD                                     |
| LOINC                            | `.hades/loinc-2.82.db`            | native LOINC              | LOINC release `2.82`                     | loinc.org (free account)                 |
| FHIR packages (combined container) | `.hades/fhir-tx.db`             | FTRM (SQLite)             | `hl7.fhir.r4.core@4.0.1` + 7 others (incl. `us.cdc.phinvads@0.12.0`, `us.nlm.vsac@0.24.0`) | packages.fhir.org / packages2.fhir.org |
| FHIR packages (tarball cache)    | `.hades/fhir-cache/`           | in-memory (fhir-json)     | same package set as above                | packages.fhir.org / packages2.fhir.org   |
| tx-ecosystem (conformance)       | `.hades/tx-ecosystem/`            | — (test data)             | rev pinned in `conformance_test.clj`     | `HL7/fhir-tx-ecosystem-ig` (auto-cloned) |

> **Same data, two providers.** The full FHIR package set (including
> VSAC's 9,071 ValueSets and phinvads' 1,967) loads as a single FTRM
> SQLite container (`fhir-tx.db`) and, for the parity tests, as the
> cached package tarballs under `fhir-cache/` (extracted on open) via the
> in-memory provider.
> Both serve the **same** resources and expand identically — guarded by
> `fhir_packages_live_test` and `vsac_parity_live_test`. They differ only
> in latency/footprint, so a benchmark number is only comparable to
> another run **using the same provider**.

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
account), grab the **LOINC Table File (CSV)** archive for release 2.82,
and unzip it:

```bash
unzip /path/to/Loinc_2.82.zip -d /tmp/Loinc_2.82
```

LOINC release detection is based on the native loader's required
`LoincTable/Loinc.csv`. Native LOINC import writes a LOINC-specific
SQLite store from the release CSVs.

### FHIR packages

All FHIR packages — including VSAC — load into a **single** FTRM
container, `fhir-tx.db`. One `install compact` chain builds it (install,
index, VACUUM):

```bash
clj -M:run install compact .hades/fhir-tx.db \
  --dist hl7.fhir.r4.core@4.0.1 \
  --dist hl7.terminology.r4@7.0.1 \
  --dist hl7.fhir.us.core@6.1.0 \
  --dist hl7.fhir.uv.ips@2.0.0 \
  --dist hl7.fhir.uv.ips@1.1.0 \
  --dist fhir.tx.support.r4@0.34.0 \
  --dist us.cdc.phinvads@0.12.0 \
  --dist us.nlm.vsac@0.24.0 \
  --cache-dir .hades/fhir-cache
```

The `--dist` set must match `fhir-packages` in
[`fixtures.clj`](../test/com/eldrix/hades/fixtures.clj). This populates
both the SQLite container (`fhir-tx.db`) and the package tarball cache
(`fhir-cache/`, holding the downloaded `.tgz` files) — the parity tests
run the in-memory provider over the cached tarballs (extracted on open)
and the FTRM provider over the container. `us.nlm.vsac@0.24.0` is
absent from packages.fhir.org (which stops at 0.17.0) but present on
packages2; the install CLI tries both registries in turn, so no extra
flag is needed.

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

### Provider parity tests

`com.eldrix.hades.provider-parity-test` runs under `clj -M:test` and
asserts the in-memory and SQLite/FTRM providers produce **the same**
result for the same fhir-data — including human-readable text. Both
`:message` and issue `:text` are part of the comparison contract:
divergence means the provider must converge on a shared helper in
`providers/common/issues.clj`, not that the test's normaliser should look the
other way. The synthetic fixture is small (a handful of toy
CodeSystems / ValueSets / a ConceptMap), so failures are fast to
triage.

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

Running tx-benchmark (flavors, the no-Docker sweep, and comparing
against the published round-0 baseline) lives in its own doc:
[`doc/tx-benchmark.md`](tx-benchmark.md).

