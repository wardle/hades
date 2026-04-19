# Hades Roadmap

A FHIR terminology server built in Clojure, wrapping Hermes (SNOMED CT) and
evolving towards a general-purpose terminology server.

**Current conformance: 466/600 passing (77.7%)** as of 2026-04-18.
See `plan/conformance-next-steps.md` for the current breakdown and the
active P2-follow-up / P3 / P4 / P5 work queue.

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

## Conformance breakdown (466/600 — 2026-04-18)

| Suite | Pass/Total | Key gaps |
|-------|-----------|----------|
| permutations | 56/56 | ✅ complete |
| fragment | 6/6 | ✅ complete |
| big | 5/5 | ✅ complete |
| simple-cases | 13/13 | ✅ complete |
| notSelectable | 48/50 | near-complete |
| version | 182/205 | P3 (4xx for mixed-version check), P4 (overload), `-default`/`-check` harness variants |
| language | 16/26 | designation xform (6), designation echo (2), vslang (2) |
| language2 | 20/25 | display validation with VS-level displayLanguage extension |
| validation | 32/46 | CC bad-code, imports, language-aware display |
| parameters | 23/35 | `used-supplement` echo, `expansion.property[]` metadata, synthetic concept-properties |
| inactive | 10/12 | CC aggregation with `this-code-not-in-vs` + `not-in-vs` ordering |
| snomed | 12/22 | SNOMED-specific: hierarchy, inactive display, post-coordination |
| case | 5/6 | one case-sensitivity edge case |
| deprecated | 5/11 | `used-valueset` echo for imports, `vs-deprecation` extension |
| errors | 5/7 | unknown system combinations (P3) |
| extensions | 4/11 | `used-supplement` echo, supplement display in validate-code |
| default-valueset-version | 4/12 | versioned VS imports |
| overload | 13/29 | P4 continuation: expand `contains[]` sort order by semver desc; merged/exclude excludes; caller-version not-found issue pair |
| exclude | 3/8 | exclude expansion interactions |
| search | 3/6 | text filter on expansion |
| metadata | 1/2 | CapabilityStatement missing ValueSet resource entry |
| batch | 0/2 | Bundle batch validation |
| other | 0/3 | dual-filter expansion |
| tho | 0/2 | real HL7 vocabulary (ActClass) — needs FHIR content loading |

### High-impact next work (by test count)

See `plan/conformance-next-steps.md` for the full priority list. Headlines:

1. **P4 — Overload multi-version continuation** (~16 tests) — session 5
   closed the core engine (version-aware dedup, multi-version tracking,
   version-aware validate-code match). Remaining: sort expansion
   contains[] by version desc; multi-version not-in-vs issue pair
   (invalid-code in caller-version); merged/exclude/exclude-merged
   expansion semantics.
2. **Supplement echo / display** (~7 tests) — `used-supplement`
   expansion param when supplements are merged, supplement designation
   in validate-code output.
3. **`expansion.property[]` metadata + synthetic concept-properties**
   (~7 tests in parameters) — expansion needs to declare which
   properties appear in `contains[].property`, and the `definition`/
   `designations`/`status` synthetic properties need to be sourced from
   the concept's top-level fields.
4. **`default-valueset-version` import** (~12 tests) — operation
   parameter for resolving unversioned import canonicals.
4. **Language xform** (~6 tests) — choose the right designation when
   multiple languages are available.
5. **`used-valueset` echo for VS-imports** (~4 tests in deprecated + default-valueset-version).
6. **SNOMED mode** (~10 tests) — hierarchy/post-coordination corners.

---

## External preflight validation (2026-04-19)

An independent team produced a 20-case `hurl` preflight suite
(`plan/preflight.hurl`, with their original results in `plan/preflight.json`)
covering FHIR
terminology operations across SNOMED, LOINC, DICOM DCM, RxNorm, VSAC
OIDs, and HL7 ConceptMaps. Their run (server header
`X-Powered-By: HAPI FHIR 8.0.0`) pre-dates the HAPI removal; replaying
the same requests against current `main` gives a concrete external
signal distinct from the HL7 tx-ecosystem conformance suite.

### Fixed since the tested build

| Old behaviour | Current behaviour |
|---------------|-------------------|
| LK02–LK04: `CodeSystem/$lookup` for non-SNOMED system → **500 `ClassCastException: String → IFn`** | 404 `OperationOutcome not-found` (clean) |
| LK05: `$lookup` for unknown SNOMED code → **HTTP 200** with empty-display `Parameters` body | 404 `OperationOutcome not-found` |
| VC01–VC03: `ValueSet/$validate-code` → 400 "operation not supported" | 200 `Parameters result=true` with display |
| SS01: `CodeSystem/$subsumes` → not reachable | 200 `outcome=subsumes` |
| EX03: `$expand` with `filter=diabetes` → not reached | 200, total=2199, 100 returned |

### Remaining gaps surfaced by preflight

1. **POST `$expand` with inline `valueSet` parameter** — cases EX02, EX05,
   EX07, EX08 all POST a FHIR `Parameters` body containing
   `{name: "valueSet", resource: {...}}` instead of a `url`. FHIR spec
   allows either. `vs-expand-enter` in `http.clj:574-609` reads only
   `url`, so these requests fall through to 404 even though the compose
   engine could expand them.
2. **Bare Pedestal 404 for unrouted paths** — FS01 (`GET /ValueSet?url=…`)
   returns a 9-byte plain-text `"Not Found"` rather than a FHIR
   `OperationOutcome`. Any future unrouted path behaves the same.
3. **ConceptMap `$translate` still a stub** — CM01, CM02 return empty
   `Parameters`. Covered by Phase 6.
4. **Non-SNOMED `CodeSystem` loading** — LK02/03/04, EX04, EX06, EX07
   cannot pass because there is no way to register LOINC, DICOM DCM,
   RxNorm, or external VSAC ValueSets. Covered by Phase 5.1; the current
   in-memory `fhir_codesystem.clj` would not scale to LOINC-size
   content and a backend tier is needed before those tests can pass
   (not just a CLI flag).

### Low-hanging fruit

Two fixes are localised and unblock real preflight cases:

- **POST `$expand` inline `valueSet`.** In `vs-expand-enter`, when `url`
  is absent, pull the inline resource out of the POST body
  (`post-resources params "valueSet"` — the helper already exists at
  `http.clj:155`), wrap it with `fhir-vs/make-fhir-value-set`, and pass
  it through the existing expand path. The `registry/valueset-expand`
  call already accepts an overlay via `ctx`; the simplest route is to
  attach the inline VS to `ctx` under a synthetic URL (or accept an
  already-constructed impl). One handler, one registry entry point, plus
  tests mirroring EX02/EX05/EX08. Flips four SNOMED preflight cases.
- **FHIR-shaped default 404.** `make-server` at `http.clj:790`
  currently installs Pedestal's stock `interceptors/not-found`, which
  is what produces FS01's bare `"Not Found"` body. Replace it with a
  Hades-specific interceptor that emits `wire/operation-outcome
  [{:severity "error" :type "not-found" …}]` and the FHIR JSON
  `Content-Type`. Configured in the connector chain — no wrapping.
  Improves every unrouted path's wire shape, not just FS01.

Neither fix requires new abstractions or backend work; both should land
before Phase 5.

Results & re-runner script captured at `/tmp/preflight-current.txt` and
`/tmp/preflight-runner.sh` during the 2026-04-19 investigation.

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
