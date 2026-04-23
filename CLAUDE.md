# Hades — CLAUDE.md

A FHIR terminology server built in Clojure, currently wrapping Hermes (SNOMED CT)
and evolving towards a general-purpose terminology server. See `plan/roadmap.md` for the
full plan.

## Quick reference

```bash
# CLI subcommands — hades now owns the full SNOMED lifecycle (acquisition,
# maintenance, serving). Hermes is used as a library; users don't run it.
clj -M:run serve --db snomed.db --port 8080           # run FHIR server
clj -M:run install --dist uk.nhs/sct-clinical --db snomed.db --api-key trud.txt
clj -M:run import  --db snomed.db /path/to/RF2
clj -M:run list    /path/to/RF2                       # list importable files
clj -M:run available                                   # list distribution providers
clj -M:run index   --db snomed.db                     # rebuild search indices
clj -M:run compact --db snomed.db                     # compact LMDB
clj -M:run status  --db snomed.db [--format json]     # database status
clj -M:run --help serve                                # per-command help

# Development
clj -M:test                              # run tests
clj -M:bench                             # run criterium benchmarks (see below)
clj -M:lint/kondo                        # static analysis
clj -M:lint/eastwood                     # lint
clj -M:test/cloverage                    # test coverage
clj -M:check                             # compilation check
clj -M:nrepl                             # start nREPL server (test paths included)

# conformance tests — runs against the pinned SNOMED CT International release.
# See `Conformance / integration test data` below for one-time setup.
clj -X:conformance
clj -X:conformance :snomed '"path/to/snomed.db"'        # override the pinned DB
clj -X:conformance :url '"http://localhost:8080/fhir"'   # test an already-running server
```

### Conformance / integration test data

Conformance, `^:live` integration tests and benchmarks all run against **one
pinned SNOMED CT International release: 20250201**. Pinning matters: the
IG's tx-ecosystem fixtures were authored against this exact release, and
any other release produces failures that are data-version drift, not real
defects. Tests, benches and conformance **only consume** the pinned DB —
they never build it. Provisioning is a separate explicit step:

```bash
clj -X:build-db                                                        # use a zip already placed locally
clj -X:build-db :username '"you@example.com"' :password '"/path/pw"'   # or download from MLDS
```

`:build-db` is idempotent — a no-op if the DB already exists. It provisions
from the first available of:

1. `.hades/snomed-intl-20250201.db` — existing built DB
2. `.hades/snomed-int-20250201.zip` — local release zip (extract + build)
3. SNOMED MLDS — auto-download using `:username` / `:password` (path to
   password file; Affiliate licence required)

Build takes ~2 minutes and produces a ~2.2 GB Hermes DB. CI caches the
built DB keyed on the pinned version. Tests and conformance fail fast with
a clear message if the DB is missing — they never auto-build.

### Interactive development via nREPL

Start an nREPL server (includes test paths):
```bash
clj -M:nrepl                    # without conformance deps
clj -M:nrepl:conformance        # with conformance deps (for REPL-driven conformance testing)
```

Then use `clj-nrepl-eval` to start a Hades server and test interactively:
```bash
# Start Hermes + Hades on port 8080
clj-nrepl-eval -p <port> '
(require (quote [com.eldrix.hermes.core :as hermes]))
(require (quote [com.eldrix.hades.impl.server :as server]))
(require (quote [com.eldrix.hades.impl.registry :as registry]))
(require (quote [com.eldrix.hades.impl.snomed :as snomed]))

(def svc (hermes/open "/path/to/snomed.db"))
(def snomed-svc (snomed/->HermesService svc))
(registry/register-codesystem "http://snomed.info/sct" snomed-svc)
(registry/register-valueset "http://snomed.info/sct" snomed-svc)
(def srv (server/start! (server/make-server {:port 8080})))
'

# Test an endpoint via HTTP
curl -s 'http://localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009'

# Test the same thing internally — returns the plain Clojure map before HAPI serialisation
clj-nrepl-eval -p <port> '
(registry/codesystem-lookup {:system "http://snomed.info/sct" :code "73211009"})
'

# Stop
clj-nrepl-eval -p <port> '(server/stop! srv) (.close svc)'
```

### REPL-driven conformance testing (the default workflow)

**Always use the REPL for conformance work.** The CLI (`clj -X:conformance`) is
slow and doesn't support the edit→reload→test cycle. Use the REPL to iterate
on changes: edit code, `restart!`, run filtered tests, check diffs.

