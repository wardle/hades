# Changes

This log documents significant changes for each release.

## [v1.4.135] - 2026-04-23

Headline: Hades passes 477 / 603 (79.1%) of the HL7 FHIR Terminology
Ecosystem IG conformance tests, up from 473 / 603.

* Dependency refresh: Clojure 1.12.4, logback 1.5.32, nREPL 1.7.0,
  HAPI validation 6.9.7 and other minor bumps. okhttp held at 4.12.0
  pending HAPI support for the 5.x Kotlin multiplatform split.
* **Reproducible conformance suite.** Conformance, `^:live` integration
  tests and benchmarks run against one pinned SNOMED CT International
  release (20250201) and a pinned tx-ecosystem fixture commit,
  provisioned explicitly via `clj -X:build-db`. Every run reports if
  upstream has moved ahead. Removes hard-coded personal paths and the
  `$SNOMED_DB` env var.
* **`$lookup` ~2× faster.** Post-coordinated expressions parsed and
  enriched per refinement; dropped unused `extended-concept` work and
  short-circuited the unknown-code path.
* **Robustness.** `regex` CodeSystem filter uses RE2/j (linear-time,
  ReDoS-safe); `ValueSet/$validate-code` with a missing `url` returns
  4xx instead of 5xx; SNOMED implicit-VS errors emit the IG's
  `operationoutcome-message-id` extension; expected `ex-info` throws
  log as terse INFO so real surprises stand out in the stack traces.
* **CI.** Single workflow with a shared `build-db` job feeding parallel
  `test` and `conformance`. Actions bumped to Node-24-capable releases.
* **Layout.** Internals moved to `impl/`, CLI to `cmd/`; test
  namespaces mirror the new structure.

## [v1.4.112] - 2026-04-22

* New CLI that owns the full SNOMED lifecycle: `serve`, `install`,
  `import`, `list`, `available`, `index`, `compact`, `status`
  subcommands via `clj -M:run`. Hermes is used as a library; users
  no longer run it directly.

## [v1.4.109] - 2026-04-22

Headline: Hades passes 473 / 603 (78.4%) of the HL7 FHIR Terminology
Ecosystem IG conformance tests, up from ~50 at the v1.4.69 baseline.

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
