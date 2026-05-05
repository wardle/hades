# Hades — CLAUDE.md

A FHIR terminology server built in Clojure, currently wrapping Hermes (SNOMED CT)
and evolving towards a general-purpose terminology server.

## Quick reference

```bash
# CLI subcommands — hades owns the full lifecycle (acquisition, maintenance,
# serving) for both SNOMED CT and FHIR conformance packages. Positional
# arguments are always paths; installable ids are passed via repeatable
# `--dist` flags (with optional `@<version>` suffix).
clj -M:run serve   snomed.db fhir.db --port 8080                                       # run FHIR server (positional sources, repeatable, mix kinds)
clj -M:run install snomed.db --dist uk.nhs/sct-clinical@2025-02-01 --api-key trud.txt  # SNOMED CT distribution
clj -M:run install fhir.db   --dist hl7.fhir.r4.core@4.0.1                             # FHIR package (registry: packages.fhir.org)
clj -M:run import  snomed.db /path/to/RF2                                              # import sources into a destination DB (dest first, then sources)
clj -M:run list    /path/to/RF2                                                        # list importable files (paths required)
clj -M:run available                                                                   # SNOMED CT distributions + common FHIR packages
clj -M:run available --dist uk.nhs/sct-clinical --api-key trud.txt                     # SNOMED releases (TRUD requires --api-key for listing too)
clj -M:run available --dist hl7.fhir.r4.core                                           # FHIR package versions from the registry
clj -M:run index   snomed.db                                                           # rebuild search indices
clj -M:run compact snomed.db                                                           # compact LMDB
clj -M:run status  snomed.db [--format json|edn]                                       # database status
clj -M:run --help install                                                              # per-command help

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
they never build it. Provisioning uses the regular hades CLI (a SNOMED
MLDS Affiliate licence is required):

```bash
# Download from MLDS, import, index, compact:
clj -M:run install .hades/snomed-intl-20250201.db \
  --dist ihtsdo.mlds/167@2025-02-01 \
  --username 'you@example.com' --password /path/to/password-file
clj -M:run index   .hades/snomed-intl-20250201.db
clj -M:run compact .hades/snomed-intl-20250201.db

# Or, if you already have the release zip on disk:
unzip /path/to/snomed-int-20250201.zip -d /tmp/snomed-rf2
clj -M:run import .hades/snomed-intl-20250201.db /tmp/snomed-rf2
clj -M:run index   .hades/snomed-intl-20250201.db
clj -M:run compact .hades/snomed-intl-20250201.db
```

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
(require (quote [com.eldrix.hades.core :as hades]))
(require (quote [com.eldrix.hades.impl.http :as http]))
(require (quote [com.eldrix.hades.impl.snomed :as snomed]))

(def hermes-svc (hermes/open "/path/to/snomed.db"))
(def svc (hades/open [(snomed/->HermesService hermes-svc)]))
(def srv (http/start! (http/make-server svc {:port 8080})))
'

# Test an endpoint via HTTP
curl -s 'http://localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009'

# Test the same thing internally — returns the plain Clojure map
# before serialisation
clj-nrepl-eval -p <port> '
(hades/lookup svc {:system "http://snomed.info/sct" :code "73211009"})
'

# Stop
clj-nrepl-eval -p <port> '(http/stop! srv) (hades/close svc)'
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
- `src/com/eldrix/hades/cmd.clj` + `impl/cli.clj` — CLI application
  (entry point + option parsing). Not part of the library surface.

The internal layering under `impl/`:

```
impl/http.clj                  Pedestal HTTP layer — routes, interceptors,
                               Parameters parsing, content negotiation
    │
impl/wire.clj                  Pure FHIR JSON map builders (string-keyed)
impl/metadata.clj
    │
impl/composite.clj             TerminologyService — dispatch by URL/version,
                               cross-provider concerns (status warnings,
                               supplements check, CodeableConcept aggregation)
    │
impl/protocols.clj             Abstract interfaces (CodeSystem, ValueSet, ConceptMap)
    │
impl/snomed.clj                SNOMED CT via Hermes
impl/in_memory.clj             In-memory deftypes + SupplementedCodeSystem wrapper
impl/sqlite/provider.clj       SQLite-backed catalogue providers
impl/compose.clj               ValueSet compose engine (include/exclude, filter, etc.)

impl/loaders/fhir.clj          FHIR JSON → fhir-data
impl/loaders/loinc.clj         LOINC release dir → fhir-data
impl/index/memory.clj          fhir-data → in-memory providers
impl/index/sqlite.clj          fhir-data → SQLite container
impl/sqlite/db.clj             SQLite open/create/schema/pragmas

