# FTRM — FHIR Terminology Container, schema v1

An open, provider-neutral file format for distributing and serving FHIR
terminology resources (`CodeSystem`, `ValueSet`, `ConceptMap`,
`NamingSystem`) inside a single SQLite database.

A v1 file is portable: any conforming implementation can read a file
written by any other, regardless of the server, language, or build
tooling involved. The format is named after its on-disk magic — the
SQLite header carries `application_id = 0x4654524D` (ASCII `FTRM`,
"FHIR TeRMinology").

This document is the contract.

---

## 1. Status of this specification

| | |
|---|---|
| Specification name | FTRM — FHIR Terminology Container |
| Schema version | **1** |
| First published | 2026-05 |
| Stability | Stable. Breaking changes will bump the schema version and ship migration tooling. |
| Reference implementation | Hades — <https://github.com/wardle/hades> |
| Reference DDL | [`resources/com/eldrix/hades/sqlite/schema-v1.sql`](../resources/com/eldrix/hades/sqlite/schema-v1.sql) |
| Licence | The schema, identifiers and prose of this specification are licensed under CC0. Implementations may use any licence. |

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**,
**SHALL NOT**, **SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **MAY** and
**OPTIONAL** in this document are to be interpreted as described in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

## 2. Goals and non-goals

**Goals.**

- A single-file, append-rebuildable distribution unit for FHIR
  terminology — small enough to ship over HTTP, big enough to hold
  multi-million-concept code systems with full hierarchy and search.
- Schema you can query directly with stock SQLite tooling. No bespoke
  binary blobs, no opaque indexes, no implementation-defined locks.
- Round-trip fidelity for the FHIR fields that matter for terminology
  operations, with deliberate JSON pass-through slots for the rest.
- A clear separation between *behavioural* fields (queryable columns)
  and *display / round-trip* fields (JSON pass-through).

**Non-goals.**

- A general-purpose FHIR resource store. FTRM only stores the four
  terminology resources above.
- A wire protocol. FTRM is a file format; servers expose it via the
  FHIR terminology REST API as they see fit.
- A versioned operational store. Writers rebuild, they do not patch in
  place; the file is a snapshot, not a journal.

## 3. File identification

Every conforming v1 file **MUST** stamp two values into the SQLite
file header:

| Pragma | Value | Meaning |
|---|---|---|
| `application_id` | `0x4654524D` (ASCII `FTRM`) | Identifies the file as an FTRM container, regardless of which server wrote it. |
| `user_version` | `1` | The schema version. |

A reader **MUST** refuse to open a file whose `application_id` does
not match. A reader **MAY** refuse a file whose `user_version` is
greater than the highest schema version it implements. A writer
**MUST NOT** stamp `application_id` onto a SQLite file it did not
itself initialise as an FTRM container.

