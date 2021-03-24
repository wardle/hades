# 🔥 Hades : A HL7 FHIR terminology server 🔥

[![Scc Count Badge](https://sloc.xyz/github/wardle/hades)](https://github.com/wardle/hades/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hades?category=cocomo&avg-wage=100000)](https://github.com/wardle/hades/)


A lightweight HL7 FHIR facade over [hermes](https://github.com/wardle/hermes), a SNOMED CT terminology server. 

The HL7 FHIR specification includes support for a terminology API, including looking up codes and translation. 

This software provides a simple FHIR server implementation, making use of the [HAPI FHIR](https://hapifhir.io) library
in order to expose the functionality available in `hermes` via a FHIR terminology API.

*This is currently incomplete*

# Quickstart

```shell
clj -M:run /path/to/snomed.db 8080
```

Result:

```log
➜  hades git:(main) ✗ clj -M:run /var/hermes/snomed-2021-03.db 8080
2021-03-23 14:50:57,175 [main] INFO  com.eldrix.hermes.terminology - hermes terminology service opened  "/var/hermes/snomed-2021-03.db" {:version 0.4, :store "store.db", :search "search.db", :created "2021-03-08T16:16:50.973088", :releases ("SNOMED Clinical Terms version: 20200731 [R] (July 2020 Release)" "31.3.0_20210120000001 UK clinical extension")}
2021-03-23 14:50:57,284 [main] INFO  org.eclipse.jetty.server.Server - jetty-9.4.18.v20190429; built: 2019-04-29T20:42:08.989Z; git: e1bc35120a6617ee3df052294e433f3a25ce7097; jvm 11.0.9.1+1
2021-03-23 14:50:57,346 [main] INFO  com.eldrix.hades.core - Initialising HL7 FHIR R4 server; providers: CodeSystem
2021-03-23 14:50:58,308 [main] INFO  org.eclipse.jetty.server.Server - Started @14980ms
```

Currently, use `hermes` to create your index file. That tool can automatically download and create an index. 
After download, it should take less than 5 minutes to start running your FHIR terminology server.

# Example usage

Here are some examples of using the FHIR terminology API:

#### Lookup a SNOMED code

```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=209629006'
```

#### How do two codes relate to one another?

Here we test how 107963000|Liver excision relates to 63816008|Hepatectomy, total left lobectomy (procedure).
```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/CodeSystem/$subsumes?system=http://snomed.info/sct&codeA=107963000&codeB=63816008&_format=json' | jq
```

Result:
```json
{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "outcome",
      "valueString": "subsumes"
    }
  ]
}
```

### Expand a valueset

Here we ask for the contents of a valueset as defined by the URL 
`http://snomed.info/sct?fhir_vs=ecl/<<50043002%20:<<263502005=<<19939008`,
that is, give me any concepts that match the constraint

* `Disorder of the respiratory system` (<<50043002)
* with a `clinical course` (<<263502005)  (or any more specific subtype of 'clinical course')
* of `subacute` (<<19939008)

```shell
curl -H "Accept: application/json" 'localhost:8080/fhir/ValueSet/$expand?url=http://snomed.info/sct?fhir_vs=ecl/<<50043002:<<263502005=<<19939008' | jq
```

Result
```json
{
  "resourceType": "ValueSet",
  "expansion": {
    "total": 13,
    "contains": [
      {
        "system": "http://snomed.info/sct",
        "code": "233761006",
        "display": "Subacute silicosis",
        "designation": [
          {
            "value": "Active silicosis"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "233761006",
        "display": "Subacute silicosis",
        "designation": [
          {
            "value": "Subacute silicosis"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "233753001",
        "display": "Subacute berylliosis",
        "designation": [
          {
            "value": "Subacute berylliosis"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "22482002",
        "display": "Subacute obliterative bronchiolitis",
        "designation": [
          {
            "value": "Subacute obliterative bronchiolitis"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "782761005",
        "display": "Subacute invasive pulmonary aspergillosis",
        "designation": [
          {
            "value": "Subacute invasive pulmonary aspergillosis"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "782761005",
        "display": "Subacute invasive pulmonary aspergillosis",
        "designation": [
          {
            "value": "Chronic necrotising pulmonary aspergillosis"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "782761005",
        "display": "Subacute invasive pulmonary aspergillosis",
        "designation": [
          {
            "value": "Chronic necrotizing pulmonary aspergillosis"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "836479005",
        "display": "Subacute obliterative bronchiolitis due to vapour",
        "designation": [
          {
            "value": "Subacute obliterative bronchiolitis due to vapor"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "836479005",
        "display": "Subacute obliterative bronchiolitis due to vapour",
        "designation": [
          {
            "value": "Subacute obliterative bronchiolitis due to vapour"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "836479005",
        "display": "Subacute obliterative bronchiolitis due to vapour",
        "designation": [
          {
            "value": "Subacute obliterative bronchiolitis caused by vapor"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "836479005",
        "display": "Subacute obliterative bronchiolitis due to vapour",
        "designation": [
          {
            "value": "Subacute obliterative bronchiolitis caused by vapour"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "836478002",
        "display": "Subacute obliterative bronchiolitis due to chemical fumes",
        "designation": [
          {
            "value": "Subacute obliterative bronchiolitis due to chemical fumes"
          }
        ]
      },
      {
        "system": "http://snomed.info/sct",
        "code": "836478002",
        "display": "Subacute obliterative bronchiolitis due to chemical fumes",
        "designation": [
          {
            "value": "Subacute obliterative bronchiolitis caused by chemical fumes"
          }
        ]
      }
    ]
  }
}
```

# Design / development notes

see https://confluence.ihtsdotools.org/display/FHIR/Implementing+Terminology+Services+with+SNOMED+CT

The operations that are currently implemented (although are still under
continued refinement and development) are:

- $lookup (on CodeSystem resource)    
- $subsumes (on CodeSystem resource)
- $expand (on ValueSet resource) - e.g. ECL, filters

The operations that still need to be implemented are:

- $closure (on ConceptMap resource)
- $translate (on ConceptMap resource)
- $validate-code (on ValueSet resource)
- $validate-code (on CodeSystem resource)

Resource implementations are needed for 

- CodeSystem  - e.g. list all code systems available    (higher-order services might compose the results for example)

All of this functionality is obviously available in `hermes` but we need to expose using these
FHIR operations.

I don't believe in loading random value sets into a single terminology server. Rather, these should be decomposed
and recombined as needed. Otherwise, developers solving problems need to coordinate with a central authority 
in order to ensure the value sets and reference data they need are available. The exact choice will be 
determined by the problem-at-hand. Decompose, make them available both as raw data and discrete computing services
that makes using them easy, and then let others compose them together to suit their needs.
