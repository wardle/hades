# Performance

Hades is built for low-latency, single-process operation. SNOMED is
served from an embedded LMDB + Lucene store
([Hermes](https://github.com/wardle/hermes)); LOINC and FHIR packages
can be held entirely in heap as plain Clojure hashmaps, or in a SQLite
container on disk. There is no database round-trip on the hot path —
`$lookup`, `$validate-code` and `$subsumes` typically return in under a
millisecond on a laptop.

## Methodology

The numbers below come from driving a running Hades over HTTP with
[wrk](https://github.com/wg/wrk). They will vary by machine and
content version; the methodology and order-of-magnitude shape are
reproducible.

- Hardware: Apple M1 MacBook Pro, 16 GB RAM
- Hades version: commit `85b4787` (measured 2026-05-09)
- Data: SNOMED CT International 2025-02-01, LOINC 2.81, FHIR R4 core
  + `hl7.terminology` packages
- Storage: SNOMED via Hermes (LMDB + Lucene); LOINC and FHIR packages
  in a SQLite container
- Driver: `wrk -t2 -c10 -d30s` (2 threads, 10 keep-alive connections,
  30 s measurement) after a 10 s warmup at the same shape. `$lookup`,
  `$subsumes` and `$validate-code` rotate through a pool of 2002 codes
  per request via a Lua script; `$expand` and `$translate` use a fixed
  URL.
- `p50` is the median latency reported by wrk, `p99` the 99th
  percentile (1 in 100 requests is slower).

## Per-operation results

| Operation                                                | p50    | p99     | Throughput   |
|----------------------------------------------------------|-------:|--------:|-------------:|
| `CodeSystem/$lookup` — SNOMED concept (random pool)      | 347 µs | 7.75 ms | 21,892 req/s |
| `CodeSystem/$lookup` — LOINC code (random pool)          | 950 µs | 2.90 ms |  9,786 req/s |
| `CodeSystem/$subsumes` — two SNOMED concepts (random)    | 222 µs | 0.97 ms | 37,503 req/s |
| `ValueSet/$validate-code` — random codes vs `isa/64572001` | 575 µs | 3.04 ms | 13,932 req/s |
| `ValueSet/$expand` — ECL refinement, page of 10          | 1.12 ms | 3.50 ms |  7,672 req/s |
| `ConceptMap/$translate` — SNOMED→ICD-10 (`73211009`)     | 162 µs | 0.83 ms | 51,098 req/s |

## Reproducing

```shell
# Start hades against the pinned fixtures
clj -M:run serve .hades/snomed-intl-20250201.db .hades/loinc-2.81.db \
                 .hades/fhir-smoke.db --port 8080

# In another terminal, drive a single endpoint with wrk
wrk -t2 -c10 -d30s --latency \
  'http://localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009'
```

For pool-rotated benchmarks, point a small Lua script at any of the
JSON code pools under
[`tx-benchmark/k6/pools/`](https://github.com/HealthSamurai/tx-benchmark/tree/main/k6/pools).

## Criterium suite

A registry-layer Criterium suite (HTTP bypassed, useful for bisecting
perf changes) is available via `clj -M:bench`.

## Comparing against other servers

Independent comparative benchmarks are welcome. The
[Health Samurai `tx-benchmark`](https://github.com/HealthSamurai/tx-benchmark)
project covers Hades, Snowstorm, Ontoserver, FHIRsmith and Termbox.
Round 0 ran **Hades v1.4.69**, a very old proof-of-concept version of
Hades predating its current development. That version used the HAPI
Java library but current Hades is HAPI-free. We've
[contributed an updated container](https://github.com/HealthSamurai/tx-benchmark/pull/1)
and await a refreshed round.
