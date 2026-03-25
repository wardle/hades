# Internal Conformance Test Plan

Maps each HL7 FHIR Terminology Ecosystem conformance test suite to the
underlying FHIR spec requirement and the Hades component responsible.

**Current: 354/572 passing (62%)** as of 2026-03-23.

## Component model

| Component | Responsibility |
|-----------|---------------|
| **compose.clj** | ValueSet compose: include/exclude, filters, version resolution, paging |
| **registry.clj** | Dispatch, overlay lookup, CC aggregation, version resolution |
| **fhir_codesystem.clj** | File-backed CS + implicit VS: lookup, validate, expand, find-matches |
| **fhir_valueset.clj** | Named ValueSet: expand (via compose), validate-code |
| **snomed.clj** | SNOMED CT via Hermes: hierarchy, descriptions, properties |
| **server.clj / fhir.clj** | HAPI translation, parameter echo, HTTP semantics |
| **protocols.clj** | Data contracts: specs for validate-result, expansion-result, lookup-result |

---

## Suite: metadata (2 tests)

### FHIR requirement
A terminology server must declare its capabilities via `GET /metadata`
(CapabilityStatement) and `GET /metadata?mode=terminology` (TerminologyCapabilities).
The response must list supported resource types (CodeSystem, ValueSet, ConceptMap)
and supported operations ($lookup, $validate-code, $expand).

| Test | What it checks |
|------|---------------|
| `metadata` | CapabilityStatement lists CodeSystem, ValueSet, ConceptMap resources with correct operations |
| `term-caps` | TerminologyCapabilities declares supported code systems and operations |

**Component:** server (HAPI capability declaration)
**Cross-cutting:** Applies equally to all terminology providers. This is pure server wiring.

---

## Suite: simple-cases (16 tests)

### FHIR requirement
Basic $expand and $lookup operations on a simple CodeSystem with a handful of
concepts (code1..code4, some inactive, some with properties). This is the
foundation — if these fail, nothing else can work.

| Test | What it checks |
|------|---------------|
| `simple-expand-all` | Expand VS that includes all codes from a CS |
| `simple-expand-active` | Expand with `activeOnly=true` — inactive codes excluded |
| `simple-expand-inactive` | Expand with `activeOnly=false` — inactive codes included with inactive flag |
| `simple-expand-enum` | Expand VS with enumerated concept list |
| `simple-expand-enum-bad` | Expand VS with enumerated list containing a non-existent code — code omitted |
| `simple-expand-isa` | Expand VS with is-a filter on hierarchy |
| `simple-expand-prop` | Expand VS with property filter |
| `simple-expand-regex` | Expand VS with regex filter |
| `simple-expand-regex2` | Expand VS with different regex pattern |
| `simple-expand-regexp-prop` | Expand VS with regex filter on a property |
| `simple-lookup-1` | $lookup returns display, properties, designations for a code |
| `simple-lookup-2` | $lookup for a different code |
| `simple-expand-all-count` | Expand echoes total count in expansion |

**Component:** compose (expand), registry (lookup dispatch)
**Cross-cutting:** These test the compose engine's core evaluation of include
types (all, enumerated, is-a, property filter, regex filter). The same compose
logic serves overlay data, file-backed CS, and SNOMED. Lookup is a CS protocol
method — each provider implements independently.

---

## Suite: validation (46 tests)

### FHIR requirement
$validate-code checks whether a code is valid in a ValueSet or CodeSystem. The
response must include structured issues with correct FHIRPath expressions, the
resolved display, and the correct result boolean. Different input modes
(code+system, Coding, CodeableConcept) produce different expression paths.

| Pattern | Tests | What it checks |
|---------|-------|---------------|
| `*-code-good` / `*-coding-good` / `*-codeableconcept-good` | 4 | Valid code in each input mode returns `result=true` with display |
| `*-code-bad-code` / `*-coding-bad-code` / `*-codeableconcept-bad-code` | 4 | Invalid code returns `result=false` with not-in-vs issue |
| `*-coding-bad-code-inactive` | 1 | Invalid inactive code reports both not-in-vs and inactive status |
| `*-bad-valueSet` | 3 | Unknown ValueSet URL returns not-found error |
| `*-bad-import` | 3 | VS with import of unknown VS returns not-found error |
| `*-bad-system` / `*-bad-system2` / `*-bad-system-local` / `*-no-system` | 5 | Unknown or missing CodeSystem in various input modes |
| `*-good-display` | 3 | Correct display string accepted |
| `*-bad-display` / `*-bad-display-ws` | 4 | Wrong display returns invalid-display issue (strict mode) |
| `*-bad-display-warning` | 3 | Wrong display returns warning (lenient mode) |
| `*-good-language` | 3 | Display matches in requested language |
| `*-bad-language` / `*-bad-language-header` / `*-bad-language-vs` / `*-bad-language-vslang` | 6 | Display mismatch when language changes preferred display |
| `*-good-regex` / `*-bad-regex` | 2 | Code validated against regex-filtered VS |
| `*-complex-codeableconcept-full` / `*-vsonly` | 2 | Multi-coding CC validation with per-coding issues |
| `*-cs-code-good` / `*-cs-code-bad-code` | 2 | Direct CodeSystem $validate-code (no ValueSet) |