**Setup** — start nREPL with conformance deps (port written to `.nrepl-port`):
```bash
clj -M:nrepl:conformance
```

**REPL API** in `com.eldrix.hades.conformance-test`:

| Function | Purpose |
|----------|---------|
| `(start! path)` | Start server with Hermes, store state. Optional `:port`. |
| `(stop!)` | Stop server, close Hermes. |
| `(restart!)` | Stop, reload all Hades namespaces, restart. |
| `(run-tests)` | Run all conformance tests. Optional `:filter`, `:modes`. |
| `(print-suites r)` | Per-suite pass/fail table. |
| `(print-failures r)` | All failures with parsed expected/actual. |
| `(print-failures r "suite")` | Failures in one suite. |
| `(print-clusters r)` | Group failures by (path, expected, actual); biggest cluster first — your highest-leverage fix candidate. |
| `(print-clusters r "suite")` | Same, restricted to one suite. |
| `(print-detail r "suite/test")` | Full detail for one test, including expected response JSON. |
| `(replay-test "test")` | Replay one test through the live HTTP server; returns `{:request :expected :actual :status}`. (Test name only — no suite prefix.) |
| `(list-tests)` / `(list-tests "suite")` | List test cases with operation + expected http-code. Use this instead of grepping `test-cases.json`. |
| `(test-info "test")` | Return `{:name :suite :operation :http-code :request :expected :setup-files :setup-resources}` for one test. Use this instead of `cat`-ing fixture files. |
| `(print-diff old new)` | Gained/lost tests between two runs. |
| `(save-results! r)` | Timestamped archive + latest.json. |
| `(save-baseline! r)` | Update baseline (intentional only). |
| `(load-latest)` | Load most recent saved results. |
| `(load-baseline)` | Load baseline counts. |

**Typical edit→test cycle from Claude Code** (using `clj-nrepl-eval`):

```bash
# 1. Start (once per session) — use double quotes to avoid shell escaping issues
clj-nrepl-eval -p $(cat .nrepl-port) "(require '[com.eldrix.hades.conformance-test :as ct])"
clj-nrepl-eval -p $(cat .nrepl-port) "(ct/start!)"   # uses the pinned DB

# 2. Run tests (filtered or all)
clj-nrepl-eval -p $(cat .nrepl-port) "(def r (ct/run-tests))"
clj-nrepl-eval -p $(cat .nrepl-port) "(def r (ct/run-tests :filter \"permutations\"))"
clj-nrepl-eval -p $(cat .nrepl-port) "(ct/print-suites r)"
clj-nrepl-eval -p $(cat .nrepl-port) "(ct/print-failures r \"permutations\")"

# 3. Edit code, then reload and retest
clj-nrepl-eval -p $(cat .nrepl-port) "(ct/restart!)"
clj-nrepl-eval -p $(cat .nrepl-port) "(def r2 (ct/run-tests :filter \"permutations\"))"
clj-nrepl-eval -p $(cat .nrepl-port) "(ct/print-diff r r2)"

# 4. When satisfied, save baseline
clj-nrepl-eval -p $(cat .nrepl-port) "(ct/save-baseline! r2)"

# 5. Stop when done
clj-nrepl-eval -p $(cat .nrepl-port) "(ct/stop!)"
```

**Important shell escaping notes for `clj-nrepl-eval`:**
- Wrap the entire Clojure form in double quotes: `"(ct/start! ...)"`
- Escape inner double quotes with backslash: `\"/path/to/snomed.db\"`
- The `!` character does NOT need escaping when inside double quotes
- Do NOT use single quotes for the outer wrapper — use double quotes

## Architecture

Source layout:

- `src/com/eldrix/hades/core.clj` — public library API (currently empty; no
  stable surface yet).
- `src/com/eldrix/hades/impl/**` — library internals. Subject to breaking
  changes; nothing outside the project should depend on these namespaces.
- `src/com/eldrix/hades/cmd/**` — CLI application (entry point +
  option parsing). Not part of the library surface.

The internal layering under `impl/`:

