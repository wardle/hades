# Installation

This walkthrough builds three terminologies side-by-side and serves
them from a single Hades process: SNOMED CT, LOINC, and a couple of
FHIR packages from
[packages.fhir.org](https://packages.fhir.org). Each block is a
discrete step — skip any terminology you don't need.

## Prerequisites

- **Java 21 or above** — required for everything below.
- **A SNOMED CT licence** — free, but you must register. Pick one:
  - [**MLDS Affiliate licence**](https://mlds.ihtsdotools.org) — the
    standard route for international users (research, evaluation,
    clinical care in non-Member countries). Provides SNOMED CT
    International edition.
  - [**UK TRUD account**](https://isd.digital.nhs.uk/trud) — for UK
    users. Provides the UK editions (UK Clinical, UK Drug, and the
    "monolith" merged edition that bundles dm+d).
  - **An RF2 release archive you already have** — e.g. obtained via
    [UMLS](https://www.nlm.nih.gov/research/umls/) or any other route.
    Import it directly, no registry credentials needed.
- **A LOINC release archive** — sign in at
  [loinc.org/downloads](https://loinc.org/downloads/) (free) and
  download the archive (e.g. `Loinc_2.81.zip`). `import` reads the zip
  directly; you don't need to unzip it first.

> Throughout this document, examples use `java -jar hades.jar`. From a
> source checkout, replace with `clj -M:run` throughout.

## 1. Install SNOMED CT

Pick the path that matches your licence. Both produce a
queryable `snomed.db` directory in a few minutes (~2 GB on disk).

**International edition (MLDS):** save your MLDS password to a file
(e.g. `mlds-password.txt`), then:

```shell
java -jar hades.jar install snomed.db \
    --dist ihtsdo.mlds/167 \
    --username 'you@example.com' \
    --password ./mlds-password.txt
```

**UK monolith edition (TRUD):** save your TRUD API key to a file
(e.g. `trud-api-key.txt`), then:

```shell
java -jar hades.jar install snomed.db \
    --dist uk.nhs/sct-monolith \
    --api-key ./trud-api-key.txt
```

**Already have the release on disk (UMLS or otherwise):** point
`import` at the RF2 archive. `.zip`/`.tgz`/`.tar.gz`/`.tar` are read
directly, or pass the path to an already-unzipped directory — either
works:

```shell
java -jar hades.jar import snomed.db /path/to/snomed-rf2.zip   # archive
java -jar hades.jar import snomed.db /path/to/unzipped-rf2/    # or a directory
```

Both `install` and `import` auto-index the destination, so
`snomed.db/` is queryable as soon as the command returns. The
[SNOMED CT](#snomed-ct) section below covers layering extensions and
pinning releases.

## 2. Build LOINC

LOINC isn't distributed via a registry — point `import` at the release
archive (or an unzipped directory; both work):

```shell
java -jar hades.jar import loinc.db /path/to/Loinc_2.81.zip
```

## 3. Install FHIR packages

```shell
java -jar hades.jar install fhir.db \
    --dist hl7.fhir.r4.core@4.0.1 \
    --dist hl7.terminology.r4@7.0.1 \
    --dist hl7.fhir.uv.ips@2.0.0
```

Packages are pulled from `packages.fhir.org` and loaded into a SQLite
container. No credentials needed. To serve in-memory instead —
faster simple lookups, larger heap — add `--cache-dir packages/` and `serve`
the cached `.tgz` tarballs directly (see
[In-memory vs SQLite container](#in-memory-vs-sqlite-container) below).

## 4. Check what you've got

```shell
java -jar hades.jar status snomed.db loinc.db fhir.db
```

Reports the CodeSystems, ValueSets and ConceptMaps each source
contributes — handy as a smoke test before exposing the server. Add
`--format json` for machine-readable output.

## 5. Serve them together

```shell
java -jar hades.jar serve snomed.db loinc.db fhir.db --port 8080
```

The composite catalogue dispatches operations to the right provider by
canonical URL — no per-terminology routes, no client-side configuration.
A single endpoint serves all three.

When two providers claim the same canonical URL (e.g. an International
and a UK SNOMED database both serving `http://snomed.info/sct`),
disambiguate with one or more `--default URL=VERSION` flags. A request
that names the bare URL is routed to the matching version; requests
that include an explicit `version` are routed normally.

```shell
java -jar hades.jar serve snomed-intl.db snomed-uk.db \
    --default http://snomed.info/sct=http://snomed.info/sct/83821000000107/version/20250416
```

## 6. Try it

```shell
# SNOMED CT — 73211009 is "Diabetes mellitus"
curl -sG 'http://localhost:8080/fhir/CodeSystem/$lookup' \
  --data-urlencode 'system=http://snomed.info/sct' \
  --data-urlencode 'code=73211009' | jq .

# LOINC — 718-7 is "Hemoglobin [Mass/volume] in Blood"
curl -sG 'http://localhost:8080/fhir/CodeSystem/$lookup' \
  --data-urlencode 'system=http://loinc.org' \
  --data-urlencode 'code=718-7' | jq .

# FHIR — expand the administrative-gender ValueSet from hl7.fhir.r4.core
curl -sG 'http://localhost:8080/fhir/ValueSet/$expand' \
  --data-urlencode 'url=http://hl7.org/fhir/ValueSet/administrative-gender' | jq .
```

See [HTTP API](http-api.md) for more examples and
[CLI reference](cli.md) for full per-command options.

---

# Per-terminology details

## SNOMED CT

Hades wraps [Hermes](https://github.com/wardle/hermes) (LMDB + Lucene)
to serve SNOMED CT. Operations are full-fidelity: ECL queries, the
implicit `http://snomed.info/sct?fhir_vs=…` / `?fhir_cm=…` URI patterns,
$translate via SNOMED map reference sets, and lookup with all
designations and properties.

### Distributions

```shell
java -jar hades.jar available
```

lists every distribution Hades knows. The common choices:

| Identifier | Source | Auth |
|---|---|---|
| `ihtsdo.mlds/167` | SNOMED CT International edition (from MLDS) | MLDS `--username` + `--password` |
| `uk.nhs/sct-monolith` | UK monolith — international + UK clinical + UK drug extension, merged | TRUD `--api-key` |
| `uk.nhs/sct-clinical` | UK clinical edition (no drug extension) | TRUD `--api-key` |
| `uk.nhs/sct-drug-ext` | UK drug extension — layer on top of UK clinical | TRUD `--api-key` |

Other national editions distributed via MLDS use the same shape:
`<member>.mlds/<package-id>` (e.g. `ihtsdo.mlds/167` for the
International edition published by IHTSDO). Run `available` to see
every member/package pair the local hermes registry knows.

Pin a release with `@<version>`, e.g.
`--dist uk.nhs/sct-clinical@2025-06-11`. Run
`java -jar hades.jar available --dist <id>` to list available versions
(some registries authenticate read access — pass the same credentials
you'd use for install).

### Layering distributions

Multiple `--dist` flags on a single `install` layer into the same
database; the auto-index runs once at the end:

```shell
java -jar hades.jar install snomed.db \
    --dist uk.nhs/sct-clinical \
    --dist uk.nhs/sct-drug-ext \
    --api-key trud-api-key.txt
```

To layer across **separate** invocations (e.g. install one distribution
today, another next week into the same DB), pass `--no-index` on every
call but the last to skip the per-call index — then run a final
unflagged install/import or a standalone `index <db>`:

```shell
java -jar hades.jar install --no-index snomed.db --dist uk.nhs/sct-clinical --api-key trud-api-key.txt
java -jar hades.jar install            snomed.db --dist uk.nhs/sct-drug-ext --api-key trud-api-key.txt
```

### Importing a manually-downloaded release

If you already have an RF2 release on disk, skip `install`. Pass either
the release archive (`.zip`/`.tgz`/`.tar.gz`/`.tar`, read directly) or an
already-unzipped directory:

```shell
java -jar hades.jar import snomed.db /path/to/snomed-rf2.zip         # archive, or an unzipped directory
java -jar hades.jar list   /path/to/unzipped-rf2/                    # preview a directory before importing
```

## LOINC

LOINC is consumed from a local release archive (the `Loinc_<version>`
download from [loinc.org](https://loinc.org/downloads/)) — passed to
`import` as the zip or an unzipped directory. There is no registry —
manual download is required for licensing reasons, but no API key is
involved.

```shell
java -jar hades.jar import loinc.db /path/to/Loinc_2.81.zip
```

`import` auto-indexes the destination — the ancestor closure and FTS
tables (which `descendant-of` filters and text search rely on) are
built before the command returns.

What's exposed:

- The `http://loinc.org` CodeSystem with `$lookup`, `$validate-code`,
  `$subsumes` (over LOINC's class hierarchy), and full property metadata
  (COMPONENT, PROPERTY, SYSTEM, METHOD_TYP, SHORTNAME, etc.)
- `AccessoryFiles/ComponentHierarchyBySystem/ComponentHierarchyBySystem.csv`
  is loaded as parent/child concept relations, so `descendant-of`
  filters in `$expand` and `$subsumes` traverse LOINC's hierarchy
- `LoincTable/MapTo.csv` is loaded as a LOINC→LOINC ConceptMap

LOINC builds into a SQLite container directly; no separate `index` or
`compact` is needed.

## FHIR packages

Any [FHIR NPM package](https://registry.fhir.org/) — IGs, conformance
packages, terminology bundles — can be served alongside SNOMED and
LOINC. The composite dispatches by canonical URL, so a `ValueSet` from
`hl7.fhir.us.core` and a `CodeSystem` from `hl7.terminology.r4` resolve
without any per-package configuration.

```shell
java -jar hades.jar available                                # show known FHIR packages
java -jar hades.jar available --dist hl7.fhir.r4.core        # list versions
```

Common packages:

| Package | Purpose |
|---|---|
| `hl7.fhir.r4.core` | FHIR R4 core (CodeSystems and ValueSets) |
| `hl7.terminology.r4` | HL7 standard CodeSystems and ValueSets |
| `hl7.fhir.us.core` | US Core implementation guide |
| `hl7.fhir.uv.ips` | International Patient Summary |

Hades's wire format is FHIR **R4** (the CapabilityStatement reports
`fhirVersion` 4.0.1). R5 packages can be installed and their
CodeSystems/ValueSets will mostly load, but the server speaks R4 to
clients and R5 ConceptMaps (`relationship`, not R4 `equivalence`) are
not fully supported.

Any other id from `packages.fhir.org` works too — e.g.
`--dist hl7.fhir.uv.sdc@3.0.0`. If the registry doesn't authenticate the
listing, `available --dist <id>` shows versions; otherwise pin a known
version with `@<version>`.

### In-memory vs SQLite container

`install` lands a FHIR package as a SQLite container by default. Add
`--cache-dir <dir>` to retain the downloaded package tarball at
`<dir>/<id>-<version>.tgz`, then `serve` that archive directly for
in-memory operation (`serve` extracts it on demand):

```shell
# Install once, keep the downloaded tarballs in ./packages
java -jar hades.jar install fhir.db \
    --dist hl7.fhir.r4.core@4.0.1 \
    --dist hl7.terminology.r4@7.0.1 \
    --cache-dir packages

# Serve in-memory (faster simple lookups, larger heap)
java -jar hades.jar serve --port 8080 \
    snomed.db loinc.db \
    packages/hl7.fhir.r4.core-4.0.1.tgz \
    packages/hl7.terminology.r4-7.0.1.tgz

# Or serve from the SQLite container (lower memory)
java -jar hades.jar serve snomed.db loinc.db fhir.db --port 8080
```

Both modes serve identical content — a provider-parity test guarantees
it — so the choice is purely a boot-time / memory / latency tradeoff:

| | `.db` (SQLite container) | FHIR JSON dir (in-memory) |
| --- | --- | --- |
| Boot time | Fast — opens the file | Slower — parses every JSON at start (~15 s for 6 HL7 packages) |
| Memory | ~80 MB regardless of package count — memory-mapped, off-heap | Whole corpus in heap (~600 MB live for the 6 HL7 packages above) |
| Simple `$lookup` | JDBC + prepared statement, tens of µs | Hashmap lookup, nanoseconds |
| Free-text search, intensional `$expand` | Faster — indexed / full-text query paths | Slower — scans the in-heap corpus |
| Best for | Most deployments, especially memory-constrained hosts and large corpora | Latency-sensitive simple lookups on small catalogues with ample RAM |

The SQLite container is `install`'s default and the recommended way to
serve. Reach for in-memory only when simple-`$lookup` latency on a small
catalogue matters more than boot time and memory; `serve` a package
archive (`.tgz`/`.zip`) or an extracted package directory directly.
`tx-resource` overlays
are always in-memory. See [FTRM](ftrm.md) for the container schema.
