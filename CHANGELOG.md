# Changes

This log documents significant changes for each release.

## [Unreleased]

Headline: Hades passes 477 / 603 (79.1%) of the HL7 FHIR Terminology
Ecosystem IG conformance tests, up from 473 / 603.

* Conformance, `^:live` integration tests and benchmarks now run
  against one pinned SNOMED CT International release (20250201),
  provisioned via `clj -X:build-db` (local zip or MLDS download).
  Drops hard-coded personal paths and the `$SNOMED_DB` env var.
* tx-ecosystem conformance fixtures pinned to a specific upstream
  commit; every run reports whether upstream main is ahead.
* `regex` CodeSystem filter uses RE2/j — linear-time, immune to
  catastrophic backtracking (ReDoS).
* SNOMED `$lookup` now supports post-coordinated expressions.
* `ValueSet/$validate-code` with a missing `url` returns a 4xx
  `OperationOutcome` with `code=invalid`, not a 5xx NPE.
* SNOMED implicit-VS `not-found` and `inactive-display` issues emit
  the IG's expected `operationoutcome-message-id` extension.
* Handled `ex-info` throws log terse INFO instead of ERROR + stack;
  only genuinely unexpected exceptions keep the stack trace.
* CI: single workflow with a shared `build-db` job feeding parallel
  `test` + `conformance`. Actions bumped to Node 24-capable releases.
* Source reorganised into `impl/` for internals and `cmd/` for the
  CLI; test namespaces mirror the new layout.

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
