# MCP Server

Hades provides a native [Model Context Protocol (MCP)](https://modelcontextprotocol.io)
server, exposing FHIR terminology operations to AI assistants over stdio
JSON-RPC. Where [hermes' MCP](https://github.com/wardle/hermes/blob/main/doc/mcp.md)
is SNOMED-only, hades wraps **multiple terminologies** — SNOMED CT
(international or any national edition), LOINC, and any HL7 FHIR
package — behind a single, FHIR-shaped tool surface. The same `lookup`,
`validate_code`, `expand`, and `translate` tools work uniformly across
every CodeSystem and ValueSet you load.

## Provisioning terminologies

The MCP server reads from the same databases as the FHIR HTTP server.
Build them once with the steps in [Installation](installation.md) —
SNOMED CT (international or UK editions), LOINC, and any HL7 FHIR
package — then point the MCP server at the resulting paths.

The worked examples below assume these specific releases — install the
same versions to reproduce their results:

```shell
# install SNOMED CT International, via MLDS credentials.
clojure -M:run install compact snomed-intl.db \
  --dist ihtsdo.mlds/167 \
  --username 'you@example.com' \
  --password ./mlds-password.txt

# install LOINC 2.82, from the unzipped LOINC Table File CSV archive.
clojure -M:run import loinc-2.82.db /path/to/Loinc_2.82/

# install a number of FHIR R4 terminology packages into fhir-tx.db:
clojure -M:run install compact fhir-tx.db \
  --dist hl7.fhir.r4.core@4.0.1 \
  --dist hl7.terminology.r4@7.0.1 \
  --dist hl7.fhir.us.core@6.1.0 \
  --dist hl7.fhir.uv.ips@2.0.0 \
  --dist hl7.fhir.uv.ips@1.1.0 \
  --dist fhir.tx.support.r4@0.34.0 \
  --dist us.cdc.phinvads@0.12.0 \
  --dist us.nlm.vsac@0.24.0
```

Then run MCP against those three stores:

```shell
clojure -M:run mcp \
  snomed-intl.db \
  loinc-2.82.db \
  fhir-tx.db
```

Obviously *you* don't manually start the MCP server, but tell your LLM (e.g. Claude, ChatGPT/Codex) how to start it.

## Setup

Run the MCP server from a packaged jar or a source checkout (shown
below). Source needs a hades clone and the `clojure` CLI
(`brew install clojure/tools/clojure` on macOS).

### Register the server

Register Hades with Claude Code using `claude mcp add`:

**From a packaged jar** — recommended for regular use; location
independent:

```shell
claude mcp add hades -- \
  java -jar /path/to/hades.jar mcp \
    /path/to/snomed-intl.db \
    /path/to/loinc-2.82.db \
    /path/to/fhir-tx.db
```

**From a source checkout** — picks up code changes on restart; good for
development. `clojure -M:run` must run in the hades source directory
(where `deps.edn` lives), so wrap it to `cd` there first:

```shell
claude mcp add hades -- bash -c 'cd /path/to/hades-source && exec clojure -M:run mcp /path/to/snomed-intl.db /path/to/loinc-2.82.db /path/to/fhir-tx.db'
```

Each positional path after `mcp` is a terminology source (auto-detected):
mix and match SNOMED, FHIR-tx SQLite, LOINC, or directories of FHIR JSON, or FHIR package tarballs.

Verify the registration:

```shell
claude mcp list
```

### Options

```shell
hades mcp <paths...> [--locale LOCALE] [--default URL=VERSION]
```

- `--locale` sets the server default locale (e.g. `en-GB`) for terminologies that support it, such as SNOMED CT.
- `--default URL=VERSION` resolves the bare canonical URL to a specific
  version when multiple providers serve the same URL. Repeatable.

## Tools

Hades exposes ten MCP tools, each a thin wrapper over a FHIR terminology
operation. Inputs use snake_case JSON; outputs are FHIR-shaped result
maps.

### Concept resolution

| Tool | What it does |
|---|---|
| `lookup` | CodeSystem $lookup — resolve `(system, code)` to display, parents, properties, designations |
| `validate_code` | CodeSystem or ValueSet $validate-code — confirm a code exists (and optionally that the supplied display matches) |
| `validate_codeable_concept` | ValueSet $validate-code over a CodeableConcept (multiple codings) |

### ValueSet and ConceptMap

| Tool | What it does |
|---|---|
| `expand` | ValueSet $expand — materialise the concepts in a ValueSet, with optional `filter` text and pagination |
| `translate` | ConceptMap $translate — map a code to one or more target codes via a ConceptMap |
| `subsumes` | CodeSystem $subsumes — test whether one code is an ancestor of another |

### Search and discovery

| Tool | What it does |
|---|---|
| `search_code_systems` | FHIR REST search across registered CodeSystems |
| `search_value_sets` | FHIR REST search across registered ValueSets |
| `search_concept_maps` | FHIR REST search across registered ConceptMaps |
| `service_info` | Counts of registered CodeSystems, ValueSets, and ConceptMaps |

## Resources

Hades exposes four MCP resources — two static guides plus two live
counts — so an LLM can read its operating manual and see what's loaded
without trial-and-error:

| URI | Description |
|---|---|
| `hades://guides/operations` | When to use lookup vs validate-code vs expand vs translate, what each returns, common workflows |
| `hades://guides/value-sets` | How ValueSets are defined (`compose`, `include`/`exclude`, filters), how Hades expands them, SNOMED ECL filter syntax |
| `hades://catalog/code-systems` | Count of registered CodeSystems, live from the providers. Find specific ones with the `search_code_systems` tool |
| `hades://catalog/value-sets` | Count of registered ValueSets, live. Find specific ones with `search_value_sets` |

## Prompts

Four prompts script common workflows by emitting a single user-role
instruction the LLM follows using the available tools:

| Prompt | Arguments | Workflow |
|---|---|---|
| `code_a_term` | `clinical_term`, `system?`, `target_value_set?` | Search → expand with filter → lookup → validate_code against the target VS |
| `build_value_set` | `clinical_domain`, `system?` | Search → expand with filter to seed → expand against draft compose → iterate → emit `ValueSet.compose` JSON |
| `translate_codes` | `source_codes`, `target_system` | service_info to find ConceptMaps → translate per code → report equivalences |
| `explore_concept` | `system`, `code` | lookup with properties → validate_code → expand with filter for related concepts → summarise |

## Example session

With the pinned SNOMED CT International + FHIR package + LOINC 2.82
fixture set above loaded into one Hades process:

> "I have a free-text problem `'type 1 diabetes mellitus'`. Code it, and
> tell me whether it can be bound to the FHIR base condition-code value
> set."

The assistant calls:

1. `expand` against `http://snomed.info/sct?fhir_vs` with
   `filter="type 1 diabetes"` — gets candidates including `46635009`
   (Type 1 diabetes mellitus).
2. `lookup` for `46635009` — confirms display "Type 1 diabetes mellitus"
   and the SNOMED parent hierarchy (Diabetes mellitus → Disorder of
   glucose metabolism).
3. `validate_code` against the FHIR ValueSet
   `http://hl7.org/fhir/ValueSet/condition-code` with the SNOMED code —
   returns `result: true`.

> "Now translate it to ICD-10, if the loaded SNOMED edition includes a
> usable map for this code."

4. `translate` with `code=46635009` and
   `target=http://hl7.org/fhir/sid/icd-10` — dispatches to SNOMED's
   built-in ICD-10 map when that map is present in the loaded SNOMED
   release. If the map or the requested source code is absent, Hades
   returns a structured `result=false` response rather than inventing a
   code.

> "And give me a draft ValueSet for all clinical findings about diabetes."

5. `expand` against `http://hl7.org/fhir/ValueSet/clinical-findings` (a
   FHIR R4 base ValueSet that filters SNOMED clinical findings) with
   `filter="diabetes"` — returns a version-dependent set of SNOMED
   clinical findings matching "diabetes". The assistant proposes a
   tighter compose block based on `is-a 73211009 |Diabetes mellitus|`
   and uses `expand` again to preview.

## LOINC and ConceptMap examples

The same pinned fixture set also demonstrates multi-terminology
translation:

| Tool call | Expected result | Why it matters |
|---|---|---|
| `translate` with `url=http://hl7.org/fhir/uv/ips/ConceptMap/loinc-pregnancy-status-to-snomed-ct-uv-ips`, `system=http://loinc.org`, `code=LA15173-0` | `SNOMED#77386006 Pregnant` | Canonical ConceptMap URL lookup. |
| `translate` with `system=http://loinc.org`, `target=http://snomed.info/sct`, `code=LA18976-3` | `SNOMED#449868002 Smokes tobacco daily` | Code-disambiguated lookup across LOINC-answer ConceptMaps. |
| `validate_code` with `url=http://hl7.org/fhir/uv/ips/ValueSet/current-smoking-status-uv-ips`, `value_set_version=2.0.0`, `system=http://snomed.info/sct`, `code=449868002` | `result=true` | Validates the translated SNOMED code against the SNOMED-based IPS ValueSet. |
| `translate` with `url=http://loinc.org/cm/map-to`, `system=http://loinc.org`, `target=http://loinc.org`, `code=1009-0` | `LOINC#1007-4` | LOINC `MapTo` replacement mapping for deprecated/discouraged source terms. |
| `translate` with `url=http://loinc.org/cm/map-to`, `system=http://loinc.org`, `target=http://loinc.org`, `code=4548-4` | `result=false` | `MapTo` is not general analyte equivalence for active lab codes. |
| `translate` with `url=http://loinc.org/cm/part-related-code-mapping/http%3A%2F%2Fsnomed.info%2Fsct`, `system=http://loinc.org`, `code=LP14635-4` | `SNOMED#67079006 Glucose (substance)`, equivalence `narrower` | LOINC part-to-SNOMED mapping while preserving relationship semantics. |

When several ConceptMaps share the same source and target systems,
Hades uses the source code as an additional discriminator. It translates
only when one candidate contains the code, or when all matching
candidates produce the same target set. If candidates disagree, Hades
preserves the ambiguity and returns a structured failure instead of
guessing clinically.

The same `lookup` and `validate_code` tools work against LOINC, the UK
SNOMED dm+d drug extension, or any HL7 FHIR package — the LLM doesn't
need to know which engine is behind each system. Hades dispatches by
canonical URL.

## Why hades MCP rather than (or alongside) hermes MCP?

Hermes' MCP is the right tool when your work is inside SNOMED CT — it
exposes 29 SNOMED-specific tools (ECL expansion, MRCM checks, refset
membership, post-coordination) that hades cannot match at the FHIR
layer.

Hades MCP is the right tool when:

- Your work spans multiple terminologies (SNOMED + LOINC + FHIR packages).
- You need to validate codes against published FHIR ValueSets (US Core,
  IPS, CDC PHIN-VADS, etc.).
- You want FHIR-shaped operations: `$lookup`, `$validate-code`, `$expand`,
  `$translate`, `$subsumes` — the canonical surface every FHIR consumer
  expects.
- You're building an LLM agent that should produce FHIR-conformant
  output without knowing whether the underlying CodeSystem is SNOMED,
  LOINC, or a CSV-extracted CodeSystem.

Both can run in the same Claude Code or Claude Desktop install — register
one as `hermes` and the other as `hades`, and let the LLM pick.
