# Architecture Fix Plan

## Goal

Align the codebase with the layering rules and data contracts described in
CLAUDE.md and `plan/conformance-plan.md` Phase 0. After this work:

- Every protocol method has a spec'd return shape (keyword-keyed maps)
- The registry is thin dispatch + version resolution — no result patching
- The server is mechanical HAPI translation — no terminology logic
- Data flows through explicit parameters and return values — no secret channels
- `ctx` is a clean, well-understood concept with a single clear role

## Current problems (inventory)

### 1. No return specs

Protocol methods in `protocols.clj` define *operations* but not *result shapes*.
Each impl invents its own response format. The registry and server guess, patch,
and re-derive missing fields.

### 2. Mixed key convention

Protocol impls use string keys (`"result"`, `"display"`, `"code"`) for result
maps but keyword keys for issue maps (`:severity`, `:text`). Compose output uses
keyword keys (`:code`, `:system`). No consistency, no specs.

### 3. Registry patches results

`enrich-vs-validate-result` (registry.clj, ~85 lines) retroactively:
- Adds not-in-vs issues
- Looks up CodeSystem to check if a code exists
- Fixes FHIRPath expressions for input mode
- Adds status warnings (draft/retired/experimental)
- Merges messages

This exists because protocol impls return incomplete results.

### 4. Server contains terminology logic

The `expand` method in server.clj (~170 lines):
- Computes `used-codesystem` URIs by scanning expansion results
- Looks up CS metadata for status warnings
- Inspects compose definitions via `(get-in vs-meta ["compose" "include"])`
  to determine compose-pinned systems
- Builds version-echo parameters
- Checks `check-system-version` and throws

This is terminology logic that belongs in lower layers or in the expansion
result itself.

### 5. `ctx` is overloaded and smuggled

`ctx` serves three distinct roles conflated into one map:

| Key | Role | Who needs it |
|-----|------|-------------|
| `:codesystems`, `:valuesets`, `:conceptmaps` | Overlay impls (tx-resource) | Registry lookup |
| `:request` | Version params, lenient-display-validation | Registry, compose, impls |
| `:value-set-version` | VS version from POST body | Server only |

The overlay role is clean — `build-tx-ctx` creates in-memory `FhirCodeSystem`
and `FhirValueSet` instances (exactly the same types as file-backed ones) and
stores them in a map that the registry checks before global atoms. This is the
right design.

But `ctx` is also smuggled through the params map. The registry does
`(assoc params :ctx ctx)` before calling protocol methods (registry.clj:343,
513). Then `FhirValueSet` pulls it back out with `(:ctx params)` (fhir_valueset.clj:111,
116) and passes it to the compose engine. `:ctx` is not part of the protocol
contract — it's a hidden dependency.

### 6. Expansion returns a bare seq

`vs-expand` returns a plain sequence of concept maps. The server must
re-derive metadata (used codesystems, compose-pinned versions, total count)
by scanning the results and reaching back through `vs-resource`.

---

## Fix plan

### Step 1: Define return specs in `protocols.clj`

**What:** Add specs for every protocol method's return value. These are the
data contracts between layers.

**Specs to add:**

```clojure
;; Issue — used across validate-code and expansion
(s/def ::severity #{"fatal" "error" "warning" "information"})
(s/def ::issue-type #{"code-invalid" "invalid" "not-found" "not-supported"
                       "business-rule" "exception" "processing"})
(s/def ::details-code string?)
(s/def ::text string?)
(s/def ::expression (s/coll-of string?))
(s/def ::issue
  (s/keys :req-un [::severity ::issue-type ::details-code ::text]
          :opt-un [::expression]))

;; Expansion concept
(s/def ::inactive boolean?)
(s/def ::inactive-status #{"inactive" "retired" "deprecated"})
(s/def ::abstract boolean?)
(s/def ::designations sequential?)
(s/def ::expansion-concept
  (s/keys :req-un [::code ::system]
          :opt-un [::display ::version ::inactive ::inactive-status
                   ::abstract ::designations]))

;; Used codesystem (expansion metadata)
(s/def ::uri string?)
(s/def ::status #{"active" "draft" "retired"})
(s/def ::experimental boolean?)
(s/def ::used-codesystem
  (s/keys :req-un [::uri]
          :opt-un [::status ::experimental]))

;; Compose-pinned system (which systems the compose locked to a version)
(s/def ::compose-pin (s/keys :req-un [::system] :opt-un [::version]))

;; Expansion result — what vs-expand returns
(s/def ::concepts (s/coll-of ::expansion-concept))
(s/def ::total nat-int?)
(s/def ::used-codesystems (s/coll-of ::used-codesystem))
(s/def ::used-valuesets (s/coll-of string?))
(s/def ::compose-pins (s/coll-of ::compose-pin))
(s/def ::expansion-result
  (s/keys :req-un [::concepts]
          :opt-un [::total ::used-codesystems ::used-valuesets
                   ::compose-pins ::issues]))

;; Validate-code result
(s/def ::result boolean?)
(s/def ::normalized-code string?)
(s/def ::message string?)
(s/def ::validate-result
  (s/keys :req-un [::result]
          :opt-un [::code ::system ::version ::display
                   ::inactive ::inactive-status ::normalized-code
                   ::message ::issues]))

;; Lookup result
(s/def ::name string?)
(s/def ::definition string?)
(s/def ::property sequential?)
(s/def ::designation sequential?)
(s/def ::lookup-result
  (s/keys :req-un [::display ::system ::code]
          :opt-un [::name ::version ::definition
                   ::property ::designation]))
```