```
impl/http.clj                  Pedestal HTTP layer — routes, interceptors,
                               Parameters parsing, content negotiation
    │
impl/wire.clj                  Pure FHIR JSON map builders (string-keyed)
impl/metadata.clj
    │
impl/registry.clj              Lookup & dispatch — routes requests to implementations
    │
impl/protocols.clj             Abstract interfaces (CodeSystem, ValueSet, ConceptMap)
    │
impl/snomed.clj                SNOMED CT via Hermes
impl/fhir_codesystem.clj       File-backed CodeSystem (+ ValueSet impl for CS-backed VS)
impl/fhir_valueset.clj         File-backed ValueSet (compose expansion + validate)
impl/compose.clj               ValueSet compose engine (include/exclude, filter, etc.)

impl/server.clj                Thin façade that exposes `make-server` over http.clj
cmd/core.clj                   CLI entry point (-main)
cmd/impl/cli.clj               CLI option parsing
```

### Layering rules (strict)

- **HTTP concerns live in `impl/http.clj` only.** Pedestal interceptors, request
  parsing, routing, content negotiation, and response-shape decisions all
  stay there. No HTTP details leak into the registry or protocol impls.
- **Wire-format shaping lives in `impl/wire.clj` / `impl/metadata.clj` only.**
  They are pure functions that produce string-keyed FHIR JSON maps. Charred
  serialises those maps in the content-negotiation interceptor.
- **Registry mediates all access.** `impl/http.clj` handlers call registry
  functions, not protocol methods directly.

### Layer responsibilities (strict)

- **Protocol impls** (snomed.clj, fhir_codesystem.clj, fhir_valueset.clj) know
  their domain. They return **complete, self-describing results** that match
  the specs in `impl/protocols.clj` (e.g. `::protos/validate-result`,
  `::protos/expansion-result`). No downstream layer should need to patch,
  enrich, or re-derive fields.
- **Registry** dispatches to the right impl by URL/version and handles
  version resolution (`force-system-version` / `system-version` /
  `check-system-version`). It does **not** patch results, add issues
  retroactively, look up other resources to fill gaps, or fix FHIRPath
  expressions. If the registry is doing post-call surgery on a result, the
  impl's return value is incomplete — fix the impl.
- **HTTP handlers are thin.** Each operation handler parses its parameters
  (from GET query or POST `Parameters`), calls the registry, and stores the
  result on the Pedestal context. The per-operation response interceptor
  (`:leave`) inspects the result and determines HTTP status / response shape
  (Parameters, ValueSet, or OperationOutcome). Handlers don't transform data,
  don't decide HTTP status, and don't build wire types directly.
- **Wire builders are pure.** `impl/wire.clj` takes internal keyword-keyed result
  maps and returns string-keyed FHIR maps. No HTTP, no dispatch, no state.
- **No secret channels.** Data flows through explicit function parameters and
  return values — never through metadata maps, dynamic vars, or by reaching
  back through `vs-resource` / `cs-resource` to get data that should have
  been in the result.

### Request-scoped overlays (`tx-resource`)

FHIR operations accept `tx-resource` parameters — temporary CodeSystem /
ValueSet resources scoped to a single request. The overlay is built by the
`tx-ctx` interceptor and attached to the Pedestal request as `:hades/ctx`.
From there `ctx` flows as an ordinary function argument: handler → registry
→ protocol impl. Registry lookup functions accept an optional first argument
`ctx` — a map that may contain `:codesystems`, `:valuesets`, and/or
`:conceptmaps` overlay maps. Overlays are checked before global atoms.

When `ctx` is nil or absent, only global registrations are consulted.

### ctx structure (strict)

The `ctx` map has exactly two concerns:

1. **Overlays** (top-level keys): `:codesystems`, `:valuesets`, `:conceptmaps` —
   maps of `{uri → protocol-impl}` for tx-resource scoped resources.
2. **Request parameters** (`:request` key): a map of operation parameters that
   affect behaviour across layers. Spec'd as `::registry/request`.

**All FHIR operation parameters belong in `:request`.** This includes:
- Version control: `:system-version`, `:force-system-version`, `:check-system-version`
- Display: `:lenient-display-validation`, `:display-language`
- Scoping: `:value-set-version`

**Never add FHIR operation parameters as top-level ctx keys.** The top level is
reserved for overlays. If a new parameter needs to flow through ctx, add it to
the `:request` map and update `::registry/request` spec.

## Code style

### General
- Pure functions for all complex logic. Side effects only at edges (server, startup).
- Functions do one thing. If you need `and` to describe it, split it.
- Prefer threading macros (`->`, `->>`) over nested calls.
- Destructure in argument lists when it aids readability.
- Use `when` not `if` when there is no else branch.
- No extraneous comments. Comments explain *why*, never *what*.
- No docstrings on private helpers with self-evident signatures.
- Don't add type hints unless needed for performance or disambiguation.

