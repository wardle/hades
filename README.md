# 🔥 Hades : A HL7 FHIR terminology server 🔥

[![Scc Count Badge](https://sloc.xyz/github/wardle/hades)](https://github.com/wardle/hades/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hades?category=cocomo&avg-wage=100000)](https://github.com/wardle/hades/)

A lightweight HL7 FHIR terminology server. Hades currently wraps
[hermes](https://github.com/wardle/hermes) as a SNOMED CT provider and is
evolving into a general-purpose FHIR terminology server with a pluggable
backend for additional codesystems, implicit value sets and concept maps.

Hades passes **473 / 603 (78.4%)** of the HL7 FHIR Terminology Ecosystem
IG conformance tests.

Hades requires Java 21 or above.

# Quickstart

Run from source with the Clojure CLI:

```shell
clj -M:run /path/to/snomed.db 8080
```

Or run the pre-built uberjar:

```shell
java -jar hades-<version>.jar /path/to/snomed.db 8080
```

# How do I create a SNOMED database file?

Use [`hermes`](https://github.com/wardle/hermes) to download and index a
SNOMED CT distribution. Use the matching major version — for `hades` v1.4
use `hermes` v1.4.

# Example usage

#### Lookup a SNOMED code

```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=209629006'
```

#### How do two codes relate to one another?

Test how 107963000|Liver excision relates to 63816008|Hepatectomy, total left lobectomy.

```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/CodeSystem/$subsumes?system=http://snomed.info/sct&codeA=107963000&codeB=63816008' | jq
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
curl -H "Accept: application/json" 'localhost:8080/fhir/ValueSet/$expand?url=http://snomed.info/sct?fhir_vs=ecl/<<50043002:<<263502005=<<19939008' | jq
```

```json
{
  "resourceType": "ValueSet",
  "expansion": {
    "total": 13,
    "contains": [
      { "system": "http://snomed.info/sct", "code": "233761006", "display": "Subacute silicosis" },
      { "system": "http://snomed.info/sct", "code": "233753001", "display": "Subacute berylliosis" },
      { "system": "http://snomed.info/sct", "code": "22482002",  "display": "Subacute obliterative bronchiolitis" }
      // ...
    ]
  }
}
```