Every connection **SHOULD** apply the following per-connection
pragmas at open time:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
```

`foreign_keys` is per-connection; without it, the foreign-key
constraints declared by the schema are not enforced. `journal_mode =
WAL` is persistent on the database and is the right default for
read-heavy serving workloads.

## 4. Versioning model

The container holds an arbitrary number of versions of any resource.
Resource identity is the tuple `(resource_type, url, version)`. The
empty string `''` (not NULL) is used in the `version` column when the
resource has no version, so primary-key constraints behave cleanly.

Writers **MUST** treat a write to an existing `(resource_type, url,
version)` as a replace: delete every dependent row keyed by that
tuple, then insert fresh.

Readers **MUST NOT** assume a single "current" version; selection
between versions of the same `url` is the caller's responsibility
(typically driven by the FHIR `system-version` /
`force-system-version` parameters).

## 5. Schema

All DDL in this section is the normative form. The reference DDL
file in the implementation repository is a verbatim copy.

### 5.1 File-level metadata

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

`tx_meta` is a free-form key/value map. Writers **MAY** record loader
provenance, build timestamp, source SHA, the schema version (echoed
for diagnostic ease), and similar diagnostic data. Readers **MUST**
ignore unknown keys. Reserved keys with assigned semantics will be
listed in future revisions; at v1 there are none.

`tx_resource` is the catalogue of every resource the file holds.
Implementations enumerate this table at boot to register providers.

### 5.2 CodeSystem

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
are columns so they can be queried and indexed. Everything else that
round-trips as raw FHIR JSON (copyright, contact, useContext,
identifier, language extensions, …) lives in `metadata` as a
string-keyed JSON document.

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

The four well-known property codes (`inactive`, `abstract`,
`notSelectable`, `status`) are projected onto `concept` columns at
insert time so they are cheap to filter on. Writers **MAY** also
retain the same values as rows in `concept_property` for round-trip
lookup; readers **SHOULD** prefer the column when both are present.

`concept` is a rowid-bearing table (no `WITHOUT ROWID`) so the FTS5
virtual table can use external content with `content_rowid='rowid'`.

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
  cs_url      TEXT NOT NULL,
  cs_version  TEXT NOT NULL DEFAULT '',
  code        TEXT NOT NULL,
  prop_code   TEXT NOT NULL,
  value_type  TEXT NOT NULL CHECK (value_type IN
                ('string','code','integer','boolean','decimal','dateTime','Coding','Quantity')),
  value_str            TEXT,
  value_int            INTEGER,
  value_bool           INTEGER CHECK (value_bool IN (0,1)),
  value_dec            REAL,
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
unique indexes by default, which would otherwise defeat de-duplication
across the polymorphic NULLs.

```sql
CREATE TABLE concept_designation (
  cs_url      TEXT NOT NULL,
  cs_version  TEXT NOT NULL DEFAULT '',
  code        TEXT NOT NULL,
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
  cs_url          TEXT NOT NULL,
  cs_version      TEXT NOT NULL DEFAULT '',
  ancestor_code   TEXT NOT NULL,
  descendent_code TEXT NOT NULL,
  depth           INTEGER NOT NULL,
  PRIMARY KEY (cs_url, cs_version, ancestor_code, descendent_code)
) WITHOUT ROWID;
CREATE INDEX ca_descendent
  ON concept_ancestor(cs_url, cs_version, descendent_code);
```

`concept_ancestor` is the transitive closure of `concept_parent`.
`depth` is the **shortest path** — in a polyhierarchy a `(ancestor,
descendent)` pair has exactly one row recording the minimum depth.

Writers **MUST** rebuild the closure for any CodeSystem they write,
so its content is consistent with `concept_parent`. Readers **MAY**
trust the closure as authoritative for `is-a` and `descendent-of`
semantics without re-deriving it from `concept_parent`.

### 5.3 Full-text search

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

External-content FTS5 indexes over `concept` and
`concept_designation`. Writers **MUST** populate them by issuing
`INSERT INTO <table> (<table>) VALUES('rebuild')` after the source
rows land; per-row triggers are too expensive during bulk load.
Diacritic folding (`remove_diacritics 2`) is part of the contract,
so `cafe` matches `café` regardless of where the file was built.

Readers performing text search **SHOULD** join FTS via:

```sql
SELECT c.* FROM concept c
JOIN concept_fts f ON f.rowid = c.rowid
WHERE c.cs_url = ? AND c.cs_version = ? AND f.concept_fts MATCH ?
ORDER BY f.rank LIMIT ?
```

`ORDER BY f.rank` is mandatory in user-facing search. Without it, a
`LIMIT N` cuts off the most relevant matches rather than the least.

### 5.4 ValueSet

```sql
CREATE TABLE valueset (
  url           TEXT NOT NULL,
  version       TEXT NOT NULL DEFAULT '',
  name          TEXT,
  title         TEXT,
  status        TEXT,
  experimental  INTEGER CHECK (experimental IN (0,1)),
  publisher     TEXT,
  jurisdiction  TEXT,
  description   TEXT,
  metadata      TEXT,                  -- JSON pass-through
  compose       TEXT,                  -- JSON: ValueSet.compose
  PRIMARY KEY (url, version)
) WITHOUT ROWID;

