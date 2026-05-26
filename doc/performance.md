# Performance

Hades runs as a single JVM process — no database server, no
orchestrator, no external services. On a laptop it sustains tens of
thousands of FHIR terminology operations per second with
sub-millisecond median latency on most operations. The numbers below
were measured on 2026-05-26 against a **2.0.236** development build.

## Headline figures

| What it does                                       | FHIR endpoint              | Median latency | 99th percentile |  Throughput  |
|----------------------------------------------------|----------------------------|---------------:|----------------:|-------------:|
| Single concept lookup (SNOMED)                     | `CodeSystem/$lookup`       |         381 µs |        10.2 ms  | 20,400 req/s |
| Single concept lookup (LOINC)                      | `CodeSystem/$lookup`       |         578 µs |         5.61 ms | 14,000 req/s |
| Free-text search, 10 results                       | `ValueSet/$expand`         |         990 µs |        21.8 ms  |  8,500 req/s |
| FHIR search by canonical URL                       | `GET /ValueSet`            |         677 µs |         7.77 ms | 10,800 req/s |
| Subsumption test (two SNOMED codes)                | `CodeSystem/$subsumes`     |         270 µs |         1.42 ms | 28,800 req/s |
| Code validation against a value set                | `ValueSet/$validate-code`  |         313 µs |         2.52 ms | 23,800 req/s |
| Value set expansion (is-a hierarchy, 10 results)   | `ValueSet/$expand`         |         258 µs |         1.13 ms | 30,800 req/s |
| Value set expansion (ECL refinement, 10 results)   | `ValueSet/$expand`         |         1.5 ms |        14.2 ms  |  5,600 req/s |
| Value set expansion (extensional, VSAC)            | `ValueSet/$expand`         |         1.2 ms |        29.9 ms  |  4,800 req/s |
| Concept translation (SNOMED → ICD-10)              | `ConceptMap/$translate`    |         363 µs |         2.73 ms | 21,300 req/s |

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
process serves all terminologies. 


## Methodology

- **Hardware:** Apple M1 Pro, 16 GB RAM
- **Hades:** 2.0.236 development build, measured 2026-05-26
- **Data:** SNOMED CT International 2025-02-01, LOINC 2.82, and the FHIR
  packages from `fhir-tx.db` (including the VSAC value sets)