### Naming
- Predicates end in `?`.
- Conversion functions use `->` (e.g., `map->parameters`).
- Protocol methods are prefixed by resource type: `cs-`, `vs-`, `cm-`.
- Registry functions match operation names: `codesystem-lookup`, `valueset-expand`.
- Use domain language from the FHIR spec, not invented synonyms.

### Data
- Use plain Clojure maps as the data interchange format between layers.
- **Keyword keys everywhere inside Clojure** (`:code`, `:system`, `:display`,
  `:result`, `:version`). This applies to protocol return values, registry
  results, compose output, and all internal data.
- **String keys only at serialisation boundaries**: in `impl/wire.clj` /
  `impl/metadata.clj` (FHIR JSON output — string keys match FHIR property names)
  and when parsing inbound FHIR JSON resources (`tx-resource` bodies and
  file-backed ingest).
- Parse FHIR JSON with `charred` (for HTTP body parsing) or
  `clojure.data.json` (for file-backed ingest and tests).

### Specs
- Use `clojure.spec.alpha` for all public API boundaries: protocol parameters,
  registry inputs, constructor arguments, **and protocol return values**.
- The canonical data shapes live in `impl/protocols.clj`: `::protos/validate-result`,
  `::protos/expansion-result`, `::protos/expansion-concept`,
  `::protos/lookup-result`, `::protos/issue`. These are the contracts between
  layers — every protocol impl must return data conforming to these specs.
- Namespace specs under the relevant module: `::protos/code`, `::registry/url`.
- Write `s/fdef` for public functions with non-trivial parameter contracts.
- Use `clojure.spec.test.alpha/instrument` in test runs to validate data at
  boundaries automatically.

## Testing

- Every new public function gets a test.
- Use `clojure.spec.test.alpha/check` (generative testing) for pure functions
  that have specs. This is especially valuable for parsers and data transformers.
- Test edge cases explicitly: nil inputs, empty collections, missing optional params.
- Integration tests for server endpoints use actual HTTP requests against a test
  server instance.
- Place test fixtures (sample FHIR JSON resources) under `test/resources/`.
- Run the full test suite after every change. No regressions.

### Benchmarking (criterium)

`clj -M:bench` runs Criterium micro-benchmarks at the registry layer (pure
function calls, HTTP bypassed). Use these to bisect the impact of perf
changes on the hot paths — faster iteration than tx-benchmark's HTTP/k6
loop.

Benchmarks are declared as data in `test/com/eldrix/hades/operations_bench.clj`.
The catalogue is a vector `operations` of `{:id :ns/name :fn #(…)}`
entries. At load time it's reduced into `benchmarks`, a map from `:id` to
zero-arg fn, for REPL lookup. Bench files live under `test/` and are
discovered by the `.*-bench$` regex; `clj -M:test` ignores them.

```bash
clj -M:bench       # run the whole catalogue — one shot, no flags
```

For a single benchmark, use the REPL:

```clojure
(require '[com.eldrix.hades.impl.operations-bench :as ob]
         '[criterium.core :as crit])
(ob/open-snomed!)                                       ; once per session
(crit/quick-bench ((ob/benchmarks :subsumes/unrelated)))
(crit/quick-bench ((ob/benchmarks :compose/refinement)))
(ob/close-snomed!)                                      ; when done
```

The fixture opens Hermes against `/Users/mark/Dev/hermes/snomed.db`,
registers it as the SNOMED CodeSystem/ValueSet/ConceptMap provider, runs
criterium, then restores registry state and closes Hermes. Benches are
skipped when the DB is absent.

To add a benchmark, append an entry to the `operations` vector. No new
deftest or var required.

### Conformance testing

The HL7 FHIR Terminology Ecosystem IG defines 706 conformance tests. We run these
programmatically via `TxTester` from `org.hl7.fhir.validation` (test-only dep).

```bash
clj -M:conformance                       # run conformance tests
```

Key classes (in `org.hl7.fhir.validation.special`):
- `TxTester` — sends requests to the server, compares responses to expected output
- `TxTestData` — loads test data from folder or NPM package
- `ITxTesterLoader` — interface for test data access

Test data lives in a local clone of `HL7/fhir-tx-ecosystem-ig` (gitignored).
The test harness starts Hades on a random port, runs `TxTester` against it, and
parses the `TestReport` result into Clojure data.