CREATE TABLE valueset_expansion (
  vs_url       TEXT NOT NULL,
  vs_version   TEXT NOT NULL DEFAULT '',
  params_hash  TEXT NOT NULL,
  expansion    TEXT NOT NULL,          -- JSON: ValueSet.expansion
  computed_at  TEXT NOT NULL,
  expires_at   TEXT,
  PRIMARY KEY (vs_url, vs_version, params_hash),
  FOREIGN KEY (vs_url, vs_version) REFERENCES valueset(url, version)
) WITHOUT ROWID;
```

`compose` is the raw FHIR JSON `ValueSet.compose`; readers expand it
at request time. `valueset_expansion` is an **optional** cache —
readers **MAY** populate it lazily, and the absence of an entry is
not an error. The `params_hash` key is implementation-defined; it
**MUST** be stable for a given normalised parameter set so cache hits
are reproducible. Writers **MUST NOT** rely on cache rows being
present.

### 5.5 ConceptMap

```sql
CREATE TABLE conceptmap (
  url            TEXT NOT NULL,
  version        TEXT NOT NULL DEFAULT '',
  name           TEXT,
  title          TEXT,
  status         TEXT,
  experimental   INTEGER CHECK (experimental IN (0,1)),
  source_uri     TEXT,
  source_version TEXT,
  target_uri     TEXT,
  target_version TEXT,
  unmapped_mode  TEXT CHECK (unmapped_mode IS NULL
                             OR unmapped_mode IN ('provided','fixed','other-map')),
  unmapped_code  TEXT,
  unmapped_url   TEXT,
  metadata       TEXT,                 -- JSON pass-through
  PRIMARY KEY (url, version)
) WITHOUT ROWID;

