# FHIR Terminology SQLite Container — Schema v1

A provider-neutral SQLite file format for FHIR terminology resources
(`CodeSystem`, `ValueSet`, `ConceptMap`, `NamingSystem`). Any FHIR
terminology server that implements the operations defined here can
read or write a v1 file. Hades is one such server.

This document is the contract.

## Status

- **Schema version**: 1
- **First published**: 2026-05
- **Stability**: stable; breaking changes will bump the schema
  version and ship a migration tool.

## File identification

Every v1 file stamps two values into the SQLite file header:

| Pragma | Value | Meaning |
|---|---|---|
| `application_id` | `0x4654524D` (ASCII `FTRM`, "FHIR TeRMinology") | Identifies the file as a FHIR terminology container, regardless of which server wrote it. |
| `user_version` | `1` | The schema version. |

A reader **MUST** refuse to open a file whose `application_id` does
not match. A reader **MAY** refuse a file whose `user_version` is
greater than the highest schema it implements. A reader **MUST NOT**
silently re-stamp an unrelated SQLite file.

Recommended: every connection sets the following per-connection
pragmas at open time:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
```

`foreign_keys` is per-connection; without it, the FK constraints
declared by the schema are not enforced. `journal_mode = WAL` is
persistent and is the right default for read-heavy serving workloads.

## Multi-version semantics

The container holds an arbitrary number of versions of any resource.
Resource identity is the tuple `(resource_type, url, version)`. The
empty string `''` (not NULL) is used in the `version` column when the
resource has no version, so that primary keys can be enforced cleanly.

Writers **MUST** treat a write to an existing `(resource_type, url,
version)` as a replace: delete every dependent row keyed by that
tuple, then insert fresh.

## Tables

### File-level metadata

```sql
CREATE TABLE tx_meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);

CREATE TABLE tx_resource (
  resource_type TEXT NOT NULL CHECK (resource_type IN ('CodeSystem','ValueSet','ConceptMap')),
  url           TEXT NOT NULL,
  version       TEXT NOT NULL DEFAULT '',
  concept_count INTEGER,
  imported_at   TEXT NOT NULL,
  PRIMARY KEY (resource_type, url, version)
);
```

`tx_meta` is a free-form key/value map. Writers may use it to record
the loader type, build timestamp, schema version (echoed for
diagnostics), source SHA, etc. Readers **MUST** ignore unknown keys.

`tx_resource` is the catalogue of every (CodeSystem, ValueSet,
ConceptMap) the file holds. Boot drivers enumerate the catalogue to
register providers.

### CodeSystem

```sql
CREATE TABLE codesystem_meta (
  url               TEXT NOT NULL,
  version           TEXT NOT NULL DEFAULT '',
  case_sensitive    INTEGER CHECK (case_sensitive IN (0,1)),
  hierarchy_meaning TEXT,
  content           TEXT,             -- 'complete'|'fragment'|'supplement'|'example'|'not-present'
  supplements       TEXT,             -- canonical of base CS when content='supplement'
  status            TEXT,
  experimental      INTEGER CHECK (experimental IN (0,1)),
  name              TEXT,
  title             TEXT,
  description       TEXT,
  publisher         TEXT,
  jurisdiction      TEXT,
  standards_status  TEXT,
  property_defs     TEXT,             -- JSON: CodeSystem.property[*]
  filter_defs       TEXT,             -- JSON: CodeSystem.filter[*]
  metadata          TEXT,             -- JSON pass-through
  PRIMARY KEY (url, version)
) WITHOUT ROWID;
```

Behavioural fields (`case_sensitive`, `hierarchy_meaning`, `content`,
`supplements`, `status`, `experimental`, `publisher`, `jurisdiction`)
live as columns so they can be queried and indexed. Everything else
that round-trips as raw FHIR JSON (copyright, contact, useContext,
identifier, language extensions, …) lives in `metadata` as a string-
keyed JSON document.

```sql
CREATE TABLE concept (
  cs_url          TEXT NOT NULL,
  cs_version      TEXT NOT NULL DEFAULT '',
  code            TEXT NOT NULL,
  display         TEXT,
  definition      TEXT,
  inactive        INTEGER CHECK (inactive IN (0,1)),
  abstract        INTEGER CHECK (abstract IN (0,1)),
  not_selectable  INTEGER CHECK (not_selectable IN (0,1)),
  status          TEXT,
  FOREIGN KEY (cs_url, cs_version) REFERENCES codesystem_meta(url, version)
);
CREATE UNIQUE INDEX concept_pk ON concept(cs_url, cs_version, code);
```

The four "well-known" property codes (`inactive`, `abstract`,
`notSelectable`, `status`) are projected onto concept columns at
insert time so they're cheap to query. They MAY also be retained as
rows in `concept_property` for round-trip lookup.

`concept` is a rowid table (no `WITHOUT ROWID`) so the FTS5 virtual
table can use external content with `content_rowid='rowid'`.

```sql
CREATE TABLE concept_parent (
  cs_url      TEXT NOT NULL,
  cs_version  TEXT NOT NULL DEFAULT '',
  code        TEXT NOT NULL,
  parent_code TEXT NOT NULL,
  PRIMARY KEY (cs_url, cs_version, code, parent_code)
) WITHOUT ROWID;
CREATE INDEX concept_parent_rev
  ON concept_parent(cs_url, cs_version, parent_code);
