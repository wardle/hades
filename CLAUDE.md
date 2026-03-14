# Hades — CLAUDE.md

A FHIR terminology server built in Clojure, currently wrapping Hermes (SNOMED CT)
and evolving towards a general-purpose terminology server. See `plan/roadmap.md` for the
full plan.

## Quick reference

```bash
clj -M:run <snomed-index-path> <port>    # run server
clj -M:test                              # run tests
clj -M:lint/kondo                        # static analysis
clj -M:lint/eastwood                     # lint
clj -M:test/cloverage                    # test coverage
clj -M:check                             # compilation check
clj -M:nrepl                             # start nREPL server (test paths included)

# conformance tests (both args required)
clj -X:conformance :snomed '"path/to/snomed.db"' :output-dir '"test/resources/conformance"'
```

### Interactive development via nREPL

Start an nREPL server (includes test paths):
```bash
clj -M:nrepl
```

Then use `clj-nrepl-eval` to start a Hades server and test interactively:
```bash
# Start Hermes + Hades on port 8080
clj-nrepl-eval -p <port> '
(require (quote [com.eldrix.hermes.core :as hermes]))
(require (quote [com.eldrix.hades.server :as server]))
(require (quote [com.eldrix.hades.registry :as registry]))
(require (quote [com.eldrix.hades.snomed :as snomed]))

(def svc (hermes/open "/path/to/snomed.db"))
(def snomed-svc (snomed/->HermesService svc))
(registry/register-codesystem "http://snomed.info/sct" snomed-svc)
(registry/register-valueset "http://snomed.info/sct" snomed-svc)
(def srv (server/make-server svc {:port 8080}))
(.start srv)
'

# Test an endpoint via HTTP
curl -s 'http://localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=73211009'

# Test the same thing internally — returns the plain Clojure map before HAPI serialisation
clj-nrepl-eval -p <port> '
(registry/codesystem-lookup {:system "http://snomed.info/sct" :code "73211009"})
'

# Stop
clj-nrepl-eval -p <port> '(.stop srv) (.close svc)'
```

## Architecture

```
server.clj / fhir.clj    HAPI FHIR layer — HTTP, serialisation
        │
   registry.clj           Lookup & dispatch — routes requests to implementations
        │
   protocols.clj           Abstract interfaces (CodeSystem, ValueSet, ConceptMap)
        │
   snomed.clj              SNOMED CT via Hermes
   fhir_codesystem.clj     File-backed CodeSystem/ValueSet (Phase 2.3+)
   fhir_conceptmap.clj     File-backed ConceptMap (Phase 6+)
```

### Layering rules (strict)

- **HAPI stays in `server.clj` and `fhir.clj` only.** No HAPI imports anywhere else.
  Protocol implementations work with plain Clojure maps.
- **Registry mediates all access.** `server.clj` calls registry functions, not protocol
  methods directly.
- **Implementations are HAPI-free.** They return plain maps. The server layer converts
  to HAPI model objects via `fhir.clj`.

### Request-scoped overlays (`tx-resource`)

FHIR operations accept `tx-resource` parameters — temporary CodeSystem/ValueSet
resources scoped to a single request. The overlay mechanism has two parts:

1. **HAPI bridge (dynamic var).** HAPI invokes `@Operation` methods via reflection,
   so we cannot add parameters to those signatures. `server.clj` defines
   `^:dynamic *tx-ctx*`, bound per-request in the servlet `service` method. Each
   operation method reads it via `(let [ctx *tx-ctx*] ...)`. The dynamic var is
   confined to `server.clj` — it exists solely to cross the HAPI reflection
   boundary.

2. **Explicit `ctx` parameter.** From the operation methods onward, `ctx` flows as
   an ordinary function argument: operation → registry → protocol impl. Registry
   lookup functions accept an optional first argument `ctx` — a map that may
   contain `:codesystems`, `:valuesets`, and/or `:conceptmaps` overlay maps.
   Overlays are checked before global atoms. The shape is specified by
   `::registry/ctx` in `registry.clj`.

When `ctx` is nil or absent, only global registrations are consulted.

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
- Use keywords for map keys within Clojure (`:code`, `:system`, `:display`).
- Use strings for map keys when the data represents FHIR JSON that will be
  serialised (`"result"`, `"display"`, `"message"`). This matches the existing
  convention in `snomed.clj` for protocol return values.
- Parse FHIR JSON with `clojure.data.json`, not HAPI parsers.

### Specs
- Use `clojure.spec.alpha` for all public API boundaries: protocol parameters,
  registry inputs, and constructor arguments.
- Namespace specs under the relevant module: `::protos/code`, `::registry/url`.
- Write `s/fdef` for public functions with non-trivial parameter contracts.

## Testing

- Every new public function gets a test.
- Use `clojure.spec.test.alpha/check` (generative testing) for pure functions
  that have specs. This is especially valuable for parsers and data transformers.
- Test edge cases explicitly: nil inputs, empty collections, missing optional params.
- Integration tests for server endpoints use actual HTTP requests against a test
  server instance.
- Place test fixtures (sample FHIR JSON resources) under `test/resources/`.
- Run the full test suite after every change. No regressions.

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

## Verification checklist

Run after every task:

1. `clj -M:test` — all tests pass
2. `clj -M:lint/kondo` — clean
3. `clj -M:lint/eastwood` — clean
4. Read back every changed file — verify it matches the task requirements
5. Confirm no HAPI imports outside `server.clj` / `fhir.clj`
6. Confirm no new atoms or mutable state without justification
7. Confirm new public functions have tests
8. Confirm specs exist for public API boundaries

## Key dependencies

| Dep | Purpose |
|-----|---------|
| `com.eldrix/hermes` | SNOMED CT terminology engine |
| `ca.uhn.hapi.fhir/*` 8.0.0 | FHIR R4 REST server + model |
| `io.pedestal/pedestal.jetty` | Jetty HTTP server |
| `org.clojure/data.json` | JSON parsing (HAPI-free layer) |
| `lambdaisland/uri` | URI parsing |

## Known bugs

None currently tracked. (Registry fallback bug from Phase 2.1 has been fixed.)