**Files changed:** `protocols.clj` only.

**Checklist before proceeding:** specs compile, existing tests still pass
(specs are additive — they don't change runtime behaviour until instrumented).

---

### Step 2: Make `ctx` an explicit protocol parameter

**What:** Add `ctx` as the first argument to protocol methods that need it,
instead of smuggling it through the params map.

**Why `ctx` needs to reach protocol impls:** `FhirValueSet.vs-expand` and
`FhirValueSet.vs-validate-code` delegate to the compose engine, which calls
back into the registry (to resolve CodeSystems referenced by the compose).
The compose engine needs `ctx` to see overlays. This is a legitimate
dependency — but it should be explicit.

**The protocol change:**

```clojure
(defprotocol ValueSet
  (vs-resource [this params])
  (vs-expand [this ctx params])           ;; ctx added
  (vs-validate-code [this ctx params]))   ;; ctx added
```

`ctx` becomes a first-class protocol parameter. No more `(assoc params :ctx ctx)`
in the registry, no more `(:ctx params)` in the impl.

`CodeSystem` protocol methods do NOT need `ctx` — they operate on their own
data and don't call back into the registry. If that changes in future (e.g.
supplements), `ctx` can be added then.

**Overlay is just construction.** The overlay mechanism doesn't change. `build-tx-ctx`
still creates in-memory impls and stores them in a map. The registry still checks
the overlay map before global atoms. The only change is how `ctx` reaches the
compose engine: through an explicit parameter instead of a smuggled key.

**Files changed:** `protocols.clj`, `registry.clj`, `fhir_valueset.clj`,
`fhir_codesystem.clj` (its `ValueSet` impl), `snomed.clj` (its `ValueSet` impl).

**Migration:** Mechanical. Add `ctx` parameter to all `vs-expand` and
`vs-validate-code` call sites. Remove `(assoc params :ctx ctx)` from registry.
Remove `(:ctx params)` from impls.

---

### Step 3: Migrate to keyword keys (one operation at a time)

**What:** Change protocol impls to return keyword-keyed maps. Update `fhir.clj`
to accept keyword keys.

**Order:** Start with the simplest operation and expand outward:

1. **`cs-lookup`** — simplest return shape, fewest consumers.
   - Change `snomed.clj/cs-lookup` to return `{:display "..." :system "..." ...}`
   - Change `fhir_codesystem.clj/cs-lookup` to return keyword keys
   - Change `fhir.clj/map->parameters` to accept keyword keys
   - Update tests

2. **`cs-validate-code`** — slightly more complex (issues).
   - Same pattern. Issues already use keyword keys, so the change is just
     the outer result map.

3. **`vs-validate-code`** — most complex (version mismatch, enrichment).
   - This is where the registry patching lives. Step 4 addresses this.

4. **`vs-expand`** — changes return shape (Step 5).

5. **`cs-resource` / `vs-resource`** — metadata returns. These are simpler
   but have many consumers.

**For each operation:**
- Change the impl to return keyword keys
- Update `fhir.clj` conversion to accept keyword keys
- Run tests
- Confirm no string-keyed maps cross the boundary

**Note on the compose engine:** `compose.clj` already uses keyword keys for
its output (`:code`, `:system`, `:display`). It reads compose definitions with
string keys (from FHIR JSON), which is correct — those are external data at
the ingestion boundary. No change needed in compose for its own output.