```

One row per `(child, parent)` edge. Polyhierarchy is supported
natively. Single-parent CodeSystems (LOINC, ICD-10) are the
degenerate case.

```sql
CREATE TABLE concept_property (
  cs_url, cs_version, code, prop_code TEXT NOT NULL,
  value_type  TEXT NOT NULL CHECK (value_type IN
                ('string','code','integer','boolean','decimal','dateTime','Coding','Quantity')),
  value_str   TEXT,
  value_int   INTEGER,
  value_bool  INTEGER,
  value_dec   REAL,
  value_coding_system  TEXT,
  value_coding_code    TEXT,
  value_coding_display TEXT,
  value_quantity       TEXT,        -- JSON: {value, unit, system, code, comparator}
  FOREIGN KEY (cs_url, cs_version, code) REFERENCES concept(cs_url, cs_version, code)
);
CREATE UNIQUE INDEX cp_uniq ON concept_property(
  cs_url, cs_version, code, prop_code, value_type,
  COALESCE(value_str, ''),  COALESCE(value_int, 0),
  COALESCE(value_bool, -1), COALESCE(value_dec, 0.0),
  COALESCE(value_coding_system, ''), COALESCE(value_coding_code, ''));
CREATE INDEX cp_pushdown
  ON concept_property(cs_url, cs_version, prop_code, value_str);
```

Polymorphic value columns mirror FHIR's `value[x]` convention. The
unique index uses `COALESCE` because SQLite treats `NULL ≠ NULL` in
unique indexes by default, which would otherwise defeat dedup across
the polymorphic NULLs.

```sql
CREATE TABLE concept_designation (
  cs_url, cs_version, code TEXT NOT NULL,
  language    TEXT,
  use_system  TEXT,
  use_code    TEXT,
  use_display TEXT,
  value       TEXT NOT NULL,
  extension   TEXT,                -- JSON, when round-trip is needed
  FOREIGN KEY (cs_url, cs_version, code) REFERENCES concept(cs_url, cs_version, code)
);
CREATE UNIQUE INDEX cd_uniq ON concept_designation(
  cs_url, cs_version, code,
  COALESCE(language, ''), COALESCE(use_system, ''),
  COALESCE(use_code, ''), value);
CREATE INDEX cd_language ON concept_designation(cs_url, cs_version, language);
CREATE INDEX cd_use      ON concept_designation(cs_url, cs_version, use_code);
```

```sql
CREATE TABLE concept_ancestor (
  cs_url, cs_version, ancestor_code, descendent_code TEXT NOT NULL,
  depth INTEGER NOT NULL,
  PRIMARY KEY (cs_url, cs_version, ancestor_code, descendent_code)
) WITHOUT ROWID;
CREATE INDEX ca_descendent
  ON concept_ancestor(cs_url, cs_version, descendent_code);
