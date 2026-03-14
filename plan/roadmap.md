# Hades Roadmap: Towards a Proper FHIR Terminology Server

This roadmap describes detailed implementation plans for evolving Hades from a
SNOMED CT FHIR facade into a general-purpose FHIR terminology server.

**This plan is test-driven.** The HL7 FHIR Terminology Ecosystem IG defines
706 conformance tests that all ecosystem terminology servers must pass. Phase 1
establishes the test infrastructure and baseline. The ordering of subsequent
phases is informed by what the tests actually require — not assumptions about
what seems logical. Phases 2–8 are presented in dependency order, but work
within each phase should be prioritised by test failure counts.

---

## Progress Summary (updated 2026-03-14)

| Item | Status | Notes |
|------|--------|-------|
| Phase 1.2 Baseline | **Done** | 53/572 passing (up from 3) |
| Phase 1.3 `tx-resource` | **Done** | server.clj parses tx-resource params, builds request-scoped overlays via `ctx` |
| Phase 2.1 Registry bugs | **Done** | `codesystem` and `concept-map` fallback fixed |
| Phase 2.1 Registry redesign | **Partial** | Overlay mechanism (ctx param) implemented; metadata maps and enumeration still needed |
| Phase 2.2 Error handling | **Partial** | $lookup returns 404 not 500; validate-code returns proper error maps for unknown systems |
| Phase 2.3 File-backed CodeSystem | **Done** | `fhir_codesystem.clj` — full CodeSystem + ValueSet protocol impl with hierarchy, comprehensive tests |
| Phase 2.4 CapabilityStatement | **Partial** | Software name/version/releaseDate added; TerminologyCapabilities has name/title/version |
| Phase 3.1 CS $validate-code | **Done** | SNOMED impl + file-backed impl + server endpoint + registry wiring |
| Phase 3.2 VS $validate-code | **Done** | SNOMED impl using `hermes/intersect-ecl` + file-backed impl + server endpoint |
| $lookup rewrite | **Done** | Correct property/designation format, version-uri, displayLanguage support |
| `fhir.clj` rewrite | **Done** | `map->parameters` correctly serialises uri/code/boolean/coding/nested types |
| Phase 4.1 cs-find-matches | **Done** | Filter evaluation on FhirCodeSystem + SNOMED ECL mapping |
| Phase 4.2 Compose engine | **Done** | `compose.clj` — pure expansion engine, `fhir_valueset.clj` — FhirValueSet deftype |
| Phase 4.3 ValueSet tx-resource | **Done** | `build-tx-ctx` two-pass (CodeSystems then ValueSets), ctx forwarded through params |
| Bug: `extract-property-value` | **Fixed** | `or` skipped falsy values (false, 0); `get-concept-property` used `some` which also skips false |
| Bug: empty designations | **Fixed** | `map->vs-expansion` emitted empty designation arrays |
| `issues` OperationOutcome | **Done** | `fhir.clj` builds OperationOutcome from issue maps; all validate-code impls return structured issues |
| codeableConcept support | **Partial** | VS $validate-code accepts and echoes back CC; good-CC tests pass; bad-CC tests need multi-coding iteration |
| `lenient-display-validation` | **Done** | Parsed from POST body; strict (error) by default, lenient (warning) when true |
| Dual-issue enrichment | **Done** | Registry adds not-in-vs + invalid-code issues; input-mode-aware expression paths |

**Current conformance: 223/572 passing** (up from 50 at start of session).

**Next high-impact work:** Phase 5 (versioned resource resolution — 126 version tests), hierarchical expansion (parameters suite — 35 tests), codeableConcept multi-coding iteration (permutations bad-cc — 14 tests), language-specific display handling (26+16 tests).

### Conformance breakdown (218/572)

| Suite | Passing | Total | Notes |
|-------|---------|-------|-------|
| permutations | 26 | 50 | good-coding/scd/cc1 pass; bad-cc need multi-coding CC iteration |
| notSelectable | 19 | 35 | prop-all variants need `used-valueset` param |
| version | 75 | 201 | many validate-code tests pass; expand + versioned resolution needed |
| validation | 32 | 60 | good+bad code/system/display/valueSet pass; CC bad-code, imports, language |
| language2 | 16 | 32 | display severity fix unlocked many; language header handling needed |
| simple-cases | 9 | 14 | expand works; enum-bad/lookup format/count issues remain |
| case | 4 | 4 | **Complete** |
| inactive | 3 | 12 | expansion works; validate-code inactive warnings need work |
| fragment | 3 | 6 | basic fragment + CC validation works |
| deprecated | 2 | 12 | experimental/draft pass; withdrawn/not-withdrawn need `used-valueset` |
| exclude | 3 | 6 | basic exclude expansion works |
| errors | 1 | 6 | combination-ok passes |
| metadata | 0 | 2 | CapabilityStatement/TerminologyCapabilities format issues |

---

## Phase 1 — Conformance Baseline & Test Infrastructure

### 1.1 Understanding the Test Suite

