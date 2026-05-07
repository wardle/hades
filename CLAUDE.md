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

> Full fixture catalogue and provisioning recipes live in
> [`doc/development.md`](doc/development.md), mirroring CI exactly. The
> notes below cover only the conformance pin.

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

Then use `clj-nrepl-eval` to drive Hades interactively. The two public
namespaces you need are `com.eldrix.hades.core` (open / close service,
in-process operation calls) and `com.eldrix.hades.impl.http` (HTTP server
lifecycle). Open them to see the current public functions and call them
from the REPL — that is the source of truth and won't drift. The
conformance-test REPL helpers below are usually a faster on-ramp than
hand-wiring a server.

### REPL-driven conformance testing (the default workflow)

**Always use the REPL for conformance work.** The CLI (`clj -X:conformance`) is
slow and doesn't support the edit→reload→test cycle. Use the REPL to iterate
on changes: edit code, `restart!`, run filtered tests, check diffs.

**Setup** — start nREPL with conformance deps (port written to `.nrepl-port`):
```bash
clj -M:nrepl:conformance
```

**REPL API.** The conformance test namespace exposes lifecycle (start /
stop / restart), test-running (with filtering by suite or test name),
result inspection (per-suite tables, failure clustering, single-test
detail), single-test replay through the live HTTP server, and result
persistence (latest archive, baseline). Open the namespace in your editor
or `(dir com.eldrix.hades.conformance-test)` from the REPL to see the
current functions and their docstrings — that is the source of truth.

**Typical edit→test cycle.** Require the conformance namespace, start the
server (once per session — uses the pinned DB), run tests (optionally
filtered to one suite while iterating), inspect failures, edit code,
restart to reload Hades namespaces, re-run, diff against the previous
result, and save the baseline only when intentional.

**Shell escaping notes for `clj-nrepl-eval`:**
- Wrap the entire Clojure form in double quotes: `"(ct/start! ...)"`
- Escape inner double quotes with backslash: `\"/path/to/snomed.db\"`
- The `!` character does NOT need escaping when inside double quotes
- Do NOT use single quotes for the outer wrapper — use double quotes

## Architecture

Source layout:

- `src/com/eldrix/hades/core.clj` — public library API: service lifecycle
  and the FHIR terminology operations as plain Clojure functions. Read the
  ns docstring for the current surface.
- `src/com/eldrix/hades/impl/**` — library internals. Subject to breaking
  changes; nothing outside the project should depend on these namespaces.
- `src/com/eldrix/hades/cmd.clj` + `impl/cli.clj` — CLI application
  (entry point + option parsing). Not part of the library surface.