After each phase, the conformance pass count must not decrease. New work should
increase it. The `messages-hades.json` externals file maps expected error message
keys to Hades-specific strings.

## Checklists

### Before making changes (design checklist)

Run before writing any code. The purpose is to ensure the change is in the right
place and flows data correctly.

1. **Identify the FHIR spec requirement.** What section of the FHIR spec governs
   this behaviour? If you can't point to a spec section, you may be solving the
   wrong problem or optimising for a test rather than the specification.
2. **Identify which layer owns this change.** Is it domain logic (protocol impl),
   dispatch/version resolution (registry), HTTP handling (http.clj), or wire
   shaping (wire.clj / metadata.clj)? If the answer is "a bit of each,"
   reconsider — the design may be unclear.
3. **Trace the data flow.** For the inputs this change needs: where do they
   originate and how do they reach this layer? For the outputs: who consumes
   them and what shape do they expect? Draw the path: http → registry → impl
   → registry → http → wire. If data needs to flow backwards (handler reaching
   back into impl metadata), the return value is incomplete.
4. **Check the spec.** Does a spec exist in `impl/protocols.clj` for the return value
   this change produces? If not, define it before writing the implementation.
   The spec is the contract — write the contract first.
5. **Check for secret channels.** Does this change require smuggling data through
   metadata, dynamic vars, or `:ctx`-in-params? If yes, redesign. The data
   should be an explicit parameter or part of a return value spec.

### After making changes (verification checklist)

Run after every task. All items must pass.

#### Automated checks
1. `clj -M:test` — all tests pass
2. `clj -M:lint/kondo` — clean
3. `clj -M:lint/eastwood` — clean

#### Architectural checks (read back every changed file)
4. **No HAPI imports anywhere in `src/`.** Runtime code is HAPI-free.
5. **No new atoms or mutable state** without justification.
6. **No secret channels introduced.** Data flows through explicit params/returns.
   No compose-in-metadata, no reaching back through `vs-resource`/`cs-resource`
   to get data that should be in the result, no ad-hoc keys smuggled in `:ctx`.
7. **Protocol impls return complete results.** If you changed a protocol impl,
   its return value should match the relevant spec (`::validate-result`,
   `::expansion-result`, `::lookup-result`). No downstream patching needed.
8. **Registry is not patching results.** If you added logic to the registry that
   modifies a result after the protocol call (adding issues, filling in missing
   fields, fixing expressions), the impl's return is incomplete — fix the impl.
9. **Handlers are thin.** If an operation handler grew beyond ~30 lines or
   contains terminology logic (version checking, status warnings, compose
   inspection, code existence checks), push that logic into a lower layer
   or into the result map.
10. **Keyword keys used internally.** Protocol returns, registry results,
    and compose output all use keyword keys. String keys appear only in
    `impl/wire.clj` / `impl/metadata.clj` (wire output) and in parsed input
    (`tx-resource` bodies, file-backed ingest).

#### Completeness checks
11. **New public functions have tests.**
12. **Specs exist for public API boundaries** — input params and return values.
13. **Changes match the task requirements** — not more, not less.

### Before releasing (release checklist)

1. **Refresh conformance figure in all three places** whenever the pass/total
   changes. The figure must match everywhere or the badge lies:
   - `README.md` — the shields.io badge URL (`conformance-PASSED%2FTOTAL%20(XX.X%25)-…`)
   - `README.md` — the prose sentence ("Hades passes **N / T (XX.X%)** …")
   - `CHANGELOG.md` — the release headline
   Get the current numbers from `(ct/run-tests)` in the REPL, or the
   `:totals` map of `test/resources/conformance-results.json`.
2. **Run the full conformance suite** and confirm no regression against the
   previous release's pass count.
3. **Update `CHANGELOG.md`** with the release's headline changes.

## Key dependencies

| Dep | Purpose |
|-----|---------|
| `com.eldrix/hermes` | SNOMED CT terminology engine |
| `io.pedestal/pedestal.jetty` | Pedestal routes + Jetty HTTP server |
| `com.cnuernber/charred` | JSON serialisation on the wire |
| `org.clojure/data.json` | JSON parsing (file-backed ingest, tests) |
| `lambdaisland/uri` | URI parsing |

## Known bugs

None currently tracked. (Registry fallback bug from Phase 2.1 has been fixed.)
