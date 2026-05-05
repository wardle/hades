(ns com.eldrix.hades.impl.sqlite.provider
  "Persistent CodeSystem/ValueSet/ConceptMap providers backed by a
  FHIR terminology SQLite container (built by `impl/index/sqlite`).

  One catalogue impl per resource type per `.db`, registered under
  every key it serves — same pattern Hermes uses for SNOMED. Each
  catalogue holds an immutable `{[url version] → entry}` map loaded at
  boot via three bulk SELECTs (one per table); JSON-shaped slots
  (compose, property-defs, pass-through metadata) are parsed once at
  load and stored as Clojure data on the entry.

  Operations:
    - `cs-lookup`, `cs-validate-code`        — direct point queries with
                                                displayLanguage selection
    - `cs-find-matches`                      — FTS + filter pushdown
    - `cs-subsumes`                          — closure-table lookup
    - `vs-expand`, `vs-validate-code`        — delegated to compose engine
    - `cm-translate`                         — direct point query

  Behavioural fidelity still trails the in-memory provider on
  case-insensitive lookup, supplements, and fragment-CS warnings."
  (:require [charred.api :as charred]
            [clojure.string :as str]
            [com.eldrix.hades.impl.canonical :as canonical]
            [com.eldrix.hades.impl.compose :as compose]
            [com.eldrix.hades.impl.display :as display]
            [com.eldrix.hades.impl.issues :as issues]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [com.eldrix.hades.impl.vs-validate :as vs-validate]
            [next.jdbc :as jdbc])
  (:import (com.google.re2j Pattern PatternSyntaxException)))

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
  {:url      (:valueset/url row)
   :version  (system-version-or-nil (:valueset/version row))
   :metadata (parse-json (:valueset/metadata row))
   :compose  (parse-json (:valueset/compose  row))})

(defn- conceptmap-row->entry [row]
  {:url            (:conceptmap/url row)
   :version        (system-version-or-nil (:conceptmap/version row))
   :source-uri     (:conceptmap/source_uri row)
   :source-version (:conceptmap/source_version row)
   :target-uri     (:conceptmap/target_uri row)
   :target-version (:conceptmap/target_version row)
   :metadata       (parse-json (:conceptmap/metadata row))})

(defn- load-codesystem-cache [ds]
  (into {}
        (map (fn [row]
               [(key-for (:codesystem_meta/url row) (:codesystem_meta/version row))
                (codesystem-row->entry row)]))
        (jdbc/execute! ds ["SELECT * FROM codesystem_meta"])))

(defn- load-valueset-cache [ds]
  (into {}
        (map (fn [row]
               [(key-for (:valueset/url row) (:valueset/version row))
                (valueset-row->entry row)]))
        (jdbc/execute! ds ["SELECT url, version, metadata, compose FROM valueset"])))

(defn- load-conceptmap-cache [ds]
  (into {}
        (map (fn [row]
               [(key-for (:conceptmap/url row) (:conceptmap/version row))
                (conceptmap-row->entry row)]))
        (jdbc/execute! ds ["SELECT * FROM conceptmap"])))

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