**Component:** validate (core validation logic), compose (finding codes in expanded VS)
**Cross-cutting:**
- *Overlay:* The test suite uses overlay CodeSystems/ValueSets exclusively.
  Internal tests should verify the same logic with both overlay and file-backed data.
- *SNOMED:* Display validation, inactive detection, and unknown-system handling
  apply identically; SNOMED adds language-specific preferred terms.
- *File-backed:* Same validate logic, different CS/VS construction path.

---

## Suite: version (205 tests)

### FHIR requirement
When validating or expanding, the server must resolve CodeSystem versions
correctly. The version can come from multiple sources with defined precedence:

1. `force-system-version` — overrides everything
2. Coding.version or compose include version — explicit in the request/definition
3. `system-version` — client-specified default
4. `check-system-version` — doesn't override, but validates compatibility

When versions mismatch, structured issues must distinguish:
- `VALUESET_VALUE_MISMATCH` (error) — caller's version exists but differs from resolved
- `VALUESET_VALUE_MISMATCH_DEFAULT` (warning) — caller's version doesn't exist, versionless include resolves differently
- `UNKNOWN_CODESYSTEM_VERSION` (error) — caller's version not found at all
- `CHECK_SYSTEM_VERSION` (error) — resolved version doesn't match check-system-version pattern

### Naming convention
```
{input}-{caller-version}-{vs-version}[-{profile}]

input:            code | coding | codeableconcept | vs-expand
caller-version:   v10 (1.0.0) | vbb (2.4.0, bad) | vnn (none)
vs-version:       vs10 (pin 1.0.0) | vs20 (pin 1.2.0) | vs1w (wildcard 1.x.x) |
                  vs1wb (malformed wildcard) | vsbb (pin 2.4.0, bad) | vsnn (no pin)
profile:          default (system-version) | check (check-system-version) |
                  force (force-system-version) | <none> (no override)
```

### Validate-code tests (168 tests)

