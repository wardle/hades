# Performance

Hades runs as a single JVM process — no database server, no
orchestrator, no external services. On a laptop it sustains tens of
thousands of FHIR terminology operations per second with
sub-millisecond median latency on most operations. The numbers below
were measured against the **v2.0.198** release on 2026-05-09.

## Headline figures

| What it does                                  | FHIR endpoint              | Median latency | 99th percentile |  Throughput  |
|-----------------------------------------------|----------------------------|---------------:|----------------:|-------------:|
| Look up a SNOMED concept                      | `CodeSystem/$lookup`       |         347 µs |         7.75 ms | 21,900 req/s |
| Look up a LOINC code                          | `CodeSystem/$lookup`       |         950 µs |         2.90 ms |  9,800 req/s |
| Test how two SNOMED codes are related         | `CodeSystem/$subsumes`     |         222 µs |         0.97 ms | 37,500 req/s |
| Validate a code is in a value set             | `ValueSet/$validate-code`  |         575 µs |         3.04 ms | 13,900 req/s |
| Expand an ECL value set (page of 10 concepts) | `ValueSet/$expand`         |         1.1 ms |         3.50 ms |  7,700 req/s |
| Translate via a SNOMED map reference set      | `ConceptMap/$translate`    |         162 µs |         0.83 ms | 51,100 req/s |

## How to read the table

- **Median latency** is the time the typical request takes — half of
  requests are faster, half are slower.
- **99th percentile** is the worst-case for 99 out of every 100
  requests. It tells you the experience under normal load when
  something occasionally takes longer than usual.
- **Throughput** is the sustained request rate, end-to-end over HTTP,
  with 10 concurrent clients hitting the server. Throughput scales
  with concurrency and core count up to the limits of the underlying
  data structures.

## Why it's this fast

Hades resolves every operation against in-process data — there is no
database round-trip on the hot path. SNOMED is served from an embedded
LMDB + Lucene store via [Hermes](https://github.com/wardle/hermes).
LOINC and FHIR packages are served either from heap (plain Clojure
hashmaps, ~nanosecond lookups) or from an on-disk SQLite container
(JDBC + prepared statements, tens of microseconds). The same JVM
process serves all terminologies; canonical-URL dispatch is a hashmap
lookup, not a routing layer.

## Methodology

- **Hardware:** Apple M1 MacBook Pro, 16 GB RAM
- **Hades:** v2.0.198 (commit `85b4787`), measured 2026-05-09
- **Data:** SNOMED CT International 2025-02-01, LOINC 2.81,
  `hl7.fhir.r4.core` and `hl7.terminology` packages
- **Driver:** [`wrk`](https://github.com/wg/wrk) `-t2 -c10 -d30s`
  (2 threads, 10 keep-alive connections, 30 s of measurement) after a
  10 s warmup at the same shape
- **Variation:** `$lookup`, `$subsumes` and `$validate-code` rotate
  through a pool of 2002 SNOMED or LOINC codes per request via a Lua
  script (so the server can't return a single cached response);
  `$expand` and `$translate` use a fixed URL

A registry-layer [Criterium](https://github.com/hugoduncan/criterium)
micro-benchmark suite (HTTP bypassed, useful for bisecting performance
changes) is also available via `clj -M:bench`.

## Comparing against other servers

Independent comparative benchmarks are welcome. The
[Health Samurai `tx-benchmark`](https://github.com/HealthSamurai/tx-benchmark)
project covers Hades, Snowstorm, Ontoserver, FHIRsmith and Termbox.
Round 0 ran **Hades v1.4.69**, an old proof-of-concept version
predating Hades' current development; that version still used the HAPI
Java library, while current Hades is HAPI-free. We've
[contributed an updated container](https://github.com/HealthSamurai/tx-benchmark/pull/1)
and await a refreshed round.
