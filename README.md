# Hades — a HL7 FHIR terminology server

[![Conformance](https://img.shields.io/badge/conformance-490%2F600%20(81.7%25)-blue)](https://github.com/HL7/fhir-tx-ecosystem-ig)

Hades is an open-source HL7 FHIR terminology server. It serves `CodeSystem`,
`ValueSet` and `ConceptMap` operations — `$lookup`, `$validate-code`,
`$subsumes`, `$expand`, `$translate` — over HTTP, with first-class support
for SNOMED CT including reference sets and the SNOMED implicit ValueSet /
ConceptMap URIs. LOINC and any FHIR NPM package (`hl7.fhir.r4.core`,
`hl7.terminology`, `hl7.fhir.us.core`, IPS, …) can be served alongside
SNOMED in the same process — the composite dispatches by canonical URL.

Hades passes **490 / 600 (81.7%)** of the
[HL7 FHIR Terminology Ecosystem IG](https://github.com/HL7/fhir-tx-ecosystem-ig)
conformance tests against the pinned International release. The figure is
reproducible — see [Conformance and benchmarks](#conformance-and-benchmarks).

## Project status

Hades runs from a developer or analyst's laptop, as a single instance, or
behind an API gateway as a horizontally scaled fleet sharing a read-only
database volume. The store has no external dependencies (no Postgres, no
Elasticsearch); a SNOMED release update is a single command.

What works today:

- **SNOMED CT** — `$lookup`, `$validate-code`, `$subsumes`, `$expand`,
  `$translate` (via SNOMED map reference sets), and the implicit
  `http://snomed.info/sct?fhir_vs=…` / `?fhir_cm=…` URI patterns
- **Full SNOMED lifecycle** in one binary: download (TRUD, MLDS, manual RF2),
  import, index, compact, serve
- **LOINC** ingestion from a release archive into a Hades SQLite container
- **FHIR NPM packages** (`hl7.fhir.r4.core`, `hl7.terminology`,
  `hl7.fhir.us.core`, `hl7.fhir.uv.ips`, etc.) loaded **in-memory at
  startup** with `--resources <pkg-dir>` for sub-microsecond hashmap
  lookups, or **into a SQLite container** with `import` for memory-
  constrained or very-large-corpus deployments — the same binary serves
  both
- `tx-resource` request-scoped overlays for transient CodeSystem / ValueSet
- Java 21+, no JVM flags required, no native compilation

On the roadmap (see [Roadmap](#roadmap)):

- ConceptMap `$translate` for FHIR-package maps using the in-memory store
  (today the SQLite container is the supported path)
- Resource read and search (`GET /fhir/CodeSystem/{id}`, etc.)
- CI / health endpoints / tagged uberjar releases

Hades requires Java 21 or above.

# Quickstart

Download the latest `hades-<version>.jar` from the
[releases page](https://github.com/wardle/hades/releases). Examples
below use `hades.jar` — substitute the actual filename. From a source
checkout, replace `java -jar hades.jar` with `clj -M:run` throughout.

Hades handles the full SNOMED CT lifecycle: download a distribution,
build a database, serve it over FHIR.

## 1. Build a SNOMED database

Pick whichever path matches your source. All three end with a `snomed.db`
directory ready for `serve`.

### a) UK edition from TRUD

Save your TRUD API key in a file (e.g. `trud-api-key.txt`), then:

```shell
java -jar hades.jar install index compact \
    --db snomed.db \
    --dist uk.nhs/sct-clinical \
    --api-key trud-api-key.txt
```

Commands execute in the order given — download + import, then build indices,
then compact. Add more `--dist` flags (e.g. `--dist uk.nhs/sct-drug-ext`) to
layer additional UK distributions into the same database before indexing.

### b) SNOMED CT International from MLDS

Save your MLDS password in a file, then:

```shell
java -jar hades.jar install index compact \
    --db snomed.db \
    --dist ihtsdo.mlds/167 \
    --username you@example.com \
    --password mlds-password.txt
```

Run `java -jar hades.jar available` to browse all MLDS distributions and their IDs.

### c) A distribution you downloaded manually

Unzip the RF2 release, then point `import` at the unzipped directory:

```shell
java -jar hades.jar list /path/to/unzipped-rf2/              # inspect what hades will import
java -jar hades.jar import index compact \
    --db snomed.db \
    /path/to/unzipped-rf2/
```

## 2. Serve

```shell
java -jar hades.jar serve --db snomed.db --port 8080
```

Verify it works — `73211009` is *Diabetes mellitus* in SNOMED CT:

```shell
curl -sH "Accept: application/fhir+json" \
  'http://localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009' | jq .
```

Use `java -jar hades.jar --help <command>` for full per-command options.

## 3. Add LOINC and FHIR packages (optional)

`serve` accepts `--db` repeatedly for on-disk databases (Hermes SNOMED,
Hades SQLite for LOINC and FHIR packages) and `--resources` repeatedly
for directories of FHIR JSON resources loaded **in-memory at start**.
Mix and match:

```shell
# 1. Build LOINC DB once from a release archive
java -jar hades.jar import --db loinc.db /path/to/Loinc_2.81

# 2. Extract FHIR packages (NPM .tgz) into directories
mkdir -p packages
for tgz in hl7.fhir.r4.core-4.0.1.tgz hl7.terminology.r4-7.0.1.tgz; do
  mkdir -p "packages/$(basename "$tgz" .tgz)"
  tar xzf "$tgz" -C "packages/$(basename "$tgz" .tgz)"
done

# 3. Serve everything together
java -jar hades.jar serve --port 8080 \
  --db snomed.db \
  --db loinc.db \
  --resources packages/hl7.fhir.r4.core-4.0.1/package \
  --resources packages/hl7.terminology.r4-7.0.1/package
```

**Choosing `--resources` (in-memory) vs `--db` (SQLite):**

| | `--resources <dir>` | `--db <sqlite>` |
| --- | --- | --- |
| Boot time | Slower — parses every JSON at start (~15 s for 6 HL7 packages) | Fast — opens the file |
| Per-request latency | Hashmap lookup, nanoseconds | JDBC + page fault, tens of µs |
| Memory | Whole corpus in heap (~1.5 GB for the 6 HL7 packages above) | Just the working set |
| Best for | Benchmark territory; small-to-medium catalogues; latency-sensitive | Memory-constrained hosts; very large corpora that don't fit in RAM |

For FHIR packages the in-memory route is the default recommendation.
Convert to a `--db` container with `import --db <out.db> <pkg-dir>`
when persistence or RAM pressure matter.

## Command reference

| Command | Purpose |
|---------|---------|
| `serve [--db PATH …] [--resources DIR …] [--port N] [--bind-address A]` | Start the FHIR server. Each `--db` opens a Hermes SNOMED store or a Hades SQLite container; each `--resources` loads a directory of FHIR JSON resources in-memory. Repeat freely. |
| `install --db PATH --dist D …` | Download and import a distribution (run `index` + `compact` afterwards) |
| `import --db PATH [paths…]` | Import sources into a database. Auto-detects RF2 (SNOMED), LOINC release archive, or FHIR JSON / NPM-package directory. |
| `list [paths…]` | List importable files under given paths |
| `available [--dist D]` | List distribution providers / releases for a given distribution |
| `index --db PATH` | Build search indices |
| `compact --db PATH` | Compact the LMDB store |
| `status --db PATH [--format json\|edn] [--modules] [--refsets]` | Show database status |

Commands can be chained on a single command line and execute in the order
given, sharing flags — for example `install index compact` above.

# Example usage

#### Lookup a SNOMED code

```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=209629006'
```

#### How do two codes relate to one another?

Test how 107963000|Liver excision relates to 63816008|Hepatectomy, total left lobectomy.

```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/CodeSystem/$subsumes?system=http://snomed.info/sct&codeA=107963000&codeB=63816008' | jq
```

```json
{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "outcome", "valueString": "subsumes" }
  ]
}
```

### Expand a value set

Ask for every concept that is a `Disorder of the respiratory system`
(`<<50043002`) with a `clinical course` (`<<263502005`) of `subacute`
(`<<19939008`). Add `&filter=sili` to drive autocomplete.

```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/ValueSet/$expand?url=http://snomed.info/sct?fhir_vs=ecl/<<50043002:<<263502005=<<19939008' | jq
```

```json
{
  "resourceType": "ValueSet",
  "expansion": {
    "total": 13,
    "contains": [
      { "system": "http://snomed.info/sct", "code": "233761006", "display": "Subacute silicosis" },
      { "system": "http://snomed.info/sct", "code": "233753001", "display": "Subacute berylliosis" },
      { "system": "http://snomed.info/sct", "code": "22482002",  "display": "Subacute obliterative bronchiolitis" }
      // ...
    ]
  }
}
```

# Conformance and benchmarks

Conformance is fully reproducible from a clean checkout — that is the
point of publishing it. Performance figures are illustrative; the
methodology is described below so that anyone evaluating alternatives
can apply the same shape of test.

## Conformance

Hades is exercised against the
[HL7 FHIR Terminology Ecosystem IG](https://github.com/HL7/fhir-tx-ecosystem-ig)
test suite using the upstream `TxTester` harness. The current pass count
(**490 / 600**, 81.7%) is pinned to a specific upstream commit so that
local and CI runs see the same test population.

```shell
clj -X:conformance
```

Per-suite gap analysis lives in [`plan/roadmap.md`](plan/roadmap.md).

## Benchmarks

The numbers below come from driving a running Hades over HTTP with
[hurl](https://hurl.dev) at `--parallel --jobs=10`. They are indicative,
not a marketing claim:

- Hardware: Apple M1 MacBook Pro, 16 GB RAM
- Data: SNOMED CT International 2025-02-01
- Iterations: 60 per call, 10 in parallel

| Operation                                                                 | p50 (ms) | p95 (ms) |
|---------------------------------------------------------------------------|---------:|---------:|
| `CodeSystem/$lookup` — SNOMED concept (`73211009`)                        |        1 |        4 |
| `ValueSet/$validate-code` — SNOMED concept against implicit VS            |      <1  |        2 |
| `ValueSet/$validate-code` — with display string                           |      <1  |        3 |
| `ValueSet/$validate-code` — within an implicit `isa/…` subtree VS         |      <1  |        2 |
| `CodeSystem/$subsumes` — two SNOMED concepts                              |      <1  |        1 |
| `ValueSet/$expand` — implicit `isa/…` subtree, page of 10                 |       14 |      158 |
| `ValueSet/$expand` — POST, `descendent-of` filter, page of 10             |        6 |       70 |
| `ValueSet/$expand` — text filter "diabetes" across all SNOMED, page of 100 |        3 |       29 |
| `ValueSet/$expand` — POST, ECL refinement, page of 10                     |        2 |        4 |
| `ValueSet/$expand` — POST, refinement + text filter "fracture"            |        3 |        9 |
| `ConceptMap/$translate` — SNOMED map reference set                        |      <1  |        2 |

A registry-layer Criterium suite (HTTP bypassed, useful for bisecting
perf changes) is available via `clj -M:bench`.

# Roadmap

Planned work, in rough priority order:

- **File-backed code systems and value sets** — load FHIR JSON (`CodeSystem`,
  `ValueSet`, `Bundle`) from a `--resources` directory at startup, so you can
  serve non-SNOMED terminologies (LOINC, ICD-10, locally-authored
  `ValueSet`s) alongside SNOMED. The underlying providers already exist
  internally; this wires them into the CLI.
- **`$translate`** — ConceptMap translation, including SNOMED CT map
  reference sets (ICD-10, CTV3, ICD-O) auto-discovered from Hermes and
  file-backed `ConceptMap` JSON.
- **Resource read and search** — `GET /fhir/CodeSystem/{id}`,
  `GET /fhir/ValueSet?url=…`, and search by common parameters.
- **Conformance coverage** — improve HL7 FHIR Terminology Ecosystem IG
  conformance test pass rate.
- **CI/CD and health endpoints** — GitHub Actions for test/lint/conformance
  on every push, tagged releases that publish the uberjar, and a
  `/health` endpoint for orchestration.

The full roadmap, including completed phases and per-test-suite gap
analysis, lives in [`plan/roadmap.md`](plan/roadmap.md).

# Acknowledgements

Hades is built on:

- [hermes](https://github.com/wardle/hermes) — SNOMED CT terminology
  engine (LMDB + Lucene); does the heavy lifting for SNOMED storage,
  search and ECL evaluation
- [Pedestal](https://pedestal.io) on Jetty — the HTTP layer
- [charred](https://github.com/cnuernber/charred) — JSON serialisation
- [HAPI FHIR](https://hapifhir.io) structures and the `TxTester` harness
  — used in the test/conformance pipeline

Conformance work draws on the test fixtures and `messages.json` externals
authored by the HL7 Terminology Ecosystem IG team.