```

The transitive closure of `concept_parent`. `depth` is the **shortest
path** (in a polyhierarchy a `(ancestor, descendent)` pair has one
row recording the minimum depth). Writers **MUST** rebuild the
closure for any CodeSystem they write; readers **MAY** trust the
closure as authoritative for `is-a` / `descendent-of` semantics.

### Full-text search

```sql
CREATE VIRTUAL TABLE concept_fts USING fts5(
  display, definition,
  content='concept', content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2');

CREATE VIRTUAL TABLE designation_fts USING fts5(
  value, language UNINDEXED, use_code UNINDEXED,
  content='concept_designation', content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2');
```

External-content FTS5 over `concept` and `concept_designation`.
Writers **MUST** populate by issuing `INSERT INTO ... ('rebuild')`
after the source rows land; per-row triggers are too expensive during
bulk load. Diacritics are folded so `cafe` matches `café`.

Readers querying with text **SHOULD** join FTS via:

```sql
SELECT c.... FROM concept c
JOIN concept_fts f ON f.rowid = c.rowid
WHERE c.cs_url = ? AND c.cs_version = ? AND f.concept_fts MATCH ?
ORDER BY f.rank LIMIT ?
```

`ORDER BY f.rank` matters — without it, a `LIMIT N` cuts off the
most relevant matches rather than the least.

### ValueSet

```sql
CREATE TABLE valueset (
  url, version TEXT NOT NULL,
  name, title, status TEXT,
  experimental INTEGER CHECK (experimental IN (0,1)),
  publisher, jurisdiction, description TEXT,
  metadata TEXT,                       -- JSON pass-through
  compose  TEXT,                       -- JSON
  PRIMARY KEY (url, version)
) WITHOUT ROWID;

CREATE TABLE valueset_expansion (
  vs_url, vs_version TEXT NOT NULL,
  params_hash TEXT NOT NULL,
  expansion   TEXT NOT NULL,            -- JSON: ValueSet.expansion
  computed_at TEXT NOT NULL,
  expires_at  TEXT,
  PRIMARY KEY (vs_url, vs_version, params_hash),
  FOREIGN KEY (vs_url, vs_version) REFERENCES valueset(url, version)
) WITHOUT ROWID;
```

`compose` is the raw FHIR JSON `ValueSet.compose`; readers expand it
at request time. `valueset_expansion` is an optional cache — readers
**MAY** populate it lazily; absence is not an error.

### ConceptMap

```sql
CREATE TABLE conceptmap (
  url, version TEXT NOT NULL,
  name, title, status TEXT,
  experimental INTEGER CHECK (experimental IN (0,1)),
  source_uri, source_version, target_uri, target_version TEXT,
  unmapped_mode TEXT CHECK (unmapped_mode IS NULL
                            OR unmapped_mode IN ('provided','fixed','other-map')),
  unmapped_code, unmapped_url TEXT,
  metadata TEXT,                     -- JSON pass-through
  PRIMARY KEY (url, version)
) WITHOUT ROWID;

CREATE TABLE conceptmap_element (
  cm_url, cm_version TEXT NOT NULL,
  group_idx INTEGER NOT NULL,
  source_system, source_version, target_system, target_version TEXT,
  source_code, source_display, target_code, target_display TEXT,
  equivalence TEXT NOT NULL,
  comment TEXT,
  depends_on, product TEXT,           -- JSON
  FOREIGN KEY (cm_url, cm_version) REFERENCES conceptmap(url, version)
);
CREATE UNIQUE INDEX cme_uniq ON conceptmap_element(
  cm_url, cm_version, group_idx,
  COALESCE(source_system, ''), source_code,
  COALESCE(target_system, ''), target_code, equivalence);
CREATE INDEX cme_fwd
  ON conceptmap_element(cm_url, cm_version, source_system, source_code);
CREATE INDEX cme_rev
  ON conceptmap_element(cm_url, cm_version, target_system, target_code);
```

`equivalence` carries the FHIR R4 `ConceptMapEquivalence` code; R5
servers may downstream-translate to the newer `relationship` codes
on the way out.

### NamingSystem

```sql
CREATE TABLE naming_system (
  url      TEXT NOT NULL PRIMARY KEY,
  name     TEXT,
  status   TEXT,
  kind     TEXT,                       -- 'codesystem'|'identifier'|'root'
  metadata TEXT
) WITHOUT ROWID;

CREATE TABLE naming_system_id (
  ns_url          TEXT NOT NULL,
  identifier_type TEXT NOT NULL CHECK (identifier_type IN ('oid','uri','uuid','other')),
  value           TEXT NOT NULL,
  preferred       INTEGER CHECK (preferred IN (0,1)),
  PRIMARY KEY (ns_url, identifier_type, value),
  FOREIGN KEY (ns_url) REFERENCES naming_system(url)
) WITHOUT ROWID;
CREATE INDEX nsi_value ON naming_system_id(identifier_type, value);
```

Servers use `naming_system_id` to resolve OID/URN/URI aliases to a
canonical CodeSystem URL. Lookup contract:

```sql
SELECT ns_url FROM naming_system_id
WHERE value = ?
ORDER BY (preferred IS NULL), preferred DESC LIMIT 1
```

Returns the canonical URL when an alias matches, or no rows. The
caller substitutes the canonical for the alias and dispatches as
usual. A request whose `system` is already canonical is unchanged.

## Build-time recommendations

Writers performing bulk loads **SHOULD** speed up the build with:

```sql
PRAGMA foreign_keys = OFF;            -- for the duration of the build
PRAGMA journal_mode = MEMORY;
PRAGMA synchronous  = OFF;
PRAGMA temp_store   = MEMORY;
```

…wrapping all inserts in one transaction. After commit, restore the
runtime defaults (`foreign_keys = ON`, `journal_mode = WAL`,
`synchronous = NORMAL`) and run `ANALYZE` so the planner has fresh
statistics. FTS rebuild (`INSERT INTO concept_fts ('rebuild')` and
`INSERT INTO designation_fts ('rebuild')`) happens after concept and
designation rows land.

The build is responsible for FK invariants by ordering: insert
`codesystem_meta` → `concept` → `concept_parent` / `concept_property`
/ `concept_designation` → `concept_ancestor`. The same rule applies
to `valueset` → `valueset_expansion` and `conceptmap` →
`conceptmap_element`.

## Round-trip semantics

A reader that writes a resource it just read **MUST** preserve:

- All FHIR JSON pass-through fields stored in `metadata` columns.
- Designation `extension` JSON.
- Property `valueQuantity` JSON.
- ConceptMap element `depends_on` and `product` JSON.

A reader **MAY** drop fields that are not represented in the schema,
provided it documents what it drops; doing so means the file is no
longer a faithful round-trip of the original FHIR JSON.

## Compatibility checks before adopting

A new server adopting the v1 format should verify:

1. It opens the file and reads `tx_resource` — boot succeeds without
   error.
2. `cs-lookup` for a known concept returns `display`, `inactive`,
   `abstract`, `not_selectable`, parents, children, properties,
   designations.
3. `cs-validate-code` honours `displayLanguage` against
   `concept_designation.language`.
4. `cs-find-matches` uses `concept_fts` (or `designation_fts`) for
   text and `cp_pushdown` / `concept_ancestor` for filters.
5. `vs-expand` parses `valueset.compose` and (optionally) caches
   into `valueset_expansion`.
6. `cm-translate` honours `cm_url`, `cm_version`, `source_system`,
   `source_code` (forward) and `target_system`, `target_code`
   (reverse).
7. `resolve-system` for an OID hits `naming_system_id` and returns
   the canonical `ns_url`.

## Reference implementation

The Hades server (https://github.com/wardle/hades) implements both a
reader (`com.eldrix.hades.impl.sqlite.provider`) and a writer
(`com.eldrix.hades.impl.index.sqlite`). The DDL lives in
[`resources/com/eldrix/hades/sqlite/schema-v1.sql`](../resources/com/eldrix/hades/sqlite/schema-v1.sql).
