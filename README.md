# Hades — a HL7 FHIR terminology server

[![Conformance](https://img.shields.io/badge/conformance-493%2F600%20(82.2%25)-blue)](https://github.com/HL7/fhir-tx-ecosystem-ig)

Hades is an open-source HL7 FHIR terminology server. It serves
`CodeSystem`, `ValueSet` and `ConceptMap` operations — `$lookup`,
`$validate-code`, `$subsumes`, `$expand`, `$translate` — over HTTP,
across multiple terminologies in the same process:

- **SNOMED CT** — ECL v2.2, reference sets, and the implicit
  `http://snomed.info/sct?fhir_vs=…` / `?fhir_cm=…` URI patterns
- **LOINC** — codes, properties, hierarchy, and LOINC→LOINC maps
- **Any FHIR NPM package** — `hl7.fhir.r4.core`, `hl7.terminology`,
  `hl7.fhir.us.core`, IPS, your own IGs

The composite dispatches by canonical URL, so a single endpoint serves
all of them.

It is exercised against the
[HL7 FHIR Terminology Ecosystem IG](https://github.com/HL7/fhir-tx-ecosystem-ig)
conformance test suite — Hades passes **493 / 600 (82.2%)** non-skipped
tests at the pinned upstream rev. See [Conformance](doc/conformance.md).

Hades is lightweight and self-contained. The same binary serves an
analyst exploring concepts on a laptop, a single instance backing a
clinical service, or a horizontally scaled fleet — there are no
external dependencies (no Postgres, no Elasticsearch), and rolling out
a new terminology release is a single command. Java 21+, no native
compilation, no JVM flags required.

## Quickstart

Download the latest `hades.jar` from the
[releases page](https://github.com/wardle/hades/releases), then
install a FHIR R4 package and serve it:

```shell
java -jar hades.jar install fhir.db --dist hl7.fhir.r4.core@4.0.1
java -jar hades.jar serve fhir.db --port 8080
```

In another terminal, expand a value set:

```shell
curl -sG 'http://localhost:8080/fhir/ValueSet/$expand' \
  --data-urlencode 'url=http://hl7.org/fhir/ValueSet/administrative-gender' | jq .
```

That's the lowest-friction path: a FHIR package with no
licence, no API key, no registration. SNOMED CT and LOINC need a free
licence — see [Installation](doc/installation.md) for the
multi-terminology walkthrough.

> Examples use `java -jar hades.jar`. From a source checkout, replace
> with `clj -M:run` throughout.

## Performance

Hades runs as a single JVM process on commodity hardware — no
Postgres, no Elasticsearch, no orchestrator. The figures below are
end-to-end over HTTP on an Apple M1 MacBook Pro driving the server at
10 concurrent connections with `wrk`. *Latency* is the time the median
request takes; *throughput* is the sustained request rate at that
concurrency. Server-class hardware with more cores scales higher.

| What it does                                       | FHIR endpoint              | Latency | Throughput   |
|----------------------------------------------------|----------------------------|--------:|-------------:|
| Single concept lookup (SNOMED)                     | `CodeSystem/$lookup`       |  347 µs | 21,900 req/s |
| Single concept lookup (LOINC)                      | `CodeSystem/$lookup`       |  950 µs |  9,800 req/s |
| Free-text search, 10 results                       | `ValueSet/$expand`         |  772 µs | 10,900 req/s |
| Subsumption test (two SNOMED codes)                | `CodeSystem/$subsumes`     |  222 µs | 37,500 req/s |
| Code validation against a value set                | `ValueSet/$validate-code`  |  575 µs | 13,900 req/s |
| Value set expansion (ECL refinement, 10 results)   | `ValueSet/$expand`         |  1.1 ms |  7,700 req/s |
| Concept translation (SNOMED → ICD-10)              | `ConceptMap/$translate`    |  162 µs | 51,100 req/s |

See [Performance](doc/performance.md) for the methodology, tail
latencies and the full per-operation breakdown.

Hades is one of the best performing terminology servers available while being very lightweight.

## Documentation

| Guide | Description |
|---|---|
| [Installation](doc/installation.md) | Downloading distributions, building databases, per-terminology details |
| [CLI reference](doc/cli.md) | All commands and flags |
| [HTTP API](doc/http-api.md) | FHIR operation examples |
| [MCP server](doc/mcp.md) | Native MCP server for AI assistants — multi-terminology tool surface |
| [FTRM](doc/ftrm.md) | The SQLite container format for FHIR terminology resources |
| [Conformance](doc/conformance.md) | HL7 FHIR Terminology Ecosystem IG — running, REPL workflow, pinning |
| [Performance](doc/performance.md) | Benchmarks and methodology |
| [Development](doc/development.md) | Building, testing, linting and releasing |

## How is Hades different to other terminology servers?

- It is open-source — many alternatives are proprietary requiring
  commercial licensing.
- It is fast, even on low-end hardware.
- It provides command-line tools to discover, inspect and install
  different terminologies.
- It 'installs' one or more SNOMED CT distributions into a specialised
  and highly optimised SNOMED database.
- It defines an open specification for a generic SQLite-based container
  of FHIR terminology data: [FTRM](doc/ftrm.md).
- It can install HL7 FHIR terminologies (usually distributed
  as JSON via npm packages) into those containers.
- It can serve multiple SNOMED databases, multiple FTRM databases and
  on-disk JSON directly with high conformance to the FHIR terminology
  conformance suite.
- It supports LOINC.
- It provides an [MCP server](doc/mcp.md) exposing the canonical FHIR
  terminology operation surface
  (`$lookup` / `$validate-code` / `$expand` / `$translate` / `$subsumes`)
  shaped as MCP tools across SNOMED CT, LOINC and arbitrary FHIR
  packages.

## Acknowledgements

Hades is built on:

- [hermes](https://github.com/wardle/hermes) — an efficient SNOMED CT
  terminology engine built on
  [LMDB](https://github.com/lmdbjava/lmdbjava) and
  [Apache Lucene](https://lucene.apache.org/); does the heavy lifting
  for SNOMED storage, search and ECL evaluation
- [Pedestal](https://pedestal.io) on Jetty — the HTTP layer
- [charred](https://github.com/cnuernber/charred) — JSON serialisation
- [HAPI FHIR](https://hapifhir.io) structures and the `TxTester`
  harness — used in the test/conformance pipeline

Conformance work draws on the test fixtures and `messages.json`
externals authored by the HL7 Terminology Ecosystem IG team.

Performance work has been helped enormously by the HealthSamurai team
and their work on
[tx-benchmark](https://github.com/HealthSamurai/tx-benchmark).

*Mark*
