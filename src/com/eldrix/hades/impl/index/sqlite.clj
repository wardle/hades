(ns com.eldrix.hades.impl.index.sqlite
  "Persistent indexer: consume a `fhir-data` seq and write it into a
  FHIR terminology SQLite container (built by `impl/sqlite/db`).

  Semantics:
  - Update-in-place by `(resource_type, url, version)`. At build start we
    scan the seq for the resource tuples it produces, delete those rows
    from every storage table + `tx_resource`, then insert fresh.
  - Multi-version coexists in one file. Same-version replaces. Other
    resources already in the file are untouched.
  - Hierarchy closure is recomputed from scratch for any CodeSystem the
    build touches; closure rows for untouched CodeSystems are left
    alone.
  - `concept` rows surface inactive / abstract / not_selectable / status
    by projecting well-known property codes (per FHIR R4 convention)
    onto the concept row at insert time. The same values are also
    retained in `concept_property` for round-trip lookup."
  (:require [clojure.data.json :as json]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prep])
  (:import (java.sql Connection PreparedStatement)
           (java.time Instant)))

(def ^:private now-iso (fn [] (str (Instant/now))))

(defn- v [s] (or s ""))           ; SQLite uses '' rather than NULL in PKs.

(defn- ->bool01 [x]
  (cond (true? x) 1 (false? x) 0 :else nil))

;; ---------------------------------------------------------------------------
;; Bulk-insert tuning
;; ---------------------------------------------------------------------------

(defn- with-bulk-pragmas! [^Connection conn f]
  ;; Per-connection build-time pragmas for throughput. FK enforcement
  ;; is suspended for the build — the indexer respects FK invariants
  ;; by construction (concept rows before children/properties/
  ;; designations). `synchronous = OFF` is per-connection so it's
  ;; safe alongside other open pools. `journal_mode` is deliberately
  ;; NOT flipped — switching to MEMORY requires exclusive file access
  ;; and races with any other connection that may have the file open.
  ;; WAL + a single transaction already delivers acceptable bulk-insert
  ;; throughput.
  (jdbc/execute! conn ["PRAGMA foreign_keys = OFF"])
  (jdbc/execute! conn ["PRAGMA synchronous = OFF"])
  (jdbc/execute! conn ["PRAGMA temp_store = MEMORY"])
  (try (f)
       (finally
         (jdbc/execute! conn ["PRAGMA synchronous = NORMAL"])
         (jdbc/execute! conn ["PRAGMA foreign_keys = ON"]))))

(def ^:private batch-size 5000)

(defn- batch-insert!
  "Stream `rows` through one prepared statement, flushing every
  `batch-size` rows. Each row is a vector of params matching the SQL's
  `?` placeholders."
  [^Connection conn ^String sql rows]
  (with-open [^PreparedStatement ps (jdbc/prepare conn [sql])]
    (loop [batch [] xs (seq rows) total 0]
      (cond
        (and (nil? xs) (empty? batch)) total

        (or (nil? xs) (= batch-size (count batch)))
        (do (prep/execute-batch! ps batch)
            (recur [] xs (+ total (count batch))))

        :else
        (recur (conj batch (first xs)) (next xs) total)))))

;; ---------------------------------------------------------------------------
;; Property value extraction
;; ---------------------------------------------------------------------------

(defn- property-row-values
  "Map a raw FHIR property `{\"code\" \"...\" \"valueX\" ...}` into the
  polymorphic columns of `concept_property`. Returns nil when no
  recognised value field is present. The returned vector is the
  9-tuple of values that follow `(cs_url, cs_version, code, prop_code)`
  in the INSERT statement."
  [prop]
  (let [code (get prop "code")]
    (when code
      (cond
        (contains? prop "valueCode")
        [code "code" (str (get prop "valueCode")) nil nil nil nil nil nil nil]

        (contains? prop "valueString")
        [code "string" (get prop "valueString") nil nil nil nil nil nil nil]

        (contains? prop "valueInteger")
        [code "integer" nil (long (get prop "valueInteger")) nil nil nil nil nil nil]

        (contains? prop "valueBoolean")
        [code "boolean" nil nil (if (get prop "valueBoolean") 1 0) nil nil nil nil nil]

        (contains? prop "valueDecimal")
        [code "decimal" nil nil nil (double (get prop "valueDecimal")) nil nil nil nil]

        (contains? prop "valueDateTime")
        [code "dateTime" (get prop "valueDateTime") nil nil nil nil nil nil nil]

        (contains? prop "valueCoding")
        (let [c (get prop "valueCoding")]
          [code "Coding" nil nil nil nil
           (get c "system") (get c "code") (get c "display") nil])

        (contains? prop "valueQuantity")
        [code "Quantity" nil nil nil nil nil nil nil
         (json/write-str (get prop "valueQuantity"))]

        :else nil))))

