# HTTP API

Hades speaks FHIR R4. Operations dispatch to the right terminology
provider by canonical URL, so a single endpoint serves SNOMED CT,
LOINC, and any loaded FHIR packages.

| Operation | Resource | Purpose |
|---|---|---|
| `$lookup` | `CodeSystem` | Display, designations and properties for a code |
| `$validate-code` | `CodeSystem` / `ValueSet` | Confirm a code exists / is in a value set |
| `$subsumes` | `CodeSystem` | Relationship between two codes |
| `$expand` | `ValueSet` | Materialise a value set (with optional filter, paging, ECL) |
| `$translate` | `ConceptMap` | Map a code via a ConceptMap or SNOMED map reference set |

GET and POST forms are both supported. Operation parameters can be
passed as query parameters (GET) or as a `Parameters` resource in the
request body (POST).

`tx-resource` parameters — temporary CodeSystem, ValueSet, or
ConceptMap resources — are honoured per-request and overlay the base
catalogue for the lifetime of that request only.

## Examples

### Lookup a SNOMED concept

```shell
curl -H "Accept: application/json" \
  'http://localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=209629006'
```

### How do two codes relate?

Test how `107963000|Liver excision` relates to
`63816008|Hepatectomy, total left lobectomy`.

```shell
curl -H "Accept: application/json" \
  'http://localhost:8080/fhir/CodeSystem/$subsumes?system=http://snomed.info/sct&codeA=107963000&codeB=63816008' | jq
```

```json
{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "outcome", "valueString": "subsumes" }
  ]
}
```

### Expand a value set

Ask for every concept that is a `Disorder of the respiratory system`
(`<<50043002`) with a `clinical course` (`<<263502005`) of `subacute`
(`<<19939008`). Add `&filter=sili` to drive autocomplete.

```shell
curl -H "Accept: application/json" \
  'http://localhost:8080/fhir/ValueSet/$expand?url=http://snomed.info/sct?fhir_vs=ecl/<<50043002:<<263502005=<<19939008' | jq
```

```json
{
  "resourceType": "ValueSet",
  "expansion": {
    "total": 6,
    "contains": [
      { "system": "http://snomed.info/sct", "code": "22482002",  "display": "Subacute obliterative bronchiolitis" },
      { "system": "http://snomed.info/sct", "code": "233761006", "display": "Subacute silicosis" },
      { "system": "http://snomed.info/sct", "code": "233753001", "display": "Subacute berylliosis" }
    ]
  }
}
```

### Validate a code in a value set

```shell
curl -sG 'http://localhost:8080/fhir/ValueSet/$validate-code' \
  --data-urlencode 'url=http://snomed.info/sct?fhir_vs=isa/64572001' \
  --data-urlencode 'system=http://snomed.info/sct' \
  --data-urlencode 'code=73211009' | jq
```

### Translate via a SNOMED map reference set

```shell
curl -sG 'http://localhost:8080/fhir/ConceptMap/$translate' \
  --data-urlencode 'system=http://snomed.info/sct' \
  --data-urlencode 'code=73211009' \
  --data-urlencode 'targetsystem=http://hl7.org/fhir/sid/icd-10' | jq
```

## Implicit SNOMED ValueSets and ConceptMaps

Hades supports the
[SNOMED CT FHIR module's implicit URI patterns](https://hl7.org/fhir/snomedct.html):

- `http://snomed.info/sct?fhir_vs` — every concept in SNOMED CT
- `http://snomed.info/sct?fhir_vs=isa/<sctid>` — `<sctid>` and all its descendants
- `http://snomed.info/sct?fhir_vs=refset` — every installed reference set
- `http://snomed.info/sct?fhir_vs=refset/<sctid>` — members of the named reference set
- `http://snomed.info/sct?fhir_vs=ecl/<expression>` — concepts matching an ECL expression
- `http://snomed.info/sct?fhir_cm=<sctid>` — ConceptMap defined by a SNOMED map reference set

Implicit value sets pinned to a SNOMED edition or version (e.g.
`http://snomed.info/sct/900000000000207008?fhir_vs=…`) are not yet
supported — the bare `http://snomed.info/sct` form resolves against
the loaded SNOMED database.
