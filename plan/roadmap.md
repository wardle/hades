# Hades Roadmap

A FHIR terminology server built in Clojure, wrapping Hermes (SNOMED CT) and
evolving towards a general-purpose terminology server.

**Current conformance: 354/572 passing (62%)** as of 2026-03-23.

---

## Completed work

| Phase | What | Result |
|-------|------|--------|
| 1 | Conformance infrastructure, `tx-resource` overlays, baseline | Test harness, overlay ctx, 53/572 |
| 2 | Registry redesign, error handling, file-backed CodeSystem, CapabilityStatement | `fhir_codesystem.clj`, overlay mechanism, 404s not 500s |
| 3 | CS & VS `$validate-code`, `$lookup` rewrite | SNOMED + file-backed impls, display matching, designations |
| 4 | Compose engine, named ValueSets, `cs-find-matches`, tx-resource ValueSets | `compose.clj`, `fhir_valueset.clj`, filter/exclude/import |
| Arch | Spec-driven contracts, keyword keys, explicit ctx, layer separation | Deleted `enrich-vs-validate-result`, slim server methods, `internal_conformance_test.clj` |

---

## Conformance breakdown (354/572)

| Suite | Pass/Total | Key gaps |
|-------|-----------|----------|
| version | 168/205 | 37 remaining: mostly version edge cases |
| language | 0/26 | BCP 47 matching, designation selection, Accept-Language |
| snomed | 0/22 | SNOMED-specific: hierarchy, inactive display, post-coordination |
| validation | 29/46 | CC bad-code, imports, language-aware display |
| parameters | 19/35 | Supplements, hierarchy, includeDefinition |
| language2 | 9/25 | Display validation with language awareness |
| permutations | 42/56 | Bad CC multi-coding iteration |
| extensions | 0/11 | Supplement handling, extension echo |
| default-valueset-version | 4/12 | Versioned VS imports |
| deprecated | 4/11 | Withdrawn concepts, `used-valueset` param |
| errors | 1/7 | Broken filters, unknown system combinations |
| exclude | 0/6 | Exclude expansion (should be working — investigate) |
| search | 0/6 | Text filter on expansion |
| big | 0/5 | Large expansion paging, circular reference detection |
| other | 0/3 | Dual-filter expansion |
| tho | 0/2 | Real HL7 vocabulary (ActClass) — needs FHIR content loading |
| batch | 0/2 | Bundle batch validation |
| notSelectable | 48/50 | Near-complete |
| simple-cases | 11/16 | Lookup format, count issues |
| inactive | 10/12 | Validate-code inactive warnings |
| case | 5/6 | One case-sensitivity edge case |
| metadata | 1/2 | CapabilityStatement missing ValueSet resource entry |
| fragment | 3/6 | Fragment CS authority handling |

### High-impact next work (by test count)

1. **Language support** (52 tests: language + language2) — BCP 47 matching, designation selection, `displayLanguage` + `Accept-Language` threading
2. **Supplements & extensions** (11+9=20 tests) — CodeSystem supplement merging during expansion and validation
3. **SNOMED mode** (22 tests) — requires Hermes; hierarchy, post-coordination, inactive display
4. **Permutations bad-CC** (14 tests) — multi-coding CodeableConcept iteration improvements
5. **Exclude/search/big/other** (20 tests) — compose features that should mostly work; investigate failures
6. **Batch** (2 tests) — Bundle request processing

---

## Phase 5 — FHIR Content Loading & Configuration

### 5.1 Load FHIR Built-in Code Systems & ValueSets

The FHIR spec distributes all built-in code systems and value sets as JSON
Bundles (`definitions/valuesets.json`, `definitions/codesystems.json`). Each
Bundle contains `entry[]`, each entry having `fullUrl` and `resource`.

- Read Bundle JSON via `clojure.data.json`, filter entries by `resourceType`
- CodeSystem entries → `make-fhir-code-system` + register
- ValueSet entries → `make-fhir-value-set` + register
- Accept a `--resources` path on startup; scan recursively for `.json` files
- Needed for THO tests (ActClass etc.) and real-world deployment

### 5.2 CORS Support

Jetty's `CrossOriginHandler` wrapping `ServletContextHandler` in `make-server`.
Default disabled, opt-in via `:cors-allowed-origins`.

### 5.3 Declarative Configuration & Custom Formats

Deferred. CLI flags suffice for now. CSV/EDN/CSVW import formats deferred
until the pluggable architecture is proven.

---

## Phase 6 — ConceptMap `$translate`

### 6.1 Registry Changes

Extend conceptmaps registry to support lookup by:
- Canonical URL
- Source + target pair
- Source system alone

### 6.2 File-Backed ConceptMap Provider

New `fhir_conceptmap.clj`:
- Parse ConceptMap JSON → forward + reverse indexes
- `cm-translate`: look up code in forward/reverse index
- Unmapped rules per group for fallback
- Output: `result` boolean, `match[]` with equivalence + concept + source

### 6.3 SNOMED CT Mappings via Hermes

Auto-discover map reference sets on startup. Register as ConceptMaps.
Forward translation via `hermes/component-refset-items`, reverse via
`hermes/reverse-map`. Known refsets: 447562003 (ICD-10), 900000000000497000
(CTV3), 446608001 (ICD-O).

---

## Phase 7 — Resource Read & Search

### 7.1 Resource Retrieval

Implement `cs-resource`, `vs-resource`, `cm-resource` protocol methods to
return full FHIR resource maps. File-backed: return parsed JSON. SNOMED:
construct from Hermes metadata.

### 7.2 FHIR Resource Conversion

New `fhir.clj` functions: `map->codesystem`, `map->valueset`, `map->conceptmap`.

### 7.3 Read & Search Endpoints

Add HAPI `@Read` and `@Search` methods to each provider. Search operates on
registry metadata. HAPI handles Bundle wrapping and pagination.

`@Read` also enables instance-level operations
(`GET /fhir/CodeSystem/sct/$lookup`) automatically.

---

## Phase 8 — Production Readiness

### 8.1 CI/CD Pipeline

GitHub Actions: test, lint, conformance, build uber-jar, publish on tagged release.

Conformance tests build a Hermes database from the **SNOMED subset shipped in
the HL7 tx-ecosystem test data** (~2k concepts in RF2 format). No full
distribution download or API keys needed. Database is built on demand by
`build-snomed-db!` and cached in CI keyed on the RF2 file hashes. See
`plan/internal-conformance.md` § "SNOMED provisioning" for full design.

### 8.2 Health Check Endpoint

`GET /health` outside `/fhir/` — version, registered resources, uptime.

### 8.3 CLI

See `plan/hades-cli.md` for the target CLI design: `serve`, `build`, `status`
subcommands with `--snomed`, `--resources`, `--port` flags.

### 8.4 Out of Scope

- **$closure**: Stateful; conflicts with immutable philosophy
- **FHIR R5**: Defer until demand; R4 is dominant
- **Drop HAPI**: Revisit after Phase 7 makes the cost visible
