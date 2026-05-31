(ns com.eldrix.hades.providers.ftrm.provider
  "Persistent CodeSystem/ValueSet/ConceptMap providers backed by a
  FHIR terminology SQLite container (built by `impl/index/ftrm`).

  One catalogue impl per resource type per `.db`, registered under
  every key it serves — same pattern Hermes uses for SNOMED. Each
  catalogue holds an immutable `{[url version] → entry}` map loaded at
  boot via three bulk SELECTs (one per table); JSON-shaped slots
  (compose, property-defs, pass-through metadata) are parsed once at
  load and stored as Clojure data on the entry.

  Operations:
    - `cs-lookup`, `cs-validate-code`        — direct point queries with
                                                displayLanguage selection
    - `cs-expand*`                      — FTS + filter pushdown
    - `cs-subsumes`                          — closure-table lookup
    - `vs-expand`, `vs-validate-code`        — stored-membership fast path,
                                                then delegated to compose engine
    - `cm-translate`                         — direct point query

  Behavioural fidelity still trails the in-memory provider on
  case-insensitive lookup, supplements, and fragment-CS warnings."
  (:require [charred.api :as charred]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.providers.common.canonical :as canonical]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.providers.common.display :as display]
            [com.eldrix.hades.providers.common.issues :as issues]
            [com.eldrix.hades.providers.common.property-filter :as property-filter]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.providers.ftrm.db :as db]
            [com.eldrix.hades.providers.common.vs-validate :as vs-validate]
            [next.jdbc :as jdbc])
  (:import (com.google.re2j Pattern PatternSyntaxException)
           (java.io Closeable)))

(set! *warn-on-reflection* true)

(defn- v [s] (or s ""))

(defn- system-version-or-nil [version]
  (when-not (str/blank? version) version))

(defn- key-for [url version] [(or url "") (or version "")])

(def ^:private read-json
  "Reusable charred parser. Faster than clojure.data.json by ~5×; the
  parser instance is reusable and thread-safe per the charred docs."
  (charred/parse-json-fn))

(defn- parse-json [^String s] (when s (read-json s)))

(defn- int->bool
  "Decode a SQLite INTEGER 0/1 column to a Clojure boolean, preserving
  NULL as nil."
  [v]
  (when (some? v) (= 1 v)))

;; ---------------------------------------------------------------------------
;; Bulk loaders — one SELECT per table; rows parsed into Clojure data
;; once and indexed by [url version].
;; ---------------------------------------------------------------------------

(defn- codesystem-row->entry [row]
  {:url               (:codesystem_meta/url row)
   :version           (system-version-or-nil (:codesystem_meta/version row))
   :case-sensitive    (int->bool (:codesystem_meta/case_sensitive row))
   :hierarchy-meaning (:codesystem_meta/hierarchy_meaning row)
   :content           (:codesystem_meta/content row)
   :supplements       (:codesystem_meta/supplements row)
   :status            (:codesystem_meta/status row)
   :experimental      (int->bool (:codesystem_meta/experimental row))
   :name              (:codesystem_meta/name row)
   :title             (:codesystem_meta/title row)
   :description       (:codesystem_meta/description row)
   :standards-status  (:codesystem_meta/standards_status row)
   :property-defs     (parse-json (:codesystem_meta/property_defs row))
   :filter-defs       (parse-json (:codesystem_meta/filter_defs   row))
   :metadata          (parse-json (:codesystem_meta/metadata      row))})

(defn- valueset-row->entry [row]
  {:url      (:valueset_resource/url row)
   :version  (system-version-or-nil (:valueset_resource/version row))
   :metadata (parse-json (:valueset_resource/metadata row))
   :compose  (parse-json (:valueset_resource/compose  row))})

(defn- conceptmap-row->entry [row]
  {:url            (:conceptmap/url row)
   :version        (system-version-or-nil (:conceptmap/version row))
   :name           (:conceptmap/name row)
   :title          (:conceptmap/title row)
   :status         (:conceptmap/status row)
   :source-uri     (:conceptmap/source_uri row)
   :source-version (:conceptmap/source_version row)
   :target-uri     (:conceptmap/target_uri row)
   :target-version (:conceptmap/target_version row)
   :metadata       (parse-json (:conceptmap/metadata row))})

(defn- latest-by-url
  "Index a `{[url version] → entry}` cache as a plain `{url → entry}` map
  keeping each URL's SemVer-greatest version — the bare-URL fallback for
  `lookup-entry`, so an unversioned reference resolves with one `get`
  instead of scanning the cache."
  [cache]
  (reduce-kv (fn [m [url version] entry]
               (cond-> m
                 (pos? (canonical/semver-compare version (:version (get m url))))
                 (assoc url entry)))
             {} cache))

(defn- load-codesystem-cache [ds]
  (into {}
        (map (fn [row]
               [(key-for (:codesystem_meta/url row) (:codesystem_meta/version row))
                (codesystem-row->entry row)]))
        (jdbc/execute! ds ["SELECT * FROM codesystem_meta"])))

