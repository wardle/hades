# Hades — a HL7 FHIR terminology server

[![Conformance](https://img.shields.io/badge/conformance-490%2F603%20(81.3%25)-blue)](https://github.com/HL7/fhir-tx-ecosystem-ig)

Hades is an open-source HL7 FHIR terminology server. It serves `CodeSystem`,
`ValueSet` and `ConceptMap` operations — `$lookup`, `$validate-code`,
`$subsumes`, `$expand`, `$translate` — over HTTP, with first-class support
for SNOMED CT including reference sets and the SNOMED implicit ValueSet /
ConceptMap URIs. LOINC and any FHIR NPM package (`hl7.fhir.r4.core`,
`hl7.terminology`, `hl7.fhir.us.core`, IPS, …) can be served alongside
SNOMED in the same process — the composite dispatches by canonical URL.

Hades passes **490 / 603 (81.3%)** of the
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
  `hl7.fhir.us.core`, `hl7.fhir.uv.ips`, etc.) installed via the
  registry (`packages.fhir.org`) or pointed at a local extracted
  package; loaded **in-memory at startup** for sub-microsecond hashmap
  lookups, or **into a SQLite container** for memory-constrained or
  very-large-corpus deployments — the same binary serves both
- `tx-resource` request-scoped overlays for transient CodeSystem / ValueSet
- Java 21+, no JVM flags required, no native compilation

On the roadmap (see [Roadmap](#roadmap)):

- Resource read and search (`GET /fhir/CodeSystem/{id}`, etc.)
- Health endpoints / tagged uberjar releases

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
java -jar hades.jar install index compact snomed.db \
    --dist uk.nhs/sct-clinical \
    --api-key trud-api-key.txt
```

Commands execute in the order given — download + import, then build indices,
then compact. Add more `--dist` flags (e.g. `--dist uk.nhs/sct-drug-ext`) to
layer additional UK distributions into the same database before indexing.

### b) SNOMED CT International from MLDS

Save your MLDS password in a file, then:

```shell
java -jar hades.jar install index compact snomed.db \
    --dist ihtsdo.mlds/167 \
    --username you@example.com \
    --password mlds-password.txt
```

Run `java -jar hades.jar available` to browse all installable distributions.

### c) A distribution you downloaded manually

Unzip the RF2 release, then point `import` at the unzipped directory.
`import` takes the destination database as its first positional argument
followed by one or more source paths; chain `index` and `compact`
afterwards as separate invocations:

```shell
java -jar hades.jar list /path/to/unzipped-rf2/        # inspect what hades will import
java -jar hades.jar import snomed.db /path/to/unzipped-rf2/
java -jar hades.jar index compact snomed.db
```

## 2. Serve

```shell
java -jar hades.jar serve snomed.db --port 8080
```

Verify it works — `73211009` is *Diabetes mellitus* in SNOMED CT:

```shell
curl -sH "Accept: application/fhir+json" \
  'http://localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009' | jq .
```

Use `java -jar hades.jar --help <command>` for full per-command options.

## 3. Add LOINC and FHIR packages (optional)

`serve` takes mixed positional sources — Hermes SNOMED databases, Hades
SQLite containers, and directories of FHIR JSON resources are auto-
detected. Mix and match:

```shell
# 1. Build LOINC DB once from a release archive
java -jar hades.jar import loinc.db /path/to/Loinc_2.81

# 2. Install FHIR packages from packages.fhir.org. --cache-dir keeps
#    the extracted JSON for in-memory serving; the destination path
#    also builds a SQLite container of the same data. Pick whichever
#    you'll serve from.
java -jar hades.jar install fhir.db \
  --dist hl7.fhir.r4.core@4.0.1 \
  --dist hl7.terminology.r4@7.0.1 \
  --cache-dir packages

# 3a. Serve from SQLite containers (lower memory):
java -jar hades.jar serve snomed.db loinc.db fhir.db --port 8080

# 3b. Or serve FHIR packages in-memory (faster lookups):
java -jar hades.jar serve --port 8080 \
  snomed.db loinc.db \
  packages/hl7.fhir.r4.core-4.0.1/package \
  packages/hl7.terminology.r4-7.0.1/package
```

**Choosing in-memory (FHIR JSON dir) vs SQLite container (`.db`):**

| | FHIR JSON dir | `.db` (SQLite container) |
| --- | --- | --- |
| Boot time | Slower — parses every JSON at start (~15 s for 6 HL7 packages) | Fast — opens the file |
| Per-request latency | Hashmap lookup, nanoseconds | JDBC + page fault, tens of µs |
| Memory | Whole corpus in heap (~1.5 GB for the 6 HL7 packages above) | Just the working set |
| Best for | Benchmark territory; small-to-medium catalogues; latency-sensitive | Memory-constrained hosts; very large corpora that don't fit in RAM |

For FHIR packages the in-memory route is the default recommendation.
Convert to a SQLite container with `import <out.db> <pkg-dir>` when
persistence or RAM pressure matter.

## Command reference

| Command | Purpose |
|---------|---------|
| `serve <paths…> [--port N] [--bind-address A]` | Start the FHIR server. Each path opens a Hermes SNOMED store, a Hades SQLite container, or a directory of FHIR JSON resources (auto-detected). |
| `install <dest-db> --dist <id>… [--cache-dir DIR]` | Download and import one or more distributions (SNOMED CT or FHIR package) into the destination database. Distribution ids may carry `@<version>`. Run `index`/`compact` after. |
| `import <dest-db> <sources…>` | Import sources into a destination database. Auto-detects RF2 (SNOMED), LOINC release archive, or FHIR JSON / NPM-package directory. Cannot be chained with other commands. |
| `list <paths…>` | List importable files under given paths |
| `available [--dist <id>…]` | List installable terminologies, or releases/versions for the given ids |
| `index <paths…>` | Build search indices on each database (no-op for FHIR-tx containers; release sources are skipped) |
| `compact <paths…>` | Compact the underlying store (LMDB compact for Hermes, VACUUM for SQLite) |
| `status <paths…> [--format json\|edn]` | Show database status |

Commands (other than `import`) can be chained on a single command line and
execute in the order given, sharing positional paths and flags — for
example `install index compact snomed.db --dist uk.nhs/sct-clinical
--api-key trud.txt`.

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
    "total": 6,
    "contains": [
      { "system": "http://snomed.info/sct", "code": "22482002",  "display": "Subacute obliterative bronchiolitis" },
      { "system": "http://snomed.info/sct", "code": "233761006", "display": "Subacute silicosis" },
      { "system": "http://snomed.info/sct", "code": "233753001", "display": "Subacute berylliosis" }
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
(**490 / 603**, 81.3%) is pinned to a specific upstream commit so that
local and CI runs see the same test population.

```shell
clj -X:conformance
```

Per-suite breakdown is printed at the end of every run; archived results
land in `test/resources/conformance/latest.json`.

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

- **Resource read and search** — `GET /fhir/CodeSystem/{id}`,
  `GET /fhir/ValueSet?url=…`, and search by common parameters.
- **Conformance coverage** — improve HL7 FHIR Terminology Ecosystem IG
  conformance test pass rate.
- **Health endpoint and tagged releases** — a `/health` endpoint for
  orchestration, and tagged releases that publish the uberjar.

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
