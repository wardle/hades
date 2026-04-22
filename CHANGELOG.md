# Changes

This log documents significant changes for each release.

## [v1.4.xxx] - not yet released

Headline: Hades passes 473 / 603 (78.4%) of the HL7 FHIR Terminology
Ecosystem IG conformance tests, up from ~50 at the v1.4.69 baseline.

* Java 21 or newer is required (Lucene 10 upgrade).
* HAPI FHIR dependency removed
* Significant performance improvements
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
* Multi-version ValueSet "overloading" — same system pulled at multiple
  versions produces correctly-versioned `contains[]` entries and
  `used-codesystem` parameters.
* `$translate`: SNOMED historical associations (9 refsets) in both
  directions, plus external map refsets (ICD-O, ICD-10, ICD-10-CM)
  bidirectional. Advertised via `ConceptMap` resources.
* Batch translation, equivalence semantics per the refset type.
* Post-coordinated SNOMED expressions: rendering, MRCM-aware validation,
  and use as codes in `$lookup` / `$validate-code` / `$expand`.

## [v1.4.69] - 2025-02-22

* Upgrade dependencies including HAPI and pedestal
* New 'pluggable' architecture with protocols defining interface between server code and codesystems, valuesets and concept maps.
* Bump to latest hermes (v1.4 series) as the backend SNOMED CT module
* Sketch out support for a dynamic registry of backend modules