(defn- load-conceptmap-cache [ds]
  (let [pairs-by-key
        (reduce
          (fn [m row]
            (let [k (key-for (:conceptmap_element/cm_url row)
                             (:conceptmap_element/cm_version row))]
              (update m k (fnil conj [])
                      {:system (:conceptmap_element/source_system row)
                       :target (:conceptmap_element/target_system row)})))
          {}
          (jdbc/execute! ds
            ["SELECT DISTINCT cm_url, cm_version, source_system, target_system
              FROM conceptmap_element
              WHERE source_system IS NOT NULL AND target_system IS NOT NULL"]))]
    (into {}
          (map (fn [row]
                 (let [k     (key-for (:conceptmap/url row) (:conceptmap/version row))
                       pairs (get pairs-by-key k)]
                   [k (cond-> (conceptmap-row->entry row)
                        (seq pairs) (assoc :pairs (vec (distinct pairs))))])))
          (jdbc/execute! ds ["SELECT * FROM conceptmap"]))))

;; ---------------------------------------------------------------------------
;; Concept fetch + render (per-request)
;; ---------------------------------------------------------------------------

(defn- fetch-concept-row
  "Fetch a single concept row. When `case-sensitive?` is false the
  match is case-insensitive via `LOWER(code)=LOWER(?)`. Returns the
  row map (with the actual stored code) or nil. `conn` may be a pooled
  connection or the datasource — Hikari connections are cheap to grab,
  but threading one through `cs-lookup` lets all five point queries
  share a single check-out."
  [conn url version code case-sensitive?]
  (if (false? case-sensitive?)
    (jdbc/execute-one! conn
      ["SELECT code, display, definition, inactive, abstract, not_selectable, status
        FROM concept
        WHERE cs_url=? AND cs_version=? AND LOWER(code)=LOWER(?)"
       url (v version) code])
    (jdbc/execute-one! conn
      ["SELECT code, display, definition, inactive, abstract, not_selectable, status
        FROM concept WHERE cs_url=? AND cs_version=? AND code=?"
       url (v version) code])))

(defn- fetch-parents
  "Return a vector of `{:code :display}` for each parent. Joining
  to `concept` to project the parent's display matches the in-memory
  provider's lookup shape — `:description` on the parent property."
  [conn url version code]
  (mapv (fn [row]
          {:code (:concept_parent/parent_code row)
           :display (:concept/display row)})
        (jdbc/execute! conn
          ["SELECT cp.parent_code, c.display
            FROM concept_parent cp
            LEFT JOIN concept c
              ON c.cs_url=cp.cs_url AND c.cs_version=cp.cs_version AND c.code=cp.parent_code
            WHERE cp.cs_url=? AND cp.cs_version=? AND cp.code=?"
           url (v version) code])))

(defn- fetch-properties [conn url version code]
  (jdbc/execute! conn
    ["SELECT prop_code, value_type, value_str, value_int, value_bool, value_dec,
             value_coding_system, value_coding_code, value_coding_display, value_quantity
      FROM concept_property WHERE cs_url=? AND cs_version=? AND code=?"
     url (v version) code]))

(defn- designation-row->entry [row]
  (let [{:concept_designation/keys [language use_system use_code use_display value]} row]
    (cond-> {:value value}
      language   (assoc :language (keyword language))
      (or use_system use_code use_display)
      (assoc :use (cond-> {}
                    use_system  (assoc :system use_system)
                    use_code    (assoc :code use_code)
                    use_display (assoc :display use_display))))))

(defn- fetch-designations
  "Fetch the concept's designations. When `languages` is a non-empty
  seq of BCP-47 tags, narrows the scan via lenient prefix matching
  — for each tag we accept exact (`= ?`) and prefix (`LIKE 'tag-%'`)
  matches, so requesting `en` still picks up rows tagged `en-GB`.
  This mirrors `display/language-matches?` so the SQL pushdown
  doesn't change behaviour. Returns `[]` when no rows match."
  ([conn url version code] (fetch-designations conn url version code nil))
  ([conn url version code languages]
   (let [base "SELECT language, use_system, use_code, use_display, value
               FROM concept_designation WHERE cs_url=? AND cs_version=? AND code=?"
         ;; The dynamic part of `clauses` is the *number* of `?`
         ;; placeholders, never user data. All language values flow
         ;; through JDBC parameter binding (PreparedStatement.setString),
         ;; same as the standard `IN (?, ?, ?)` idiom for variadic binds.
         [sql params]
         (if (seq languages)
           (let [clauses (str/join " OR "
                                   (repeat (count languages) "language = ? OR language LIKE ?"))
                 sql (str base " AND (" clauses ")")
                 lang-params (mapcat (fn [l] [l (str l "-%")]) languages)]
             [sql (into [url (v version) code] lang-params)])
           [base [url (v version) code]])]
     (mapv designation-row->entry
           (jdbc/execute! conn (into [sql] params))))))

(defn- fetch-children
  "Return a vector of `{:code :display}` for each child."
  [conn url version code]
  (mapv (fn [row]
          {:code (:concept_parent/code row)
           :display (:concept/display row)})
        (jdbc/execute! conn
          ["SELECT cp.code, c.display
            FROM concept_parent cp
            LEFT JOIN concept c
              ON c.cs_url=cp.cs_url AND c.cs_version=cp.cs_version AND c.code=cp.code
            WHERE cp.cs_url=? AND cp.cs_version=? AND cp.parent_code=?"
           url (v version) code])))

(defn- typed-property-value
  [{:concept_property/keys [value_type value_str value_int value_bool value_dec
                            value_coding_system value_coding_code value_coding_display
                            value_quantity]}]
  (case value_type
    "code"     (when value_str (keyword value_str))
    "string"   value_str
    "integer"  value_int
    "boolean"  (int->bool value_bool)
    "decimal"  value_dec
    "dateTime" value_str
    "Coding"   (cond-> {}
                 value_coding_system  (assoc :system value_coding_system)
                 value_coding_code    (assoc :code value_coding_code)
                 value_coding_display (assoc :display value_coding_display))
    "Quantity" (when value_quantity (read-json value_quantity))
    nil))

(defn- property->lookup-entry [row]
  (let [pc (:concept_property/prop_code row)
        v  (typed-property-value row)]
    (when (and pc (some? v))
      {:code (keyword pc) :value v})))

;; ---------------------------------------------------------------------------
;; Catalogue dispatch helpers
;;
;; Methods take the `params`/`ctx` they're given, extract the (url,
;; version) the caller is hitting, and look up the matching cache
;; entry. Version may be implicit; we tolerate either form.
;; ---------------------------------------------------------------------------

(defn- lookup-entry
  "Resolve the entry for `url`+`version`: an exact `[url version]` hit in
  `cache`, else the unversioned (`\"\"`) entry, else — for a bare URL — the
  SemVer-latest version via the precomputed `latest` index (`latest-by-url`).
  Ranking the latest explicitly is a healthcare-safety requirement (never
  rely on hash-map iteration order); the index keeps it O(1) on the hot path."
  [cache latest url version]
  (or (when (not (str/blank? version)) (get cache (key-for url version)))
      (get cache (key-for url ""))
      (get latest url)))

(defn- params-version [params]
  (or (:version params) (:system-version params)))

;; ---------------------------------------------------------------------------
;; cs-expand* helpers (FTS + filter pushdown)
;; ---------------------------------------------------------------------------

(defn- fts-query
  "Translate a free-text filter into an FTS5 query for the unicode61
  indexes: split into Unicode word tokens and emit each as a quoted prefix
  term (`\"token\"*`), implicitly AND-ed. This is autocomplete-style
  token-prefix matching (\"acut ast\" matches \"acute asthma\") — the same
  semantic the SNOMED provider uses, shared here by `cs-expand*` (concept /
  designation FTS) and materialised ValueSet membership. Quoting makes a
  token that happens to be an FTS5 keyword (and/or/not/near) a literal.
  Returns nil for input that yields no usable tokens."
  [^String text]
  (when-not (str/blank? text)
    (let [tokens (->> (str/split text #"[^\p{L}\p{Nd}]+")
                      (remove str/blank?))]
      (when (seq tokens)
        (str/join " " (map (fn [t] (str \" t \" \*)) tokens))))))

(def ^:private searchable-designation-clause
  "WHERE-clause excerpt restricting a `concept_designation` row to one
  whose `(use_system, use_code)` pair is on the searchable-name
  allowlist defined here. A null/empty
  `use_code` is treated as a name (the writer did not classify it).
  LOINC ontology axes (PROPERTY, TIME_ASPCT, SYSTEM, SCALE_TYP,
  METHOD_TYP, CLASS) are deliberately excluded — bare axis values are
  not concept names and would explode result sets without adding
  signal."
  (str "((cd.use_code IS NULL OR cd.use_code = '')"
       " OR (cd.use_system = 'http://loinc.org'"
       "     AND cd.use_code IN ('LONG_COMMON_NAME','SHORTNAME','RELATEDNAMES2',"
       "                         'LinguisticVariantDisplayName','COMPONENT'))"
       " OR (cd.use_system = 'http://terminology.hl7.org/CodeSystem/designation-usage'"
       "     AND cd.use_code IN ('display','synonym')))"))

(def ^:private fts-hits-subquery
  "Subquery yielding `(rowid, rank)` for every `concept.rowid` hit by
  EITHER a `concept_fts` (display / definition) match OR an allowlisted
  `designation_fts` match. UNION ALL + `MIN(rank)` per rowid dedupes
  concepts that match both streams while retaining the best rank.
  Two `?` placeholders, both bound to the same FTS query string. The
  caller filters the resulting concept rows by `cs_url`/`cs_version`
  in the outer WHERE — the FTS streams span every CodeSystem in the
  container, mirroring the prior single-stream behaviour."
  (str "(SELECT rowid, MIN(rank) AS rank FROM ("
       "  SELECT f.rowid AS rowid, f.rank AS rank "
       "  FROM concept_fts f WHERE f.concept_fts MATCH ? "
       "  UNION ALL "
       "  SELECT cd_c.rowid AS rowid, df.rank AS rank "
       "  FROM designation_fts df "
       "  JOIN concept_designation cd ON cd.rowid = df.rowid "
       "  JOIN concept cd_c ON cd_c.cs_url = cd.cs_url "
       "                   AND cd_c.cs_version = cd.cs_version "
       "                   AND cd_c.code = cd.code "
       "  WHERE df.designation_fts MATCH ? AND " searchable-designation-clause
       ") GROUP BY rowid)"))

(defn- direct-column [property]
  ;; Filters keyed on `code` / `display` map to columns on `concept`
  ;; rather than `concept_property`.
  (case property
    "code"    "c.code"
    "display" "c.display"
    nil))

(defn- in-list-placeholders [n]
  (str/join "," (repeat n "?")))

(defn- code-sql
  [expr case-sensitive?]
  (if (false? case-sensitive?)
    (str "LOWER(" expr ")")
    expr))

(defn- code-param-sql
  [case-sensitive?]
  (if (false? case-sensitive?) "LOWER(?)" "?"))

(defn- code-in-list-placeholders
  [n case-sensitive?]
  (str/join "," (repeat n (code-param-sql case-sensitive?))))

(defn- filter->sql-clause
  "Translate one Query filter to {:sql ... :params [...] :post-filter ...}.
  `:post-filter` is a fn `concept-row -> boolean` applied after the SQL
  result lands; used for `regex` (SQLite has no portable REGEXP) and for
  any unsupported op (returns false to drop)."
  [{:keys [property op value]} case-sensitive?]
  (case op
    ("is-a")
    {:sql (str "(" (code-sql "c.code" case-sensitive?) " = " (code-param-sql case-sensitive?)
               " OR EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND " (code-sql "a.ancestor_code" case-sensitive?) " = " (code-param-sql case-sensitive?)
               " AND a.descendent_code=c.code))")
     :params [value value]}

    ("descendent-of" "descendant-of")
    {:sql (str "EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND " (code-sql "a.ancestor_code" case-sensitive?) " = " (code-param-sql case-sensitive?)
               " AND a.descendent_code=c.code)")
     :params [value]}

    ("is-not-a")
    {:sql (str "(" (code-sql "c.code" case-sensitive?) " <> " (code-param-sql case-sensitive?)
               " AND NOT EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND " (code-sql "a.ancestor_code" case-sensitive?) " = " (code-param-sql case-sensitive?)
               " AND a.descendent_code=c.code))")
     :params [value value]}

    ("generalizes")
    {:sql (str "(" (code-sql "c.code" case-sensitive?) " = " (code-param-sql case-sensitive?)
               " OR EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND a.ancestor_code=c.code AND "
               (code-sql "a.descendent_code" case-sensitive?) " = " (code-param-sql case-sensitive?) "))")
     :params [value value]}

    "="
    (if-let [col (direct-column property)]
      {:sql (if (= "code" property)
              (str (code-sql col case-sensitive?) " = " (code-param-sql case-sensitive?))
              (str col " = ?"))
       :params [value]}
      {:sql (str "EXISTS (SELECT 1 FROM concept_property p "
                 "WHERE p.cs_url=c.cs_url AND p.cs_version=c.cs_version "
                 "AND p.code=c.code AND p.prop_code=? "
                 "AND COALESCE(p.value_str, CAST(p.value_int AS TEXT), CAST(p.value_dec AS TEXT)) = ?)")
       :params [property value]})

    "in"
    (let [parts (str/split value #",")]
      (if-let [col (direct-column property)]
        {:sql (if (= "code" property)
                (str (code-sql col case-sensitive?) " IN (" (code-in-list-placeholders (count parts) case-sensitive?) ")")
                (str col " IN (" (in-list-placeholders (count parts)) ")"))
         :params parts}
        {:sql (str "EXISTS (SELECT 1 FROM concept_property p "
                   "WHERE p.cs_url=c.cs_url AND p.cs_version=c.cs_version "
                   "AND p.code=c.code AND p.prop_code=? "
                   "AND COALESCE(p.value_str, CAST(p.value_int AS TEXT), CAST(p.value_dec AS TEXT)) "
                   "IN (" (in-list-placeholders (count parts)) "))")
         :params (into [property] parts)}))

    "not-in"
    (let [parts (str/split value #",")]
      (if-let [col (direct-column property)]
        {:sql (if (= "code" property)
                (str (code-sql col case-sensitive?) " NOT IN (" (code-in-list-placeholders (count parts) case-sensitive?) ")")
                (str col " NOT IN (" (in-list-placeholders (count parts)) ")"))
         :params parts}
        {:sql (str "NOT EXISTS (SELECT 1 FROM concept_property p "
                   "WHERE p.cs_url=c.cs_url AND p.cs_version=c.cs_version "
                   "AND p.code=c.code AND p.prop_code=? "
                   "AND COALESCE(p.value_str, CAST(p.value_int AS TEXT), CAST(p.value_dec AS TEXT)) "
                   "IN (" (in-list-placeholders (count parts)) "))")
         :params (into [property] parts)}))

    "exists"
    (let [exists? (= "true" value)
          sql-op (if exists? "EXISTS" "NOT EXISTS")]
      (if (direct-column property)
        ;; "code" / "display" always exist when the row does, so the
        ;; check degenerates to a constant. Modelled as a tautology on
        ;; the row to keep the SQL composable.
        {:sql (if exists? "1=1" "1=0") :params []}
        {:sql (str sql-op " (SELECT 1 FROM concept_property p "
                   "WHERE p.cs_url=c.cs_url AND p.cs_version=c.cs_version "
                   "AND p.code=c.code AND p.prop_code=?)")
         :params [property]}))

    "regex"
    (let [^Pattern pat (try (Pattern/compile value)
                            (catch PatternSyntaxException _ nil))
          target (or (direct-column property) :property)
          fetch-string (fn [row]
                         (case target
                           "c.code"    (:concept/code row)
                           "c.display" (:concept/display row)
                           :property
                           (some (fn [pr]
                                   (when (= property (:concept_property/prop_code pr))
                                     (or (:concept_property/value_str pr)
                                         (some-> (:concept_property/value_int pr) str)
                                         (some-> (:concept_property/value_dec pr) str))))
                                 (::row-properties row))))]
      ;; SQLite has no portable REGEXP. Materialise candidates and
      ;; post-filter in Clojure. SQL stays a tautology.
      {:sql "1=1"
       :params []
       :post-filter (when pat
                      (fn [row]
                        (let [s (fetch-string row)]
                          (boolean (when s (.matches (.matcher pat ^CharSequence s)))))))})

    ;; Unsupported op — drop everything by post-filter.
    {:sql "1=1"
     :params []
     :post-filter (constantly false)
     :unsupported-op op}))

(defn- search-concepts-sql
  "Compose the SQL for `cs-expand*`. Returns `{:sql :params :post-filters :issues}`.

  When a free-text query is supplied, `fts-hits-subquery` is JOINed in
  to surface concepts hit by either the primary-display FTS index
  (`concept_fts`) or an allowlisted designation FTS index
  (`designation_fts`). Results are ordered by `rank` (best of the two
  streams per concept) so that LIMIT cuts off least-relevant matches
  first. Searchable designations are restricted to name-like usage codes
  so axis-only LOINC values do not explode result sets.

  `LIMIT` is pushed into SQL only when there are no post-filters
  (regex). With post-filters present, SQL would drop survivors after
  the limit was applied, returning fewer rows than the caller asked
  for; the caller slices in Clojure instead. See `cs-expand*`."
  [url version filters text active-only? max-hits case-sensitive?]
  (let [base-where    ["c.cs_url = ?" "c.cs_version = ?"]
        base-params   [url (v version)]
        active-clause (when active-only?
                        ["(c.inactive IS NULL OR c.inactive = 0)"])
        fts           (fts-query text)
        fts-from      (when fts (str " JOIN " fts-hits-subquery " hits ON hits.rowid = c.rowid "))
        fts-params    (when fts [fts fts])
        filter-clauses (map #(filter->sql-clause % case-sensitive?) filters)
        clause-sqls   (map :sql filter-clauses)
        clause-params (mapcat :params filter-clauses)
        post-filters  (filter some? (map :post-filter filter-clauses))
        unsupported   (keep :unsupported-op filter-clauses)
        where         (str/join " AND " (concat base-where active-clause clause-sqls))
        order-by      (when fts " ORDER BY hits.rank ")
        sql-limit?    (and max-hits (empty? post-filters))
        sql (str "SELECT c.rowid AS rowid, c.code, c.display, c.definition, "
                 "c.inactive, c.abstract, c.not_selectable, c.status "
                 "FROM concept c " (or fts-from "")
                 "WHERE " where
                 (or order-by "")
                 (when sql-limit? (str " LIMIT " (long max-hits))))]
    {:sql sql
     :params (concat fts-params base-params clause-params)
     :post-filters post-filters
     :issues (when (seq unsupported)
               (mapv (fn [op]
                       {:severity "warning"
                        :type "not-supported"
                        :details-code "not-supported"
                        :text (str "The filter operation '" op "' is not supported")})
                     unsupported))}))

(defn- search-concepts-row->concept [url version display-langs row designations]
  (let [code (:concept/code row)
        inactive? (= 1 (:concept/inactive row))
        abstract? (= 1 (:concept/abstract row))
        lang-display (when (seq display-langs)
                       (display/find-display-for-language designations display-langs))]
    (cond-> {:code code
             :system url
             :version version
             :display (or lang-display (:concept/display row))}
      (seq designations) (assoc :designations designations)
      inactive? (assoc :inactive true)
      abstract? (assoc :abstract true))))

;; ---------------------------------------------------------------------------
;; CodeSystem catalogue
;; ---------------------------------------------------------------------------

(defn- not-found-result [meta code]
  (let [{:keys [url version content]} meta
        fragment? (= "fragment" content)
        msg (if fragment?
              (str "Unknown Code '" code "' in the CodeSystem '" url "' version '" version
                   "' - note that the code system is labeled as a fragment, so the code may be valid in some other fragment")
              (str "Unknown code '" code "' in the CodeSystem '" url "' version '" version "'"))]
    (cond-> {:result fragment?
             :code (keyword code)
             :system url
             :version version
             :issues [{:severity     (if fragment? "warning" "error")
                       :type         "code-invalid"
                       :details-code "invalid-code"
                       :text         msg
                       :expression   ["Coding.code"]}]}
      (not fragment?) (assoc :message msg))))

(defn- cs-resource-from-meta
  [{:keys [url version name title status experimental description content
           case-sensitive hierarchy-meaning standards-status]}]
  (cond-> {}
    url                    (assoc :url url)
    version                (assoc :version version)
    name                   (assoc :name name)
    title                  (assoc :title title)
    status                 (assoc :status status)
    (some? experimental)   (assoc :experimental experimental)
    description            (assoc :description description)
    content                (assoc :content content)
    (some? case-sensitive) (assoc :case-sensitive case-sensitive)
    hierarchy-meaning      (assoc :hierarchy-meaning hierarchy-meaning)
    standards-status       (assoc :standards-status standards-status)))


;; ---------------------------------------------------------------------------
;; ValueSet catalogue
;; ---------------------------------------------------------------------------

(defn- vs-resource-from-entry [{:keys [url version metadata compose]}]
  (let [name         (get metadata "name")
        title        (get metadata "title")
        status       (get metadata "status")
        experimental (get metadata "experimental")]
    (cond-> {:url url}
      version              (assoc :version version)
      name                 (assoc :name name)
      title                (assoc :title title)
      status               (assoc :status status)
      (some? experimental) (assoc :experimental (boolean experimental))
      (map? compose)       (assoc :compose compose))))

(defn- resolve-vs-version
  "Choose the stored version to serve for a ValueSet `url`, given the
  caller's requested `version` and the URL's `versions` actually stored.
  Mirrors the precedence the cache `lookup-entry` enforced: an exact
  requested version, else the unversioned (`\"\"`) entry, else the
  SemVer-latest stored version (healthcare safety: rank explicitly, never
  rely on storage order)."
  [versions version]
  (cond
    (and (not (str/blank? version)) (some #{version} versions)) version
    (some #{""} versions)                                       ""
    :else (reduce (fn [best v]
                    (if (or (nil? best) (pos? (canonical/semver-compare v best))) v best))
                  nil versions)))

(defn- resolve-vs
  "Resolve the concrete stored version to serve for ValueSet `url` and, when
  its membership is materialised, the precomputed `member_count` and
  `member_systems` needed to answer `$expand` without scanning member rows.
  Returns `{:version :member-count :member-systems}` or nil for an unknown
  URL — one PK-indexed query, no compose blob read. A non-nil
  `:member-count` means membership lives in `valueset_member`."
  [ds url version]
  (when url
    (let [rows (jdbc/execute! ds ["SELECT version, member_count, member_systems, member_id_lo, member_id_hi
                                   FROM valueset WHERE url = ?" url])]
      (when (seq rows)
        (let [chosen (resolve-vs-version (map :valueset/version rows) version)
              row    (some #(when (= chosen (:valueset/version %)) %) rows)]
          {:version        chosen
           :member-count   (:valueset/member_count row)
           :member-systems (some-> (:valueset/member_systems row) parse-json)
           :member-id-lo   (:valueset/member_id_lo row)
           :member-id-hi   (:valueset/member_id_hi row)})))))

(defn- load-vs-entry
  "Point-read a ValueSet's entry for an already-resolved concrete
  `version`. `summary?` skips the `compose` column entirely — a `_summary`
  listing only needs the metadata, and `compose` blobs run to megabytes,
  so reading and JSON-parsing one just to drop it is the dominant cost of
  a summary page."
  ([ds url version] (load-vs-entry ds url version false))
  ([ds url version summary?]
   (some-> (jdbc/execute-one! ds
             (if summary?
               ["SELECT url, version, metadata FROM valueset_resource WHERE url = ? AND version = ?"
                url version]
               ["SELECT url, version, metadata, compose FROM valueset_resource WHERE url = ? AND version = ?"
                url version]))
           valueset-row->entry)))

(defn- limit-offset-sql [cnt offset]
  (cond
    (and cnt (pos? (or offset 0))) (str " LIMIT " (long cnt) " OFFSET " (long offset))
    cnt                            (str " LIMIT " (long cnt))
    (pos? (or offset 0))           (str " LIMIT -1 OFFSET " (long offset))
    :else                          ""))

(defn- member-row->concept [display-langs row]
  (let [designations (some->> (:valueset_member/designations row)
                              parse-json
                              (mapv compose/normalise-designation))
        lang-display (when (seq display-langs)
                       (display/find-display-for-language designations display-langs))]
    (cond-> {:code    (:valueset_member/code row)
             :system  (:valueset_member/system row)
             :display (or lang-display (:valueset_member/display row))}
      (:valueset_member/system_version row) (assoc :version (:valueset_member/system_version row))
      (seq designations)                    (assoc :designations designations))))

(defn- expand-from-members
  "Expand a materialised ValueSet straight from `valueset_member`, paging in
  SQL with no compose parse. Produces the same result shape as
  `compose/stored-extensional-expand`.

  Unfiltered: `:total` is the precomputed `member-count` (no scan) and the
  page is a clustered index range. Filtered: a single windowed
  (`count(*) OVER ()`) query over `valueset_member_fts` — autocomplete-style
  token-prefix matching (`fts-query`), bounded to this ValueSet's member-id
  range via `rowid BETWEEN` (when known) so the FTS scan touches only its
  rows. The range covers every member and the `vs_url` filter discards any
  foreign rows in it, so correctness doesn't rely on the ids being
  contiguous. used-codesystems / compose-pins come from the precomputed
  `member-systems`."
  [ds
   {:keys [version member-count member-systems member-id-lo member-id-hi]}
   {:keys [url offset filter displayLanguage] cnt :count}]
  (let [display-langs (display/parse-display-language displayLanguage)
        lim   (limit-offset-sql cnt offset)
        fts-q (fts-query filter)
        ->concept #(member-row->concept display-langs %)
        ;; `plan` + reduce: build concepts straight off the ResultSet, never
        ;; allocating next.jdbc's intermediate per-row map. The page is
        ;; `count`-bounded and consumed eagerly in-scope, so the connection
        ;; closes with the reduction — no lazy row escapes the query.
        [total concepts]
        (cond
          (str/blank? filter)
          [member-count
           (into [] (map ->concept)
                 (jdbc/plan ds [(str "SELECT system, system_version, code, display, designations "
                                     "FROM valueset_member WHERE vs_url=? AND vs_version=?"
                                     " ORDER BY ord" lim)
                                url (v version)]))]

          ;; filter present but no word tokens (e.g. punctuation) → no matches
          (nil? fts-q) [0 []]

          :else
          (let [rng (when (and member-id-lo member-id-hi) [member-id-lo member-id-hi])
                sql (str "SELECT m.system, m.system_version, m.code, m.display, m.designations, "
                         "count(*) OVER () AS total "
                         "FROM valueset_member_fts f JOIN valueset_member m ON m.id = f.rowid "
                         "WHERE f.valueset_member_fts MATCH ? AND m.vs_url=? AND m.vs_version=?"
                         (when rng " AND f.rowid BETWEEN ? AND ?")
                         " ORDER BY m.ord" lim)
                ;; the windowed `total` repeats on every row — capture it in the
                ;; same pass that builds the page.
                total* (volatile! nil)
                cs (persistent!
                     (reduce (fn [acc row]
                               (vreset! total* (:total row))
                               (conj! acc (->concept row)))
                             (transient [])
                             (jdbc/plan ds (into [sql fts-q url (v version)] rng))))]
            [(or (some-> @total* long) (when (zero? (or offset 0)) 0)) cs]))]
    (cond-> {:concepts              concepts
             :used-codesystems      (mapv (fn [m]
                                            (let [sys (get m "system") sv (get m "version")]
                                              {:uri (if sv (str sys "|" sv) sys)}))
                                          member-systems)
             :compose-pins          (into [] (keep (fn [m]
                                                     (when-let [sv (get m "version")]
                                                       {:system (get m "system") :version sv})))
                                          member-systems)
             :multi-version-systems #{}}
      total               (assoc :total total)
      (seq display-langs)  (assoc :display-language displayLanguage))))

(defn- like-pattern
  "Build a case-insensitive SQL `LIKE` pattern for a `::input/string-filter`,
  escaping `\\ % _` so they match literally."
  [{:keys [value modifier]}]
  (let [esc (str/replace (str/lower-case value) #"([\\%_])" "\\\\$1")]
    (case (or modifier :starts-with)
      :starts-with (str esc "%")
      :contains    (str "%" esc "%"))))

(defn- string-filter-sql
  "WHERE fragment + bind params matching a `valueset_resource.metadata`
  JSON field (`r.metadata`) against a `::input/string-filter`. `:exact`
  is a case-sensitive equality; the others are case-insensitive `LIKE`."
  [json-key {:keys [modifier] :as f}]
  (if (= :exact modifier)
    [(str "json_extract(r.metadata,'$." json-key "') = ?") (:value f)]
    [(str "lower(json_extract(r.metadata,'$." json-key "')) LIKE ? ESCAPE '\\'")
     (like-pattern f)]))

;; ---------------------------------------------------------------------------
;; Catalogue listing — `cs-metadata`/`vs-metadata` honour `:_count` by
;; pushing `ORDER BY url,version LIMIT` into SQL. Both tables are
;; `WITHOUT ROWID`, clustered on `(url, version)` = the listing sort order,
;; so a page reads only `:_count` rows with no sort or full scan.
;; ---------------------------------------------------------------------------

(defn- col-filter-sql
  "WHERE fragment + bind matching a direct catalogue column against a
  `::input/string-filter`. `:exact` is case-sensitive equality; the
  others a case-insensitive `LIKE`."
  [col {:keys [modifier] :as f}]
  (if (= :exact modifier)
    [(str col " = ?") (:value f)]
    [(str "lower(" col ") LIKE ? ESCAPE '\\'") (like-pattern f)]))

(defn- catalogue-where
  "Build `[where-sql binds]` for a catalogue listing over a clustered
  table whose columns are url/version/status/name/title/description.
  `where-sql` is nil when unfiltered."
  [{:keys [url version status name title description]}]
  (let [clauses (cond-> []
                  url                        (conj ["url = ?" url])
                  (not (str/blank? version)) (conj ["version = ?" version])
                  status                     (conj ["status = ?" status])
                  name                       (conj (col-filter-sql "name" name))
                  title                      (conj (col-filter-sql "title" title))
                  description                (conj (col-filter-sql "description" description)))]
    [(when (seq clauses) (str " WHERE " (str/join " AND " (map first clauses))))
     (mapcat rest clauses)]))

(defn- limit-clause [_count]
  (when _count (str " LIMIT " (long _count))))

;; ---------------------------------------------------------------------------
;; ConceptMap catalogue
;; ---------------------------------------------------------------------------


;; ---------------------------------------------------------------------------
;; Public constructor
;; ---------------------------------------------------------------------------

(deftype FtrmProvider [^Closeable ds cs-cache cs-latest cm-cache cm-latest]
  Closeable
  (close [_] (.close ds))

  protos/NamingService
  (naming-resolver [_] (fn [id] (db/resolve-identifier ds id)))

  protos/CodeSystem
  (cs-metadata [_ {:keys [_count] :as opts}]
    ;; Page `codesystem_meta` directly (clustered on (url,version)) so a
    ;; `:_count` listing reads only its page; routing/index callers pass
    ;; no `:_count` and stream the lot, still in (url,version) order.
    (let [[where binds] (catalogue-where opts)
          sql (str "SELECT url, version, content, case_sensitive, supplements"
                   " FROM codesystem_meta" where
                   " ORDER BY url, version" (limit-clause _count))]
      (into []
            (map (fn [{:keys [url version content case_sensitive supplements]}]
                   (cond-> {:url url}
                     (seq version)          (assoc :version version)
                     content                (assoc :content content)
                     (some? case_sensitive) (assoc :case-sensitive (= 1 case_sensitive))
                     supplements            (assoc :supplements supplements))))
            (jdbc/plan ds (into [sql] binds)))))

  (cs-resource [_ params]
    (let [meta (lookup-entry cs-cache cs-latest (:url params) (params-version params))]
      (cs-resource-from-meta meta)))

  (cs-lookup [_ {:keys [system code displayLanguage properties] :as params}]
    (let [meta (lookup-entry cs-cache cs-latest system (params-version params))
          {:keys [url version name case-sensitive]} meta]
      (if-not meta
        (issues/unknown-system-lookup system code)
        (with-open [conn (jdbc/get-connection ds)]
          (if-let [row (fetch-concept-row conn url version code case-sensitive)]
            (let [actual-code (:concept/code row)
                  inactive? (int->bool (:concept/inactive row))
                  abstract? (int->bool (:concept/abstract row))
                  {:keys [want? want-typed?]} (property-filter/parse properties)
                  display-langs (display/parse-display-language displayLanguage)
                  ;; If the caller asks for designations explicitly we
                  ;; must return all of them. When designations are only
                  ;; needed to pick a display for the supplied
                  ;; `displayLanguage`, restrict the SQL to those
                  ;; languages — turns a 66-row scan into a 2-3 row
                  ;; lookup on the same index.
                  designation-langs (when (and (seq display-langs) (not (want? "designation")))
                                      (mapv :lang display-langs))
                  fetch-desigs? (or (want? "designation") (seq display-langs))
                  designations (when fetch-desigs?
                                 (fetch-designations conn url version actual-code
                                                     designation-langs))
                  parents      (when (want? "parent")
                                 (fetch-parents conn url version actual-code))
                  children     (when (want? "child")
                                 (fetch-children conn url version actual-code))
                  prop-rows    (when want-typed?
                                 (fetch-properties conn url version actual-code))
                  lang-display (when (seq display-langs)
                                 (display/find-display-for-language designations display-langs))]
              {:name        name
               :version     version
               :display     (or lang-display (:concept/display row))
               :system      url
               :code        (keyword actual-code)
               :definition  (:concept/definition row)
               :abstract    (boolean abstract?)
               :properties  (concat
                              ;; `inactive` is a typed concept property
                              ;; (http://hl7.org/fhir/concept-properties#inactive),
                              ;; not a slice — gate by name not by the
                              ;; slice flag.
                              (when (want? "inactive")
                                [{:code :inactive :value (boolean inactive?)}])
                              (when parents
                                (mapv (fn [{:keys [code display]}]
                                        (cond-> {:code :parent :value (keyword code)}
                                          display (assoc :description display)))
                                      parents))
                              (when children
                                (mapv (fn [{:keys [code display]}]
                                        (cond-> {:code :child :value (keyword code)}
                                          display (assoc :description display)))
                                      children))
                              (when prop-rows
                                (keep property->lookup-entry prop-rows)))
               ;; Designations fetched solely for display selection are
               ;; consumed by `find-display-for-language` and dropped
               ;; — only emit them on the wire when the caller asked.
               :designations (if (want? "designation") (or designations []) [])})
            (issues/unknown-code-lookup url code))))))

  (cs-validate-code [_ {:keys [system code display displayLanguage] :as params}]
    (let [meta (lookup-entry cs-cache cs-latest system (params-version params))
          {:keys [url version case-sensitive]} meta]
      (if (nil? meta)
        (issues/unknown-system-validate system code)
        (with-open [conn (jdbc/get-connection ds)]
          (let [row (fetch-concept-row conn url version code case-sensitive)]
            (if (nil? row)
              (not-found-result meta code)
              (let [actual-code (:concept/code row)
                    case-differs? (and (false? case-sensitive) (not= code actual-code))
                    primary-display (:concept/display row)
                    inactive-row?  (= 1 (:concept/inactive row))
                    status-row     (:concept/status row)
                    inactive-status (when (#{"retired" "inactive"} status-row) status-row)
                    inactive?      (or inactive-row? (some? inactive-status))
                    display-langs (display/parse-display-language displayLanguage)
                    ;; Designations are only needed to (a) verify the
                    ;; supplied display, or (b) pick a language-specific
                    ;; display. Skip the read entirely otherwise. The
                    ;; SQL language filter is only applied on the (b)
                    ;; path; when `display` is set, the unhappy path
                    ;; passes the full designation set to
                    ;; `format-display-mismatch` so it can enumerate
                    ;; alternatives across languages.
                    need-designations? (or display (seq display-langs))
                    designation-langs (when (and (seq display-langs) (nil? display))
                                        (mapv :lang display-langs))
                    designations (when need-designations?
                                   (fetch-designations conn url version actual-code
                                                       designation-langs))
                    concept {:code actual-code :display primary-display :designations designations}
                    best-display (or (when (seq display-langs)
                                       (display/find-display-for-language designations display-langs))
                                     primary-display)
                    base (cond-> {:result true
                                  :display best-display
                                  :code (keyword code)
                                  :system url
                                  :version version}
                           inactive?     (assoc :inactive true
                                                :inactive-status (or inactive-status "inactive"))
                           case-differs? (assoc :normalized-code (keyword actual-code)))
                    case-issue (when case-differs?
                                 {:severity     "information"
                                  :type         "business-rule"
                                  :details-code "code-rule"
                                  :text         (issues/format-case-mismatch
                                                 code actual-code url version)
                                  :expression   ["Coding.code"]})
                    display-issue (when (and display (not (display/display-matches? concept display display-langs)))
                                    {:severity     "error"
                                     :type         "invalid"
                                     :details-code "invalid-display"
                                     :text         (issues/format-display-mismatch
                                                    display url code primary-display designations
                                                    displayLanguage
                                                    (get-in meta [:metadata "language"]))
                                     :expression   ["Coding.display"]})
                    issues (filterv some? [case-issue display-issue])]
                (cond-> base
                  display-issue (assoc :result false :message (:text display-issue))
                  (seq issues) (assoc :issues issues)))))))))

  (cs-subsumes [_ {:keys [systemA system codeA codeB] :as params}]
    ;; FHIR's $subsumes carries `systemA` / `systemB` (the composite has
    ;; already verified they're equal). Older callers may pass a single
    ;; `:system`; accept either spelling.
    (let [system'    (or systemA system)
          meta       (lookup-entry cs-cache cs-latest system' (params-version params))
          {:keys [url version case-sensitive]} meta]
      (if (nil? meta)
        (issues/unknown-system-subsumes system')
        (with-open [conn (jdbc/get-connection ds)]
          (let [rowA (fetch-concept-row conn url version codeA case-sensitive)
                rowB (fetch-concept-row conn url version codeB case-sensitive)]
            (cond
              (nil? rowA) (issues/unknown-code-subsumes url codeA "codeA")
              (nil? rowB) (issues/unknown-code-subsumes url codeB "codeB")
              :else
              {:outcome
               (let [actualA (:concept/code rowA)
                     actualB (:concept/code rowB)]
                 (cond
                   (= actualA actualB) "equivalent"
                   (jdbc/execute-one! conn
                     ["SELECT 1 FROM concept_ancestor
                       WHERE cs_url=? AND cs_version=? AND ancestor_code=? AND descendent_code=? LIMIT 1"
                      url (v version) actualA actualB])
                   "subsumes"
                   (jdbc/execute-one! conn
                     ["SELECT 1 FROM concept_ancestor
                       WHERE cs_url=? AND cs_version=? AND ancestor_code=? AND descendent_code=? LIMIT 1"
                      url (v version) actualB actualA])
                   "subsumed-by"
                   :else "not-subsumed"))}))))))

  (cs-expand* [_ {:keys [system version filters text active-only max-hits displayLanguage]
                         :as query}]
    (let [meta (lookup-entry cs-cache cs-latest (or system (:url query)) (or version (params-version query)))]
      (if (nil? meta)
        {:concepts []}
        (let [url (:url meta)
              ver (:version meta)
              {:keys [sql params post-filters issues]}
              (search-concepts-sql url ver filters text active-only max-hits (:case-sensitive meta))
              regex-on-property? (some (fn [{:keys [op property]}]
                                         (and (= "regex" op)
                                              (nil? (direct-column property))))
                                       filters)
              display-langs (display/parse-display-language displayLanguage)
              ;; Materialise each result-set row to a plain map so it
              ;; outlives the cursor step, then push post-filtering and
              ;; max-hits capping into the transducer so `plan` can stop
              ;; reading the DB once enough survivors are seen. Without
              ;; this the DB allocates the full candidate set even when
              ;; the caller asked for `count=10`.
              materialise (map (fn [row]
                                 {:concept/code           (:concept/code row)
                                  :concept/display        (:concept/display row)
                                  :concept/definition     (:concept/definition row)
                                  :concept/inactive       (:concept/inactive row)
                                  :concept/abstract       (:concept/abstract row)
                                  :concept/not_selectable (:concept/not_selectable row)
                                  :concept/status         (:concept/status row)}))
              ;; SQL applies LIMIT only when no post-filters; otherwise
              ;; we must cap survivors after filtering, which is what
              ;; lets `plan` short-circuit.
              limit-xf (when (and max-hits (seq post-filters))
                         (take (long max-hits)))]
          ;; `plan` keeps using `ds` (cursor lives on its own pool
          ;; check-out); per-row `fetch-properties` and `fetch-designations`
          ;; share `conn` so we collapse N pool check-outs to one.
          (with-open [conn (jdbc/get-connection ds)]
            (let [post-filter-xf (when (seq post-filters)
                                   (filter (fn [row]
                                             (let [enriched (cond-> row
                                                              regex-on-property?
                                                              (assoc ::row-properties
                                                                     (fetch-properties conn url ver (:concept/code row))))]
                                               (every? #(% enriched) post-filters)))))
                  xform (apply comp (filterv some? [materialise post-filter-xf limit-xf]))
                  survivors (into [] xform (jdbc/plan ds (into [sql] params)))
                  concepts (mapv (fn [row]
                                   (let [desigs (when (seq display-langs)
                                                  (fetch-designations conn url ver (:concept/code row)))]
                                     (search-concepts-row->concept url ver display-langs row desigs)))
                                 survivors)]
              (cond-> {:concepts concepts}
                (seq issues) (assoc :issues issues))))))))

  protos/ValueSet
  (vs-metadata [_ {:keys [url version status name title description _count]}]
    (let [string-clauses (cond-> []
                           name        (conj (string-filter-sql "name" name))
                           title       (conj (string-filter-sql "title" title))
                           description (conj (string-filter-sql "description" description)))
          meta-filter?   (or status (seq string-clauses))
          clauses        (-> (cond-> []
                               url                        (conj ["v.url = ?" url])
                               (not (str/blank? version)) (conj ["v.version = ?" version])
                               status                     (conj ["json_extract(r.metadata,'$.status') = ?" status]))
                             (into string-clauses))
          sql (str "SELECT v.url, v.version FROM valueset v"
                   (when meta-filter?
                     " JOIN valueset_resource r ON r.url = v.url AND r.version = v.version")
                   (when (seq clauses)
                     (str " WHERE " (str/join " AND " (map first clauses))))
                   ;; `valueset` is WITHOUT ROWID, clustered on (url,version),
                   ;; so this ORDER BY is the natural scan order — a `:_count`
                   ;; listing reads only its page.
                   " ORDER BY v.url, v.version"
                   (limit-clause _count))]
      ;; `plan` streams the result-set cursor straight into one vector via
      ;; the xform — no second collection (unlike `execute!`, which builds
      ;; its own vector first).
      (into []
            (map (fn [{:valueset/keys [url version]}]
                   (cond-> {:url url}
                     (not (str/blank? version)) (assoc :version version))))
            (jdbc/plan ds (into [sql] (mapcat rest) clauses)))))

  (vs-resource [_ {:keys [url summary?] :as params}]
    (when-let [{:keys [version]} (resolve-vs ds url
                                             (or (:valueSetVersion params) (params-version params)))]
      (some-> (load-vs-entry ds url version summary?) vs-resource-from-entry)))

  (vs-expand [_ svc params]
    (let [url (:url params)
          {:keys [version member-count] :as resolved}
          (resolve-vs ds url (or (:valueSetVersion params) (params-version params)))]
      (cond
        ;; Membership materialised and the request needs no per-concept
        ;; enrichment (`activeOnly` needs inactive status, `property=` needs
        ;; properties — neither is carried on member rows): page in SQL.
        (and member-count (not (:activeOnly params)) (empty? (:properties params)))
        (expand-from-members ds resolved params)

        version
        (let [entry (load-vs-entry ds url version)
              expanding (conj (or (:expanding params) #{}) url)]
          (or (compose/stored-extensional-expand (:compose entry) params)
              (compose/expand-compose svc (:compose entry)
                (assoc params :expanding expanding :purpose :expand)))))))

  (vs-validate-code [_ svc params]
    (when-let [{:keys [version]} (resolve-vs ds (:url params)
                                             (or (:valueSetVersion params) (params-version params)))]
      (vs-validate/validate-code svc (load-vs-entry ds (:url params) version) params)))

  protos/ConceptMap
  (cm-metadata [_ {:keys [url version]}]
    (eduction
     (filter (fn [[[k-url k-ver] _]]
               (and (or (nil? url)     (= url k-url))
                    (or (nil? version) (= version k-ver)))))
     (map (fn [[[k-url k-ver] {:keys [source-uri target-uri pairs]}]]
            (cond-> {:url    k-url
                     :system source-uri
                     :target target-uri}
              (not (str/blank? k-ver)) (assoc :version k-ver)
              (seq pairs)              (assoc :pairs pairs))))
     cm-cache))

  (cm-resource [_ params]
    (let [{:keys [url version name title status source-uri target-uri metadata]}
          (lookup-entry cm-cache cm-latest (:url params) (params-version params))
          description (get metadata "description")]
      (cond-> {:url url}
        version     (assoc :version version)
        name        (assoc :name name)
        title       (assoc :title title)
        status      (assoc :status status)
        description (assoc :description description)
        source-uri  (assoc :source-uri source-uri)
        target-uri  (assoc :target-uri target-uri))))

  (cm-translate [_ {:keys [code system target] :as params}]
    (let [entry (lookup-entry cm-cache cm-latest (:url params) (params-version params))
          {:keys [url version]} entry
          target-system (when (not= target (:target-uri entry)) target)
          rows (when entry
                 (if system
                   (jdbc/execute! ds
                     ;; Index `cme_fwd(cm_url, cm_version, source_system, source_code)`
                     ;; serves this lookup with all four columns covered.
                     ["SELECT target_system, target_code, target_display, equivalence, comment
                       FROM conceptmap_element
                       WHERE cm_url=? AND cm_version=? AND source_system=? AND source_code=?"
                      url (v version) system code])
                   (jdbc/execute! ds
                     ["SELECT target_system, target_code, target_display, equivalence, comment
                       FROM conceptmap_element
                       WHERE cm_url=? AND cm_version=? AND source_code=?"
                      url (v version) code])))
          matches (->> rows
                       (filter (fn [r]
                                 (or (nil? target-system)
                                     (= target-system (:conceptmap_element/target_system r)))))
                       (mapv (fn [r]
                               (cond-> {:equivalence (:conceptmap_element/equivalence r)
                                        :system      (:conceptmap_element/target_system r)
                                        :code        (:conceptmap_element/target_code r)}
                                 (:conceptmap_element/target_display r)
                                 (assoc :display (:conceptmap_element/target_display r))))))]
      (if (seq matches)
        {:result true :matches matches}
        {:result false :message "No matches found"}))))

(defn open
  "Open a FHIR terminology SQLite container and return the
  `FtrmProvider`. The provider is `Closeable` and satisfies
  CodeSystem + ValueSet + ConceptMap; resource-type methods return
  empty results when the container holds no rows of that type.

  OID/URN/alias resolution is served by `NamingService`: the provider
  resolves an identifier to its canonical URL against `naming_system_id`
  on demand, and the composite re-dispatches by that URL.

  Logs a per-resource-type tally at boot."
  [path]
  (let [ds       (db/open path)
        cs-cache (load-codesystem-cache ds)
        cm-cache (load-conceptmap-cache ds)
        by-type  (group-by :resource-type (db/list-resources ds))]
    (log/info "opened FTRM container"
              {:source path
               :codesystems (count (get by-type "CodeSystem" []))
               :valuesets   (count (get by-type "ValueSet" []))
               :conceptmaps (count (get by-type "ConceptMap" []))})
    (->FtrmProvider ds cs-cache (latest-by-url cs-cache) cm-cache (latest-by-url cm-cache))))