The internal layering under `impl/` (read each file for the current
public surface — names drift, the layering doesn't):

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
impl/protocols/                Abstract interfaces and result/input specs
                               (CodeSystem, ValueSet, ConceptMap)
    │
impl/snomed.clj                SNOMED CT via Hermes
impl/in_memory.clj             In-memory providers
impl/sqlite/provider.clj       SQLite-backed catalogue providers
impl/compose.clj               ValueSet compose engine (include/exclude, filter, etc.)

impl/loaders/fhir.clj          FHIR JSON → fhir-data
impl/loaders/loinc.clj         LOINC release dir → fhir-data
impl/index/memory.clj          fhir-data → in-memory providers
impl/index/sqlite.clj          fhir-data → SQLite container
impl/load.clj                  Convenience constructors over the load pipeline
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
  the result specs under `impl/protocols/` — read those for the current
  contract. No downstream layer should need to patch, enrich, or re-derive
  fields.
- **Composite** dispatches to the right impl by URL/version and handles
  version resolution (the version-resolution flags carried in the operation
  params). It does **not** patch results, add issues retroactively, look up
  other resources to fill gaps, or fix FHIRPath expressions. If the
  composite is doing post-call surgery on a result, the impl's return value
  is incomplete — fix the impl.
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
  back through protocol resource-fetching methods to recover data that
  should already have been in the result.

### Request-scoped overlays (`tx-resource`)

FHIR operations accept `tx-resource` parameters — temporary CodeSystem,
ValueSet, and ConceptMap resources scoped to a single request. A
dedicated interceptor in `impl/http.clj` parses them, builds in-memory
providers, and folds them onto the base service for the lifetime of the
request only. The composite dispatches to overlay providers first, then
to base providers, by URL/version.

From a handler's view this is invisible: handlers read the per-request
service off the context and call operation functions on `core`. The
cross-layer flags carried by FHIR operation parameters
(version-resolution flags, lenient-display, display-language, etc.) are
parsed once at the same interceptor boundary and merged into each
handler's params; grep `impl/http.clj` for the current names.

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
- The canonical data shapes for protocol parameters and return values live
  under `impl/protocols/` — read those files; they are the contracts between
  layers, and every protocol impl must return data conforming to them.
- Namespace specs under the relevant module.
- Write `s/fdef` for public functions with non-trivial parameter contracts.
- Use `clojure.spec.test.alpha/instrument` in test runs to validate data
  at boundaries automatically. Note: `instrument` only checks `:args`.
  Don't add `:ret` specs that aren't actually exercised — either drop
  them, or assert via `(s/assert ::spec result)` at the call site (the
  composite layer does this for the main operation contracts).

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

Bench files live under `test/` and are discovered by the `.*-bench$`
regex; `clj -M:test` ignores them. Open the bench namespace to see how to
add or invoke benchmarks from the REPL. The fixture opens the canonical
pinned Hermes DB (see the test fixtures namespace) and closes the service
afterwards. Missing fixtures throw with the install hint (same behaviour
as the live test suite); under `CI=true` the throw is fatal. Provision
via the steps in `Conformance / integration test data` above.

### tx-benchmark

When the user says **"run tx-benchmark"** — with or without a flavor —
they mean one of three pre-defined recipes documented in
[`doc/development.md`](doc/development.md) § *Run tx-benchmark*:

- **`preflight`** — correctness check across every op (~1 min)
- **`quick`** — preflight + every passing test at 1 VU / 10 s (broad regression sweep, ~5 min)
- **`full`** — preflight + warmup + bench at VUs 1 / 10 / 50 (~30+ min, Docker)

If the user doesn't name a flavor, ask which one — don't invent a new
shape. Each flavor in the doc is a single self-contained shell block
that boots hades, waits, runs, and tears down — execute it as written.
Don't read or run scripts under `~/Dev/tx-benchmark/scripts/` that
aren't present at the pinned SHA (untracked local additions like
`run-native.ts`, `bench-hades-native.sh` are stale scaffolding from
prior sessions).

### Conformance testing

The HL7 FHIR Terminology Ecosystem IG defines a large suite of conformance
tests. We run these programmatically via the HL7 validator (test-only dep).

```bash
clj -X:conformance                       # run conformance tests
```

Test data lives in a local clone of `HL7/fhir-tx-ecosystem-ig` (gitignored).
The test harness starts Hades on a random port, runs the validator's TX
tester against it, and parses the `TestReport` result into Clojure data.

After each phase, the conformance pass count must not decrease. New work
should increase it. Hades-specific overrides for expected error messages
live under `resources/`.

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
4. **Check the spec.** Does a spec exist under `impl/protocols/` for the
   return value this change produces? If not, define it before writing the
   implementation. The spec is the contract — write the contract first.
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
   No compose-in-metadata, no reaching back through protocol resource-fetching
   methods to recover data that should be in the result, no ad-hoc keys
   smuggled through the request context.
7. **Protocol impls return complete results.** If you changed a protocol impl,
   its return value must conform to the result spec under `impl/protocols/`.
   No downstream patching needed.
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
    and compose output all use keyword keys. String keys appear only at
    serialisation boundaries (`impl/wire.clj` / `impl/metadata.clj` for FHIR
    JSON output) and at parse boundaries (inbound `tx-resource` bodies and
    file-backed ingest).

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
7. **Test fixture install hints stay current.** When CLI surface changes,
   re-read every fixture-existence check in the test fixtures namespace and
   confirm the printed install hint actually works against the current
   parser.

### Bumping the tx-ecosystem pin

The conformance test namespace pins the upstream tx-ecosystem rev so CI
and local dev see the same test population. Every conformance run shows
the pinned rev and whether upstream main has moved ahead — that banner is
the trigger to consider bumping.

To bump: update the pinned rev, re-run conformance (the harness checks
the new rev out), fix any new failures the updated fixtures surface,
update the conformance baseline file under `test/resources/`, then update
the conformance figure per the release checklist above.

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
