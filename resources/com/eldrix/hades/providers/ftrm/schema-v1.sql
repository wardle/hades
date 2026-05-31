-- FHIR terminology SQLite container, schema v1.
--
-- File-level identity is stamped via PRAGMA application_id = 0x4654524D
-- ('FTRM') and PRAGMA user_version = 1, set by db.clj at create-time and
-- verified at open. The schema is provider-neutral: the `tx_` prefix
-- denotes "terminology container" rather than any single server.
--
-- Storage tables follow the FHIR R4 conceptual model for CodeSystem,
-- ValueSet and ConceptMap. Polymorphic value columns mirror FHIR's
-- value[x] convention. JSON-shaped slots (compose, property/filter
-- definitions, ConceptMap depends_on/product, pass-through metadata)
-- stay as TEXT to preserve their raw FHIR form.

-- ---------------------------------------------------------------------------
-- File-level metadata
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tx_meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);

CREATE TABLE IF NOT EXISTS tx_resource (
  resource_type TEXT NOT NULL CHECK (resource_type IN ('CodeSystem','ValueSet','ConceptMap')),
  url           TEXT NOT NULL,
  version       TEXT NOT NULL DEFAULT '',
  concept_count INTEGER,
  imported_at   TEXT NOT NULL,
  PRIMARY KEY (resource_type, url, version)
);