CREATE TABLE conceptmap_element (
  cm_url         TEXT NOT NULL,
  cm_version     TEXT NOT NULL DEFAULT '',
  group_idx      INTEGER NOT NULL,
  source_system  TEXT,
  source_version TEXT,
  target_system  TEXT,
  target_version TEXT,
  source_code    TEXT NOT NULL,
  source_display TEXT,
  target_code    TEXT NOT NULL,
  target_display TEXT,
  equivalence    TEXT NOT NULL,
  comment        TEXT,
  depends_on     TEXT,                 -- JSON
  product        TEXT,                 -- JSON
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

`equivalence` carries the FHIR R4 `ConceptMapEquivalence` code. R5
servers reading a v1 file **MAY** translate to the newer
`relationship` codes when serving, but **MUST NOT** rewrite the
column on disk — a v1 file is an R4-flavoured snapshot.

`group_idx` preserves the input ordering of `ConceptMap.group[*]` so
that round-tripping the resource yields the same structure. A reader
that does not need the group structure **MAY** ignore `group_idx`.

### 5.6 NamingSystem

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

Servers use `naming_system_id` to resolve OID, URN and URI aliases
to a canonical CodeSystem URL. The required lookup contract is:

```sql
SELECT ns_url FROM naming_system_id
WHERE value = ?
ORDER BY (preferred IS NULL), preferred DESC LIMIT 1
```

This returns the canonical URL when an alias matches, or no rows.
The caller substitutes the canonical URL for the alias and dispatches
as usual. A request whose `system` is already canonical is unchanged.

URN forms (`urn:oid:<oid>`, `urn:uuid:<uuid>`) are stored as the
*bare* identifier in the `value` column. A reader handling user input
**SHOULD** strip the `urn:oid:` / `urn:uuid:` prefix before lookup if
the first lookup misses.

## 6. Build-time recommendations

The schema is identical at build and serve time, but bulk loads
benefit from temporarily relaxed pragmas:

```sql
PRAGMA foreign_keys = OFF;            -- for the duration of the build
PRAGMA journal_mode = MEMORY;
PRAGMA synchronous  = OFF;
PRAGMA temp_store   = MEMORY;
```

…wrapping all inserts in a single transaction. After commit, restore
the runtime defaults (`foreign_keys = ON`, `journal_mode = WAL`,
`synchronous = NORMAL`) and run `ANALYZE` so the planner has fresh
statistics. The FTS rebuild — `INSERT INTO concept_fts (concept_fts)
VALUES ('rebuild')` and the equivalent for `designation_fts` —
happens after concept and designation rows have landed.

The build is responsible for foreign-key invariants by ordering of
inserts:

1. `codesystem_meta`
2. `concept`
3. `concept_parent`, `concept_property`, `concept_designation`
4. `concept_ancestor`

The same rule applies to `valueset` → `valueset_expansion` and
`conceptmap` → `conceptmap_element`.

## 7. Round-trip semantics

A reader that writes a resource it just read **MUST** preserve:

- All FHIR JSON pass-through fields stored in `metadata` columns.
- `concept_designation.extension` JSON.
- `concept_property.value_quantity` JSON.
- `conceptmap_element.depends_on` and `conceptmap_element.product`
  JSON.

A reader **MAY** drop fields that are not represented in the schema,
provided it documents what it drops. Doing so means the file is no
longer a faithful round-trip of the original FHIR JSON.

## 8. Conformance

An implementation is a conforming FTRM v1 **reader** if:

1. It refuses to open files whose `application_id` is not the FTRM
   stamp.
2. It refuses to open, or supports an explicit migration step for,
   files whose `user_version` differs from `1`.
3. It enumerates `tx_resource` and registers each row as an
   addressable resource by `(resource_type, url, version)`.
4. For every CodeSystem it surfaces, `cs-lookup` returns `display`,
   `definition`, the `inactive`/`abstract`/`notSelectable`/`status`
   well-knowns, parents, children, properties and designations.
5. `cs-validate-code` honours `displayLanguage` against
   `concept_designation.language`.
6. `cs-find-matches` (or the implementation's equivalent) uses
   `concept_fts` / `designation_fts` for text and
   `cp_pushdown` / `concept_ancestor` for filter pushdown.
7. `vs-expand` parses `valueset.compose` and **MAY** consult
   `valueset_expansion` as a cache.
8. `cm-translate` honours `(cm_url, cm_version, source_system,
   source_code)` for the forward direction and `(cm_url, cm_version,
   target_system, target_code)` for the reverse.
9. Alias resolution (e.g. OID → canonical URL) uses
   `naming_system_id` with the lookup contract in §5.6.

An implementation is a conforming FTRM v1 **writer** if it produces
files that a conforming reader can open per (1)–(2), and:

10. Every CodeSystem it writes has a fully-rebuilt `concept_ancestor`
    closure consistent with `concept_parent`.
11. FTS5 virtual tables are populated via `('rebuild')` after the
    underlying rows are written.
12. Foreign-key ordering is respected; the file passes `PRAGMA
    foreign_key_check;` at the end of the build.
13. Round-trip fidelity for the JSON pass-through slots in §7 is
    preserved unless the writer documents what it drops.

## 9. Security considerations

- An FTRM file is an opaque SQLite database. A reader that opens an
  attacker-supplied file inherits the SQLite library's attack
  surface; treat untrusted files as untrusted SQL input. The
  `application_id` check is a sanity gate, not a security boundary.
- The `compose` and `metadata` JSON slots are pass-through. A reader
  evaluating their contents (for example, expanding a ValueSet
  whose compose includes URLs) **SHOULD** apply the same network
  egress / fetch controls it would apply to a FHIR resource received
  on the wire.
- Diacritic folding in FTS5 (`remove_diacritics 2`) is part of the
  match semantics; a reader that swaps it out changes observable
  match behaviour. Don't.

## 10. Acknowledgements

The FTRM container builds on:

- the [FHIR R4 terminology module](https://hl7.org/fhir/R4/terminology-module.html)
  (CodeSystem, ValueSet, ConceptMap, NamingSystem);
- [SQLite](https://www.sqlite.org/) and the FTS5 module;
- the conformance population maintained at
  [`HL7/fhir-tx-ecosystem-ig`](https://github.com/HL7/fhir-tx-ecosystem-ig).

Hades is the reference implementation. The schema, identifiers and
prose of this specification are released under CC0 so the format is
free to adopt without licensing friction.
