# Changes

This log documents significant changes for each release.

## Not yet released

* `GET /ValueSet?_summary=true` browse now builds each summary row from the `valueset` hot table's inline columns instead of reading and JSON-parsing the per-row `compose`/`metadata` blob. A `_count=100` summary page drops ~11× (≈27 ms → ≈2.4 ms in-process); output is unchanged.

## [v2.0.270] - 2026-06-04

* **New native LOINC provider.** LOINC is now served by a dedicated SQLite-backed provider with a close-to-source table structure, indexed and queried directly rather than forced through a generic FTRM container at load time. Provider namespaces were restructured for simpler multi-provider support.
* **ConceptMap search/discovery** (`GET /ConceptMap`). A shared FHIR REST-search filter matcher (`providers/common/search_filter`) backs ConceptMap search at the composite and metadata-opts filtering inside providers, wired through `core`, HTTP and the MCP tools.
* **OID / URN / URI alias resolution.** Providers expose a NamingService that maps an identifier (bare OID/UUID, `urn:oid:`/`urn:uuid:`, or URI alias) to its canonical `{:url :kind}`. The composite consults it only on a dispatch miss, then re-dispatches by canonical URL — canonical-URL requests pay nothing and providers never see an alias. Version-blind across CodeSystem/ValueSet/ConceptMap.
* **`ValueSet/$batch-validate-code`.** Validate a list of nested `Parameters` against a shared ValueSet, returning one result per item; the batch never fails as a whole. The per-item validation core is shared with `$validate-code`.
* `import` and `serve` now accept archive files (`.tgz`/`.tar.gz`/`.tar`/`.zip`) as positional sources; each is extracted to a temporary directory, walked like any other source, and removed afterwards (#19). FHIR package installs keep the download cache to just the `.tgz` tarballs — no unpacked extracts.
* FHIR REST search (`GET /CodeSystem`, `GET /ValueSet`) now pages at the provider: FTRM reads only the requested page from its clustered tables instead of the composite walking and sorting the whole catalogue, and `_summary` listings no longer read or parse the `compose` blob. Browse latency on a large catalogue drops by roughly 50×.
* `Bundle.total` is omitted from a paginated searchset (permitted by FHIR): an exact deduplicated count across providers would need a full key enumeration, and summing per-provider counts would double-count shared canonicals. It is still reported when the result fits in one page (and for `_summary=count` / `_count=0`).
* FTRM `CodeSystem`/`ConceptMap` point operations (`$lookup`, `$validate-code`, `$subsumes`, `$translate`) resolve an unversioned reference to its SemVer-latest version via a precomputed `url → entry` index, instead of scanning the metadata cache on every version-less request.
* Performance enhancements for valueset expansion.
* `$validate-code` against a SNOMED CT ValueSet whose `compose` uses a filter include with `expressions = true` now validates post-coordinated expressions: membership is decided by structural expression subsumption (`hermes/subsumes`) against the include's `concept` filters, with `expressions = false`/absent rejecting expressions. The rendered expression is returned as the display.
* `$validate-code` against an implicit SNOMED ValueSet (`?fhir_vs=isa/X`, `?fhir_vs`) now correctly accepts post-coordinated expressions by structural expression subsumption. Arbitrary `?fhir_vs=ecl/…` with an expression is declined as `not-supported`; plain-concept membership in arbitrary ECL is unchanged (handled via `intersect-ecl`).
* `?fhir_vs=isa/X` is now reflexive — includes X itself — for both `$validate-code` and `$expand`, matching the FHIR SNOMED CT specification.
* Display validation is now consistent across every provider: a `$validate-code` display mismatch is strict (`error`, `result=false`) by default and lenient (`warning`, `result=true`) only when `lenient-display-validation` is set, reported on `Coding.display`. A malformed `displayLanguage` operation parameter is now rejected as `invalid-display`.
* Hades passes 504 / 600 (84.0%) tests in the pinned HL7 FHIR Terminology Conformance suite.

## [v2.0.206] - 2026-05-09

* SNOMED CT ECL filters now accept `constraint` as well as `expression` as the property name carrying the ECL — `constraint` is the FHIR-spec spelling. Both compose the same ECL via `=` or `in`, and the not-supported error text echoes whichever name was supplied.
* `$subsumes` with empty or malformed `Parameters` (no `codeA`/`codeB`/`system` or `codingA`/`codingB`) now returns a `400 invalid` `OperationOutcome` instead of a misleading `404 "No endpoint matches path"`.
* Fixed `used-codesystem` metadata (`status`, `experimental`, `standards-status`) being dropped from `$expand` against SQLite-backed CodeSystems (LOINC, IGs, hl7.terminology); the collector now reads catalogue metadata via the composite's correctly-parameterised lookup.

## [v2.0.198] - 2026-05-09

* FHIR REST search latency on the URL-by-url hot path roughly halved.
* Validation message wording is now identical across in-memory and
  SQLite providers.
* Source-walker I/O errors are surfaced instead of silently skipped.

## [v2.0.191] - 2026-05-07

Headline: Hades is now a **multi-terminology** FHIR server. SNOMED CT,
LOINC, and arbitrary FHIR NPM packages run side-by-side in one process,
dispatched by canonical URL. v1.x served SNOMED only.

Hades passes **493 / 600 (82.2%)** tests in the HL7 FHIR
Terminology Ecosystem IG conformance suite against the pinned
tx-ecosystem rev (`fb9078f6`).

The major version bump signals two things: the surface is broader (LOINC,
FHIR packages, mixed sources on a single `serve`), and the CLI now takes
**positional paths** for terminology sources rather than `--db` flags.
v1.x command lines need to be reworked.

### New: FTRM open specification for SQLite FHIR terminology containers

* An open specification for single fine, re-usable, versioned FHIR terminology containers based on SQLite.
* Hades can build FTRM files from LOINC and from FHIR terminology packages 

### New: LOINC support

* **LOINC release ingestion** from a Loinc release directory into a
  Hades SQLite container. The loader reads `LoincTableCore`, `Part.csv`,
  `LoincPartLink_Primary.csv`, `ComponentHierarchyBySystem.csv`, `MapTo`,
  `AnswerLists`, and the per-language `LinguisticVariants/*.csv`.
* A real Loinc 2.81 import yields 202k concepts (incl. 73k LP-parts),
  7M designations across 21 languages, 661k typed Coding axis links,
  and a 252k-row ancestor closure powering `$subsumes` over LOINC's DAG.
* Operations supported: `$lookup`, `$validate-code`, `$expand` (with
  `displayLanguage`, regex / equals / in property filters), `$subsumes`,
  `$translate` against LOINC's MapTo refset.
* `import loinc.db /path/to/Loinc_2.81` — single command from release
  archive to an FTRM container ready to be served.

### New: FHIR NPM package support

* **`install` from `packages.fhir.org`.** `hades install fhir.db
  --dist hl7.fhir.r4.core@4.0.1 --dist hl7.terminology.r4@7.0.1`
  resolves versions against the registry, downloads the tgz, extracts,
  and ingests CodeSystem / ValueSet / ConceptMap / NamingSystem /
  CodeSystem-supplement resources into a FTRM SQLite container.
* **In-memory serving as an alternative.** Pass an extracted package
  directory directly to `serve` — Hades parses it on boot and serves
  from heap for sub-microsecond hashmap lookups. `--cache-dir` on
  `install` keeps the extracted JSON for this purpose.
* **Choose per package.** `serve` accepts each positional path
  independently as Hermes (SNOMED), Hades SQLite container, or FHIR
  resource directory — auto-detected. Pick in-memory for latency-
  sensitive small corpora, SQLite for memory-constrained or very
  large corpora; the same binary serves both.
* JSON reader strips a leading UTF-8 BOM and treats malformed sidecar
  JSON (openapi specs etc.) as a soft skip so a single broken file
  doesn't abort a directory walk.

### New: composite TerminologyService

* `core/open` takes a vector of providers (each implementing the
  `CodeSystem` / `ValueSet` / `ConceptMap` protocols in
  `impl/protocols.clj`) and assembles a single composite service.
* Dispatch is by canonical URL with version resolution
  (`force-system-version` / `system-version` / `check-system-version`),
  semver-latest selection across multi-version registrations, and
  bare-URL aliasing via `NamingSystem` resources.
* Cross-provider concerns — status warnings, supplement application,
  `CodeableConcept` aggregation across mixed-system codings — live in
  the composite.
* **Request-scoped overlays.** FHIR `tx-resource` parameters are
  parsed per request, indexed in-memory, and folded onto the base
  service for the lifetime of the call. The base service is unchanged.

### CLI

* **Positional paths only for sources.** `serve snomed.db loinc.db
  packages/hl7.fhir.r4.core/package` — paths are not flagged. Bare
  arguments are always filesystem paths; distribution ids carry on
  `--dist` flags only.
* `install`, `import`, `index`, `compact`, `status`, `available`,
  `list` work uniformly across SNOMED, LOINC, and FHIR-package paths.
* Subcommands chain: `install index compact snomed.db --dist
  uk.nhs/sct-clinical --api-key trud.txt`.
* `available` lists releases for SNOMED distributions (TRUD, MLDS) and
  versions for FHIR packages from `packages.fhir.org`.
* `--cache-dir` on `install` retains the extracted FHIR-package
  directory for in-memory serving alongside the SQLite container.

### Concept-shape extension

* `:concept` now carries an optional `:parents` vector alongside the
  existing single `:parent-code`, supporting LOINC's multi-parent DAG.
  The SQLite indexer and the in-memory builder consume either form.

### Dependencies

* `sqlite-jdbc 3.50.3.0 → 3.53.0.0`
* `next.jdbc 1.3.1048 → 1.3.1093`
* `HikariCP 6.2.1 → 7.0.2`

## [v1.4.138] - 2026-04-24

* Improve performance of common implicit valueset expansion

## [v1.4.135] - 2026-04-23

Headline: Hades passes 477 / 600 (79.5%) tests in the HL7
FHIR Terminology Ecosystem IG conformance suite, up from 473 / 600.

* Dependency refresh: Clojure 1.12.4, logback 1.5.32, nREPL 1.7.0,
  HAPI validation 6.9.7 and other minor bumps. okhttp held at 4.12.0
  pending HAPI support for the 5.x Kotlin multiplatform split.
* **Reproducible conformance suite.** Conformance, `^:live` integration
  tests and benchmarks run against one pinned SNOMED CT International
  release (20250201) and a pinned tx-ecosystem fixture commit,
  provisioned explicitly via `clj -M:run install ihtsdo.mlds/167@2025-02-01`.
  Every run reports if upstream has moved ahead. Removes hard-coded
  personal paths and the `$SNOMED_DB` env var.
* **`$lookup` ~2× faster.** Post-coordinated expressions parsed and
  enriched per refinement; dropped unused `extended-concept` work and
  short-circuited the unknown-code path.
* **Robustness.** `regex` CodeSystem filter uses RE2/j (linear-time,
  ReDoS-safe); `ValueSet/$validate-code` with a missing `url` returns
  4xx instead of 5xx; SNOMED implicit-VS errors emit the IG's
  `operationoutcome-message-id` extension; expected `ex-info` throws
  log as terse INFO so real surprises stand out in the stack traces.
* **CI.** Single workflow with a shared `build-data` job feeding parallel
  `test` and `conformance`. Actions bumped to Node-24-capable releases.
* **Layout.** Internals moved to `impl/`, CLI to `cmd/`; test
  namespaces mirror the new structure.

## [v1.4.112] - 2026-04-22

* New CLI that owns the full SNOMED lifecycle: `serve`, `install`,
  `import`, `list`, `available`, `index`, `compact`, `status`
  subcommands via `clj -M:run`. Hermes is used as a library; users
  no longer run it directly.

## [v1.4.109] - 2026-04-22

Headline: Hades passes 473 / 600 (78.8%) tests in the HL7
FHIR Terminology Ecosystem IG conformance suite, up from ~50 at the
v1.4.69 baseline.

* Java 21 or newer is required (Lucene 10 upgrade).
* HAPI FHIR dependency removed; pure Pedestal + charred on the wire.
* Significant performance improvements.
* New pluggable-protocol architecture: `CodeSystem`, `ValueSet` and
  `ConceptMap` protocols in `protocols.clj` with spec-driven return-value
  contracts (`::validate-result`, `::expansion-result`, `::lookup-result`).
* `$lookup`: full property/designation support, post-coordinated SNOMED
  expressions (SCG + MRCM validation), language-aware display selection
  via `Accept-Language` / `displayLanguage`.
* `$validate-code`: version resolution, case-insensitive matching with
  warnings, display-mismatch warnings/errors, expression validation.
* `$subsumes`: historical-equivalence fallback (SAME-AS, REPLACED-BY, etc.)
  so retired concepts resolve to their successors.
* `$expand`: compose engine supports `include` / `exclude`, filters,
  explicit concept lists, nested `valueSet` references with circular-
  reference detection, `count` / `offset` paging, `filter` text search,
  `displayLanguage`, `activeOnly`, `abstract` / `notSelectable`,
  `designation` use codings.
* POST `$expand` accepts an inline `valueSet` Parameters body.
* Multi-version ValueSet "overloading" — same system pulled at multiple
  versions produces correctly-versioned `contains[]` entries and
  `used-codesystem` parameters.
* SNOMED compose: attribute filters translate into ECL refinements;
  dedicated concept-expansion engine backed by Lucene.
* `$translate`: SNOMED historical associations (9 refsets) in both
  directions, plus external map refsets (ICD-O, ICD-10, ICD-10-CM)
  bidirectional. Advertised via `ConceptMap` resources.
* Batch translation, equivalence semantics per the refset type.
* Post-coordinated SNOMED expressions: rendering, MRCM-aware validation,
  and use as codes in `$lookup` / `$validate-code` / `$expand`.
* FHIR-shaped `OperationOutcome` for unrouted paths.
* Criterium-based bench harness (`clj -M:bench`) for registry-layer
  micro-benchmarks.
* Conformance harness: REPL-driven workflow, auto-build of a SNOMED
  subset database from the HL7 tx-ecosystem RF2 data.

## [v1.4.69] - 2025-02-22

* Upgrade dependencies including HAPI and pedestal
* New 'pluggable' architecture with protocols defining interface between server code and codesystems, valuesets and concept maps.
* Bump to latest hermes (v1.4 series) as the backend SNOMED CT module
* Sketch out support for a dynamic registry of backend modules
