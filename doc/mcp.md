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

## Setup

The recommended setup runs Hades from a source checkout via the
Clojure CLI. Restarting the MCP picks up source changes immediately —
no jar rebuild, no stable-name dance, no chance of running yesterday's
binary by accident. Prerequisites: a hades source clone and the
`clojure` CLI (`brew install clojure/tools/clojure` on macOS).

### Claude Desktop

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hades": {
      "command": "clojure",
      "args": [
        "-M:run", "mcp",
        "/path/to/snomed.db",
        "/path/to/fhir.db",
        "/path/to/loinc.db"
      ],
      "cwd": "/path/to/hades-source"
    }
  }
}
```

Each positional path after `mcp` is a terminology source (auto-detected):
mix and match SNOMED, FHIR-tx SQLite, LOINC, or directories of FHIR JSON.

### Claude Code

Same shape. `claude mcp add-json` accepts the JSON object directly:

```shell
claude mcp add-json --scope user hades '{
  "command": "clojure",
  "args": ["-M:run", "mcp", "/path/to/snomed.db", "/path/to/loinc.db"],
  "cwd": "/path/to/hades-source"
}'
```

Use `--scope project` to scope to one project, or `--scope local` for a
local-only configuration. To verify:

```shell
claude mcp list
```

### Running from a packaged jar

If you don't want a source checkout — a deployment scenario, or a
locked-down host — point at the uberjar instead. Build it with
`clojure -T:build uber`, which writes `target/hades-<version>.jar`. The
`release` task additionally copies that to `target/hades.jar` (a
versionless mirror) but only as part of publishing a GitHub release; for
local use you'll typically reference the versioned filename directly:

```json
{
  "mcpServers": {
    "hades": {
      "command": "java",
      "args": [
        "-jar", "/path/to/hades-2.0.207.jar", "mcp",
        "/path/to/snomed.db",
        "/path/to/loinc.db"
      ]
    }
  }
}
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
| `find_matches` | CodeSystem $find-matches — search-as-you-type against a CodeSystem (Lucene for SNOMED, FTS for FHIR-tx) |
| `search_code_systems` | FHIR REST search across registered CodeSystems |
| `search_value_sets` | FHIR REST search across registered ValueSets |
| `service_info` | List every CodeSystem, ValueSet, and ConceptMap installed on the server, with totals |

## Resources

Hades exposes four MCP resources — two static guides plus two live
catalogues — so an LLM can read its operating manual and discover what's
loaded without trial-and-error:

| URI | Description |
|---|---|
| `hades://guides/operations` | When to use lookup vs validate-code vs expand vs translate, what each returns, common workflows |
| `hades://guides/value-sets` | How ValueSets are defined (`compose`, `include`/`exclude`, filters), how Hades expands them, SNOMED ECL filter syntax |
| `hades://catalog/code-systems` | Every CodeSystem the server can answer for, with canonical URL and version. Live from the providers — updates as overlays land |
| `hades://catalog/value-sets` | Every ValueSet the server can answer for. Live |

## Prompts

Four prompts script common workflows by emitting a single user-role
instruction the LLM follows using the available tools:

| Prompt | Arguments | Workflow |
|---|---|---|
| `code_a_term` | `clinical_term`, `system?`, `target_value_set?` | Search → find_matches → lookup → validate_code against the target VS |
| `build_value_set` | `clinical_domain`, `system?` | Search → find_matches to seed → expand against draft compose → iterate → emit `ValueSet.compose` JSON |
| `translate_codes` | `source_codes`, `target_system` | service_info to find ConceptMaps → translate per code → report equivalences |
| `explore_concept` | `system`, `code` | lookup with properties → validate_code → find_matches for siblings → summarise |

## Example session

With SNOMED CT International + FHIR R4 packages + LOINC 2.81 loaded
into one Hades process:

> "I have a free-text problem `'type 1 diabetes mellitus'`. Code it, and
> tell me whether it can be bound to the FHIR base condition-code value
> set."

The assistant calls:

1. `find_matches` against `http://snomed.info/sct` with `query="type 1
   diabetes"` — gets 154 candidates including `46635009` (Type 1 diabetes
   mellitus).
2. `lookup` for `46635009` — confirms display "Type 1 diabetes mellitus"
   and the SNOMED parent hierarchy (Diabetes mellitus → Disorder of
   glucose metabolism).
3. `validate_code` against the FHIR ValueSet
   `http://hl7.org/fhir/ValueSet/condition-code` with the SNOMED code —
   returns `result: true`.

> "Now translate it to ICD-10."

4. `translate` with `code=46635009` and
   `target=http://hl7.org/fhir/sid/icd-10` — returns
   `E10.9` ("Type 1 diabetes mellitus, without complications") via
   SNOMED's built-in ICD-10 ConceptMap, with equivalence `relatedto`.

> "And give me a draft ValueSet for all clinical findings about diabetes."

5. `expand` against `http://hl7.org/fhir/ValueSet/clinical-findings` (a
   FHIR R4 base ValueSet that filters SNOMED clinical findings) with
   `filter="diabetes"` — returns 631 SNOMED concepts, all clinical
   findings, all matching "diabetes". The assistant proposes a tighter
   compose block based on `is-a 73211009 |Diabetes mellitus|` and uses
   `expand` again to preview.

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