**Note on `cs-resource` / `vs-resource`:** These currently return metadata
maps with string keys (`"name"`, `"status"`, `"version"`). They are consumed
by the server and by registry functions that check status. After migration
they return keyword keys; the server converts when building HAPI objects.

---

### Step 4: Push enrichment logic into protocol impls

**What:** Eliminate `enrich-vs-validate-result` from the registry by making
protocol impls return complete `::validate-result` maps.

**Current enrichment (what `enrich-vs-validate-result` does):**

| Enrichment | Where it should live |
|-----------|---------------------|
| Add not-in-vs issue | Already in the impl (fhir_valueset.clj:201-213) — duplicated in registry |
| Look up CS to check if code exists | Impl should do this during validation, not after |
| Fix FHIRPath expressions for input mode | Server layer (it knows the input mode) |
| Add inactive warnings | Impl (it knows if the concept is inactive) |
| Add CS/VS status warnings | Expansion result metadata (Step 5) or server |
| Merge messages | Impl (return a complete `:message`) |

**Approach:**

1. **FhirValueSet.vs-validate-code** already does most of the work. It finds
   the code in the expansion, checks display, checks version mismatch. Make it
   also:
   - Check whether the code exists in the CodeSystem (when not found in the VS)
     by calling `registry/codesystem-validate-code` directly — it already has
     `ctx` (after Step 2)
   - Return complete issues with canonical FHIRPath expressions (`Coding.code`)
   - Return a complete `:message`

2. **snomed.clj vs-validate-code** — similar. It already returns a fairly
   complete result. Ensure it returns `:inactive` flag and complete issues.

3. **fhir_codesystem.clj vs-validate-code** — same. Its CodeSystem-as-ValueSet
   validation is already complete.

4. **Delete `enrich-vs-validate-result`** from registry.clj. The registry's
   `valueset-validate-code` becomes:
   ```clojure
   (defn valueset-validate-code
     ([params] (valueset-validate-code nil params))
     ([ctx {:keys [url system code valueSetVersion] :as params}]
      (let [vs-lookup (if url
                        (if valueSetVersion (versioned-uri url valueSetVersion) url)
                        (when system system))]
        (if-let [vs (when vs-lookup (valueset ctx vs-lookup))]
          (protos/vs-validate-code vs ctx params)
          {:result false, :code code, :system system,
           :message (str "A definition for the value set '" (or vs-lookup url system) "' could not be found")
           :issues [{:severity "error", :type "not-found", :details-code "not-found",
                     :text (str "...")}]}))))
   ```

5. **FHIRPath expression adjustment stays in server.clj.** The server knows
   the input mode (`:code`, `:coding`, `:codeableConcept`). It adjusts
   canonical expressions (`Coding.code` → `code` for code mode,
   `Coding.code` → `CodeableConcept.coding[N].code` for CC mode). This is a
   presentation concern. Add a small helper in `fhir.clj`:
   ```clojure
   (defn adjust-issue-expressions [issues input-mode coding-index] ...)
   ```

---

### Step 5: Make `vs-expand` return `::expansion-result`

**What:** Change `vs-expand` to return a map with metadata, not a bare sequence.

**The compose engine change:** `expand-compose` currently returns `(vec paged)`.
Change it to return:

```clojure
{:concepts         (vec paged)
 :total            (count after-filter)  ;; before paging
 :used-codesystems [...]                 ;; collected during expansion
 :used-valuesets   [...]                 ;; collected during expansion
 :compose-pins     [...]}               ;; extracted from compose includes
```

To collect `used-codesystems`, `expand-include` returns a richer result
(concepts + metadata about the CS used), and `expand-compose` aggregates.

**The protocol impl change:** `FhirValueSet.vs-expand` wraps the compose
result. `FhirCodeSystem.vs-expand` (CodeSystem-as-ValueSet) builds the map
directly. `HermesService.vs-expand` builds it from SNOMED search results.

**The registry change:** `valueset-expand` returns the `::expansion-result`
map as-is from the protocol impl. No scanning or re-derivation.

**The server change:** The `expand` method reads `used-codesystems`,
`compose-pins`, and `total` directly from the result map. The 170-line
method shrinks to ~30 lines:

```clojure
;; Pseudocode
(let [result (registry/valueset-expand ctx params)
      vs-impl (registry/valueset ctx url')
      vs-meta (when vs-impl (protos/vs-resource vs-impl {}))
      expansion-params (fhir/build-expansion-params
                         {:used-codesystems (:used-codesystems result)
                          :compose-pins     (:compose-pins result)
                          :request          (:request ctx)
                          :active-only      active-only?
                          :display-lang     display-lang
                          ;; ... other echo params
                          })]
  (fhir/build-valueset-response vs-meta result expansion-params))
```

