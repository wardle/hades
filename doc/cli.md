# CLI reference

Hades owns the full lifecycle for SNOMED CT and FHIR conformance
packages — discovery, download, import, indexing, compaction and
serving — through a single binary. Examples below use
`java -jar hades.jar`; from a source checkout, replace with `clj -M:run`.

## Commands

| Command | Purpose |
|---------|---------|
| `serve <paths…> [--port N] [--bind-address A] [--default URL=VERSION]… [--locale LOCALE]` | Start the FHIR server. Each path opens a Hermes SNOMED store, a Hades SQLite container, or a directory of FHIR JSON resources (auto-detected). Use `--default URL=VERSION` (repeatable) when multiple providers claim the same canonical URL — bare-URL requests resolve to the chosen version. `--locale` sets the server default locale (e.g. `en-GB`) for terminologies that support it, such as SNOMED CT. |
| `mcp <paths…> [--default URL=VERSION]… [--locale LOCALE]` | Start a Model Context Protocol server over stdio, exposing FHIR terminology operations as MCP tools. Source paths and `--default` / `--locale` work identically to `serve`. See [MCP server](mcp.md). |
| `install <dest-db> --dist <id>… [--no-index] [--cache-dir DIR]` | Download and import one or more distributions (SNOMED CT or FHIR package) into the destination database. Auto-indexes when done; pass `--no-index` to skip (for layered loads). Distribution ids may carry `@<version>`. |
| `import <dest-db> <sources…> [--no-index]` | Import sources into a destination database. Auto-detects RF2 (SNOMED), LOINC release archive, or FHIR JSON / NPM-package directory. Auto-indexes when done. |
| `list <paths…>` | List importable files under given paths. |
| `available [--dist <id>…]` | List installable terminologies, or releases/versions for the given ids. |
| `index <paths…>` | Rebuild search indices on each database. Useful for explicit recovery or to finish a layered load. Release sources are silently skipped. |
| `compact <paths…>` | Compact the underlying store (LMDB compact for Hermes, VACUUM for SQLite). Optional space optimisation. |
| `status <paths…> [--format json\|edn] [--modules] [--refsets]` | Show database status. `--modules` and `--refsets` add SNOMED-specific detail to the report. |

Commands can be chained on a single command line and execute in the
order given, sharing positional paths and flags — for example
`install compact snomed.db --dist ihtsdo.mlds/167 --username you@example.com --password ./pw.txt`.
`index` and `compact` silently skip release source paths so they're
safe to chain after `import`.

Per-command help is available with `--help`:

```shell
java -jar hades.jar --help install
```

## Examples

```shell
# Run a FHIR server on port 8080 across SNOMED, LOINC and FHIR packages
java -jar hades.jar serve snomed.db loinc.db fhir.db --port 8080

# Download, import and index SNOMED CT International edition (MLDS)
java -jar hades.jar install snomed.db \
    --dist ihtsdo.mlds/167@2025-02-01 \
    --username 'you@example.com' --password ./mlds-password.txt

# UK alternative: SNOMED CT UK monolith edition (TRUD)
java -jar hades.jar install snomed.db \
    --dist uk.nhs/sct-monolith \
    --api-key ./trud-api-key.txt

# Install one or more FHIR packages from packages.fhir.org (no auth)
java -jar hades.jar install fhir.db --dist hl7.fhir.r4.core@4.0.1

# Import an unzipped RF2 release into a destination database
java -jar hades.jar import snomed.db /path/to/RF2

# Preview the importable files under a path
java -jar hades.jar list /path/to/RF2

# Discover available terminologies and versions
java -jar hades.jar available
java -jar hades.jar available --dist ihtsdo.mlds/167 \
    --username 'you@example.com' --password ./mlds-password.txt
java -jar hades.jar available --dist hl7.fhir.r4.core

# Maintenance
java -jar hades.jar index   snomed.db
java -jar hades.jar compact snomed.db
java -jar hades.jar status  snomed.db --format json

# Start an MCP server over stdio for AI assistants
java -jar hades.jar mcp snomed.db loinc.db
```

See [Installation](installation.md) for full setup recipes covering
SNOMED CT, LOINC and FHIR packages.