- **Driver:** [`wrk`](https://github.com/wg/wrk) `-t2 -c10 -d30s`
  (2 threads, 10 keep-alive connections, 30 s of measurement) after a
  10 s warmup at the same shape. Figures are the best of three runs —
  on a shared laptop, background load perturbs individual runs, so the
  least-contended run best reflects sustained capability
- **Variation:** each operation rotates its requests through a pool so
  the server can't return a single cached response. `$lookup`,
  `$subsumes` and `$validate-code` rotate through 2002 SNOMED or LOINC
  codes; free-text search rotates through ~40 representative 3–6
  character clinical prefixes (e.g. `diab`, `card`, `pneum`); the is-a
  and extensional `$expand` rows rotate through concepts and VSAC value
  sets respectively; `$translate` rotates SNOMED codes through the
  SNOMED → ICD-10 implicit map; the ECL `$expand` row uses a fixed
  refinement value set

### Reproducing these figures

Start Hades serving the data above (e.g.
`clj -M:run serve .hades/snomed-intl-20250201.db .hades/loinc-2.82.db .hades/fhir-tx.db --port 8080`),
then run the self-contained script below — it needs `wrk` and `jq`,
builds the request pools from the running server, writes the rotate
script to a temp dir, and prints median / p99 latency and requests/sec
for each operation.

```bash
BASE=http://localhost:8080
P="$(mktemp -d)"; trap 'rm -rf "$P"' EXIT
enc() { jq -rn --arg s "$1" '$s|@uri'; }

# Request pools, built from the running server
curl -fsS "$BASE/fhir/ValueSet/\$expand?url=$(enc 'http://snomed.info/sct?fhir_vs=isa/404684003')&count=2002" \
  | jq -r '.expansion.contains[].code' > "$P/snomed.txt"
curl -fsS "$BASE/fhir/ValueSet/\$expand?url=$(enc 'http://loinc.org/vs')&count=2002" \
  | jq -r '.expansion.contains[].code' > "$P/loinc.txt"
curl -fsS "$BASE/fhir/ValueSet?_count=60" \
  | jq -r '.entry[].resource.url | select(test("cts.nlm"))' > "$P/vsac.txt"
printf '%s\n' diab card pneum hyper hypo asth angin arrhy brady tachy ische infarc \
  sepsis anaem leuk lymph melan carcin aden fract contus lacer abras burn ulcer gastr \
  hepat nephr cysti derm arthr myalg neuro psych depres anxiet schizo demen epilep migrai > "$P/prefixes.txt"
sed 's#^#http://snomed.info/sct?fhir_vs=isa/#' "$P/snomed.txt" > "$P/isa.txt"
echo 'http://snomed.info/sct?fhir_vs=ecl/<404684003:363698007=<<39057004' > "$P/ecl.txt"

# Per request, substitute a pool value into the template at @@ (optionally %-encoded)
cat > "$P/rotate.lua" <<'LUA'
local pool = {}
for line in io.lines(os.getenv("WRK_POOL")) do
  if #line > 0 then pool[#pool + 1] = line end
end
local tmpl  = os.getenv("WRK_TEMPLATE")
local doenc = os.getenv("WRK_ENCODE") == "1"
local i = 0
local function urlencode(s)
  return (s:gsub("[^%w%-_%.~]", function(c) return string.format("%%%02X", string.byte(c)) end))
end
request = function()
  i = i + 1
  local v = pool[(i % #pool) + 1]
  if doenc then v = urlencode(v) end
  return wrk.format("GET", (tmpl:gsub("@@", function() return v end)))
end
LUA

# operation | pool | encode? | request path (@@ = rotated pool value)
ops="$(cat <<'EOF'
Single concept lookup (SNOMED)|snomed.txt|0|/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=@@
Single concept lookup (LOINC)|loinc.txt|0|/fhir/CodeSystem/$lookup?system=http://loinc.org&code=@@
Free-text search, 10 results|prefixes.txt|0|/fhir/ValueSet/$expand?url=http%3A%2F%2Fsnomed.info%2Fsct%3Ffhir_vs&filter=@@&count=10
FHIR search by canonical URL|vsac.txt|1|/fhir/ValueSet?url=@@
Subsumption test (two SNOMED codes)|snomed.txt|0|/fhir/CodeSystem/$subsumes?system=http://snomed.info/sct&codeA=@@&codeB=64572001
Code validation against a value set|snomed.txt|0|/fhir/ValueSet/$validate-code?url=http%3A%2F%2Fsnomed.info%2Fsct%3Ffhir_vs&system=http://snomed.info/sct&code=@@
Value set expansion (is-a hierarchy)|isa.txt|1|/fhir/ValueSet/$expand?url=@@&count=10
Value set expansion (ECL refinement)|ecl.txt|1|/fhir/ValueSet/$expand?url=@@&count=10
Value set expansion (extensional, VSAC)|vsac.txt|1|/fhir/ValueSet/$expand?url=@@
Concept translation (SNOMED → ICD-10)|snomed.txt|0|/fhir/ConceptMap/$translate?url=http%3A%2F%2Fsnomed.info%2Fsct%3Ffhir_cm%3D447562003&system=http://snomed.info/sct&code=@@
EOF
)"

printf '%-40s %10s %10s %12s\n' operation p50 p99 req/s
while IFS='|' read -r name pool enc tmpl; do
  [ -z "$name" ] && continue
  export WRK_POOL="$P/$pool" WRK_TEMPLATE="$tmpl" WRK_ENCODE="$enc"
  wrk -t2 -c10 -d10s          -s "$P/rotate.lua" "$BASE" >/dev/null 2>&1 || true   # warmup
  out="$(wrk -t2 -c10 -d30s --latency -s "$P/rotate.lua" "$BASE")"
  printf '%-40s %10s %10s %12s\n' "$name" \
    "$(awk '/^[[:space:]]*50%/{print $2}'   <<<"$out")" \
    "$(awk '/^[[:space:]]*99%/{print $2}'   <<<"$out")" \
    "$(awk '/Requests\/sec/{print $2}'      <<<"$out")"
done <<< "$ops"
```

A registry-layer [Criterium](https://github.com/hugoduncan/criterium)
micro-benchmark suite (HTTP bypassed, useful for bisecting performance
changes) is also available via `clj -M:bench`. I use this to check for
performance regressions.

## Comparing against other servers

Independent comparative benchmarks are welcome. Health Samurai's
[`tx-benchmark`](https://github.com/HealthSamurai/tx-benchmark) runs a
standard k6 load suite against FHIR terminology servers. For running it
against Hades and comparing a fresh run with the published results, see
[`doc/tx-benchmark.md`](tx-benchmark.md).