The HAPI ValueSet construction moves to a helper in `fhir.clj`.

---

### Step 6: Clean up `ctx`

**What:** Separate the distinct roles currently conflated in `ctx`.

After Steps 1–5, `ctx` has a cleaner shape but still mixes overlays with
request parameters. The goal is clarity, not necessarily a different data
structure.

**The clean shape:**

```clojure
(s/def ::ctx
  (s/nilable
    (s/keys :opt-un [;; Overlay impls — checked before global atoms
                     ::codesystems    ;; {url → CodeSystem impl}
                     ::valuesets      ;; {url → ValueSet impl}
                     ::conceptmaps   ;; {url → ConceptMap impl}
                     ;; Request parameters — version control, display
                     ::request])))   ;; ::request spec below

(s/def ::request
  (s/keys :opt-un [::lenient-display-validation  ;; boolean
                   ::system-version              ;; {system-url → version}
                   ::force-system-version         ;; {system-url → version}
                   ::check-system-version]))      ;; {system-url → version-pattern}
```

**Remove `:value-set-version` from `ctx`.** Currently server.clj puts
`:value-set-version` in `ctx` and reads it back later. This should be a
regular parameter passed to `registry/valueset-validate-code` (which already
accepts `:valueSetVersion` in its params map).

**Overlay is just construction.** This doesn't change. `build-tx-ctx` creates
in-memory impls using `make-fhir-code-system` and `make-fhir-value-set` —
the exact same types used for file-backed resources. The overlay map is just
a request-scoped registry that shadows the global atoms. No special overlay
machinery, no special overlay code paths.

---

### Step 7: Move HAPI construction helpers to `fhir.clj`

**What:** Extract the HAPI ValueSet/Parameters construction from server.clj
into reusable `fhir.clj` functions.

**New functions in `fhir.clj`:**

```clojure
(defn build-expansion-params
  "Build HAPI expansion parameter components from an expansion result and
  request parameters."
  [{:keys [used-codesystems compose-pins request ...]}]
  ...)

(defn build-valueset-response
  "Build a HAPI ValueSet from vs-meta, expansion result, and expansion params."
  [vs-meta expansion-result expansion-params opts]
  ...)

(defn adjust-issue-expressions
  "Adjust canonical FHIRPath expressions for input mode.
  Coding.code → code (code mode)
  Coding.code → CodeableConcept.coding[N].code (CC mode)"
  [issues input-mode coding-index]
  ...)
```

After this, each server operation method is ~30 lines.

---

## Execution order and dependencies

```
Step 1: Define return specs
  │
  ├─→ Step 2: Make ctx explicit protocol parameter
  │     │
  │     ├─→ Step 3: Migrate to keyword keys (incremental, per-operation)
  │     │     │
  │     │     ├─→ Step 4: Push enrichment into impls (removes enrich-vs-validate-result)
  │     │     │
  │     │     └─→ Step 5: Rich expansion result (removes server terminology logic)
  │     │
  │     └─→ Step 6: Clean up ctx
  │
  └─→ Step 7: Move HAPI helpers to fhir.clj (can start after Step 3)
```

Steps 1 and 2 are quick, mechanical changes. Step 3 is incremental (one
operation at a time). Steps 4 and 5 are the big wins — they eliminate the
registry patching and server terminology logic. Steps 6 and 7 are cleanup.

## Verification at each step

After each step, run:
- `clj -M:test` — no regressions
- `clj -M:lint/kondo` — clean
- `clj -M:lint/eastwood` — clean
- Conformance test count — must not decrease

Each step should be a separate commit. If a step causes regressions, fix
the step — don't paper over it in the next one.

## What this does NOT change

- **The overlay mechanism.** `build-tx-ctx` still creates in-memory impls
  from tx-resource JSON. The registry still checks overlays before global
  atoms. This design is correct.
- **The protocol definitions.** The same operations exist (`cs-lookup`,
  `vs-expand`, etc.). Only the parameter lists and return shapes change.
- **The compose engine's core logic.** `expand-compose` still evaluates
  include/exclude/filter. It just returns a richer result and receives
  `ctx` explicitly.
- **SNOMED support.** `HermesService` still wraps Hermes. Its protocol
  methods just need to return keyword-keyed maps matching the specs.
