# 🔥 Hades : A HL7 FHIR terminology server 🔥

[![Scc Count Badge](https://sloc.xyz/github/wardle/hades)](https://github.com/wardle/hades/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/hades?category=cocomo&avg-wage=100000)](https://github.com/wardle/hades/)
[![Conformance](https://img.shields.io/badge/conformance-473%2F603%20(78.4%25)-blue)](https://github.com/HL7/fhir-tx-ecosystem-ig)

A lightweight HL7 FHIR terminology server for SNOMED CT. Hades exposes
`CodeSystem`, `ValueSet`, and `ConceptMap` FHIR operations (`$lookup`,
`$validate-code`, `$subsumes`, `$expand`, `$translate`) over HTTP,
backed by the [hermes](https://github.com/wardle/hermes) SNOMED CT
terminology engine.

Hades passes **473 / 603 (78.4%)** of the HL7 FHIR Terminology Ecosystem
IG conformance tests.

Hades requires Java 21 or above.

# Quickstart

Hades handles the full SNOMED CT lifecycle: download a distribution,
build a database, serve it over FHIR.

Every example below uses `clj -M:run` to run from source; with the pre-built
uberjar, replace that with `java -jar hades-<version>.jar`.

## 1. Build a SNOMED database

Pick whichever path matches your source. All three end with a `snomed.db`
directory ready for `serve`.

### a) UK edition from TRUD

Save your TRUD API key in a file (e.g. `trud-api-key.txt`), then:

```shell
clj -M:run install index compact \
    --db snomed.db \
    --dist uk.nhs/sct-clinical \
    --api-key trud-api-key.txt
```

Commands execute in the order given — download + import, then build indices,
then compact. Add more `--dist` flags (e.g. `--dist uk.nhs/sct-drug-ext`) to
layer additional UK distributions into the same database before indexing.

### b) SNOMED CT International from MLDS

Save your MLDS password in a file, then:

```shell
clj -M:run install index compact \
    --db snomed.db \
    --dist ihtsdo.mlds/167 \
    --username you@example.com \
    --password mlds-password.txt
```

Run `clj -M:run available` to browse all MLDS distributions and their IDs.

### c) A distribution you downloaded manually

Unzip the RF2 release, then point `import` at the unzipped directory:

```shell
clj -M:run list /path/to/unzipped-rf2/              # inspect what hades will import
clj -M:run import index compact \
    --db snomed.db \
    /path/to/unzipped-rf2/
```

## 2. Serve

```shell
clj -M:run serve --db snomed.db --port 8080
```

Use `clj -M:run --help <command>` for full per-command options.

## Command reference

| Command | Purpose |
|---------|---------|
| `serve --db PATH [--port N] [--bind-address A] [--locale L]` | Start the FHIR server |
| `install --db PATH --dist D …` | Download and import a distribution (run `index` + `compact` afterwards) |
| `import --db PATH [paths…]` | Import RF2 files from local disk |
| `list [paths…]` | List importable files under given paths |
| `available [--dist D]` | List distribution providers / releases for a given distribution |
| `index --db PATH` | Build search indices |
| `compact --db PATH` | Compact the LMDB store |
| `status --db PATH [--format json\|edn] [--modules] [--refsets]` | Show database status |

Commands can be chained on a single command line and execute in the order
given, sharing flags — for example `install index compact` above.

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

# Roadmap

Planned work, in rough priority order:

- **File-backed code systems and value sets** — load FHIR JSON (`CodeSystem`,
  `ValueSet`, `Bundle`) from a `--resources` directory at startup, so you can
  serve non-SNOMED terminologies (LOINC, ICD-10, locally-authored
  `ValueSet`s) alongside SNOMED. The underlying providers already exist
  internally; this wires them into the CLI.
- **`$translate`** — ConceptMap translation, including SNOMED CT map
  reference sets (ICD-10, CTV3, ICD-O) auto-discovered from Hermes and
  file-backed `ConceptMap` JSON.
- **Resource read and search** — `GET /fhir/CodeSystem/{id}`,
  `GET /fhir/ValueSet?url=…`, and search by common parameters.
- **Conformance coverage** — drive the HL7 FHIR Terminology Ecosystem IG
  pass rate above 80% (currently 473/603). See
  [`plan/conformance-next-steps.md`](plan/conformance-next-steps.md) for
  the active work queue.
- **CI/CD and health endpoints** — GitHub Actions for test/lint/conformance
  on every push, tagged releases that publish the uberjar, and a
  `/health` endpoint for orchestration.

The full roadmap, including completed phases and per-test-suite gap
analysis, lives in [`plan/roadmap.md`](plan/roadmap.md).