| Scenario | Count | FHIR requirement |
|----------|-------|-----------------|
| Valid version, matching VS pin | ~20 | Basic version-aware validation: result=true, correct version echoed |
| Valid version, different VS pin | ~20 | Version mismatch: result=false, VALUESET_VALUE_MISMATCH error |
| Valid version, no VS pin | ~15 | Versionless include resolves to latest; mismatch if caller specifies different |
| Bad version (doesn't exist) | ~25 | UNKNOWN_CODESYSTEM_VERSION error + VALUESET_VALUE_MISMATCH_DEFAULT warning |
| No version, various VS pins | ~15 | No mismatch possible; resolved version from VS pin |
| Wildcard VS pin (1.x.x) | ~15 | Wildcard resolution: 1.0.0 matches 1.x.x |
| Malformed wildcard (1) | ~15 | Bad wildcard: server resolves what it can, reports issues |
| + system-version profile | ~40 | system-version provides default; doesn't override compose pin |
| + check-system-version profile | ~40 | check-system-version validates resolved version against pattern |
| + force-system-version profile | ~40 | force-system-version overrides everything including compose pin |

### Expand tests (37 tests)

| Scenario | Count | FHIR requirement |
|----------|-------|-----------------|
| Expand with various VS version pins | 9 | Expansion uses correct CS version; concepts from that version only |
| + force profile | 9 | force-system-version overrides compose pin |
| + default profile | 9 | system-version provides default; compose pin takes precedence |
| + check profile | 9 | check-system-version validates; throws if incompatible |
| Mixed-version VS | 1 | VS includes from multiple CS versions |

**Component:** version (resolution logic — pure functions), compose (threading
resolved version through expansion), validate (version mismatch issue generation)

**Cross-cutting:**
- *Overlay:* Tests use overlay CS with explicit versions. The version resolution
  logic is independent of storage — it operates on registered CS metadata.
- *SNOMED:* SNOMED has a single version derived from the installed release
  (module + effectiveDate). Version resolution still applies (e.g. caller specifies
  a SNOMED version that doesn't match the installed one).
- *File-backed:* Multiple versions of the same CS can be loaded from different
  files. Version resolution works identically.

### Simple version tests (10 tests)

| Test | What it checks |
|------|---------------|
| `version-simple-code-bad-version1` | Code input with non-existent CS version → UNKNOWN_CODESYSTEM_VERSION |
| `version-simple-coding-bad-version1` | Coding input with non-existent CS version |
| `version-simple-codeableconcept-bad-version1` | CC input with non-existent CS version |
| `version-simple-codeableconcept-bad-version2` | CC with two codings, one bad version |
| `version-simple-code-good-version` | Code with matching version → result=true |
| `version-simple-coding-good-version` | Coding with matching version → result=true |
| `version-simple-codeableconcept-good-version` | CC with matching version → result=true |
| `version-version-profile-none` | Version validation with no profile |
| `version-version-profile-default` | Version validation with system-version default |
| `validation-version-profile-coding` | Coding validation with system-version default |

---

## Suite: default-valueset-version (12 tests)

### FHIR requirement
The `default-valueset-version` parameter specifies which version of a ValueSet
to use when the request doesn't include one. The server resolves the VS version
first, then expands/validates using that VS definition.

| Test | What it checks |
|------|---------------|
| `direct-expand-one` / `direct-expand-two` | Expand VS version 1 vs version 2 directly |
| `indirect-expand-one` / `indirect-expand-two` | Expand VS that imports a versioned VS |
| `indirect-expand-zero` | Expand with no version specified → uses latest |
| `indirect-expand-zero-pinned` | VS import pins a specific version of another VS |
| `indirect-expand-zero-pinned-wrong` | VS import pins a non-existent version → error |
| `indirect-validation-*` | Same patterns for validate-code |

**Component:** registry (ValueSet version resolution), compose (versioned VS imports)
**Cross-cutting:** Applies to all ValueSets regardless of backing store. The
version resolution is at the registry level.

---

## Suite: permutations (56 tests)

### FHIR requirement
Validate-code must work correctly across all combinations of:
- Input type: coding, CodeableConcept (single coding, dual coding)
- ValueSet composition: all, enumerated, is-a, import, exclude-list, exclude-filter, exclude-import
- Result: good (code found) vs bad (code not found)

These are combinatorial coverage tests — they exercise the compose engine's
include/exclude logic through the validation path.

| Pattern | Count | What it checks |
|---------|-------|---------------|
| `good-coding-*` | 7 | Valid coding against 7 VS composition types |
| `bad-coding-*` | 7 | Invalid coding against 7 VS composition types |
| `good-cc1-*` | 7 | Valid CC (single coding) against 7 types |
| `bad-cc1-*` | 7 | Invalid CC (single coding) against 7 types |
| `good-cc2-*` | 7 | Valid CC (two codings, first matches) against 7 types |
| `bad-cc2-*` | 7 | Invalid CC (two codings, neither matches) against 7 types |
| `good-scd-*` | 7 | Valid second-coding-only CC against 7 types |
| `bad-scd-*` | 7 | Invalid second-coding-only CC against 7 types |

**Component:** compose (all composition types), validate (CC iteration logic)
**Cross-cutting:** Composition logic is identical across overlay, file-backed,
and SNOMED. The CC iteration (checking each coding, aggregating per-coding
issues) is in the validate component.

---

## Suite: language (26 tests)

### FHIR requirement
$expand must respect `displayLanguage` to select the best matching designation.
Language can be specified via:
- `displayLanguage` parameter
- `Accept-Language` HTTP header
- ValueSet language field
- VS compose include language filter

When the requested language has a matching designation, use it. When not, fall
back to the default display and optionally emit an informational issue.

| Pattern | Count | What it checks |
|---------|-------|---------------|
| `language-echo-{lang}-none` | 4 | Baseline: expand with no language specified, default displays shown |
| `language-echo-{lang}-{lang}-{source}` | 12 | Expand with language from param/VS/header: correct designation selected |
| `language-xform-*-soft/hard/default` | 6 | Language negotiation: requested language differs from CS default, soft/hard fallback |
| `language-echo-en-designation` | 1 | Single designation echoed |
| `language-echo-en-designations` | 1 | Multiple designations echoed with includeDesignations |

**Component:** language (BCP 47 matching, designation selection — pure functions)
**Cross-cutting:**
- *Overlay/file-backed:* Designations stored in concept definitions with language tags
- *SNOMED:* Hermes has its own locale-aware description selection; the language
  component provides the interface contract, Hermes provides the implementation

---

## Suite: language2 / display (25 tests)

### FHIR requirement
Display validation must account for language. When `displayLanguage` is
specified, the server checks the display against the designation in that
language, not the default. A display that matches the English designation
may fail validation when German is requested.

| Pattern | Count | What it checks |
|---------|-------|---------------|
| `validation-right-{disp-lang}-{req-lang}` | 12 | Correct display for various language combinations |
| `validation-wrong-{disp-lang}-{req-lang}` | 13 | Wrong display for various language combinations, including `-bad` (lenient mode) |

Naming: `validation-{right|wrong}-{display-language}-{requested-language}`
- `right` = display matches the designation in the requested language
- `wrong` = display doesn't match
- Language segments: `de`, `en`, `none`, `ende` (both), `ende-N` (variant)

**Component:** language (display matching with language awareness), validate
(threading displayLanguage through validation)
**Cross-cutting:** Same as language suite. Display validation logic is shared
across all terminology providers.

---

## Suite: parameters (35 tests)

### FHIR requirement
$expand must correctly handle and echo expansion parameters: `activeOnly`,
`excludeNested`, `includeDesignations`, `includeDefinition`, `property`,
`offset`, `count`. Supplement CodeSystems modify the base CS with additional
properties/designations.

| Pattern | Count | What it checks |
|---------|-------|---------------|
| `*-hierarchy` | 3 | excludeNested affects hierarchical expansion |
| `*-active` / `*-inactive` | 10 | activeOnly filtering across composition types |
| `*-designations` | 3 | includeDesignations returns designation arrays |
| `*-definitions` / `*-definitions2` / `*-definitions3` | 7 | includeDefinition returns concept definitions |
| `*-property` | 3 | property parameter filters returned properties |
| `*-supplement-*` | 9 | Supplement CS adds/overrides properties; bad supplement → error |

**Component:** compose (parameter threading), properties (supplement merging)
**Cross-cutting:**
- *Supplement handling* is critical for all providers. A supplement CodeSystem
  adds properties/designations to an existing CS. The compose engine must merge
  supplement data during expansion. Bad supplements must be detected and reported.
- *Hierarchy* is relevant for SNOMED (deep hierarchies) and file-backed CS
  (defined hierarchies). Overlay CS typically have flat structures.

---

## Suite: notSelectable (50 tests)

### FHIR requirement
Concepts marked `notSelectable` (via the `http://hl7.org/fhir/concept-properties#notSelectable`
property) are for navigation only — they appear in expansions but cannot be
used for coding. $validate-code must reject notSelectable concepts. The property
may be represented in different ways: as a boolean property, as a concept
property, or inferred.

| Pattern | Count | What it checks |
|---------|-------|---------------|
| `notSelectable-{prop-type}-all` | 4 | Expand all: notSelectable concepts included with flag |
| `notSelectable-{prop-type}-true` | 5 | Expand with notSelectable filter = true |
| `notSelectable-{prop-type}-false` | 4 | Expand with notSelectable filter = false |
| `notSelectable-{prop-type}-in` / `*-out` | 2 | Property filter includes/excludes notSelectable |
| `notSelectable-{prop-type}-{sel}-{code}` | 32 | Validate-code for selectable/not-selectable concepts |
| `*-param-true` / `*-param-false` | 4 | activeOnly parameter interaction with notSelectable |

Property type variants: `prop` (standard property), `noprop` (no explicit property),
`reprop` (redefined property), `unprop` (unnamed property)

**Component:** properties (notSelectable detection across property representations)
**Cross-cutting:** notSelectable is a concept property defined in the CS
definition. Detection logic must handle all property representation variants.
Applies to file-backed CS primarily; SNOMED doesn't use notSelectable.

---

## Suite: inactive (12 tests)

### FHIR requirement
Inactive concepts must be filterable via `activeOnly` in expansion and
flagged in validate-code responses. The `inactive` property indicates a
concept is no longer current.

| Test | What it checks |
|------|---------------|
| `inactive-expand` | Default expand includes inactive with flag |
| `inactive-inactive-expand` | activeOnly=false includes inactive |
| `inactive-active-expand` | activeOnly=true excludes inactive |
| `inactive-{n}-validate` | Validate active/inactive codes with various activeOnly settings |
| `inactive-{n}a-validate` | Same with activeOnly=true |
| `inactive-{n}b-validate` | Same with activeOnly=false |

**Component:** properties (inactive detection), compose (activeOnly filtering)
**Cross-cutting:** Inactive detection must work for:
- File-backed: `inactive` boolean property, `status` property = retired/inactive
- SNOMED: concept active flag from Hermes
- Overlay: same as file-backed

---

## Suite: deprecated (11 tests)

### FHIR requirement
Concepts and CodeSystems have lifecycle status (active, draft, retired,
experimental). Expansion and validation must handle these correctly:
- Deprecated/retired codes: included in expansion with status warning
- Experimental CS: informational warning
- Draft CS: informational warning

| Test | What it checks |
|------|---------------|
| `withdrawn` / `not-withdrawn` | Expand CS with withdrawn concepts |
| `withdrawn-validate` / `not-withdrawn-validate` | Validate against withdrawn CS |
| `experimental` / `experimental-validate` | Experimental CS status warning |
| `draft` / `draft-validate` | Draft CS status warning |
| `vs-deprecation` | VS with deprecation status |
| `deprecating-validate` / `deprecating-validate-2` | Validate code in deprecated CS |

**Component:** properties (status detection, deprecation), validate (status warnings)
**Cross-cutting:** Status properties exist in all CS types. The warning
generation should be in the validate/expand components, not in server.clj.

---

## Suite: case (6 tests)

### FHIR requirement
CodeSystems declare case sensitivity via `caseSensitive`. When false, code
matching is case-insensitive but the server should emit an informational issue
encouraging correct case usage.

| Test | What it checks |
|------|---------------|
| `case-insensitive-code1-{1,2,3}` | Case-insensitive CS: wrong-case code matches with informational issue |
| `case-sensitive-code1-{1,2,3}` | Case-sensitive CS: wrong-case code does not match |

**Component:** validate (case-sensitive matching logic)
**Cross-cutting:** Case sensitivity is a CS property. File-backed CS defines it
in the resource JSON. SNOMED is case-insensitive for most descriptions but
case-sensitive for concept IDs. The validate component checks the CS property.

---

## Suite: exclude (6 tests)

### FHIR requirement
ValueSet compose supports `exclude` groups that remove concepts from the
included set. Exclude can reference enumerated lists, filters, or imports.

| Test | What it checks |
|------|---------------|
| `exclude-1` | Exclude specific codes from all-inclusive VS |
| `exclude-2` | Exclude by filter |
| `exclude-zero` | Exclude removes all codes → empty expansion |
| `exclude-all` | Exclude everything → empty expansion |
| `exclude-combo` | Exclude with multiple groups |
| `include-combo` | Include with multiple groups (complement test) |

**Component:** compose (exclude evaluation)
**Cross-cutting:** Exclude logic is pure compose engine. Same code path for
all backing stores.

---

## Suite: fragment (6 tests)

### FHIR requirement
A CodeSystem with `content: fragment` contains only a subset of its codes.
Validation against a fragment CS should accept known codes, reject known-bad
codes, but not claim authority over codes it doesn't have.

| Test | What it checks |
|------|---------------|
| `validation-fragment-code-good` / `*-coding-good` / `*-codeableconcept-good` | Known code validates successfully |
| `validation-fragment-code-bad-code` / `*-coding-*` / `*-codeableconcept-*` | Unknown code: fragment CS cannot authoritatively reject |

**Component:** validate (fragment-aware validation)
**Cross-cutting:** Fragment status is a CS property. Relevant for file-backed CS
and overlay CS. SNOMED is always `content: complete`.

---

## Suite: search (6 tests)

### FHIR requirement
$expand supports a `filter` text search parameter that narrows results to
concepts matching the search string in their display or designations.

| Test | What it checks |
|------|---------------|
| `search-all-yes` / `search-all-no` | Text filter on all-inclusive VS: matching/non-matching |
| `search-filter-yes` / `search-filter-no` | Text filter on property-filtered VS |
| `search-enum-yes` / `search-enum-no` | Text filter on enumerated VS |

**Component:** compose (text search filtering)
**Cross-cutting:** Text search implementation differs by provider:
- File-backed: string matching on display/designations
- SNOMED: Hermes search (optimised full-text)
- Overlay: same as file-backed

---

## Suite: errors (7 tests)

### FHIR requirement
Malformed requests must return structured OperationOutcome errors, not 200
with wrong data or 500 with stack traces.

| Test | What it checks |
|------|---------------|
| `unknown-system1` / `unknown-system2` | Validate code in unknown CS → not-found error |
| `broken-filter-validate` / `broken-filter2-validate` | Malformed filter in VS → error |
| `broken-filter-expand` | Malformed filter in expansion → error |
| `combination-ok` | Valid multi-system validation |
| `combination-bad` | Invalid multi-system validation → per-system errors |

**Component:** compose (filter validation), validate (unknown system detection),
server (error → OperationOutcome mapping)
**Cross-cutting:** Error handling is a cross-cutting concern. Each layer detects
its own errors (compose: bad filters; registry: unknown systems; server: maps to HTTP).

---

## Suite: extensions (11 tests)

### FHIR requirement
Expansion must correctly handle FHIR extensions on concepts, designations, and
properties. Supplement CodeSystems with correct/incorrect URLs must be
handled. Inactive concept display handling in CS $validate-code.

| Test | What it checks |
|------|---------------|
| `extensions-echo-all` | Expand echoes all extensions on concepts |
| `extensions-echo-enumerated` | Expand echoes extensions on enumerated concepts |
| `extensions-echo-bad-supplement` | Bad supplement URL → OperationOutcome error |
| `validate-code-bad-supplement` / `*-coding-*` / `*-codeableconcept-*` | Validate with bad supplement → error |
| `validate-coding-bad-supplement-url` | CS validate-code with bad supplement URL |
| `validate-coding-good-supplement` / `*-good2-*` | Valid supplement: supplemented properties available |
| `validate-code-inactive-display` | CS validate-code for inactive concept returns correct display |
| `validate-code-inactive` | CS validate-code for inactive concept returns inactive flag |

**Component:** properties (supplement merging, extension handling), validate
(supplement validation), compose (supplement-aware expansion)
**Cross-cutting:** Supplements are a FHIR mechanism for adding properties to an
existing CS without modifying it. Critical for file-backed and overlay CS.
SNOMED doesn't typically use supplements (Hermes has its own extension mechanism).

---

## Suite: big (5 tests)

### FHIR requirement
Servers must handle large expansions gracefully: respect count/offset for
paging, detect circular ValueSet references, and not crash on large result sets.

| Test | What it checks |
|------|---------------|
| `big-echo-no-limit` | Large expansion with no limit |
| `big-echo-zero-fifty-limit` | Paging: offset=0, count=50 |
| `big-echo-fifty-fifty-limit` | Paging: offset=50, count=50 |
| `big-circle-bang` | Circular VS reference → error (not infinite loop) |
| `big-circle-validate` | Validate-code against circular VS → error |

**Component:** compose (paging, circular reference detection)
**Cross-cutting:** Paging is compose engine logic. Circular detection uses the
`expanding` set passed through compose calls. Both are independent of backing store.

---

## Suite: batch (2 tests)

### FHIR requirement
Servers should support batch validation — a Bundle of multiple validate-code
requests processed in a single HTTP request.

| Test | What it checks |
|------|---------------|
| `batch-validate` | Batch of valid validate-code requests → individual responses |
| `batch-validate-bad` | Batch with some invalid requests → per-entry errors |

**Component:** server (batch request handling)
**Cross-cutting:** Pure server wiring. The validation logic is the same as
single requests; the server just iterates the Bundle entries.

---

## Suite: tho (2 tests)

### FHIR requirement
Correct expansion of real-world vocabulary (HL7 ActClass) with activeOnly filtering.

| Test | What it checks |
|------|---------------|
| `act-class` | Expand ActClass vocabulary |
| `act-class-activeonly` | Expand ActClass with activeOnly=true |

**Component:** compose, properties (activeOnly filtering on real vocabulary)
**Cross-cutting:** Tests with a real HL7 vocabulary. Requires the ActClass CS
to be loaded. Same compose/properties logic as other CS types.

---

## Suite: other (3 tests)

### FHIR requirement
Edge cases and user-reported scenarios: dual-filter ValueSets and multi-criteria
filtering.

| Test | What it checks |
|------|---------------|
| `dual-filter` | Expand VS with two filters on different properties |
| `validation-dual-filter-in` | Validate code that passes both filters |
| `validation-dual-filter-out` | Validate code that fails one filter |

**Component:** compose (multi-filter evaluation)
**Cross-cutting:** Multi-filter is compose engine logic, independent of backing store.

---

## Suite: translate (1 test)

### FHIR requirement
$translate maps a code from one CodeSystem to another using a ConceptMap.

| Test | What it checks |
|------|---------------|
| `translate-1` | Basic concept translation |

**Component:** translate (ConceptMap evaluation)
**Cross-cutting:** ConceptMap support is a separate protocol. Relevant for
file-backed ConceptMaps and future SNOMED map support.

---

## Suite: snomed (22 tests)

### FHIR requirement
SNOMED CT-specific operations: hierarchy traversal, inactive concept handling,
post-coordination, and SNOMED-specific properties.

| Test | What it checks |
|------|---------------|
| `snomed-inactive-display` | CS validate-code for inactive SNOMED concept returns correct display |
| `snomed-procedure-in-display` / `*-out-display` | Validate SNOMED procedure code with display matching |
| `snomed-expand-inactive` | Expand SNOMED VS including inactive concepts |
| `snomed-expand-diabetes` | Expand descendants of a SNOMED concept (diabetes) |
| `snomed-expand-procedures` | Expand SNOMED procedures hierarchy |
| `lookup` / `lookup-pc` | $lookup for SNOMED concept and post-coordinated expression |
| `validate-code-pc-good` / `*-bad1` / `*-bad2` | CS validate-code for post-coordinated expressions |
| `validate-code-pc-none` / `*-list` / `*-list-no-pc` / `*-filter` | VS validate-code with post-coordination |
| `expand-pc-none` / `*-list` / `*-filter` | Expand VS with post-coordinated concepts |
| `validate-code-implied-*` | Validate with implied ValueSet (URL-based hierarchy) |

**Component:** snomed (Hermes integration)
**Cross-cutting:** These are SNOMED-specific by nature. Post-coordination is
unique to SNOMED. Hierarchy traversal uses Hermes subsumption. The protocol
interface is the same but the implementation is entirely Hermes-specific.

---

## Suite: permutations (already covered above — 56 tests)

---

## Internal test strategy

### Layer 1: Pure function tests (no I/O, no state)

| Component | What to test | Example |
|-----------|-------------|---------|
| version | `resolve-version` given compose-pin, caller-version, system-version, force, check | `(resolve-version {:compose-pin "1.0" :caller "2.0" :force nil}) => {:resolved "1.0" :issues [...]}` |
| version | `version-matches?` for wildcard patterns | `(version-matches? "1.x.x" "1.2.3") => true` |
| version | Issue generation for each mismatch type | One test per message-id: VALUESET_VALUE_MISMATCH, _DEFAULT, UNKNOWN_CODESYSTEM_VERSION, CHECK_SYSTEM_VERSION |
| language | BCP 47 tag matching | `(best-match "de" ["de-CH" "en" "de"]) => "de"` |
| language | Designation selection with fallback | `(select-display concepts "fr") => {:display "..." :lang "fr" :fallback? false}` |
| properties | Inactive detection across representations | `(concept-inactive? concept cs-properties) => true` |
| properties | notSelectable detection across representations | All 4 property variants from the test suite |
| properties | Status classification | `(concept-status concept) => :retired` |

### Layer 2: Compose engine tests (in-memory CS impls, no HTTP)

| What to test | Example |
|-------------|---------|
| Include all | Expand VS with `include: [{system: X}]` → all concepts from X |
| Include enumerated | Expand VS with explicit concept list → those concepts only |
| Include is-a | Expand VS with is-a filter → descendants |
| Include property filter | Expand VS with property filter → matching concepts |
| Include regex filter | Expand VS with regex → matching concepts |
| Exclude | All exclude types: list, filter, import |
| Multi-include | VS with multiple include groups → union |
| Import | VS that imports another VS |
| Paging | offset/count applied correctly; total reflects pre-paged count |
| Circular detection | VS A imports VS B imports VS A → error |
| Version-aware include | Include with version pin → concepts from that CS version only |
| activeOnly | Inactive concepts filtered when activeOnly=true |
| Text search | filter parameter narrows by display text |
| Supplement merging | Supplement CS modifies properties/designations |

### Layer 3: Validate engine tests (in-memory impls, no HTTP)

| What to test | Example |
|-------------|---------|
| Code found | Code in VS → result=true, display, version |
| Code not found | Code not in VS → result=false, not-in-vs issue |
| Case sensitivity | Case-insensitive CS: wrong case → result=true + informational issue |
| Display match/mismatch | Strict and lenient modes |
| Language-aware display | Display validated against requested language designation |
| Version mismatch | All 4 mismatch types from the version suite |
| Fragment CS | Fragment CS cannot authoritatively reject unknown codes |
| Inactive validation | Inactive code → result=true/false depending on activeOnly |
| notSelectable | notSelectable concept → rejected in validation |
| Multi-coding CC | Each coding checked, per-coding issues with correct FHIRPath |
| Unknown system | System not registered → not-found error |
| Bad supplement | Supplement URL doesn't match → error |

### Layer 4: Integration tests (HTTP, full stack)

Reserve full-stack tests for:
- HAPI parameter binding (does the server parse input correctly?)
- Response serialization (does fhir.clj produce valid FHIR JSON?)
- Batch handling
- Metadata/capability declarations
- Parameter echo in expansion responses
- Error → HTTP status code mapping

These are thin and mechanical. If layers 1-3 pass, layer 4 failures indicate
HAPI wiring issues, not terminology logic bugs.

---

## SNOMED provisioning for conformance tests

### Key insight: the test data ships its own SNOMED subset

The HL7 `fhir-tx-ecosystem-ig` repo includes a **pre-extracted SNOMED CT
subset** in RF2 format at `tx-source/snomed/`. This is a ~2,176 concept subset
of the International Edition (module `900000000000207008`), containing exactly
the concepts needed by the conformance tests. No full distribution download
is required.

**Subset contents** (snapshot date `20250909`):

```
tx-source/snomed/
├── Terminology/
│   ├── sct2_Concept_Snapshot_INT_20250909.txt
│   ├── sct2_Description_Snapshot-en_INT_20250909.txt
│   ├── sct2_Relationship_Snapshot_INT_20250909.txt
│   ├── sct2_RelationshipConcreteValues_Snapshot_INT_20250909.txt
│   ├── sct2_sRefset_OWLExpressionSnapshot_INT_20250909.txt
│   └── sct2_TextDefinition_Snapshot-en_INT_20250909.txt
├── Refset/
│   ├── der2_cRefset_AssociationSnapshot_INT_20250909.txt
│   ├── Language/der2_cRefset_LanguageSnapshot-en_INT_20250909.txt
│   └── Metadata/der2_ssRefset_ModuleDependencySnapshot-en_INT_20250909.txt
└── Readme.txt
```

The subset is generated from a full international distribution using the
`snomed-owl-toolkit` and `snomed-subontology-extraction` tools, driven by a
concept list in `tx-source/snomed-subset.txt`. When the HL7 test suite updates,
they regenerate the subset and commit it — the RF2 files change in-repo.

### Problem

Currently, conformance tests require a manually-built Hermes database at a
developer-specific path. This makes tests non-reproducible and not runnable
in CI without manual setup.

### Solution: build from the shipped subset

Since the RF2 files are already in the test data repo, we can build the Hermes
database automatically using Hermes' import/index API — no authentication,
no downloads beyond the git clone we already do.

| Function | Purpose |
|----------|---------|
| `hermes/import-snomed` | Import RF2 files into an LMDB database |
| `hermes/index` | Build search indices |
| `hermes/compact` | Compact the database |

### Design

#### 1. Build on demand from test data

An `build-snomed-db!` function checks whether the database exists and is current.
If not, it builds from the RF2 files already cloned by `ensure-test-data!`:

```clojure
(def ^:private snomed-db-path ".hades/snomed-conformance.db")
(def ^:private snomed-rf2-dir ".hades/tx-ecosystem/tx-source/snomed")

(defn- build-snomed-db!
  "Build the SNOMED database from the conformance test subset if needed."
  []
  (ensure-test-data!)  ;; clone tx-ecosystem repo if missing
  (when-not (.exists (io/file snomed-db-path))
    (log/info "Building SNOMED conformance database from test subset")
    (hermes/import-snomed snomed-db-path [snomed-rf2-dir])
    (hermes/index snomed-db-path)
    (hermes/compact snomed-db-path)
    (log/info "SNOMED conformance database ready" {:path snomed-db-path})))
```

The build takes seconds for ~2k concepts (vs. hours for a full distribution).

#### 2. Staleness detection

The database should be rebuilt when the RF2 files change (i.e. when the
tx-ecosystem repo updates). Options:

- **Simple**: delete `.hades/snomed-conformance.db` whenever `ensure-test-data!`
  pulls new commits. A git-rev marker file (`.hades/snomed-conformance.rev`)
  stores the commit hash used to build the current database.
- **Manual**: `(ct/rebuild-snomed!)` force-rebuilds.

#### 3. Updated `start!` signature

```clojure
;; Zero-arg: build from subset automatically
(ct/start!)

;; Explicit path: use a pre-existing database (e.g. full edition for manual testing)
(ct/start! "/path/to/full/snomed.db")
```

When no path is given, `start!` calls `build-snomed-db!` and opens
`.hades/snomed-conformance.db`. The explicit path bypasses the auto-build.

#### 4. CI/CD integration

No authentication or secrets required — the RF2 subset is public in the
tx-ecosystem repo.

```yaml
conformance:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: DeLaGuardo/setup-clojure@13.4
      with:
        cli: latest
    - name: Cache SNOMED conformance database
      uses: actions/cache@v4
      with:
        path: .hades/snomed-conformance.db
        key: snomed-${{ hashFiles('.hades/tx-ecosystem/tx-source/snomed/**') }}
    - name: Run conformance tests
      run: clj -X:conformance
```

Key points:
- **No secrets needed** — the subset is open-source test data.
- **Cache the built database** keyed on the RF2 file hashes — rebuild only
  when the HL7 repo updates the SNOMED subset.
- **Zero-config** — `clj -X:conformance` with no `:snomed` argument.

#### 5. Version tracking

When the HL7 conformance tests update to a new SNOMED version:
1. `ensure-test-data!` pulls the new RF2 files from the tx-ecosystem repo.
2. The staleness check detects the changed commit hash.
3. `build-snomed-db!` rebuilds the database automatically.
4. CI cache key changes (RF2 hash changed) → CI rebuilds too.

No manual intervention. No version pins to maintain.

### Migration

1. Add `com.eldrix/hermes` as an extra-dep in the `:conformance` alias (it's
   currently only a main dep — needed at test time for import/index).
2. Add `build-snomed-db!` with import/index/compact from `snomed-rf2-dir`.
3. Update `start!` to accept zero args, defaulting to the auto-built database.
4. Add staleness detection (git-rev marker file).
5. Update `.gitignore` to exclude `.hades/snomed-conformance.db`.
6. Add CI workflow (Phase 8.1 in roadmap).
7. Update CLAUDE.md quick-reference with the new zero-arg `(ct/start!)` form.

---

*Architecture refactoring (specs, keyword keys, explicit ctx, layer separation)
is complete. See commit f933993 for details.*