cmd.clj                        CLI entry point (-main)
impl/cli.clj                   CLI option parsing
```

### Layering rules (strict)

- **HTTP concerns live in `impl/http.clj` only.** Pedestal interceptors, request
  parsing, routing, content negotiation, and response-shape decisions all
  stay there. No HTTP details leak into the composite or protocol impls.
- **Wire-format shaping lives in `impl/wire.clj` / `impl/metadata.clj` only.**
  They are pure functions that produce string-keyed FHIR JSON maps. Charred
  serialises those maps in the content-negotiation interceptor.
- **Composite mediates all access.** `impl/http.clj` handlers call the
  service-level operation functions in `core` (which delegate to the
  composite), not protocol methods directly.

### Layer responsibilities (strict)

- **Protocol impls** (snomed.clj, in_memory.clj, sqlite/provider.clj) know
  their domain. They return **complete, self-describing results** that match
  the specs in `impl/protocols.clj` (e.g. `::protos/validate-result`,
  `::protos/expansion-result`). No downstream layer should need to patch,
  enrich, or re-derive fields.
- **Composite** dispatches to the right impl by URL/version and handles
  version resolution (`force-system-version` / `system-version` /
  `check-system-version`). It does **not** patch results, add issues
  retroactively, look up other resources to fill gaps, or fix FHIRPath
  expressions. If the composite is doing post-call surgery on a result, the
  impl's return value is incomplete — fix the impl.
- **HTTP handlers are thin.** Each operation handler parses its parameters
  (from GET query or POST `Parameters`), calls the operation, and stores
  the result on the Pedestal context. The per-operation response interceptor
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

FHIR operations accept `tx-resource` parameters — temporary CodeSystem,
ValueSet, and ConceptMap resources scoped to a single request. The
`derive-svc` interceptor in `impl/http.clj` parses them, builds providers
via `loaders/fhir` + `index/memory` + `in_memory/build-from-fhir-data`,
and folds them onto the base service via `core/with-overlays`. The
result replaces `:hades/svc` for the rest of the interceptor chain.

From a handler's view, the request-scoped overlay is invisible: every
handler reads `:hades/svc` and calls the operation functions on `core`
(`hades/lookup`, `hades/expand`, etc.). The composite dispatches to
overlay providers first, then to base providers, by URL/version.

Operation parameters that affect behaviour across layers
(`system-version`, `force-system-version`, `check-system-version`,
`lenient-display-validation`, `display-language`, `value-set-version`)
flow as flat keys on the `params` map passed to operation functions —
parsed once into `:hades/flags` by the same interceptor and merged into
each handler's params via `merge-flags`.

## Code style

### General
- Pure functions for all complex logic. Side effects only at edges (server, startup).
- Functions do one thing. If you need `and` to describe it, split it.
- Prefer threading macros (`->`, `->>`) over nested calls.
- Destructure at the function boundary, not in the body. `(fn [{:keys [request] :as ctx}] …)` reveals the shape at entry; a `let` binding that just unpacks an argument is dead weight.
- Use `when` not `if` when there is no else branch.
- No extraneous comments. Comments explain *why*, never *what*. A one-line `;; why` at a `cond->` branch or a non-obvious condition is fine.
- No docstrings on private helpers with self-evident signatures.
- Don't add type hints unless needed for performance or disambiguation.

### Bindings, control flow, and shape
- A `let` binding should earn its name. If a local exists only to be threaded into the next line, inline it. Three named locals each used once usually want to be one threaded form.
- `update-in` beats `assoc-in` when the new value is a function of the old one. Old code: read, compute, write. `update-in` does all three in one place.
- Don't compute-then-`assoc` when you can `cond->` and skip. `(if pred new-val old-val)` followed by an unconditional write rewrites the same value on the no-op path; `cond->` with the predicate makes the write itself conditional. The structure now mirrors the work — common path stays untouched.
- Nest threading macros when it sharpens the spine. An inner `->` carrying the unconditional writes inside an outer `cond->` carrying the conditional ones reads as "always do A and B; sometimes also do C." Use the shape of the form to communicate which writes are common-path and which are guarded.
- Prefer code where the *structure* shows control flow (`cond->`, `when-let`, threading shape) over code where everything is unconditional and the conditionality lives inside expressions.

### Naming
- Predicates end in `?`.
- Conversion functions use `->` (e.g., `map->parameters`).
- Protocol methods are prefixed by resource type: `cs-`, `vs-`, `cm-`.
- Operation functions in `core` match FHIR operation names: `lookup`,
  `expand`, `validate-code`, `subsumes`, `translate`.
- Use domain language from the FHIR spec, not invented synonyms.

### Data
- Use plain Clojure maps as the data interchange format between layers.
- **Keyword keys everywhere inside Clojure** (`:code`, `:system`, `:display`,
  `:result`, `:version`). This applies to protocol return values, composite
  results, compose output, and all internal data.
- **String keys only at serialisation boundaries**: in `impl/wire.clj` /
  `impl/metadata.clj` (FHIR JSON output — string keys match FHIR property names)
  and when parsing inbound FHIR JSON resources (`tx-resource` bodies and
  file-backed ingest).
- Parse FHIR JSON with `charred` (for HTTP body parsing) or
  `clojure.data.json` (for file-backed ingest and tests).

### Specs
- Use `clojure.spec.alpha` for all public API boundaries: protocol parameters,
  operation inputs, constructor arguments, **and protocol return values**.
- The canonical data shapes live in `impl/protocols.clj`: `::protos/validate-result`,
  `::protos/expansion-result`, `::protos/expansion-concept`,
  `::protos/lookup-result`, `::protos/issue`. These are the contracts between
  layers — every protocol impl must return data conforming to these specs.
- Namespace specs under the relevant module: `::protos/code`,
  `::compose/expand-params`.
- Write `s/fdef` for public functions with non-trivial parameter contracts.
- Use `clojure.spec.test.alpha/instrument` in test runs to validate data
  at boundaries automatically. Note: `instrument` only checks `:args`.
  Don't add `:ret` specs that aren't actually exercised — either drop
  them, or assert via `(s/assert ::spec result)` at the call site (the
  composite layer does this for the five main operation contracts).

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

`clj -M:bench` runs Criterium micro-benchmarks against `core` operation
functions (HTTP bypassed). Use these to bisect the impact of perf
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

The fixture opens the canonical pinned Hermes DB (see
`com.eldrix.hades.fixtures`), wraps it as the SNOMED
CodeSystem/ValueSet/ConceptMap provider, runs criterium, then closes
the service. Missing fixtures throw with the install hint (same
behaviour as the live test suite); under `CI=true` the throw is fatal.
Provision via the steps in `Conformance / integration test data` above.

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

0. **Consume what already exists before producing anything new.**
   - **Grep for existing helpers** before writing a utility (comparator,
     parser, formatter, predicate). Check `src/` and `deps.edn`. If
     something with a similar name or job exists, reuse it. Do not
     introduce a "fallback" alongside a new mechanism unless the
     fallback is clearly justified — duplicate logic is dead code in
     waiting.
   - **Cite the existing code you checked.** State explicitly: "I
     grepped for X / Y / Z; nothing matches" or "found `ns/foo`,
     reusing it." This forces the consultation step.
   - **Consult relevant memories.** When a memory covers this area
     (specs, naming, idioms, refactors, conventions), name the
     memory you're applying. If unsure whether one applies, name the
     closest and proceed.
1. **Identify the FHIR spec requirement.** What section of the FHIR spec governs
   this behaviour? If you can't point to a spec section, you may be solving the
   wrong problem or optimising for a test rather than the specification.
2. **Identify which layer owns this change.** Is it domain logic (protocol impl),
   dispatch/version resolution (composite), HTTP handling (http.clj), or wire
   shaping (wire.clj / metadata.clj)? If the answer is "a bit of each,"
   reconsider — the design may be unclear.
3. **Trace the data flow.** For the inputs this change needs: where do they
   originate and how do they reach this layer? For the outputs: who consumes
   them and what shape do they expect? Draw the path: http → composite → impl
   → composite → http → wire. If data needs to flow backwards (handler reaching
   back into impl metadata), the return value is incomplete.
4. **Check the spec.** Does a spec exist in `impl/protocols.clj` for the return value
   this change produces? If not, define it before writing the implementation.
   The spec is the contract — write the contract first.
5. **Check for secret channels.** Does this change require smuggling data through
   metadata, dynamic vars, or `:ctx`-in-params? If yes, redesign. The data
   should be an explicit parameter or part of a return value spec.

### Refactors preserve observable behaviour

A "tidy this up", "simplify this", "rename this", or "remove this useless
binding" request **never** changes observable behaviour. If your edit changes
any of the following, stop and confirm with the user before proceeding:

- **Lazy → eager** evaluation (a thunk replaced by its called result).
- **Thunk → value**, or **value → thunk**.
- **Deferred → immediate** computation (e.g. computing a value at the call
  site that the callee was meant to compute on demand).
- **Sync → async**, or async semantics (channel buffer sizes, error
  propagation, abort handling).
- **Ordering** of side effects, log output, or returned items.
- **Error semantics** (what's thrown, when, with what `:reason`).
- **Public API shape** (arities, parameter order, return shape, naming).

When in doubt, list the invariants you're maintaining as part of the change.
The smallest refactor still has a contract. Name it before changing it.

### Naming public functions

Before naming a public fn, write the call-site that uses it and check
whether the name communicates without context. If the answer needs
domain knowledge a fresh reader won't have, propose two alternatives
instead of one and let the user pick. Mirror Clojure-core idioms when
the shape matches: `*-seq` is a flat sequence; `*-tree` is hierarchical;
predicates end in `?`; conversions use `->`.

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
8. **Composite is not patching domain results.** If you added logic to the
   composite that modifies a result after the protocol call (adding issues,
   filling in missing fields, fixing expressions), the impl's return is
   incomplete — fix the impl. (The composite legitimately *adds*
   cross-provider concerns: status warnings, supplements check, inactive
   warnings, CodeableConcept aggregation. It does not patch domain data.)
9. **Handlers are thin.** If an operation handler grew beyond ~30 lines or
   contains terminology logic (version checking, status warnings, compose
   inspection, code existence checks), push that logic into a lower layer
   or into the result map.
10. **Keyword keys used internally.** Protocol returns, composite results,
    and compose output all use keyword keys. String keys appear only in
    `impl/wire.clj` / `impl/metadata.clj` (wire output) and in parsed input
    (`tx-resource` bodies, file-backed ingest).

#### Completeness checks
11. **New public functions have tests.**
12. **Specs exist for public API boundaries** — input params and return values.
13. **Changes match the task requirements** — not more, not less.

#### Self-review the diff

14. **Re-read the diff before reporting done.** Check for:
    - Helpers you wrote that the project already has (you should have
      caught this in step 0 above — but verify).
    - Parallel/redundant logic kept "as fallback" when one path is
      sufficient. If you can't justify both, delete one.
    - Verbose forms where idioms exist (fully-qualified spec keywords
      where an alias is in scope, `let` bindings used exactly once,
      explicit constructions where threading would be clearer).
    - Features beyond what was asked (extra options, defensive
      validation, new abstractions). Remove unless explicitly justified.
    - Behaviour-preserving refactors that silently changed semantics
      (see "Refactors preserve observable behaviour" above).

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
4. **Verify the three conformance figures match.** Grep all three locations
   (badge URL in `README.md`, prose sentence in `README.md`, headline in
   `CHANGELOG.md`) and confirm pass/total/percent are identical. A drift
   between e.g. `490/600` and `490/603` means the badge is lying — a
   one-character typo in any one place breaks the contract.
5. **Audit `CHANGELOG.md` for stale CLI surface.** For every CLI flag
   removed in this release, confirm the CHANGELOG (and `README.md`) does
   not still document it under examples or migration notes. Common
   regressions: `--resources <dir>`, `--db`, `--snomed`, `-r`, `-s`.
6. **`git status` must be clean of untracked source/test files.** Run
   `git status --short` and confirm no `??` lines under `src/` or `test/`
   — a new namespace that compiles locally but isn't tracked will pass
   `clj -M:test` here and fail in CI (or, worse, ship a release whose jar
   doesn't include the file).
7. **`fixtures.clj` install hints stay current.** When CLI surface changes,
   re-read every `assert-exists!` call site in `test/com/eldrix/hades/fixtures.clj`
   and confirm the printed `clj -M:run install …` hint actually works
   against the current parser.

### Bumping the tx-ecosystem pin

`conformance_test.clj` pins `tx-ecosystem-pinned-rev` to a specific upstream
commit so CI and local dev see the same test population. Every conformance
run shows the pinned rev and whether `upstream main` has moved ahead — that
banner is the trigger to consider bumping.

To bump:

1. Update `tx-ecosystem-pinned-rev` to the new upstream SHA.
2. Run `clj -X:conformance` — `ensure-test-data!` checks the new rev out.
3. Fix any new failures the updated fixtures surface.
4. Update `conformance-baseline.json` (pass/fail/total + `tx-ecosystem-rev`).
5. Update the conformance figure per the release checklist above.

Bumping is a deliberate act — never let CI pick up new tests implicitly.

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