(defn- well-known-property
  "Pull a well-known scalar property from a concept's :properties seq.
  Used to project `inactive` / `notSelectable` / `status` onto concept
  columns. Matches by FHIR property code, returns the typed value
  (boolean, string) or nil."
  [props target-code expected-type]
  (some (fn [p]
          (when (= target-code (get p "code"))
            (case expected-type
              :boolean (when (contains? p "valueBoolean") (get p "valueBoolean"))
              :code    (or (get p "valueCode") (get p "valueString"))
              nil)))
        props))

;; ---------------------------------------------------------------------------
;; Scan: which (resource-type, url, version) tuples will this build write?
;; ---------------------------------------------------------------------------

(defn- targets [fhir-data]
  (reduce (fn [acc fd]
            (case (:type fd)
              :codesystem-meta (conj acc ["CodeSystem" (v (:url fd)) (v (:version fd))])
              :valueset        (conj acc ["ValueSet"   (v (:url fd)) (v (:version fd))])
              :conceptmap      (conj acc ["ConceptMap" (v (:url fd)) (v (:version fd))])
              acc))
          #{} fhir-data))

;; ---------------------------------------------------------------------------
;; Delete pass — invalidate every row keyed by a target tuple
;; ---------------------------------------------------------------------------

(defn- delete-targets! [^Connection conn target-set]
  (let [cs-targets (filter #(= "CodeSystem"  (first %)) target-set)
        vs-targets (filter #(= "ValueSet"    (first %)) target-set)
        cm-targets (filter #(= "ConceptMap"  (first %)) target-set)]
    (doseq [[_ url version] cs-targets]
      ;; Order matters: dependents first, then concept (FK referent),
      ;; then codesystem_meta (FK referent for concept).
      (jdbc/execute! conn ["DELETE FROM concept_property    WHERE cs_url=? AND cs_version=?" url version])
      (jdbc/execute! conn ["DELETE FROM concept_designation WHERE cs_url=? AND cs_version=?" url version])
      (jdbc/execute! conn ["DELETE FROM concept_parent      WHERE cs_url=? AND cs_version=?" url version])
      (jdbc/execute! conn ["DELETE FROM concept_ancestor    WHERE cs_url=? AND cs_version=?" url version])
      (jdbc/execute! conn ["DELETE FROM concept             WHERE cs_url=? AND cs_version=?" url version])
      (jdbc/execute! conn ["DELETE FROM codesystem_meta     WHERE url=?    AND version=?"    url version]))
    (doseq [[_ url version] vs-targets]
      (jdbc/execute! conn ["DELETE FROM valueset_expansion  WHERE vs_url=? AND vs_version=?" url version])
      (jdbc/execute! conn ["DELETE FROM valueset            WHERE url=?    AND version=?"    url version]))
    (doseq [[_ url version] cm-targets]
      (jdbc/execute! conn ["DELETE FROM conceptmap_element  WHERE cm_url=? AND cm_version=?" url version])
      (jdbc/execute! conn ["DELETE FROM conceptmap          WHERE url=?    AND version=?"    url version]))
    (doseq [[rt url version] target-set]
      (jdbc/execute! conn ["DELETE FROM tx_resource         WHERE resource_type=? AND url=? AND version=?"
                           rt url version]))))

;; ---------------------------------------------------------------------------
;; Insert passes
;; ---------------------------------------------------------------------------

(defn- insert-codesystems!
  [^Connection conn fhir-data]
  (let [metas (filter #(= :codesystem-meta (:type %)) fhir-data)
        rows  (mapv (fn [m]
                      [(v (:url m)) (v (:version m))
                       (->bool01 (:case-sensitive m))
                       (:hierarchy-meaning m)
                       (:content m)
                       (:supplements-target m)
                       (:status m)
                       (->bool01 (:experimental m))
                       (:name m)
                       (:title m)
                       (:description m)
                       (get-in m [:metadata "publisher"])
                       (when-let [j (get-in m [:metadata "jurisdiction"])]
                         (json/write-str j))
                       (:standards-status m)
                       (when-let [pd (:property-defs m)] (json/write-str pd))
                       (when-let [fd (:filter-defs m)]   (json/write-str fd))
                       (when-let [md (:metadata m)]      (json/write-str md))])
                    metas)]
    (when (seq rows)
      (batch-insert! conn
        "INSERT INTO codesystem_meta
           (url, version, case_sensitive, hierarchy_meaning, content, supplements,
            status, experimental, name, title, description, publisher, jurisdiction,
            standards_status, property_defs, filter_defs, metadata)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        rows))))

(defn- insert-concepts!
  [^Connection conn fhir-data]
  (let [concepts (filter #(= :concept (:type %)) fhir-data)
        rows (mapv (fn [c]
                     (let [props (:properties c)
                           inactive   (well-known-property props "inactive" :boolean)
                           abstract   (well-known-property props "abstract" :boolean)
                           not-sel    (well-known-property props "notSelectable" :boolean)
                           status     (well-known-property props "status" :code)]
                       [(v (:system c)) (v (:version c)) (:code c)
                        (:display c) (:definition c)
                        (->bool01 inactive)
                        (->bool01 abstract)
                        (->bool01 not-sel)
                        status]))
                   concepts)]
    (when (seq rows)
      (batch-insert! conn
        "INSERT INTO concept
           (cs_url, cs_version, code, display, definition,
            inactive, abstract, not_selectable, status)
         VALUES (?,?,?,?,?,?,?,?,?)"
        rows))
    (count rows)))

(defn- insert-parents!
  [^Connection conn fhir-data]
  ;; Concepts may carry a single `:parent-code` (FHIR JSON nested
  ;; concepts) or a `:parents` vector (LOINC ComponentHierarchyBySystem,
  ;; SNOMED RF2 is-a). Both shapes flow through the same INSERT.
  (let [rows (for [c fhir-data
                   :when (= :concept (:type c))
                   parent (or (seq (:parents c))
                              (when-let [p (:parent-code c)] [p]))
                   :when (and parent (not= parent (:code c)))]
               [(v (:system c)) (v (:version c)) (:code c) parent])]
    (when (seq rows)
      (batch-insert! conn
        "INSERT OR IGNORE INTO concept_parent
           (cs_url, cs_version, code, parent_code)
         VALUES (?,?,?,?)"
        rows))))

(defn- insert-properties!
  [^Connection conn fhir-data]
  (let [rows (for [c fhir-data
                   :when (= :concept (:type c))
                   p (:properties c)
                   :let [vals (property-row-values p)]
                   :when vals]
               (into [(v (:system c)) (v (:version c)) (:code c)] vals))]
    (when (seq rows)
      (batch-insert! conn
        "INSERT OR IGNORE INTO concept_property
           (cs_url, cs_version, code, prop_code, value_type,
            value_str, value_int, value_bool, value_dec,
            value_coding_system, value_coding_code, value_coding_display,
            value_quantity)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
        rows))))

(defn- insert-designations!
  [^Connection conn fhir-data]
  (let [rows (for [c fhir-data
                   :when (= :concept (:type c))
                   d (:designations c)
                   :let [u (:use d)]]
               [(v (:system c)) (v (:version c)) (:code c)
                (some-> (:language d) name)
                (:system u) (:code u) (:display u)
                (:value d)
                (when-let [ext (:extension d)] (json/write-str ext))])]
    (when (seq rows)
      (batch-insert! conn
        "INSERT OR IGNORE INTO concept_designation
           (cs_url, cs_version, code, language, use_system, use_code, use_display, value, extension)
         VALUES (?,?,?,?,?,?,?,?,?)"
        rows))))

(defn- insert-valuesets!
  [^Connection conn fhir-data]
  (let [vses (filter #(= :valueset (:type %)) fhir-data)
        rows (mapv (fn [vs]
                     (let [md (:metadata vs)]
                       [(v (:url vs)) (v (:version vs))
                        (get md "name")
                        (get md "title")
                        (get md "status")
                        (->bool01 (get md "experimental"))
                        (get md "publisher")
                        (when-let [j (get md "jurisdiction")] (json/write-str j))
                        (get md "description")
                        (when md (json/write-str md))
                        (when-let [c (:compose vs)]  (json/write-str c))]))
                   vses)]
    (when (seq rows)
      (batch-insert! conn
        "INSERT INTO valueset
           (url, version, name, title, status, experimental,
            publisher, jurisdiction, description, metadata, compose)
         VALUES (?,?,?,?,?,?,?,?,?,?,?)"
        rows))))

(defn- insert-conceptmaps!
  [^Connection conn fhir-data]
  (let [cms (filter #(= :conceptmap (:type %)) fhir-data)]
    (when (seq cms)
      (batch-insert! conn
        "INSERT INTO conceptmap
           (url, version, name, title, status, experimental,
            source_uri, source_version, target_uri, target_version,
            unmapped_mode, unmapped_code, unmapped_url, metadata)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        (mapv (fn [cm]
                (let [md (:metadata cm)
                      um (:unmapped cm)]
                  [(v (:url cm)) (v (:version cm))
                   (get md "name")
                   (get md "title")
                   (get md "status")
                   (->bool01 (get md "experimental"))
                   (:source-uri cm) (:source-version cm)
                   (:target-uri cm) (:target-version cm)
                   (:mode um) (:code um) (:url um)
                   (when md (json/write-str md))]))
              cms))
      (let [rows (for [cm cms
                       [gi g] (map-indexed vector (:groups cm))
                       el (:elements g)
                       t  (:target el)]
                   [(v (:url cm)) (v (:version cm)) (long gi)
                    (:source g) (:source-version g)
                    (:target g) (:target-version g)
                    (:code el) (:display el)
                    (:code t) (:display t)
                    (or (:equivalence t) "equivalent")
                    (:comment t)
                    (when-let [d (:depends-on t)] (json/write-str d))
                    (when-let [p (:product t)]    (json/write-str p))])]
        (when (seq rows)
          (batch-insert! conn
            "INSERT OR IGNORE INTO conceptmap_element
               (cm_url, cm_version, group_idx,
                source_system, source_version, target_system, target_version,
                source_code, source_display, target_code, target_display,
                equivalence, comment, depends_on, product)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            rows))))))

;; ---------------------------------------------------------------------------
;; Hierarchy closure
;; ---------------------------------------------------------------------------

(defn- ancestors-of
  "BFS from `start` over the `code → #{parents}` map. Returns a seq of
  `[ancestor depth]` tuples with the SHORTEST depth to each ancestor —
  the right semantics for polyhierarchic concepts."
  [parents-of start]
  (loop [frontier #{start} depth 1 seen #{} acc []]
    (let [next-set (into #{} (mapcat #(parents-of % #{})) frontier)
          fresh    (reduce disj next-set seen)]
      (if (empty? fresh)
        acc
        (recur fresh
               (inc depth)
               (into seen fresh)
               (into acc (map (fn [anc] [anc depth])) fresh))))))

(defn- closure-rows
  "Compute the (ancestor, descendent, depth) closure for one CodeSystem
  from a `code → #{parent-code}` map. Pure."
  [parents-of]
  (mapcat (fn [child]
            (map (fn [[anc d]] [anc child d])
                 (ancestors-of parents-of child)))
          (keys parents-of)))

(defn- rebuild-closure!
  [^Connection conn cs-targets]
  (doseq [[_ url version] cs-targets]
    (let [parents-of (reduce
                       (fn [m {:concept_parent/keys [code parent_code]}]
                         (update m code (fnil conj #{}) parent_code))
                       {}
                       (jdbc/execute! conn
                         ["SELECT code, parent_code FROM concept_parent
                           WHERE cs_url=? AND cs_version=?"
                          url version]))
          rows (mapv (fn [[anc desc d]] [url version anc desc (long d)])
                     (closure-rows parents-of))]
      (when (seq rows)
        (batch-insert! conn
          "INSERT INTO concept_ancestor
             (cs_url, cs_version, ancestor_code, descendent_code, depth)
           VALUES (?,?,?,?,?)"
          rows)))))

;; ---------------------------------------------------------------------------
;; FTS rebuild
;; ---------------------------------------------------------------------------

(defn- rebuild-fts! [^Connection conn]
  ;; External-content FTS5 tables are populated by issuing the special
  ;; 'rebuild' command against them. Cheaper than per-row triggers
  ;; during bulk load.
  (jdbc/execute! conn ["INSERT INTO concept_fts(concept_fts) VALUES('rebuild')"])
  (jdbc/execute! conn ["INSERT INTO designation_fts(designation_fts) VALUES('rebuild')"]))

;; ---------------------------------------------------------------------------
;; tx_resource catalogue update
;; ---------------------------------------------------------------------------

(defn- update-resource-catalogue!
  [^Connection conn fhir-data target-set]
  (let [now (now-iso)
        concepts-by-cs (->> fhir-data
                            (filter #(= :concept (:type %)))
                            (group-by (fn [c] [(v (:system c)) (v (:version c))])))]
    (doseq [[rt url version] target-set]
      (let [cnt (case rt
                  "CodeSystem" (count (get concepts-by-cs [url version] []))
                  "ValueSet"   nil
                  "ConceptMap" (->> fhir-data
                                    (filter #(and (= :conceptmap (:type %))
                                                  (= url (v (:url %)))
                                                  (= version (v (:version %)))))
                                    (mapcat :groups)
                                    (mapcat :elements)
                                    (mapcat :target)
                                    count
                                    (#(when (pos? %) %)))
                  nil)]
        (jdbc/execute! conn
          ["INSERT INTO tx_resource
              (resource_type, url, version, concept_count, imported_at)
            VALUES (?,?,?,?,?)"
           rt url version cnt now])))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn build!
  "Run a fhir-data seq through the SQLite indexer, writing into the
  database at `db-path`. Creates the file if absent (stamped with
  application_id + schema version). Update-in-place by
  `(resource_type, url, version)`.

  `opts`:
    :loader-type  optional string recorded in `tx_meta` (e.g. \"loinc-csv\").

  `build!` is a one-shot operation: it opens its own pooled datasource,
  runs the transaction, and closes the pool before returning. Callers
  query the file by reopening via `sqlite-provider/open-providers` (or
  `db/open`)."
  [^String db-path fhir-data {:keys [loader-type]}]
  (let [ds (db/create! db-path)
        target-set (targets fhir-data)
        cs-targets (filter #(= "CodeSystem" (first %)) target-set)]
    (try
      (with-open [conn (jdbc/get-connection ds)]
        (with-bulk-pragmas! conn
          (fn []
            (jdbc/with-transaction [tx conn]
              (delete-targets!     tx target-set)
              (insert-codesystems! tx fhir-data)
              (insert-concepts!    tx fhir-data)
              (insert-parents!     tx fhir-data)
              (insert-properties!  tx fhir-data)
              (insert-designations! tx fhir-data)
              (insert-valuesets!   tx fhir-data)
              (insert-conceptmaps! tx fhir-data)
              (rebuild-closure!    tx cs-targets)
              (update-resource-catalogue! tx fhir-data target-set)
              (rebuild-fts! tx))))
        ;; ANALYZE outside the transaction so its writes hit the new pages.
        (jdbc/execute! conn ["ANALYZE"]))
      (db/write-meta! ds (cond-> {:tx_schema_version (str db/schema-version)
                                  :built_at (now-iso)}
                           loader-type (assoc :loader_type loader-type)))
      {:db-path db-path
       :resources (->> target-set
                       (mapv (fn [[rt url version]]
                               {:resource-type rt
                                :url url
                                :version (when-not (= "" version) version)})))
       :totals {:targets (count target-set)
                :codesystem-targets (count cs-targets)}}
      (finally
        (db/close! ds)))))