(defn- lookup-entry [cache url version]
  (or (when (not (str/blank? version)) (get cache (key-for url version)))
      (get cache (key-for url ""))
      ;; If the registered key was bare-URL but the cache only has
      ;; versioned forms, pick the SemVer-latest entry for this URL.
      ;; Healthcare safety: hash-map iteration order is unspecified, so
      ;; we must explicitly rank by version (per FHIR tx-ecosystem and
      ;; the composite's `pick-latest-semver` policy).
      (->> cache
           (reduce-kv (fn [best [k-url k-ver] entry]
                        (if (= url k-url)
                          (if (or (nil? best)
                                  (pos? (canonical/semver-compare k-ver (first best))))
                            [k-ver entry]
                            best)
                          best))
                      nil)
           second)))

(defn- params-version [params]
  (or (:version params) (:system-version params)))

;; ---------------------------------------------------------------------------
;; cs-find-matches helpers (FTS + filter pushdown)
;; ---------------------------------------------------------------------------

(defn- fts-query
  "Translate a free-text query into FTS5 syntax. Splits on whitespace,
  drops tokens that contain only non-word characters, and double-quotes
  each remaining token (with embedded `\"` doubled per FTS5's literal
  rule). Tokens are implicitly AND-ed by FTS5. Returns nil for blank
  input or input that produces no usable tokens."
  [^String text]
  (when-not (str/blank? text)
    (let [tokens (->> (str/split text #"\s+")
                      (map #(str/replace % #"[\"]" ""))
                      (map #(str/replace % #"[^\p{L}\p{Nd}_-]" ""))
                      (remove str/blank?))]
      (when (seq tokens)
        (str/join " " (map (fn [t] (str \" t \")) tokens))))))

(defn- direct-column [property]
  ;; Filters keyed on `code` / `display` map to columns on `concept`
  ;; rather than `concept_property`.
  (case property
    "code"    "c.code"
    "display" "c.display"
    nil))

(defn- in-list-placeholders [n]
  (str/join "," (repeat n "?")))

(defn- filter->sql-clause
  "Translate one Query filter to {:sql ... :params [...] :post-filter ...}.
  `:post-filter` is a fn `concept-row -> boolean` applied after the SQL
  result lands; used for `regex` (SQLite has no portable REGEXP) and for
  any unsupported op (returns false to drop)."
  [{:keys [property op value]}]
  (case op
    ("is-a")
    {:sql (str "(c.code = ? OR EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND a.ancestor_code=? AND a.descendent_code=c.code))")
     :params [value value]}

    ("descendent-of" "descendant-of")
    {:sql (str "EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND a.ancestor_code=? AND a.descendent_code=c.code)")
     :params [value]}

    ("is-not-a")
    {:sql (str "(c.code <> ? AND NOT EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND a.ancestor_code=? AND a.descendent_code=c.code))")
     :params [value value]}

    ("generalizes")
    {:sql (str "(c.code = ? OR EXISTS (SELECT 1 FROM concept_ancestor a "
               "WHERE a.cs_url=c.cs_url AND a.cs_version=c.cs_version "
               "AND a.ancestor_code=c.code AND a.descendent_code=?))")
     :params [value value]}

    "="
    (if-let [col (direct-column property)]
      {:sql (str col " = ?") :params [value]}
      {:sql (str "EXISTS (SELECT 1 FROM concept_property p "
                 "WHERE p.cs_url=c.cs_url AND p.cs_version=c.cs_version "
                 "AND p.code=c.code AND p.prop_code=? "
                 "AND COALESCE(p.value_str, CAST(p.value_int AS TEXT), CAST(p.value_dec AS TEXT)) = ?)")
       :params [property value]})

    "in"
    (let [parts (str/split value #",")]
      (if-let [col (direct-column property)]
        {:sql (str col " IN (" (in-list-placeholders (count parts)) ")")
         :params (vec parts)}
        {:sql (str "EXISTS (SELECT 1 FROM concept_property p "
                   "WHERE p.cs_url=c.cs_url AND p.cs_version=c.cs_version "
                   "AND p.code=c.code AND p.prop_code=? "
                   "AND COALESCE(p.value_str, CAST(p.value_int AS TEXT), CAST(p.value_dec AS TEXT)) "
                   "IN (" (in-list-placeholders (count parts)) "))")
         :params (into [property] parts)}))

    "not-in"
    (let [parts (str/split value #",")]
      (if-let [col (direct-column property)]
        {:sql (str col " NOT IN (" (in-list-placeholders (count parts)) ")")
         :params (vec parts)}
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

(defn- find-matches-sql
  "Compose the SQL for `cs-find-matches`. Returns `{:sql :params :post-filters :issues}`.

  When a free-text query is supplied the FTS5 virtual table is JOINed
  in and results are ordered by `rank` (relevance) so that LIMIT cuts
  off least-relevant matches first.

  `LIMIT` is pushed into SQL only when there are no post-filters
  (regex). With post-filters present, SQL would drop survivors after
  the limit was applied, returning fewer rows than the caller asked
  for; the caller slices in Clojure instead. See `cs-find-matches`."
  [url version filters text active-only? max-hits]
  (let [base-where    ["c.cs_url = ?" "c.cs_version = ?"]
        base-params   [url (v version)]
        active-clause (when active-only?
                        ["(c.inactive IS NULL OR c.inactive = 0)"])
        fts           (fts-query text)
        fts-from      (when fts " JOIN concept_fts f ON f.rowid = c.rowid ")
        fts-clause    (when fts ["f.concept_fts MATCH ?"])
        fts-params    (when fts [fts])
        filter-clauses (mapv filter->sql-clause filters)
        clause-sqls   (mapv :sql filter-clauses)
        clause-params (vec (mapcat :params filter-clauses))
        post-filters  (filterv some? (map :post-filter filter-clauses))
        unsupported   (keep :unsupported-op filter-clauses)
        where         (str/join " AND " (concat base-where active-clause fts-clause clause-sqls))
        order-by      (when fts " ORDER BY f.rank ")
        sql-limit?    (and max-hits (empty? post-filters))
        sql (str "SELECT c.rowid AS rowid, c.code, c.display, c.definition, "
                 "c.inactive, c.abstract, c.not_selectable, c.status "
                 "FROM concept c " (or fts-from "")
                 "WHERE " where
                 (or order-by "")
                 (when sql-limit? (str " LIMIT " (long max-hits))))]
    {:sql sql
     :params (vec (concat base-params fts-params clause-params))
     :post-filters post-filters
     :issues (when (seq unsupported)
               (mapv (fn [op]
                       {:severity "warning"
                        :type "not-supported"
                        :details-code "not-supported"
                        :text (str "The filter operation '" op "' is not supported")})
                     unsupported))}))

(defn- find-matches-row->concept [url version display-langs row designations]
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

(defn- cs-resource-from-meta [meta]
  (cond-> {}
    (:url meta)              (assoc :url (:url meta))
    (:version meta)          (assoc :version (:version meta))
    (:name meta)             (assoc :name (:name meta))
    (:title meta)            (assoc :title (:title meta))
    (:status meta)           (assoc :status (:status meta))
    (some? (:experimental meta)) (assoc :experimental (:experimental meta))
    (:description meta)      (assoc :description (:description meta))
    (:content meta)          (assoc :content (:content meta))
    (some? (:case-sensitive meta)) (assoc :case-sensitive (:case-sensitive meta))
    (:hierarchy-meaning meta) (assoc :hierarchy-meaning (:hierarchy-meaning meta))
    (:standards-status meta) (assoc :standards-status (:standards-status meta))))

(deftype SqliteCodeSystemCatalogue [ds cache]
  protos/CodeSystem
  (cs-metadata [_]
    (mapv (fn [[[url version] _]]
            (cond-> {:url url}
              (not (str/blank? version)) (assoc :version version)))
          cache))

  (cs-resource [_ params]
    (let [meta (lookup-entry cache (:url params) (params-version params))]
      (cs-resource-from-meta meta)))

  (cs-lookup [_ {:keys [system code displayLanguage properties] :as params}]
    (let [meta (lookup-entry cache system (params-version params))
          {:keys [url version name case-sensitive]} meta]
      (if-not meta
        (issues/unknown-system-lookup system code)
        (with-open [conn (jdbc/get-connection ds)]
          (if-let [row (fetch-concept-row conn url version code case-sensitive)]
            (let [actual-code (:concept/code row)
                  inactive? (int->bool (:concept/inactive row))
                  abstract? (int->bool (:concept/abstract row))
                  ;; FHIR `_property=…` filter. When unset, return the
                  ;; full lookup; when set, restrict to those slices.
                  ;; "designation"/"parent"/"child" are slice keys; any
                  ;; other entry is a typed-property code.
                  want         (when (seq properties) (set properties))
                  slice-keys   #{"designation" "parent" "child"}
                  want?        (fn [k] (or (nil? want) (contains? want k)))
                  want-typed?  (or (nil? want)
                                   (some #(not (slice-keys %)) want))
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
                              [{:code :inactive :value (boolean inactive?)}]
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
    (let [meta (lookup-entry cache system (params-version params))
          {:keys [url version case-sensitive]} meta]
      (if (nil? meta)
        {:result false :code (keyword code) :system system
         :message (str "No CodeSystem found for " system)}
        (with-open [conn (jdbc/get-connection ds)]
          (let [row (fetch-concept-row conn url version code case-sensitive)]
            (if (nil? row)
              (not-found-result meta code)
              (let [actual-code (:concept/code row)
                    case-differs? (and (false? case-sensitive) (not= code actual-code))
                    primary-display (:concept/display row)
                    display-langs (display/parse-display-language displayLanguage)
                    ;; Designations are only needed to (a) verify the
                    ;; supplied display, or (b) pick a language-specific
                    ;; display. Skip the read entirely otherwise. When a
                    ;; displayLanguage is set, restrict the SQL to that
                    ;; language so we don't pull every translation just
                    ;; to find one.
                    need-designations? (or display (seq display-langs))
                    designation-langs (when (seq display-langs)
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
                           case-differs? (assoc :normalized-code (keyword actual-code)))
                    case-issue (when case-differs?
                                 {:severity     "information"
                                  :type         "business-rule"
                                  :details-code "code-rule"
                                  :text         (str "The code '" code "' differs from the correct code '"
                                                     actual-code "' by case. Although the code system '"
                                                     url (when version (str "|" version))
                                                     "' is case insensitive, implementers are strongly "
                                                     "encouraged to use the correct case anyway")
                                  :expression   ["Coding.code"]})
                    display-issue (when (and display (not (display/display-matches? concept display display-langs)))
                                    (let [msg (str "The display '" display "' is incorrect for code '"
                                                   code "'; correct display is '" best-display "'")]
                                      {:severity     "error"
                                       :type         "invalid"
                                       :details-code "invalid-display"
                                       :text         msg
                                       :expression   ["Coding.display"]}))
                    issues (filterv some? [case-issue display-issue])]
                (cond-> base
                  display-issue (assoc :result false :message (:text display-issue))
                  (seq issues) (assoc :issues issues)))))))))

  (cs-subsumes [_ {:keys [systemA system codeA codeB] :as params}]
    ;; FHIR's $subsumes carries `systemA` / `systemB` (the composite has
    ;; already verified they're equal). Older callers may pass a single
    ;; `:system`; accept either spelling.
    (let [system' (or systemA system)
          meta (lookup-entry cache system' (params-version params))
          {:keys [url version]} meta]
      {:outcome
       (cond
         (= codeA codeB) "equivalent"
         (and meta
              (jdbc/execute-one! ds
                ["SELECT 1 FROM concept_ancestor
                  WHERE cs_url=? AND cs_version=? AND ancestor_code=? AND descendent_code=? LIMIT 1"
                 url (v version) codeA codeB]))
         "subsumes"
         (and meta
              (jdbc/execute-one! ds
                ["SELECT 1 FROM concept_ancestor
                  WHERE cs_url=? AND cs_version=? AND ancestor_code=? AND descendent_code=? LIMIT 1"
                 url (v version) codeB codeA]))
         "subsumed-by"
         :else "not-subsumed")}))

  (cs-find-matches [_ {:keys [system version filters text active-only max-hits displayLanguage]
                       :as query}]
    (let [meta (lookup-entry cache (or system (:url query)) (or version (params-version query)))]
      (if (nil? meta)
        {:concepts []}
        (let [url (:url meta)
              ver (:version meta)
              {:keys [sql params post-filters issues]}
              (find-matches-sql url ver filters text active-only max-hits)
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
                                     (find-matches-row->concept url ver display-langs row desigs)))
                                 survivors)]
              (cond-> {:concepts concepts}
                (seq issues) (assoc :issues issues)))))))))

;; ---------------------------------------------------------------------------
;; ValueSet catalogue
;; ---------------------------------------------------------------------------

(defn- vs-resource-from-entry [{:keys [url version metadata compose]}]
  {:url     url
   :version version
   :name    (get metadata "name")
   :title   (get metadata "title")
   :status  (get metadata "status")
   :experimental (get metadata "experimental")
   :compose compose})

(deftype SqliteValueSetCatalogue [_ds cache]
  protos/ValueSet
  (vs-metadata [_]
    (mapv (fn [[[url version] _]]
            (cond-> {:url url}
              (not (str/blank? version)) (assoc :version version)))
          cache))

  (vs-resource [_ params]
    (some-> (lookup-entry cache (:url params) (params-version params))
            vs-resource-from-entry))

  (vs-expand [_ svc params]
    (let [entry (lookup-entry cache (:url params) (params-version params))
          expanding (conj (or (:expanding params) #{}) (:url params))]
      (compose/expand-compose svc (:compose entry)
        (assoc params :expanding expanding :purpose :expand))))

  (vs-validate-code [_ svc params]
    (when-let [entry (lookup-entry cache (:url params) (params-version params))]
      (vs-validate/validate-code svc entry params))))

;; ---------------------------------------------------------------------------
;; ConceptMap catalogue
;; ---------------------------------------------------------------------------

(deftype SqliteConceptMapCatalogue [ds cache]
  protos/ConceptMap
  (cm-metadata [_]
    (mapv (fn [[[url version] entry]]
            (cond-> {:url url
                     :system (:source-uri entry)
                     :target (:target-uri entry)}
              (not (str/blank? version)) (assoc :version version)))
          cache))

  (cm-resource [_ params]
    (let [entry (lookup-entry cache (:url params) (params-version params))]
      (cond-> {:url (:url entry)}
        (:version entry)    (assoc :version    (:version entry))
        (:source-uri entry) (assoc :source-uri (:source-uri entry))
        (:target-uri entry) (assoc :target-uri (:target-uri entry)))))

  (cm-translate [_ {:keys [code system target] :as params}]
    (let [entry (lookup-entry cache (:url params) (params-version params))
          {:keys [url version source-uri target-uri]} entry
          source-system (or system source-uri)
          target-system (or target target-uri)
          rows (when entry
                 (jdbc/execute! ds
                   ;; Index `cme_fwd(cm_url, cm_version, source_system, source_code)`
                   ;; serves this lookup with all four columns covered.
                   ["SELECT target_system, target_code, target_display, equivalence, comment
                     FROM conceptmap_element
                     WHERE cm_url=? AND cm_version=? AND source_system=? AND source_code=?"
                    url (v version) source-system code]))
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
      {:result (boolean (seq matches))
       :matches matches})))

;; ---------------------------------------------------------------------------
;; Public constructor
;; ---------------------------------------------------------------------------

(defn open-providers
  "Open a FHIR terminology SQLite container and return:

    {:codesystem    ?cs-impl     ; nil when the .db has no CodeSystems
     :valueset      ?vs-impl
     :conceptmap    ?cm-impl
     :naming-system fn            ; (fn [id] -> canonical-url-or-nil)
     :datasource    ds}

  Each impl is one catalogue that serves every resource of its type
  in the file. The `:naming-system` fn closes over the datasource and
  resolves OID/URN aliases to canonical URLs via the `naming_system_id`
  table. Boot drivers register all four keys with the registry."
  [path]
  (let [ds (db/open path)
        cs-cache (load-codesystem-cache ds)
        vs-cache (load-valueset-cache ds)
        cm-cache (load-conceptmap-cache ds)]
    {:datasource ds
     :codesystem (when (seq cs-cache) (->SqliteCodeSystemCatalogue ds cs-cache))
     :valueset   (when (seq vs-cache) (->SqliteValueSetCatalogue   ds vs-cache))
     :conceptmap (when (seq cm-cache) (->SqliteConceptMapCatalogue ds cm-cache))
     :naming-system (fn [id] (db/resolve-system ds id))}))