-- ---------------------------------------------------------------------------
-- CodeSystem
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS codesystem_meta (
  url               TEXT NOT NULL,
  version           TEXT NOT NULL DEFAULT '',
  case_sensitive    INTEGER CHECK (case_sensitive IN (0,1)),
  hierarchy_meaning TEXT,
  content           TEXT,                 -- 'complete'|'fragment'|'supplement'|'example'|'not-present'
  supplements       TEXT,                 -- canonical of base CS when content='supplement'
  status            TEXT,
  experimental      INTEGER CHECK (experimental IN (0,1)),
  name              TEXT,
  title             TEXT,
  description       TEXT,
  publisher         TEXT,
  jurisdiction      TEXT,
  standards_status  TEXT,
  property_defs     TEXT,                 -- JSON
  filter_defs       TEXT,                 -- JSON
  metadata          TEXT,                 -- JSON pass-through (copyright, contact, useContext, ...)
  PRIMARY KEY (url, version)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS csm_status      ON codesystem_meta(status);
CREATE INDEX IF NOT EXISTS csm_content     ON codesystem_meta(content);
CREATE INDEX IF NOT EXISTS csm_publisher   ON codesystem_meta(publisher);
CREATE INDEX IF NOT EXISTS csm_supplements ON codesystem_meta(supplements);

-- Concepts. Surfaces inactive/abstract/notSelectable as columns rather
-- than burying them in concept_property — every terminology operation
-- needs them. Rowid-bearing so external-content FTS5 can reference it.
CREATE TABLE IF NOT EXISTS concept (
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
CREATE UNIQUE INDEX IF NOT EXISTS concept_pk
  ON concept(cs_url, cs_version, code);
CREATE INDEX IF NOT EXISTS concept_inactive
  ON concept(cs_url, cs_version, inactive);

-- Polyhierarchy. One row per (concept, parent) edge. SNOMED, NCIt, MeSH
-- and ICD-O all need this; LOINC's single-parent case is the degenerate.
CREATE TABLE IF NOT EXISTS concept_parent (
  cs_url      TEXT NOT NULL,
  cs_version  TEXT NOT NULL DEFAULT '',
  code        TEXT NOT NULL,
  parent_code TEXT NOT NULL,
  PRIMARY KEY (cs_url, cs_version, code, parent_code)
) WITHOUT ROWID;
-- Children lookup (parent → children).
CREATE INDEX IF NOT EXISTS concept_parent_rev
  ON concept_parent(cs_url, cs_version, parent_code);

-- Concept properties. Polymorphic value columns mirror FHIR `value[x]`;
-- the indexer writes whichever matches the source value-type. The
-- unique index uses COALESCE so polymorphic NULLs don't defeat
-- de-duplication (SQLite treats NULL ≠ NULL in unique indexes by default).
CREATE TABLE IF NOT EXISTS concept_property (
  cs_url      TEXT NOT NULL,
  cs_version  TEXT NOT NULL DEFAULT '',
  code        TEXT NOT NULL,
  prop_code   TEXT NOT NULL,
  value_type  TEXT NOT NULL CHECK (value_type IN
                ('string','code','integer','boolean','decimal','dateTime','Coding','Quantity')),
  value_str   TEXT,                    -- string | code | dateTime
  value_int   INTEGER,
  value_bool  INTEGER CHECK (value_bool IN (0,1)),
  value_dec   REAL,
  value_coding_system  TEXT,
  value_coding_code    TEXT,
  value_coding_display TEXT,
  value_quantity       TEXT,           -- JSON: {value, unit, system, code, comparator}
  FOREIGN KEY (cs_url, cs_version, code) REFERENCES concept(cs_url, cs_version, code)
);
CREATE UNIQUE INDEX IF NOT EXISTS cp_uniq ON concept_property(
  cs_url, cs_version, code, prop_code, value_type,
  COALESCE(value_str, ''),
  COALESCE(value_int, 0),
  COALESCE(value_bool, -1),
  COALESCE(value_dec, 0.0),
  COALESCE(value_coding_system, ''),
  COALESCE(value_coding_code, '')
);
-- Pushdown index: $expand WHERE prop=X AND value=Y across millions of rows.
CREATE INDEX IF NOT EXISTS cp_pushdown
  ON concept_property(cs_url, cs_version, prop_code, value_str);

CREATE TABLE IF NOT EXISTS concept_designation (
  cs_url      TEXT NOT NULL,
  cs_version  TEXT NOT NULL DEFAULT '',
  code        TEXT NOT NULL,
  language    TEXT,
  use_system  TEXT,
  use_code    TEXT,
  use_display TEXT,
  value       TEXT NOT NULL,
  extension   TEXT,                     -- JSON: extensions on the designation/Coding
  FOREIGN KEY (cs_url, cs_version, code) REFERENCES concept(cs_url, cs_version, code)
);
CREATE UNIQUE INDEX IF NOT EXISTS cd_uniq ON concept_designation(
  cs_url, cs_version, code,
  COALESCE(language, ''),
  COALESCE(use_system, ''),
  COALESCE(use_code, ''),
  value
);
CREATE INDEX IF NOT EXISTS cd_language ON concept_designation(cs_url, cs_version, language);
CREATE INDEX IF NOT EXISTS cd_use      ON concept_designation(cs_url, cs_version, use_code);

-- Transitive closure for is-a / descendent-of pushdown. Built by the
-- indexer's closure pass after concept rows land. `depth` is the
-- shortest path (in a polyhierarchy a (ancestor, descendent) pair has
-- one entry recording the minimum depth).
CREATE TABLE IF NOT EXISTS concept_ancestor (
  cs_url          TEXT NOT NULL,
  cs_version      TEXT NOT NULL DEFAULT '',
  ancestor_code   TEXT NOT NULL,
  descendent_code TEXT NOT NULL,
  depth           INTEGER NOT NULL,
  PRIMARY KEY (cs_url, cs_version, ancestor_code, descendent_code)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS ca_descendent
  ON concept_ancestor(cs_url, cs_version, descendent_code);

-- Full-text search. External-content FTS5 references rowid on
-- concept / concept_designation. The build is responsible for
-- populating these via `INSERT INTO ... ('rebuild')` after the source
-- rows land. Diacritics are folded so 'cafe' matches 'café'.
CREATE VIRTUAL TABLE IF NOT EXISTS concept_fts USING fts5(
  display,
  definition,
  content='concept',
  content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2'
);

CREATE VIRTUAL TABLE IF NOT EXISTS designation_fts USING fts5(
  value,
  language UNINDEXED,
  use_code UNINDEXED,
  content='concept_designation',
  content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2'
);

-- ---------------------------------------------------------------------------
-- ValueSet
-- ---------------------------------------------------------------------------

-- Narrow resolution row: every column is small, so rows never overflow
-- and the whole table is a dense, cache-resident B-tree. The hot path
-- (`resolve_vs` → `$expand`) reads here on every request and never has
-- to traverse a compose/metadata blob to reach `member_count` et al. The
-- multi-KB JSON blobs live in `valueset_resource`, read only by the
-- full-resource path (`vs-resource` / `load-vs-entry`).
CREATE TABLE IF NOT EXISTS valueset (
  url           TEXT NOT NULL,
  version       TEXT NOT NULL DEFAULT '',
  name          TEXT,
  title         TEXT,
  status        TEXT,
  experimental  INTEGER CHECK (experimental IN (0,1)),
  publisher     TEXT,
  jurisdiction  TEXT,
  description   TEXT,
  member_count  INTEGER,                    -- non-NULL ⇒ membership materialised in valueset_member
  member_systems TEXT,                      -- JSON [{system, version?}] — distinct systems of materialised members
  member_id_lo  INTEGER,                    -- MIN(valueset_member.id) for this VS
  member_id_hi  INTEGER,                    -- MAX(valueset_member.id) for this VS
  PRIMARY KEY (url, version)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS vs_status    ON valueset(status);
CREATE INDEX IF NOT EXISTS vs_publisher ON valueset(publisher);

-- Full-resource JSON blobs, split out of `valueset` so the hot
-- resolution path never drags these multi-KB overflow pages through the
-- valueset B-tree. One row per stored ValueSet; read only when the full
-- resource or its compose is needed.
CREATE TABLE IF NOT EXISTS valueset_resource (
  url       TEXT NOT NULL,
  version   TEXT NOT NULL DEFAULT '',
  metadata  TEXT,                           -- JSON pass-through
  compose   TEXT,                           -- JSON
  PRIMARY KEY (url, version)
) WITHOUT ROWID;

-- Materialised membership for stored-extensional ValueSets: one row per
-- enumerated `include.concept` entry. Lets `$expand` page with
-- LIMIT/OFFSET and count cheaply, instead of parsing the (potentially
-- multi-MB) `valueset.compose` blob per request. Only populated when the
-- compose is purely stored-extensional with a display on every concept
-- (`compose/extensional-members`); intensional ValueSets keep `compose`
-- and `member_count = NULL`. `ord` preserves authored include order.
--
-- `id` is an explicit INTEGER PRIMARY KEY (a stable rowid alias — VACUUM
-- never renumbers it, unlike an implicit rowid). The valueset table records
-- this VS's actual `[member_id_lo, member_id_hi]` = MIN/MAX member id; a
-- filtered $expand bounds the FTS scan to that range via `rowid BETWEEN`
-- (FTS5 pushes rowid constraints into the doclist walk). The range always
-- *covers* every member (so it never under-returns), and the query also
-- filters by (vs_url, vs_version), so any foreign rows that fall in the
-- range — were the ids ever non-contiguous — are discarded: correctness
-- doesn't depend on contiguity, only the scan's tightness does. Members are
-- in fact inserted consecutively, so the range is normally gap-free. The
-- unique index on (vs_url, vs_version, ord) serves ordered paging.
CREATE TABLE IF NOT EXISTS valueset_member (
  id             INTEGER PRIMARY KEY,
  vs_url         TEXT NOT NULL,
  vs_version     TEXT NOT NULL DEFAULT '',
  ord            INTEGER NOT NULL,
  system         TEXT,
  system_version TEXT,
  code           TEXT NOT NULL,
  display        TEXT,
  designations   TEXT                        -- JSON (raw FHIR designation array), NULL when none
);
CREATE UNIQUE INDEX IF NOT EXISTS valueset_member_pk
  ON valueset_member(vs_url, vs_version, ord);

-- Token-prefix filtering for $expand `filter`: the `unicode61` tokenizer
-- (same as `concept_fts`) gives autocomplete-style word matching, queried
-- with prefix terms (`"acut"* "ast"*`) — the semantic the SNOMED provider
-- and FHIR's `filter` 'left matching' example use. Index-backed, so a
-- filtered expand of a large materialised ValueSet no longer scans every
-- member row; combined with the `rowid BETWEEN` member-id range, the scan
-- is bounded to the target ValueSet's rows. External-content over
-- `valueset_member` (content_rowid is the stable `id`); `index!` populates
-- it via the FTS5 'rebuild' command after member rows land.
CREATE VIRTUAL TABLE IF NOT EXISTS valueset_member_fts USING fts5(
  code,
  display,
  content='valueset_member',
  content_rowid='id',
  tokenize='unicode61 remove_diacritics 2'
);

-- ---------------------------------------------------------------------------
-- ConceptMap
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS conceptmap (
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
  unmapped_mode  TEXT CHECK (unmapped_mode IS NULL OR unmapped_mode IN ('provided','fixed','other-map')),
  unmapped_code  TEXT,
  unmapped_url   TEXT,
  metadata       TEXT,                     -- JSON pass-through
  PRIMARY KEY (url, version)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS conceptmap_element (
  cm_url         TEXT NOT NULL,
  cm_version     TEXT NOT NULL DEFAULT '',
  group_idx      INTEGER NOT NULL,
  source_system  TEXT,
  source_version TEXT,
  target_system  TEXT,
  target_version TEXT,
  source_code    TEXT NOT NULL,
  source_display TEXT,
  target_code    TEXT,                    -- NULL for equivalence='unmatched' (no target)
  target_display TEXT,
  equivalence    TEXT NOT NULL,
  comment        TEXT,
  depends_on     TEXT,                     -- JSON
  product        TEXT,                     -- JSON
  FOREIGN KEY (cm_url, cm_version) REFERENCES conceptmap(url, version)
);
CREATE UNIQUE INDEX IF NOT EXISTS cme_uniq ON conceptmap_element(
  cm_url, cm_version, group_idx,
  COALESCE(source_system, ''), source_code,
  COALESCE(target_system, ''), COALESCE(target_code, ''),
  equivalence
);
CREATE INDEX IF NOT EXISTS cme_fwd
  ON conceptmap_element(cm_url, cm_version, source_system, source_code);
CREATE INDEX IF NOT EXISTS cme_rev
  ON conceptmap_element(cm_url, cm_version, target_system, target_code);

-- ---------------------------------------------------------------------------
-- NamingSystem (OID/URN → URL aliases)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS naming_system (
  url      TEXT NOT NULL PRIMARY KEY,
  name     TEXT,
  status   TEXT,
  kind     TEXT,                           -- 'codesystem'|'identifier'|'root'
  metadata TEXT                            -- JSON pass-through
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS naming_system_id (
  ns_url          TEXT NOT NULL,
  identifier_type TEXT NOT NULL CHECK (identifier_type IN ('oid','uri','uuid','other')),
  value           TEXT NOT NULL,
  preferred       INTEGER CHECK (preferred IN (0,1)),
  PRIMARY KEY (ns_url, identifier_type, value),
  FOREIGN KEY (ns_url) REFERENCES naming_system(url)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS nsi_value ON naming_system_id(value);
