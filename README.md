# 🔥 Hades : A HL7 FHIR terminology server 🔥

[![Scc Count Badge](https://sloc.xyz/github/wardle/hades)](https://github.com/wardle/hades/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hades?category=cocomo&avg-wage=100000)](https://github.com/wardle/hades/)


A lightweight HL7 FHIR server.


This is currently in development, but it currently works as a lightweight 
wrapper over [hermes](https://github.com/wardle/hermes), a SNOMED CT terminology server. 

The development plan is to turn this into a general purpose FHIR terminology 
server. Unlike most servers, it will be lightweight and principally designed
to operate read-only. It will provide access to terminology services via a 
pluggable architecture, permitting the use of backend servers (such as `hermes` 
for SNOMED CT) together with an ability to import general purpose and custom
value sets from the filesystem.

Please see branch ['decoupled-architecture'](https://github.com/wardle/hades/tree/decoupled-architecture)
for ongoing development.

# Background

The HL7 FHIR specification includes support for a terminology API, including looking up codes and translation. 

This software currently provides a simple FHIR server implementation, making use of the 
[HAPI FHIR](https://hapifhir.io) library in order to expose the functionality available in `hermes` via a FHIR terminology API. 

However, the FHIR terminology specification is quite simple, defining a HTTP REST API 
through which terminology data can be returned. In static languages, such as Java, one must
take the FHIR specifications and generate code from those specifications. That code
is then used to generate data. In dynamic languages, while code generation can be 
used, it makes more sense to just process data.

The current development plan is therefore to develop `hades` as a generic FHIR terminology
server, which can provide access to multiple codesystems including those in the 
FHIR standard, as well as external codesystems such as SNOMED CT. For small codesystems,
and the codesystems that form part of FHIR itself, these can be imported directly
from the local filesystem in their canonical formats. For larger codesystems, such
as SNOMED CT, an external library such as [hermes](https://github.com/wardle/hermes), 
can be used.

Historically, I have not *usually* advised using a FHIR terminology server in 
order to fully make use of SNOMED CT in health and care applications. In essence, 
the FHIR terminology standard supposes that you might wish to treat 
terminologies interchangeably, but any real
usage outside of trivial applications ends up making use of ad-hoc extensions 
that are usually terminology server specific. As such, you end up simply using 
the FHIR standard as a transport. 

However, there is a need to be able to handle certain aspects of codesystems in
a generic way, and the FHIR terminology specification enables that approach. We need
good tooling to make sense of codes in context, independent of source applications. 

The core principles behind the design of `hades` are therefore:

- dynamic pluggable codesystems
- immutability by default - prefer to build a new service rather than 
changing-in-place - load codesystems declaratively and reproducibly with versioning
- codesystems can be loaded from FHIR resources (e.g local JSON for built-in 
FHIR codesystems), custom modules (e.g. for SNOMED CT via `hermes`), or
local data such as CSV, JSON and EDN.

The [[FHIR terminology service standard]](http://hl7.org/fhir/terminology-service.html) defines the following endpoints:

- Specific results in the capabilities endpoint to list supported codesystems
- [base]/ValueSet
  - Value set expansion : e.g. `GET [base]/ValueSet/23/$expand?filter=abdo`
  - Value set validation : e.g. `GET [base]/ValueSet/23/$validate-code?system=http://loinc.org&code=1963-8&display=test`
  - Batch validation
- [base]/CodeSystem
  - Concept lookup : e.g. `GET [base]/CodeSystem/loinc/$lookup?code=1963-8` or 
  `GET [base]/CodeSystem/$lookup?system=http://loinc.org&code=1963-8&property=code&property=display&property=designations`
  - Subsumption testing : e.g. `GET [base]/CodeSystem/$subsumes?system=http://snomed.info/sct&codeA=235856003&codeB=3738000`
- [base]/ConceptMap
  - Translation : e.g. `GET [base]/ConceptMap/$translate?system=http://hl7.org/fhir/composition-status
    &code=preliminary&source=http://hl7.org/fhir/ValueSet/composition-status
    &target=http://terminology.hl7.org/ValueSet/v3-ActStatus`
  - Batch translation - see [[https://hl7.org/fhir/terminology-service.html#batch2]](https://hl7.org/fhir/terminology-service.html#batch2

This means that the architecture contains the following modules:

- server - a web server with routes for a FHIR terminology server /ValueSet /CodeSystem and /ConceptMap
- format - processing to parse and emit appropriately structured JSON and XML to and from FHIR standard
- registry - a registry of supported codesystems and how they are implemented
- implementations - different codesystems will have different implementations of each capability
- import - a mechanism to import codesystems / valuesets from a filesystem, or another FHIR server, and make them
available, or cached, within `hades`.


The current code tightly couples a FHIR terminology API with the underlying
`hermes` service and so while an interesting proof-of-concept, needs reworking.

The roadmap is therefore:

1. Pluggable architecture with dynamic registration of codesystems, value sets and concept maps.
2. Exploratory work to determine whether better to forego using the HAPI FHIR library in favour of 
directly returning data. Initial experiments suggest this is possible, but for XML support.
3. Ability to use Hermes as a codesystem, valueset and concept map 'provider'.
4. Ability to load in and register FHIR value sets from the specification
5. Ability to load in and register custom value sets from the local filesystem



# Quickstart

You can run a FHIR SNOMED CT terminology server directly from source code, if you have the clojure
command line tools installed:

```shell
clj -M:run /path/to/snomed.db 8080
```

Otherwise, you can download a pre-built jar file. 

```shell
java -jar hades-server-v0.10.xxx.jar /path/to/snomed/db 8080
```

Result:

```log
➜  hades git:(main) ✗ clj -M:run /var/hermes/snomed-2021-03.db 8080
2021-03-23 14:50:57,175 [main] INFO  com.eldrix.hermes.terminology - hermes terminology service opened  "/var/hermes/snomed-2021-03.db" {:version 0.4, :store "store.db", :search "search.db", :created "2021-03-08T16:16:50.973088", :releases ("SNOMED Clinical Terms version: 20200731 [R] (July 2020 Release)" "31.3.0_20210120000001 UK clinical extension")}
2021-03-23 14:50:57,284 [main] INFO  org.eclipse.jetty.server.Server - jetty-9.4.18.v20190429; built: 2019-04-29T20:42:08.989Z; git: e1bc35120a6617ee3df052294e433f3a25ce7097; jvm 11.0.9.1+1
2021-03-23 14:50:57,346 [main] INFO  com.eldrix.hades.core - Initialising HL7 FHIR R4 server; providers: CodeSystem
2021-03-23 14:50:58,308 [main] INFO  org.eclipse.jetty.server.Server - Started @14980ms
```

# How do I create a SNOMED database file?

Use [`hermes`](https://github.com/wardle/hermes) to create your index file. That tool can automatically download and create an index. 
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

Of course, you can use any ECL expression and add an optional filter as well. 
If you add `&filter=sili` then you'll basically have an endpoint that can drive
fast autocompletion. 

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

# Original (and now outdated) design / development notes

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
