# Hades — a HL7 FHIR terminology server

[![Conformance](https://img.shields.io/badge/conformance-490%2F603%20(81.3%25)-blue)](https://github.com/HL7/fhir-tx-ecosystem-ig)

Hades is an open-source HL7 FHIR terminology server. It serves `CodeSystem`,
`ValueSet` and `ConceptMap` operations — `$lookup`, `$validate-code`,
`$subsumes`, `$expand`, `$translate` — over HTTP, across multiple
terminologies in the same process:

- **SNOMED CT** — ECL v2.2, reference sets, and the implicit
  `http://snomed.info/sct?fhir_vs=…` / `?fhir_cm=…` URI patterns
- **LOINC** — codes, properties, hierarchy, and LOINC→LOINC maps
- **Any FHIR NPM package** — `hl7.fhir.r4.core`, `hl7.terminology`,
  `hl7.fhir.us.core`, IPS, your own IGs

The composite dispatches by canonical URL, so a single endpoint serves
all of them.

It is exercised against the
[HL7 FHIR Terminology Ecosystem IG](https://github.com/HL7/fhir-tx-ecosystem-ig)
conformance test suite — see [Conformance and benchmarks](#conformance-and-benchmarks).

## Project status

Hades runs from a developer or analyst's laptop, as a single instance, or
behind an API gateway as a horizontally scaled fleet sharing a read-only
database volume. The store has no external dependencies — no Postgres,
no Elasticsearch — and a release update for any terminology is a single
command.

Capabilities:

- **`$lookup`, `$validate-code`, `$subsumes`, `$expand`, `$translate`**
  across every loaded terminology, dispatched to the right provider by
  canonical URL
- **SNOMED CT** — full ECL, reference sets, $translate via SNOMED map
  reference sets, and the implicit
  `http://snomed.info/sct?fhir_vs=…` / `?fhir_cm=…` URI patterns;
  download (TRUD, MLDS, manual RF2), import, index, compact and serve
  all in one binary
- **LOINC** — codes with full property metadata, the multi-axial
  hierarchy (so `descendant-of` and `$subsumes` work), and the
  LOINC→LOINC `MapTo` ConceptMap; ingested from a release archive into
  a SQLite container
- **FHIR NPM packages** — installable via `packages.fhir.org`
  (`hl7.fhir.r4.core`, `hl7.terminology`, `hl7.fhir.us.core`,
  `hl7.fhir.uv.ips`, etc.) or pointed at a local extracted package;
  loaded **in-memory at startup** for sub-microsecond hashmap lookups,
  or **into a SQLite container** for memory-constrained or
  very-large-corpus deployments — the same binary serves both
- **Mix and match** — multiple SNOMED databases, LOINC, FHIR packages,
  and on-disk JSON directories all combine in a single `serve` command
- `tx-resource` request-scoped overlays for transient CodeSystem / ValueSet
- Java 21+, no JVM flags required, no native compilation

The wire format is FHIR R4. Hades is exercised against the HL7 FHIR
Terminology Ecosystem IG conformance suite — see
[Conformance and benchmarks](#conformance-and-benchmarks).

## Performance

Hades is built for low-latency, single-process operation. SNOMED is
served from an embedded LMDB + Lucene store ([Hermes](https://github.com/wardle/hermes));
LOINC and FHIR packages can be held entirely in heap as plain Clojure
hashmaps, or in a SQLite container on disk. There is no database
round-trip on the hot path — `$lookup`, `$validate-code` and
`$subsumes` typically return in under a millisecond on a laptop. See
[Benchmarks](#benchmarks) for per-operation numbers.

> **A note on the Health Samurai `tx-benchmark` Round 0 chart.**
> The published [scoreboard](https://healthsamurai.github.io/tx-benchmark/results/round-0/)
> shows Hades at 6%. That number is from a container that pinned
> Hades **v1.4.69 / Hermes v1.4.1520** (now several major versions
> behind) and was configured **SNOMED-only** — no LOINC, no RxNorm, no
> FHIR packages — even though the test mix relies on all of them. Tests
> targeting other terminologies therefore returned 404. We have
> contributed an updated container loading the current multi-terminology
> Hades; an updated round is awaited.

On the roadmap (see [Roadmap](#roadmap)):

- Resource read and search (`GET /fhir/CodeSystem/{id}`, etc.)
- Health endpoints / tagged uberjar releases

Hades requires Java 21 or above.

# Quickstart

Download the latest `hades-<version>.jar` from the
[releases page](https://github.com/wardle/hades/releases). Examples
below use `hades.jar` — substitute the actual filename. From a source
checkout, replace `java -jar hades.jar` with `clj -M:run` throughout.

This walkthrough builds three terminologies side-by-side and serves them
from a single Hades process: SNOMED CT (UK monolith edition from TRUD),
LOINC, and a couple of FHIR conformance packages from
[packages.fhir.org](https://packages.fhir.org). Each block is a discrete
step — skip any terminology you don't need.

You'll need:

- Java 21 or above
- A [TRUD](https://isd.digital.nhs.uk/trud) API key, saved to a file
  (e.g. `trud-api-key.txt`), for the UK SNOMED download
- A LOINC release archive — sign in at
  [loinc.org/downloads](https://loinc.org/downloads/) (free) and unzip
  it locally (e.g. to `/tmp/Loinc_2.81/`)

## 1. Install SNOMED CT (UK monolith edition)

The monolith is the merged UK edition (international + clinical + drug
extensions in one). One command downloads, imports and indexes:

```shell
java -jar hades.jar install snomed.db \
    --dist uk.nhs/sct-monolith \
    --api-key trud-api-key.txt
```

`install` auto-indexes the destination so the resulting `snomed.db/` is
queryable as soon as it returns. Build takes a few minutes; the
database is around 2 GB.

## 2. Build LOINC

LOINC isn't distributed via a registry — point `import` at the unzipped
release directory:

```shell
java -jar hades.jar import loinc.db /tmp/Loinc_2.81/
```

## 3. Install FHIR conformance packages

```shell
java -jar hades.jar install fhir.db \
    --dist hl7.fhir.r4.core@4.0.1 \
    --dist hl7.terminology.r4@7.0.1 \
    --dist hl7.fhir.uv.ips@2.0.0
```

Packages are pulled from `packages.fhir.org` and loaded into a SQLite
container. To serve in-memory instead — faster lookups, larger heap —
add `--cache-dir packages/` and `serve` the extracted directories
directly (see [FHIR packages](#fhir-packages) below).

## 4. Check what you've got

```shell
java -jar hades.jar status snomed.db loinc.db fhir.db
```

Reports the CodeSystems, ValueSets and ConceptMaps each source
contributes — handy as a smoke test before exposing the server. Add
`--format json` for machine-readable output.

## 5. Serve them together

```shell
java -jar hades.jar serve snomed.db loinc.db fhir.db --port 8080
```

The composite catalogue dispatches operations to the right provider by
canonical URL — no per-terminology routes, no client-side configuration.
A single endpoint serves all three.

When two providers claim the same canonical URL (e.g. an International
and a UK SNOMED database both serving `http://snomed.info/sct`),
disambiguate with one or more `--default URL=VERSION` flags. A request
that names the bare URL is routed to the matching version; requests
that include an explicit `version` are routed normally.

```shell
java -jar hades.jar serve snomed-intl.db snomed-uk.db \
    --default http://snomed.info/sct=http://snomed.info/sct/83821000000107/version/20250416
```

## 6. Try it

```shell
# SNOMED CT — 73211009 is "Diabetes mellitus"
curl -sG 'http://localhost:8080/fhir/CodeSystem/$lookup' \
  --data-urlencode 'system=http://snomed.info/sct' \
  --data-urlencode 'code=73211009' | jq .

# LOINC — 718-7 is "Hemoglobin [Mass/volume] in Blood"
curl -sG 'http://localhost:8080/fhir/CodeSystem/$lookup' \
  --data-urlencode 'system=http://loinc.org' \
  --data-urlencode 'code=718-7' | jq .

# FHIR — expand the administrative-gender ValueSet from hl7.fhir.r4.core
curl -sG 'http://localhost:8080/fhir/ValueSet/$expand' \
  --data-urlencode 'url=http://hl7.org/fhir/ValueSet/administrative-gender' | jq .
```

Use `java -jar hades.jar --help <command>` for full per-command options.

# Terminologies

## SNOMED CT

Hades wraps [Hermes](https://github.com/wardle/hermes) (LMDB + Lucene)
to serve SNOMED CT. Operations are full-fidelity: ECL queries, the
implicit `http://snomed.info/sct?fhir_vs=…` / `?fhir_cm=…` URI patterns,
$translate via SNOMED map reference sets, and lookup with all
designations and properties.

### Distributions

```shell
java -jar hades.jar available
```

lists every distribution Hades knows. The common choices:

| Identifier | Source | Auth |
|---|---|---|
| `uk.nhs/sct-monolith` | UK monolith (intl + clinical + drug ext, merged) | TRUD `--api-key` |
| `uk.nhs/sct-clinical` | UK clinical edition | TRUD `--api-key` |
| `uk.nhs/sct-drug-ext` | UK drug extension (layer on top of clinical) | TRUD `--api-key` |
| `ihtsdo.mlds/167` | SNOMED CT International (from MLDS) | MLDS `--username` + `--password` |

Pin a release with `@<version>`, e.g.
`--dist uk.nhs/sct-monolith@2025-02-01`. Run
`java -jar hades.jar available --dist <id>` to list available versions
(some registries authenticate read access — pass the same credentials
you'd use for install).

### Layering distributions

Multiple `--dist` flags on a single `install` layer into the same
database; the auto-index runs once at the end:

```shell
java -jar hades.jar install snomed.db \
    --dist uk.nhs/sct-clinical \
    --dist uk.nhs/sct-drug-ext \
    --api-key trud-api-key.txt
```

To layer across **separate** invocations (e.g. install one distribution
today, another next week into the same DB), pass `--no-index` on every
call but the last to skip the per-call index — then run a final
unflagged install/import or a standalone `index <db>`:

```shell
java -jar hades.jar install --no-index snomed.db --dist uk.nhs/sct-clinical --api-key trud-api-key.txt
java -jar hades.jar install            snomed.db --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt
```

### Importing a manually-downloaded release

If you already have an RF2 release on disk, skip `install`:

```shell
java -jar hades.jar list /path/to/unzipped-rf2/                       # preview what will be imported
java -jar hades.jar import snomed.db /path/to/unzipped-rf2/
```

## LOINC

LOINC is consumed from a local release directory (the unzipped
`Loinc_<version>` archive from [loinc.org](https://loinc.org/downloads/)).
There is no registry — manual download is required for licensing reasons,
but no API key is involved.

```shell
java -jar hades.jar import loinc.db /path/to/Loinc_2.81/
```

`import` auto-indexes the destination — the ancestor closure and FTS
tables (which `descendant-of` filters and text search rely on) are
built before the command returns.

What's exposed:

- The `http://loinc.org` CodeSystem with `$lookup`, `$validate-code`,
  `$subsumes` (over LOINC's class hierarchy), and full property metadata
  (COMPONENT, PROPERTY, SYSTEM, METHOD_TYP, SHORTNAME, etc.)
- `MultiAxialHierarchy.csv` is loaded as parent/child concept relations,
  so `descendant-of` filters in `$expand` and `$subsumes` traverse
  LOINC's hierarchy
- `LoincTable/MapTo.csv` is loaded as a LOINC→LOINC ConceptMap

LOINC builds into a SQLite container directly; no separate `index` or
`compact` is needed.

## FHIR packages

Any [FHIR NPM package](https://registry.fhir.org/) — IGs, conformance
packages, terminology bundles — can be served alongside SNOMED and
LOINC. The composite dispatches by canonical URL, so a `ValueSet` from
`hl7.fhir.us.core` and a `CodeSystem` from `hl7.terminology.r4` resolve
without any per-package configuration.

```shell
java -jar hades.jar available                                # show known FHIR packages
java -jar hades.jar available --dist hl7.fhir.r4.core        # list versions
```

Common packages:

| Package | Purpose |
|---|---|
| `hl7.fhir.r4.core` | FHIR R4 core (CodeSystems and ValueSets) |
| `hl7.terminology.r4` | HL7 standard CodeSystems and ValueSets |
| `hl7.fhir.us.core` | US Core implementation guide |
| `hl7.fhir.uv.ips` | International Patient Summary |

Hades's wire format is FHIR **R4** (the CapabilityStatement reports
`fhirVersion` 4.0.1). R5 packages can be installed and their
CodeSystems/ValueSets will mostly load, but the server speaks R4 to
clients and R5 ConceptMaps (`relationship`, not R4 `equivalence`) are
not fully supported.

Any other id from `packages.fhir.org` works too — e.g.
`--dist hl7.fhir.uv.sdc@3.0.0`. If the registry doesn't authenticate the
listing, `available --dist <id>` shows versions; otherwise pin a known
version with `@<version>`.

### In-memory vs SQLite container

`install` lands a FHIR package as a SQLite container by default. Add
`--cache-dir <dir>` to also keep the extracted JSON, then `serve` the
package directory directly for in-memory operation:

```shell
# Install once, keep the extracted JSON in ./packages
java -jar hades.jar install fhir.db \
    --dist hl7.fhir.r4.core@4.0.1 \
    --dist hl7.terminology.r4@7.0.1 \
    --cache-dir packages

# Serve in-memory (faster lookups, larger heap)
java -jar hades.jar serve --port 8080 \
    snomed.db loinc.db \
    packages/hl7.fhir.r4.core-4.0.1/package \
    packages/hl7.terminology.r4-7.0.1/package

# Or serve from the SQLite container (lower memory)
java -jar hades.jar serve snomed.db loinc.db fhir.db --port 8080
```

| | FHIR JSON dir (in-memory) | `.db` (SQLite container) |
| --- | --- | --- |
| Boot time | Slower — parses every JSON at start (~15 s for 6 HL7 packages) | Fast — opens the file |
| Per-request latency | Hashmap lookup, nanoseconds | JDBC + page fault, tens of µs |
| Memory | Whole corpus in heap (~1.5 GB for the 6 HL7 packages above) | Just the working set |
| Best for | Latency-sensitive use; small-to-medium catalogues | Memory-constrained hosts; very large corpora |

In-memory is the default recommendation for FHIR packages. Convert a
package directory to a SQLite container later with
`import <out.db> <pkg-dir>` if RAM pressure becomes an issue.

## Command reference

| Command | Purpose |
|---------|---------|
| `serve <paths…> [--port N] [--bind-address A] [--default URL=VERSION]…` | Start the FHIR server. Each path opens a Hermes SNOMED store, a Hades SQLite container, or a directory of FHIR JSON resources (auto-detected). Use `--default URL=VERSION` (repeatable) when multiple providers claim the same canonical URL — bare-URL requests resolve to the chosen version. |
| `install <dest-db> --dist <id>… [--no-index] [--cache-dir DIR]` | Download and import one or more distributions (SNOMED CT or FHIR package) into the destination database. Auto-indexes when done; pass `--no-index` to skip (for layered loads). Distribution ids may carry `@<version>`. |
| `import <dest-db> <sources…> [--no-index]` | Import sources into a destination database. Auto-detects RF2 (SNOMED), LOINC release archive, or FHIR JSON / NPM-package directory. Auto-indexes when done. |
| `list <paths…>` | List importable files under given paths |
| `available [--dist <id>…]` | List installable terminologies, or releases/versions for the given ids |
| `index <paths…>` | Rebuild search indices on each database. Useful for explicit recovery or to finish a layered load. Release sources are silently skipped. |
| `compact <paths…>` | Compact the underlying store (LMDB compact for Hermes, VACUUM for SQLite). Optional space optimisation. |
| `status <paths…> [--format json\|edn]` | Show database status |

Commands can be chained on a single command line and execute in the
order given, sharing positional paths and flags — for example
`install compact snomed.db --dist uk.nhs/sct-clinical --api-key
trud.txt`. `index` and `compact` silently skip release source paths
so they're safe to chain after `import`.

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

Conformance is fully reproducible from a clean checkout. Performance
figures are illustrative; the methodology is described below so that
anyone evaluating alternatives can apply the same shape of test.

## Conformance

Hades is exercised against the
[HL7 FHIR Terminology Ecosystem IG](https://github.com/HL7/fhir-tx-ecosystem-ig)
test suite using the upstream `TxTester` harness. The pass count (see
the badge above) is pinned to a specific upstream commit so that local
and CI runs see the same test population.

```shell
clj -X:conformance
```

Per-suite breakdown is printed at the end of every run; archived results
land in `test/resources/conformance/latest.json`.

## Benchmarks

The numbers below come from driving a running Hades over HTTP with
[hurl](https://hurl.dev) at `--parallel --jobs=10`. The methodology is
described so anyone can apply the same shape of test to alternative
servers — these are not a marketing claim, but they are reproducible.

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

### Comparing against other servers

Independent comparative benchmarks are welcome. The
[Health Samurai `tx-benchmark`](https://github.com/HealthSamurai/tx-benchmark)
project covers Hades, Snowstorm, Ontoserver, FHIRsmith and Termbox; its
Round 0 results currently use **Hades v1.4.69** (several major versions
old) configured **SNOMED-only**, so most tests in the mix — LOINC,
RxNorm, HL7-published ValueSets — return 404 from Hades and the
composite score is not informative. We've contributed an updated
container running the current multi-terminology Hades; expect refreshed
numbers once that round publishes.

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