The HL7 [FHIR Terminology Ecosystem IG](https://github.com/HL7/fhir-tx-ecosystem-ig)
defines the formal test suite for FHIR terminology servers. This is the
standard that other servers (Ontoserver, Snowstorm, HAPI FHIR) are measured
against, and that HL7 uses to approve servers for use with the FHIR tooling.

**Test suite structure (706 tests in 30 suites):**

| Operation         | Count | Notes                                    |
|-------------------|-------|------------------------------------------|
| validate-code     | 422   | ValueSet $validate-code — by far largest |
| expand            | 190   | ValueSet $expand                         |
| cs-validate-code  | 42    | CodeSystem $validate-code                |
| related           | 28    | $related (concept relationships)         |
| lookup            | 16    | CodeSystem $lookup                       |
| translate         | 3     | ConceptMap $translate                    |
| term-caps         | 2     | TerminologyCapabilities                  |
| batch-validate    | 2     | Batch validation                         |
| metadata          | 1     | CapabilityStatement                      |

**Test modes:**
- `general` — **the baseline all servers must pass.** Uses synthetic/test code
  systems loaded via the `tx-resource` parameter (not real-world terminologies)
- `snomed` — SNOMED CT-specific tests (22 tests). Requires Hermes backend.
- `tx.fhir.org` — internal QA for the HL7 coordination server only. **Not
  required for other servers.** Includes LOINC, UCUM tests.
- `omop`, `icd-11` — domain-specific, only if those terminologies are supported
- `flat` — modifier for servers that cannot produce hierarchical expansions

**Critical design insight:** The `general` mode tests do NOT require pre-loaded
real-world code systems. Instead, each test suite defines `setup` resources —
synthetic CodeSystem and ValueSet JSON files under
`http://hl7.org/fhir/test/CodeSystem/...` — that must be accepted by the
server via the `tx-resource` parameter at test time. This means:
1. The server must accept CodeSystem/ValueSet resources dynamically (not just
   from filesystem on startup)
2. A file-backed CodeSystem provider is **prerequisite** to running most tests
3. Named ValueSet support (compose expansion) is needed for test ValueSets

**Test case format:** Each test specifies an `operation`, a `request` (FHIR
Parameters JSON), and an expected `response` (ValueSet, Parameters, or
OperationOutcome JSON). Comparison uses wildcards (`$id$`, `$uuid$`,
`$instant$`), optional markers (`$optional$`), and externalized error message
strings. Array order is never significant.

**Running the tests:**
```
java -jar validator.jar -txTests \
  -source {repo-root} \
  -tx {server-url} \
  -version R4 \
  -externals {messages-file} \
  -output {folder} \
  -modes general,snomed \
  -filter {optional-name-filter}
```

Test cases are authored in R5 format; the validator auto-converts for R4
servers. Failing tests produce output files for diffing against expected
results. On full success, the validator produces a compliance statement.

**Conformance levels:** No formal tiers. The `general` mode is the baseline.
Additional modes (`snomed`, `omop`, etc.) extend scope. The FHIR Product
Director reviews outcomes and can approve servers passing an "acceptable
alternative set of tests" — so there is human discretion, not strict
all-or-nothing.

### 1.2 Establish Baseline ✓

**Done.** Baseline established and ratcheting upward: now 53/572 passing (up
from initial 3). Conformance test harness implemented in
`test/com/eldrix/hades/conformance_test.clj` using `TxTester` from
`org.hl7.fhir.validation`. Results saved to `test/resources/conformance-results.json`.

### 1.3 `tx-resource` Support ✓

**Done.** The server parses `tx-resource` parameters from POST request bodies,
creates temporary `FhirCodeSystem` providers from embedded CodeSystem resources,
and passes them as request-scoped overlays through the registry call chain.
ValueSet tx-resource handling depends on Phase 4 (compose engine) for ValueSets
with compose definitions.

**Design decision — dynamic var to bridge HAPI:**

HAPI invokes `@Operation` methods via reflection based on annotations; we cannot
add custom parameters to those signatures. The overlay context therefore crosses
the HAPI boundary via a dynamic var (`^:dynamic *tx-ctx*` in `server.clj`),
bound per-request in the servlet `service` method override. Each operation method
reads it with `(let [ctx *tx-ctx*] ...)` and passes it as an explicit argument
to registry functions. The dynamic var is thread-safe (`binding` is per-thread,
and HAPI dispatches on the servlet thread) and confined entirely to `server.clj`.

From the operation methods onward, `ctx` flows as an ordinary function argument
through registry → protocol impl — no dynamic vars in the Clojure layers.

**Implementation (in `server.clj`):**
- `*tx-ctx*` — dynamic var, bound in `service` method, read in operation methods
- `extract-tx-resources` — parses POST body as FHIR Parameters JSON, extracts
  `tx-resource` entries
- `build-tx-ctx` — creates overlay map from resource maps (`FhirCodeSystem`
  instances for CodeSystem resources)
- `wrap-request` — wraps HttpServletRequest so the body InputStream can be
  re-read by HAPI after our parsing
- Each operation method: `(let [ctx *tx-ctx*] ...)` then passes `ctx` to
  registry functions

### 1.4 Ongoing Conformance Tracking

Re-run the test suite after every phase. Track pass rate over time. The
test suite is the objective measure of progress towards being a "proper"
FHIR terminology server.

Create a `messages-hades.json` externals file mapping expected error message
paths to Hades-specific error strings (same format as
`messages-ontoserver.csiro.au.json` in the test repo).

**Reference:** The [terminology server comparison](https://build.fhir.org/ig/HL7/fhir-extensions//qa-txservers.html)
reports generated by HL7 IGs show which servers pass for their required
code systems. Hades should aim to appear in these reports.

---

## Phase 2 — Foundation

This phase builds the minimum infrastructure needed to run conformance tests
meaningfully: registry redesign, error handling, and the file-backed
CodeSystem provider. These are prerequisites for `tx-resource` support
(Phase 1.3), which unlocks the `general` mode test suite.

### 2.1 Registry Redesign — Richer Registration with Metadata

The registry atoms (`registry/codesystems`, `registry/valuesets`,
`registry/conceptmaps`) currently store `{uri-or-id → impl}` with no metadata.
The same implementation can appear under multiple keys (e.g., SNOMED is
registered as both `"http://snomed.info/sct"` and `"sct"`) with no way to
distinguish canonical URL from logical ID, deduplicate, or enumerate metadata.

There is already an unused spec (`::codesystem` with `::url`, `::identifiers`,
`::name`, `::title`, `::description`) in registry.clj that was clearly intended for this purpose.

**Implementation:**
- Change atom values from bare impls to metadata maps:
  ```clojure
  {uri-or-id {:impl   <protocol-impl>
              :url    "http://snomed.info/sct"
              :id     "sct"
              :name   "SNOMED CT"
              :title  "SNOMED Clinical Terms"
              :version "..."
              :description "..."}}
  ```
- Update `register-codesystem` to accept a metadata map alongside the impl.
  Register under both the canonical URL and the logical ID, pointing to the
  same metadata map — this makes deduplication trivial (same `:url` value)
- Update `codesystem`, `valueset`, `concept-map` lookup functions to unwrap
  `:impl` from the metadata map
- Add `all-codesystems`, `all-valuesets`, `all-conceptmaps` functions that
  return deduplicated metadata (distinct by `:url`) for enumeration
- Wire the existing `::codesystem` spec to validate registration data
- Apply same pattern to valuesets and conceptmaps atoms
- **Request-scoped overlays for `tx-resource`: ✓** Registry lookup functions
  (`codesystem`, `valueset`, `concept-map`) accept an optional first `ctx`
  argument with `:codesystems` and/or `:valuesets` overlay maps, checked before
  global atoms. All registry operation functions (`codesystem-lookup`,
  `valueset-expand`, etc.) accept and forward `ctx`. The HAPI boundary is
  crossed via a dynamic var in `server.clj` (see Phase 1.3 for rationale).

**Code system versioning:**
Registry lookups must handle version-qualified URIs:
- For SNOMED: `http://snomed.info/sct/[module]/version/[date]` —
  `parse-snomed-uri` in snomed.clj already extracts edition and
  version from the URI. Wire this into registry lookup so version-qualified
  URIs resolve to the correct provider.
- For other systems: support the FHIR `|version` canonical suffix
  (e.g., `http://loinc.org|2.72`). Simple string split on `|` before
  lookup, with version passed through to the provider.

**Bug fix (`codesystem` and `concept-map` lookup functions in registry.clj): ✓**
Fixed. Both `codesystem` and `concept-map` fallback lookups now correctly call
`(uri-without-query uri-or-logical-id)` instead of invoking the string as a function.

**Files to change:** `registry.clj`, `core.clj` (update registration calls),
`snomed.clj` (provide metadata from HermesService)

### 2.2 OperationOutcome Error Handling

Currently, exceptions propagate as raw HTTP errors. FHIR requires structured
`OperationOutcome` responses. The conformance test suite expects
OperationOutcome for all error conditions — without this, test failures will
be unparseable. **This is prerequisite to getting meaningful test output.**

HAPI already renders its own exception types as OperationOutcome automatically
— the gap is Clojure/Java exceptions that aren't HAPI types, and nil returns
from the registry.

**Design principle:** Keep HAPI contained to `server.clj` and `fhir.clj`.
The registry and protocol implementations must not depend on HAPI classes.
Pedestal interceptors don't apply here — HAPI runs as a plain servlet, not
through Pedestal's routing.

**Implementation — two layers:**

1. **Registry/implementation layer** (HAPI-free): throw `ex-info` with a
   `:type` key for error classification:
   - Registry lookup returning nil → `(ex-info "..." {:type :not-found, ...})`
   - Invalid input (e.g., non-numeric SNOMED code) → `{:type :invalid}`
   - Unsupported operation → `{:type :not-supported}`
   - Replace existing `NotImplementedOperationException` in
     `parse-implicit-value-set` (snomed.clj) with a plain `ex-info` using
     `{:type :not-supported}`
   - Replace `ex-info` in `codesystem-subsumes` (registry.clj, cross-system
     subsumption check) to include `{:type :not-supported}`

2. **Server layer** (`server.clj`): wrap each resource provider operation
   method in a try/catch that maps `:type` to HAPI exceptions:
   - `:not-found` → `ResourceNotFoundException` (404)
   - `:invalid` → `InvalidRequestException` (400)
   - `:not-supported` → `NotImplementedOperationException` (501)
   - Unrecognised `ex-info` → `InternalErrorException` (500)
   - Also check for nil returns from registry before calling
     `fhir/map->parameters` and throw `:not-found` equivalent
   - HAPI renders all of these as OperationOutcome with proper issue codes

No custom `fhir/operation-outcome` helper needed — HAPI handles the rendering.

**Files to change:** `server.clj` (try/catch wrappers), `registry.clj`
(throw on nil lookups), `snomed.clj` (replace HAPI exception import with
plain ex-info)

### 2.3 Generic File-Backed CodeSystem Provider ✓

**Done.** Implemented in `src/com/eldrix/hades/fhir_codesystem.clj` with
comprehensive tests in `test/com/eldrix/hades/fhir_codesystem_test.clj`.

`FhirCodeSystem` deftype implements both `CodeSystem` and `ValueSet` protocols:
- Code indexing via `build-code-index` (flattens nested concepts)
- Hierarchy construction via `build-hierarchy` (nesting + explicit parent/child properties)
- Ancestor checking via BFS
- `cs-lookup`: display, parent/child properties, designations, definition, abstract flag
- `cs-validate-code`: case-insensitive display matching, designation matching
- `cs-subsumes`: hierarchy-based subsumption
- `vs-expand`: filtering with offset/count pagination
- `vs-validate-code`: system/code membership
- `make-fhir-code-system` factory from plain Clojure map (parsed JSON)
- `register!` for global registry registration
- HAPI-free — uses plain Clojure maps throughout
- `cs-find-matches`: filter concepts by property values and/or display text

**Protocol implementation (`ValueSet`) — implicit "all codes" value set:**

A single type implements both protocols (same pattern as `HermesService`).
This handles the implicit value set for the code system itself (all codes).
Composed value sets referencing this code system are Phase 4.

- `vs-resource`: return value set metadata
- `vs-expand`: iterate `code->concept`, apply filter string matching on
  display/designations, respect `offset`/`count`
- `vs-validate-code`: check membership in `code->concept`

**Files to create:** `src/com/eldrix/hades/fhir_codesystem.clj`
**Files to change:** `registry.clj` (register new providers)

### 2.4 CapabilityStatement at `/fhir/metadata` (partial ✓)

HAPI's `RestfulServer` auto-generates a CapabilityStatement by introspecting
the registered resource providers and their `@Operation` annotations. This is
sufficient initially — it will correctly advertise `$lookup`, `$subsumes`,
`$expand`, and `$translate`. The conformance test suite includes 1 metadata
test and 2 TerminologyCapabilities tests.

**Done so far:**
- CapabilityStatement software component: name "Hades", version, releaseDate
- TerminologyCapabilities: `.setVersion`, `.setName "Hades"`,
  `.setTitle "Hades FHIR Terminology Server"`

**Remaining:**
- `TerminologyCapabilities` (`/fhir/metadata?mode=terminology`) needs
  enumerable registry metadata (Phase 2.1) to list supported code systems

**Files changed:** `server.clj`

---

## Phase 3 — `$validate-code` & `$lookup`

`$validate-code` accounts for **464 of 706 tests** (422 ValueSet + 42
CodeSystem). `$lookup` accounts for 16. Together these represent ~68% of the
conformance suite. This is where the biggest pass-rate gains come from.

### 3.1 CodeSystem `$validate-code` ✓

Implemented for SNOMED. Protocol method `cs-validate-code` was a no-op —
now fully wired: `snomed.clj` impl, `server.clj` endpoint, `registry.clj` dispatch.

**Parameters (per FHIR spec):**
- `url` (0..1 uri), `code` (0..1 code), `version` (0..1 string),
  `display` (0..1 string), `coding` (0..1 Coding),
  `codeableConcept` (0..1 CodeableConcept), `date` (0..1 dateTime),
  `abstract` (0..1 boolean), `displayLanguage` (0..1 code)
- Client must provide exactly one of: `code`+`system`, `coding`, or
  `codeableConcept`

**Required output:** `result` (1..1 boolean), `message` (0..1 string),
`display` (0..1 string)

**Implementation in `snomed.clj`:**
- Parse `code` via `parse-long`
- Use `hermes/concept` to look up — returns a `Concept` record with `:id`,
  `:active`, `:moduleId`, `:definitionStatusId`, or nil if not found
- If nil: return `{"result" false, "message" "Code not found"}`
- If found but inactive: return `{"result" true, "message" "Code is
  inactive", ...}` (FHIR spec: inactive codes are still valid in the code
  system)
- If `abstract` is false and concept is abstract (for SNOMED: check if
  `definitionStatusId` indicates a non-leaf concept — though SNOMED doesn't
  truly have "abstract" codes, so this may be a no-op for SNOMED)
- If `display` provided: check against **all** descriptions via
  `hermes/descriptions` (returns seq of `Description` records, each with
  `:term`, `:typeId`, `:active`, `:languageCode`). Match `display` against
  any `:term` value. Display case sensitivity is code-system-dependent;
  SNOMED is case-insensitive for matching purposes. If no match:
  `{"result" false, "message" "Display '...' not valid for code",
  "display" <preferred-term>}`
- If `codeableConcept` provided (multiple codings): return true if **any**
  coding is valid in this code system. Server.clj unwraps this.
- Use `hermes/preferred-synonym*` with `hermes/match-locale` for the
  `displayLanguage` parameter to return the recommended display
- Always return `display` in the response (the recommended display for the
  code) even when `display` was not provided in the request
- Return map: `{"result" true/false, "display" preferred-term, "message" "..."}`

**Implementation in `server.clj`:**
- Add `ValidateCodeCodeSystemOperation` definterface
- Add the operation method to `CodeSystemResourceProvider` with HAPI
  annotations — same pattern as `LookupCodeSystemOperation` in server.clj
- Unwrap `coding`/`codeableConcept` to `code`+`system` as per `$lookup`
  (as done in the `lookup` method in server.clj)
- Wire through `registry/codesystem-validate-code`

**Implementation in `registry.clj`:**
- Fill in `codesystem-validate-code` body (currently empty):
  look up code system from registry by `:system`, call
  `protos/cs-validate-code`

**Tests:**
- Valid active SNOMED code → `{"result" true, "display" "..."}`
- Valid inactive SNOMED code → `{"result" true, "inactive" true}`
- Non-existent code → `{"result" false, "message" "Code not found"}`
- Valid code with matching display (any synonym/FSN) → `{"result" true}`
- Valid code with non-matching display → `{"result" false, "message" "..."}`
- Valid code with `displayLanguage` → returns locale-appropriate preferred term

### 3.2 ValueSet `$validate-code` ✓

Implemented for SNOMED implicit value sets. Uses `hermes/intersect-ecl` for
efficient membership checking. Server endpoint and registry dispatch wired.

**Parameters:** `url`, `code`, `system`, `systemVersion`, `display`,
`coding`, `codeableConcept`, `valueSetVersion`, `displayLanguage`

**Implementation in `snomed.clj`:**

No need to expand the full value set. Hermes provides efficient membership
checks for each implicit value set type. Dispatch on the `:query` key from
`parse-implicit-value-set`:

- `:isa` (e.g., `?fhir_vs=isa/[sctid]`): use `hermes/subsumed-by?` —
  direct O(1) store lookup, returns boolean
- `:in-refset` (e.g., `?fhir_vs=refset/[sctid]`): use
  `hermes/component-refset-ids` which returns the set of refset IDs for a
  component, then `(contains? result refset-id)`
- `:ecl` (arbitrary ECL): use `hermes/intersect-ecl` with a single-element
  collection `[concept-id]` and the ECL expression — returns only matching
  IDs, index-backed via Lucene, no full expansion needed
- `:all`: any valid SNOMED concept — delegate to `cs-validate-code`
- `:refsets`: check if concept is a reference set (i.e., subsumed by
  `ReferenceSetConcept`) — use `hermes/subsumed-by?`

If code is valid in the value set and `display` is provided, validate display
using same logic as 3.1 (`hermes/descriptions` match).

Return `{"result" true/false, "display" "...", "message" "..."}`

**Implementation in `server.clj`:**
- Add `ValidateCodeValueSetOperation` definterface
- Add the operation method to `ValueSetResourceProvider`

**Implementation in `registry.clj`:**
- Fill in `valueset-validate-code` body (currently empty):
  look up value set from registry by `:url`, call `protos/vs-validate-code`

**Note:** This implementation handles SNOMED implicit value sets and
file-backed code systems (Phase 2.3). Named/composed value sets (Phase 4)
will add their own `vs-validate-code` via the compose engine.

---

### 3.3 $lookup Improvements ✓ (not originally planned as separate item)

Significant rework of `cs-lookup` in `snomed.clj`:
- Correct Hermes key names (`:directParentRelationships` camelCase, not kebab-case)
- Children via `hermes/child-relationships-of-type` (not in `extended-concept`)
- Attribute relationships from `(dissoc (:directParentRelationships ec) snomed/IsA)`
- Concrete values via `hermes/concrete-values`
- `version-uri` helper for SNOMED edition version strings
- Correct FHIR parameter types: keywords for valueCode, `"value"` for designation text
- `displayLanguage` properly extracted and passed through from server.clj
- Returns 404 (not 500) for unknown systems

`fhir.clj` `map->parameters` rewritten to correctly serialise:
- `UriType` for system/url/source/target/targetSystem parameters
- `CodeType` for keyword values
- `BooleanType` for boolean values
- `Coding` for maps with `:code` and `:system`
- Nested parts for other maps
- Sequential values expanded to multiple parameters with same name

---

## Phase 4 — Named ValueSets & Compose Expansion

This phase is higher priority than in previous iterations because the
conformance test suite loads synthetic ValueSets via `tx-resource` that
require compose expansion. Without this, most `general` mode tests cannot
pass — the test ValueSets reference the test CodeSystems via compose includes.

### 4.1 Compose Expansion Engine

The core of named ValueSet support. A compose evaluator that takes a parsed
ValueSet definition and delegates to registered CodeSystem providers via the
registry. This is a registry-level concern, not a standalone provider type.

**FHIR compose semantics (from the spec):**

Invariants governing the compose structure:
- **vsd-1**: An include/exclude must have `valueSet[]` or `system` (or both)
- **vsd-2**: `concept[]` or `filter[]` require `system` to be present
- **vsd-3**: `concept[]` and `filter[]` are **mutually exclusive** within a
  single include — cannot have both

Combination rules:
- Multiple `filter[]` within one include: **AND** (all filters must apply)
- Multiple `include[]` elements: **union** across includes
- `exclude[]`: applied after all includes, **subtracts** matching codes
- `valueSet[]` can appear alongside `system`+`concept[]` or
  `system`+`filter[]` — the results are intersected
- `compose.inactive`: whether to include inactive codes (overridable by
  `$expand` `activeOnly` parameter)
- `compose.lockedDate`: pins code system versions for evaluation

**Implementation — new `compose.clj` or expand within `registry.clj`:**

For each `include` element:
1. If `concept[]` present (with `system`): enumerate the listed codes.
   Look up each code via the registered CodeSystem provider's `cs-lookup`
   to get display/designations. Codes not found in the system are an error.
2. If `filter[]` present (with `system`): pass filters to the registered
   CodeSystem provider. Use `cs-find-matches` (defined in `protocols.clj`, currently
   unused) — define its contract to accept a sequence of FHIR filter maps
   `[{:property p :op op :value v} ...]` where all must be satisfied (AND).
   - SNOMED impl: map filter ops to ECL. `is-a` → `<< value`,
     `descendant-of` → `< value`. For `concept` property, map directly.
     Other properties may need Hermes search params.
   - File-backed impl: in-memory filtering on the property/hierarchy index.
     `is-a` → walk child tree. `regex` → regex match. `in`/`not-in` →
     set membership. `exists` → property presence check.
3. If `valueSet[]` present: recursively expand each referenced ValueSet
   (look up from `registry/valuesets` and call `vs-expand`).
   **Circular reference detection**: maintain a set of ValueSet URLs
   currently being expanded; throw if a URL is encountered twice.
4. If both `system`-based results and `valueSet[]` results exist in the
   same include: **intersect** them.

Union all include results. Then for each `exclude` element, apply the same
evaluation logic and subtract matching codes from the result.

**Protocol changes:**
- Define `cs-find-matches` contract: accepts a params map including
  `:filters` (seq of `{:property :op :value}`), optional `:filter` (text
  search string). Returns a seq of concept maps matching ALL filters.
- SNOMED implementation (`snomed.clj`): map FHIR filters to ECL constraints
  and Hermes search params
- File-backed implementation (`fhir_codesystem.clj`): in-memory filtering
  with support for the 9 FHIR filter operators (`=`, `is-a`,
  `descendant-of`, `is-not-a`, `regex`, `in`, `not-in`, `generalizes`,
  `exists`)

### 4.2 Named/Stored ValueSets

Load ValueSet JSON resources from the filesystem, FHIR definition bundles,
and `tx-resource` parameters. Register in the registry and expand via the
compose engine (4.1).

**Implementation:**
- Parse ValueSet JSON via `clojure.data.json` (same approach as Phase 2.3)
- Store the parsed compose definition alongside registry metadata
- Register by canonical URL in `registry/valuesets`
- On `vs-expand`: pass the stored compose definition to the compose engine
- On `vs-validate-code`: either expand and check membership, or (for simple
  extensional value sets with only `concept[]` includes) check directly
  against the enumerated code set without full expansion
- FHIR definition bundles (Phase 5.1) already produce ValueSet entries —
  register those here too
- **`tx-resource` ValueSets:** When a `tx-resource` parameter contains a
  ValueSet, parse it and register it in the request-scoped overlay (2.1).
  This is the mechanism by which the conformance tests provide their test
  ValueSets.

**$expand parameters not currently handled (server.clj):**
Several `$expand` parameters are accepted by the HAPI interface but not
passed through to the implementation. Wire these through:
- `offset`/`count`: pagination (flat expansions only; count=0 returns just
  total). Servers not obliged to support paging but if they do, must support
  both.
- `includeDesignations`/`designation`: control designation inclusion
- `excludeNested`: prevent hierarchical nesting in results
- `activeOnly`: override compose.inactive setting
- `displayLanguage`: already partially handled
- `exclude-system`, `system-version`, `force-system-version`,
  `check-system-version`: code system version control parameters
- `expansion.parameter`: record all parameters that affected the expansion
  in the response
- `too-costly`: return OperationOutcome with code `too-costly` at server
  discretion for large expansions

### 4.3 Expansion Caching (Deferred)

Deferred until there is measured evidence of a performance problem. FHIR
built-in code systems are small (in-memory maps). SNOMED expansion is
Lucene-backed via Hermes. Compose expansion across multiple small systems
is unlikely to be a bottleneck. The immutable-by-design philosophy means
cache invalidation is trivial (restart), but the complexity isn't warranted
yet.

---

## Phase 5 — FHIR Content Loading & Configuration

With the file-backed CodeSystem provider (Phase 2.3) and compose engine
(Phase 4.1) in place, Hades can now load real-world FHIR content.

### 5.1 Load FHIR Built-in Code Systems & ValueSets

The FHIR spec distributes all built-in code systems and value sets as JSON
Bundles (`definitions/valuesets.json`, `definitions/codesystems.json`). Each
Bundle contains `entry[]`, each entry having `fullUrl` and `resource` with
`resourceType` distinguishing CodeSystem from ValueSet.

**Implementation:**
- Formalize the exploratory code in the `fhir.clj` comment block: read Bundle
  JSON via `clojure.data.json`, filter entries by `resourceType`
- For each `CodeSystem` entry: create a file-backed provider (2.3) and
  register in the registry by its canonical URL (`:url` field) with metadata
  from the resource
- For each `ValueSet` entry: parse and register as a named ValueSet (4.2)
- On startup, accept a path to the FHIR definitions directory. Import all
  code systems and value sets found. No selectivity needed initially — these
  are small and loading all is fast. Selective loading can come later with
  declarative configuration if needed
- This is also needed for the THO (HL7 Terminology) tests in `general` mode,
  which reference ActClass and other HL7 code systems

**Files to change:** `core.clj` (accept definitions path, trigger loading),
new loader function in `fhir_codesystem.clj` or separate `loader.clj`

### 5.2 Declarative Configuration (Deferred)

Deferred until the pluggable architecture is proven with Hermes + file-backed
providers. For now, extend the CLI to accept optional additional arguments
(e.g., `<snomed-path> <port> [fhir-definitions-path]`) or use environment
variables. Full declarative config (EDN file specifying all terminology
sources) can be added once there are enough provider types to warrant it.

### 5.3 Custom/Local Code Systems (Deferred)

Support for CSV, CSVW, EDN and other non-FHIR formats deferred. The file-
backed provider (2.3) already handles FHIR CodeSystem JSON, which is the
canonical interchange format. Local codes can be expressed as FHIR JSON.
Additional import formats can be added later once the pluggable architecture
is solid.

### 5.4 CORS Support

Since HAPI runs as a servlet inside raw Jetty (no Pedestal routing), the right
mechanism is Jetty's `CrossOriginHandler` wrapping the `ServletContextHandler`
in `make-server` (server.clj). This is a Jetty-level concern, not a
HAPI interceptor — it handles preflight OPTIONS and the CORS headers before
the request reaches the servlet.

**Implementation:**
- Add `org.eclipse.jetty.server.handler.CrossOriginHandler` to the handler
  chain in `make-server`, wrapping the existing `ServletContextHandler`
- Default: CORS **disabled** (no cross-origin access). Opt-in only.
- Configurable via server options: `:cors-allowed-origins` (list of origins
  or `"*"`), `:cors-allowed-headers`, `:cors-enabled` (default false)
- When enabled, allow standard FHIR headers (`Accept`, `Content-Type`,
  `Authorization`, `Prefer`) and GET/POST/OPTIONS methods
- Pass config through from startup (feeds into future declarative config)

**Files to change:** `server.clj` (`make-server`), `core.clj` (pass config)

---

## Phase 6 — ConceptMap `$translate`

### 6.1 Registry Changes for ConceptMap Lookup

The current `registry/conceptmaps` atom is keyed by `(source-uri, target-uri)`
tuples. The `$translate` spec requires finding ConceptMaps by:
- `url` (canonical ConceptMap URL)
- `source` + `target` (source/target value set URIs)
- `system` + `targetsystem` (code system URIs)
- `system` alone (return all known translations)

**Implementation:**
- Extend the conceptmaps registry to support multiple lookup paths:
  - By canonical URL: `conceptmap-url → impl`
  - By source+target pair: `(source-uri, target-uri) → [impl ...]`
  - By source system: `source-system → [impl ...]` (for "all translations")
- `register-concept-map` accepts metadata (url, source, target, groups) plus
  impl, and indexes under all applicable keys
- Lookup function tries URL first, then source+target, then source-only

### 6.2 File-Backed ConceptMap Provider

Load FHIR ConceptMap JSON resources from filesystem and FHIR definition
bundles.

**ConceptMap resource structure (from FHIR spec):**
- Top-level: `url`, `sourceUri`/`sourceCanonical`, `targetUri`/`targetCanonical`
- `group[]`: each group has `source` (system URI), `sourceVersion`,
  `target` (system URI), `targetVersion`
- `group.element[]`: each element has `code`, `display`, and `target[]`
- `group.element.target[]`: each has `code`, `display`, `equivalence`
  (required), `comment`, `dependsOn[]`, `product[]`
- `group.unmapped`: fallback for unmapped codes — mode is `provided`
  (use source code), `fixed` (use specified code), or `other-map`
  (delegate to another ConceptMap)

**Data model — build on parse:**
- Forward index: `(source-system, source-code)` → list of
  `{:target-system :target-code :equivalence :comment :display}`
- Reverse index: `(target-system, target-code)` → list of
  `{:source-system :source-code :equivalence :display}`
  with equivalence values inverted (`subsumes` ↔ `specializes`,
  `wider` ↔ `narrower`, others unchanged)
- Unmapped rules per group for fallback behavior

**Protocol implementation (`ConceptMap`):**
- `cm-resource`: return resource metadata
- `cm-translate`: look up code in forward index (or reverse index if
  `reverse=true`). Apply unmapped rules for codes with no matches.

**$translate output (per FHIR spec):**
- `result` (1..1 boolean): true only if at least one match has equivalence
  that is NOT `unmatched` or `disjoint`
- `message` (0..1 string): error details or hints
- `match[]` (0..*): each contains `equivalence`, `concept` (Coding with
  system+code+display), `source` (canonical URL of the ConceptMap), and
  optionally `product[]`

**Files to create:** `src/com/eldrix/hades/fhir_conceptmap.clj`

### 6.3 SNOMED CT Mappings via Hermes

Auto-discover installed map reference sets from Hermes on startup and
register them as ConceptMaps.

**Discovery (on startup):**
- Call `hermes/installed-reference-sets` to get all installed refset IDs
- For each, retrieve a sample item via `hermes/component-refset-items` and
  check the type: `SimpleMapRefsetItem`, `ComplexMapRefsetItem`, or
  `ExtendedMapRefsetItem` indicate map refsets
- For each discovered map refset, register a ConceptMap with:
  - Source system: `http://snomed.info/sct`
  - Target system: determined from a lookup table of known refset IDs to
    target system URIs (e.g., refset `447562003` → ICD-10
    `http://hl7.org/fhir/sid/icd-10`). For unknown refset IDs not in the
    table: log a warning and either skip or register with a generic URI
    scheme (e.g., `http://snomed.info/sct/map/[refset-id]`).
  - Canonical URL: derive from refset ID (e.g.,
    `http://snomed.info/sct/[module]/cm/[refset-id]` or similar convention)

**Translation implementation in `snomed.clj`:**
- `cm-translate` for forward (SNOMED → target): call
  `hermes/component-refset-items svc (parse-long code) refset-id` to get
  map refset items. Extract `:mapTarget` from each active item as the
  target code.
- For complex/extended maps: return **all** active targets as matches
  without evaluating `:mapRule`/`:mapGroup`/`:mapPriority` (best effort).
  Include `:mapAdvice` in the match `comment` field for human consumption.
  Include `:mapGroup` and `:mapPriority` as additional context if useful.
- `cm-translate` for reverse (target → SNOMED): use
  `hermes/reverse-map svc refset-id target-code` which returns refset items
  for SNOMED concepts mapping to that target code. Extract
  `:referencedComponentId` as the source SNOMED code.
- Equivalence: for simple maps use `relatedto` (no further semantics
  available). For complex/extended maps, could derive from `:correlationId`
  if meaningful, otherwise default to `relatedto`.

**Known map refset IDs and their target systems:**
- `447562003` — SNOMED → ICD-10 (international complex map)
- `999002271000000101` — SNOMED → ICD-10 (UK complex map)
- `900000000000497000` — SNOMED → CTV3 (simple map)
- `446608001` — SNOMED → ICD-O (complex map)
- Others discoverable at runtime from installed reference sets

**Files to change:** `snomed.clj` (implement `cm-translate`, add discovery),
`core.clj` (trigger discovery and registration on startup)

### 6.4 Cross-CodeSystem Subsumption (Deferred)

Stretch goal from README: test subsumption across code systems when a map
exists to a common system. Depends on `$translate` working first. Complex and
potentially ambiguous (multiple mappings, varying equivalence). Defer until
Phases 6.1-6.3 are proven.

---

## Phase 7 — Resource Read & Search

### 7.1 Protocol Implementation for Resource Retrieval

The `cs-resource`, `vs-resource`, and `cm-resource` protocol methods already
exist in `protocols.clj` but are no-ops in all implementations.
Each must return a plain Clojure map (HAPI-free) representing the full FHIR
resource. The server layer converts to HAPI model objects.

**CodeSystem `cs-resource` — file-backed (fhir_codesystem.clj):**
Straightforward — the parsed JSON already contains the full resource. Return
it as-is (or a curated subset: `url`, `version`, `name`, `title`, `status`,
`content`, `description`, `hierarchyMeaning`, `count`, `property[]`,
`filter[]`, `concept[]`).

**CodeSystem `cs-resource` — SNOMED (snomed.clj):**
Construct a full CodeSystem resource for SNOMED CT from Hermes metadata:
- `url`: `http://snomed.info/sct`
- `version`: from `hermes/release-information` (module + effectiveTime)
- `name`: `"SNOMEDCT"`, `title`: from release information `:term`
- `status`: `"active"`, `content`: `"not-present"` (concepts not enumerated
  in the resource itself — they're in Hermes)
- `hierarchyMeaning`: `"is-a"`
- `property[]`: declare SNOMED properties — `parent`, `child`, `moduleId`,
  `inactive`, `sufficientlyDefined` (already used in `cs-lookup`)
- `filter[]`: declare supported filters — `concept` with `is-a`,
  `descendant-of`, `in`, `not-in`; `expression` with `=` (ECL)
- `count`: could use `hermes/stream-all-concepts` to count, or omit

**ValueSet `vs-resource`:**
- File-backed: return the parsed ValueSet JSON
- SNOMED implicit value sets: construct minimal resource with `url`,
  `status`, `compose` reflecting the implicit definition

**ConceptMap `cm-resource`:**
- File-backed: return the parsed ConceptMap JSON
- SNOMED map refsets: construct resource with `url`, `source`, `target`,
  `group[]` structure. Groups populated from map refset metadata.

### 7.2 FHIR Resource Conversion Functions

`server.clj` `@Read` and `@Search` methods must return HAPI model objects.
Need conversion functions in `fhir.clj` analogous to the existing
`map->parameters` and `map->vs-expansion`:

- `map->codesystem`: Clojure map → `org.hl7.fhir.r4.model.CodeSystem`.
  Non-trivial — CodeSystem has nested structures for properties, filters,
  and optionally concepts. But the structure is regular and mappable.
- `map->valueset`: Clojure map → `org.hl7.fhir.r4.model.ValueSet`.
  Includes compose and/or expansion depending on context.
- `map->conceptmap`: Clojure map → `org.hl7.fhir.r4.model.ConceptMap`.
  Groups, elements, targets with equivalence.

These conversions keep HAPI contained to `server.clj` and `fhir.clj`.

### 7.3 Read Endpoints

Add HAPI `@Read` annotated methods to each resource provider in `server.clj`.

**`GET /fhir/CodeSystem/[id]`:**
- Look up by logical ID in registry (Phase 2.1 metadata includes `:id`)
- Call `cs-resource` on the impl, convert via `map->codesystem`
- If not found, throw `:not-found` ex-info (Phase 2.2 error handling)

**`GET /fhir/ValueSet/[id]`** and **`GET /fhir/ConceptMap/[id]`:**
Same pattern.

**Free win:** Once `@Read` works, HAPI automatically routes instance-level
operations like `GET /fhir/CodeSystem/sct/$lookup?code=...` — no additional
work needed.

### 7.4 Search Endpoints

Add HAPI `@Search` annotated methods to each resource provider. Search
operates on the deduplicated registry metadata (Phase 2.1 `all-codesystems`
etc.) — no need to call protocol methods.

**CodeSystem search parameters (minimum useful subset):**
- `url` (uri) — canonical URL
- `version` (string) — business version
- `name` (string) — machine-friendly name
- `title` (string) — human-friendly title
- `status` (code) — publication status

**ValueSet search parameters:**
- `url` (uri), `version` (string), `name` (string), `status` (code)
- `reference` (uri) — code system URL referenced in compose

**ConceptMap search parameters:**
- `url` (uri), `name` (string), `status` (code)
- `source` (uri) — source value set/system
- `target` (uri) — target value set/system

**Implementation:** Filter `all-codesystems` / `all-valuesets` /
`all-conceptmaps` by the provided search parameters. Convert each matching
metadata entry to a HAPI resource via the conversion functions (7.2). HAPI
handles Bundle wrapping and pagination of search results.

**Files to change:** `server.clj` (read/search methods), `fhir.clj`
(conversion functions), `snomed.clj` / `fhir_codesystem.clj` /
`fhir_conceptmap.clj` (implement `*-resource` protocol methods)

---

## Phase 8 — Production Readiness

**Note on testing:** Tests should be written alongside each phase, not batched
here. Each phase should include unit tests for new protocol implementations
and integration tests for new server endpoints. The existing test
infrastructure (`:test` alias, cognitect test-runner, clj-kondo, eastwood)
is sufficient.

### 8.1 CI/CD Pipeline

The build and lint aliases already exist in `deps.edn`. Just needs a GitHub
Actions workflow.

- `.github/workflows/ci.yml`:
  - Trigger on push/PR
  - Run `clj -M:test`
  - Run `clj -M:lint/kondo` and `clj -M:lint/eastwood`
  - Build uber-jar (`clj -M:build uber`)
  - On tagged release: publish uber-jar as GitHub release artifact

### 8.2 Health Check Endpoint

Useful for container deployments (Kubernetes liveness/readiness probes).

- Add a non-FHIR endpoint outside `/fhir/` path — e.g., `GET /health`
- Return 200 + JSON: `{version, registered-codesystems, registered-valuesets,
  uptime}`
- Implementation: add a second servlet or a Jetty handler before the FHIR
  servlet in `make-server`

### 8.3 Authentication & Security

The primary deployment model for a read-only terminology server is behind a
reverse proxy (nginx, Caddy, cloud load balancer) which handles TLS and auth.
Building auth into Hades itself adds complexity with marginal benefit.

- Document reverse-proxy deployment setup (TLS termination, auth)
- If standalone deployment is needed: add optional API key authentication
  via a Jetty handler (check `Authorization` header, reject with 401)
- SMART-on-FHIR and OAuth2 are overkill unless Hades is deployed standalone
  in a clinical network — defer unless there's demand

### 8.4 FHIR R5 Support (Deferred)

R4 is the dominant deployed version. R5 would require a separate HAPI
dependency (`hapi-fhir-structures-r5`), different model classes, and
different resource shapes (ValueSet compose refactored, TerminologyCapabilities
restructured). Defer until there's demand. Document R4 conformance in
the CapabilityStatement.

### 8.5 `$closure` Operation (Out of Scope)

The `$closure` operation is stateful by design: the server maintains named
closure tables across requests, tracks versions, and returns incremental
ConceptMap updates. This fundamentally conflicts with Hades' immutable/
stateless philosophy.

For SNOMED subsumption use cases, clients can use `$subsumes` directly or
build their own closure tables from `$expand` results. Hermes provides
`subsumed-by?`, `all-parents`, and `all-children` for efficient hierarchy
traversal.

Explicitly out of scope. Document in CapabilityStatement as not supported.

### 8.6 Drop HAPI Dependency (Exploratory)

As noted in the README roadmap: explore replacing HAPI with direct JSON/EDN
responses. HAPI adds significant JAR size, startup time, and Java interop
boilerplate (the definterfaces in server.clj are cumbersome).

**Decision point: after Phase 7.** The resource conversion functions
(`map->codesystem`, `map->valueset`, `map->conceptmap`) written in Phase 7.2
will make the cost of HAPI very visible. If those conversions are painful,
that's the signal to revisit.

**Trade-offs:**
- Pro: smaller artifact, faster startup, simpler code, no Java interop
- Pro: the `fhir.clj` comment block already shows plain JSON is feasible
- Con: lose XML support (or need a separate XML serializer)
- Con: lose automatic FHIR validation that HAPI provides
- Con: need to handle content negotiation, parameter parsing, error
  responses manually (though much of this is straightforward in Clojure)
