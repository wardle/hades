(ns com.eldrix.hades.providers.ftrm.index
  "Persistent indexer: consume a `fhir-data` seq and write it into a
  FHIR terminology SQLite container (built by `impl/ftrm/db`).

  Two-phase contract (mirrors Hermes `import` + `index`):

  - `build!` streams `fhir-data` once, dispatching on `:type` and
    flushing per-table batches as it goes. It writes raw rows
    (`codesystem_meta`, `concept`, `concept_parent`,
    `concept_property`, `concept_designation`, `valueset`,
    `conceptmap`, `conceptmap_element`) plus the `tx_resource`
    catalogue. Concept-bound auxiliary rows can also arrive as
    standalone fhir-data entries — `:concept-designation`,
    `:concept-property`, `:concept-parent` — so loaders that produce
    them lazily (e.g. LOINC PartLink / ComponentHierarchy) don't have
    to materialise per-code lookup maps. It does NOT compute the
    ancestor closure or rebuild the FTS tables; large imports stream
    straight to disk in bounded heap, then the derived structures are
    built afterwards.
  - `index!` reads `concept_parent` from the file, recomputes the
    ancestor closure for every CodeSystem present, and rebuilds the
    `concept_fts` / `designation_fts` tables. Drives the existing
    `clj -M:run index <db>` CLI for SQLite-backed terminology
    containers.

  Update-in-place semantics:
  - As `build!` streams, the FIRST time it encounters a
    `(resource-type, url, version)` tuple it deletes that tuple's
    rows from every storage table + `tx_resource` before the next
    insert. Single-pass; never walks the seq twice. Multi-version
    coexists in one file. Other resources already in the file are
    untouched.

  Concept rows surface inactive / abstract / not_selectable / status
  by projecting well-known property codes (per FHIR R4 convention)
  onto the concept row at insert time. The same values are also
  retained in `concept_property` for round-trip lookup."
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.providers.ftrm.db :as db]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prep])
  (:import (java.sql Connection PreparedStatement)
           (java.time Instant)
           (java.util ArrayList HashMap)))

(set! *warn-on-reflection* true)

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
  ;; by construction (codesystem_meta and concept rows are written
  ;; before children/properties/designations get flushed). `synchronous
  ;; = OFF` is per-connection so it's safe alongside other open pools.
  ;; `journal_mode` is deliberately NOT flipped — switching to MEMORY
  ;; requires exclusive file access and races with any other
  ;; connection that may have the file open. WAL + a single
  ;; transaction already delivers acceptable bulk-insert throughput.
  (jdbc/execute! conn ["PRAGMA foreign_keys = OFF"])
  (jdbc/execute! conn ["PRAGMA synchronous = OFF"])
  (jdbc/execute! conn ["PRAGMA temp_store = MEMORY"])
  (try (f)
       (finally
         (jdbc/execute! conn ["PRAGMA synchronous = NORMAL"])
         (jdbc/execute! conn ["PRAGMA foreign_keys = ON"]))))

(def ^:private batch-size 5000)

(defn- batch-insert!
  "One-shot batch insert used by `index!` (closure rebuild). For the
  streaming path see `flush-batcher!` below."
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
;; Per-target tx_resource cleanup — called lazily when a tuple is first
;; observed during the streaming insert. The data tables (concept,
;; codesystem_meta, valueset, …) handle PK collisions natively via
;; INSERT OR REPLACE, so no per-target DELETE is needed there. The
;; tx_resource catalogue lacks an OR REPLACE path (it's written once
;; per import after the rows land), so we clear any prior catalogue
;; row up-front to keep `imported_at` and `concept_count` honest.
;; ---------------------------------------------------------------------------

(defn- clear-tx-resource! [^Connection conn rt url version]
  (jdbc/execute! conn ["DELETE FROM tx_resource WHERE resource_type=? AND url=? AND version=?"
                       rt url version]))

(defn- clear-valueset-members! [^Connection conn url version]
  (jdbc/execute! conn ["DELETE FROM valueset_member WHERE vs_url=? AND vs_version=?"
                       url version]))

;; ---------------------------------------------------------------------------
;; Row builders — pure fns from a fhir-data entry to the params vector(s)
;; matching each prepared statement's placeholders.
;; ---------------------------------------------------------------------------

(defn- codesystem-meta-row [m]
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
   (when-let [j (get-in m [:metadata "jurisdiction"])] (json/write-str j))
   (:standards-status m)
   (when-let [pd (:property-defs m)] (json/write-str pd))
   (when-let [fd (:filter-defs m)]   (json/write-str fd))
   (when-let [md (:metadata m)]      (json/write-str md))])

(defn- concept-row [c]
  (let [props (:properties c)]
    [(v (:system c)) (v (:version c)) (:code c)
     (:display c) (:definition c)
     (->bool01 (well-known-property props "inactive" :boolean))
     (->bool01 (well-known-property props "abstract" :boolean))
     (->bool01 (well-known-property props "notSelectable" :boolean))
     (well-known-property props "status" :code)]))

(defn- concept-parent-rows [c]
  ;; Concepts may carry a single `:parent-code` (FHIR JSON nested
  ;; concepts) or a `:parents` vector (LOINC ComponentHierarchyBySystem,
  ;; SNOMED RF2 is-a). Both shapes flow through the same INSERT.
  (for [parent (or (seq (:parents c))
                   (when-let [p (:parent-code c)] [p]))
        :when (and parent (not= parent (:code c)))]
    [(v (:system c)) (v (:version c)) (:code c) parent]))

(defn- concept-property-rows [c]
  (for [p (:properties c)
        :let [vals (property-row-values p)]
        :when vals]
    (into [(v (:system c)) (v (:version c)) (:code c)] vals)))

(defn- concept-designation-rows [c]
  (for [d (:designations c)
        :let [u (:use d)]]
    [(v (:system c)) (v (:version c)) (:code c)
     (some-> (:language d) name)
     (:system u) (:code u) (:display u)
     (:value d)
     (when-let [ext (:extension d)] (json/write-str ext))]))

(defn- standalone-designation-row
  "Build a `concept_designation` row from a `:concept-designation`
  fhir-data entry — used by loaders (e.g. LOINC linguistic variants)
  that stream designations independently of their concept rows."
  [d]
  (let [u (:use d)]
    [(v (:system d)) (v (:version d)) (:code d)
     (some-> (:language d) name)
     (:system u) (:code u) (:display u)
     (:value d)
     (when-let [ext (:extension d)] (json/write-str ext))]))

(defn- standalone-property-row
  "Build a `concept_property` row from a `:concept-property` fhir-data
  entry — used by loaders that stream property links independently of
  their concept rows (e.g. LOINC LoincPartLink_Primary). Returns nil
  when the inner property has no recognised valueX field."
  [{:keys [system version code property]}]
  (when-let [vals (property-row-values property)]
    (into [(v system) (v version) code] vals)))

(defn- standalone-parent-row
  "Build a `concept_parent` row from a `:concept-parent` fhir-data entry
  — used by loaders that stream hierarchy edges independently of their
  concept rows (e.g. LOINC ComponentHierarchyBySystem). Returns nil for
  blank or self-referential edges."
  [{:keys [system version code parent]}]
  (when (and code parent (not= code parent))
    [(v system) (v version) code parent]))

(defn- member-systems-json
  "Distinct `{system, version?}` of `members`, as a JSON string for the
  `valueset.member_systems` column — lets `$expand` report
  used-codesystems / compose-pins without a DISTINCT scan over members.
  nil when no member carries a system."
  [members]
  (let [systems (->> members
                     (keep (fn [{:keys [system version]}]
                             (when system
                               (cond-> {"system" system} version (assoc "version" version)))))
                     distinct
                     seq)]
    (when systems (json/write-str systems))))

(defn- valueset-row [vs member-count member-systems]
  (let [md (:metadata vs)]
    [(v (:url vs)) (v (:version vs))
     (get md "name")
     (get md "title")
     (get md "status")
     (->bool01 (get md "experimental"))
     (get md "publisher")
     (when-let [j (get md "jurisdiction")] (json/write-str j))
     (get md "description")
     member-count
     member-systems]))

(defn- valueset-resource-row [vs]
  [(v (:url vs)) (v (:version vs))
   (when-let [md (:metadata vs)] (json/write-str md))
   (when-let [c (:compose vs)]   (json/write-str c))])

(defn- valueset-member-rows
  "Explode `members` (from `compose/extensional-members`) into
  `valueset_member` rows for ValueSet `vs`, numbered by authored order."
  [vs members]
  (map-indexed
    (fn [i {:keys [system version code display designations]}]
      [(v (:url vs)) (v (:version vs)) (long i)
       system version code display
       (when (seq designations) (json/write-str designations))])
    members))

(defn- conceptmap-row [cm]
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

(defn- conceptmap-element-rows [cm]
  (for [[gi g] (map-indexed vector (:groups cm))
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
     (when-let [p (:product t)]    (json/write-str p))]))

;; ---------------------------------------------------------------------------
;; SQL
;; ---------------------------------------------------------------------------

;; Data-row inserts (concept, valueset, conceptmap, designations,
;; properties, parents, conceptmap_element) use INSERT OR REPLACE / OR
;; IGNORE so a row whose composite primary key collides with an existing
;; one overwrites in place. Layered/last-wins semantics per row.
;;
;; codesystem_meta is the exception: it uses an UPSERT with a content-
;; precedence guard. A `content: 'not-present'` row is a stub — a
;; package may ship one purely so the URL is registerable for
;; downstream VS expansion / validation, intending the concepts to come
;; from a real terminology service. A stub must never overwrite a
;; non-stub meta, regardless of write order. Among same-rank rows the
;; unconditional column copy below gives last-wins.
;;
;; WHERE: skip the UPDATE only when the incoming row is a stub AND the
;; existing row is a non-stub. Every other combination (incoming non-
;; stub, existing stub OR non-stub; incoming stub, existing stub or
;; null) falls through to last-wins.
(def ^:private codesystem-meta-sql
  "INSERT INTO codesystem_meta
     (url, version, case_sensitive, hierarchy_meaning, content, supplements,
      status, experimental, name, title, description, publisher, jurisdiction,
      standards_status, property_defs, filter_defs, metadata)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
   ON CONFLICT(url, version) DO UPDATE SET
     case_sensitive    = excluded.case_sensitive,
     hierarchy_meaning = excluded.hierarchy_meaning,
     content           = excluded.content,
     supplements       = excluded.supplements,
     status            = excluded.status,
     experimental      = excluded.experimental,
     name              = excluded.name,
     title             = excluded.title,
     description       = excluded.description,
     publisher         = excluded.publisher,
     jurisdiction      = excluded.jurisdiction,
     standards_status  = excluded.standards_status,
     property_defs     = excluded.property_defs,
     filter_defs       = excluded.filter_defs,
     metadata          = excluded.metadata
   WHERE excluded.content != 'not-present'
      OR codesystem_meta.content IS NULL
      OR codesystem_meta.content = 'not-present'")

(def ^:private concept-sql
  "INSERT OR REPLACE INTO concept
     (cs_url, cs_version, code, display, definition,
      inactive, abstract, not_selectable, status)
   VALUES (?,?,?,?,?,?,?,?,?)")

(def ^:private concept-parent-sql
  "INSERT OR IGNORE INTO concept_parent
     (cs_url, cs_version, code, parent_code)
   VALUES (?,?,?,?)")

(def ^:private concept-property-sql
  "INSERT OR IGNORE INTO concept_property
     (cs_url, cs_version, code, prop_code, value_type,
      value_str, value_int, value_bool, value_dec,
      value_coding_system, value_coding_code, value_coding_display,
      value_quantity)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")

(def ^:private concept-designation-sql
  "INSERT OR IGNORE INTO concept_designation
     (cs_url, cs_version, code, language, use_system, use_code, use_display, value, extension)
   VALUES (?,?,?,?,?,?,?,?,?)")

(def ^:private valueset-sql
  "INSERT OR REPLACE INTO valueset
     (url, version, name, title, status, experimental,
      publisher, jurisdiction, description, member_count, member_systems)
   VALUES (?,?,?,?,?,?,?,?,?,?,?)")

(def ^:private valueset-resource-sql
  "INSERT OR REPLACE INTO valueset_resource (url, version, metadata, compose)
   VALUES (?,?,?,?)")

(def ^:private valueset-member-sql
  "INSERT OR REPLACE INTO valueset_member
     (vs_url, vs_version, ord, system, system_version, code, display, designations)
   VALUES (?,?,?,?,?,?,?,?)")

(def ^:private conceptmap-sql
  "INSERT OR REPLACE INTO conceptmap
     (url, version, name, title, status, experimental,
      source_uri, source_version, target_uri, target_version,
      unmapped_mode, unmapped_code, unmapped_url, metadata)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")

(def ^:private conceptmap-element-sql
  "INSERT OR IGNORE INTO conceptmap_element
     (cm_url, cm_version, group_idx,
      source_system, source_version, target_system, target_version,
      source_code, source_display, target_code, target_display,
      equivalence, comment, depends_on, product)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")

;; ---------------------------------------------------------------------------
;; Streaming insert
;; ---------------------------------------------------------------------------

(defn- flush-batch! [^Connection conn ^PreparedStatement ps ^ArrayList buf]
  (when-not (.isEmpty buf)
    (prep/execute-batch! ps (vec buf))
    (.clear buf)
    (.commit conn)))

(defn- add-row!
  "Append a row to a per-table buffer; flush+commit when it reaches `batch-size`."
  [^Connection conn ^PreparedStatement ps ^ArrayList buf row]
  (.add buf row)
  (when (>= (.size buf) batch-size)
    (flush-batch! conn ps buf)))

(defn- run-import!
  "Single-pass over `fhir-data`, dispatching on `:type`. Per-table
  prepared statements + bounded `ArrayList` buffers (single-threaded,
  scoped to this call) flush every `batch-size` rows.

  Tracks first-seen target tuples (CodeSystem / ValueSet / ConceptMap)
  and runs the corresponding `delete-…-target!` lazily before the
  first insert touching that tuple. Buffers are pre-flushed when
  needed so the delete sees a clean slate on disk for the rows it
  removes. Returns `{:targets #{[rt url version] …}}` — the catalogue
  write counts rows directly from the database after the streaming
  inserts commit."
  [^Connection conn take-fhir-data!]
  (with-open [^PreparedStatement ps-cs   (jdbc/prepare conn [codesystem-meta-sql])
              ^PreparedStatement ps-c    (jdbc/prepare conn [concept-sql])
              ^PreparedStatement ps-cp   (jdbc/prepare conn [concept-parent-sql])
              ^PreparedStatement ps-cpp  (jdbc/prepare conn [concept-property-sql])
              ^PreparedStatement ps-cd   (jdbc/prepare conn [concept-designation-sql])
              ^PreparedStatement ps-vs   (jdbc/prepare conn [valueset-sql])
              ^PreparedStatement ps-vsr  (jdbc/prepare conn [valueset-resource-sql])
              ^PreparedStatement ps-vsm  (jdbc/prepare conn [valueset-member-sql])
              ^PreparedStatement ps-cm   (jdbc/prepare conn [conceptmap-sql])
              ^PreparedStatement ps-cme  (jdbc/prepare conn [conceptmap-element-sql])]
    (let [b-cs   (ArrayList.)
          b-c    (ArrayList.)
          b-cp   (ArrayList.)
          b-cpp  (ArrayList.)
          b-cd   (ArrayList.)
          b-vs   (ArrayList.)
          b-vsr  (ArrayList.)
          b-vsm  (ArrayList.)
          b-cm   (ArrayList.)
          b-cme  (ArrayList.)
          targets   (HashMap.)        ; "rt|url|version" string → [rt url version]
          ;; Mark a (resource-type, url, version) as touched by this
          ;; import. First observation also clears any prior
          ;; tx_resource catalogue row so the post-import write isn't
          ;; rejected by the PK on (resource_type, url, version).
          ;; Code-keyed data rows (concept, conceptmap_element, …) overwrite
          ;; via INSERT OR REPLACE — no whole-resource DELETE. `valueset_member`
          ;; is the exception: it is keyed positionally (ord), so a re-import
          ;; whose membership shrank would leave stale high-ord rows that leak
          ;; phantom codes into $expand. Clear its prior definition on first
          ;; touch so each re-import is a wholesale replace.
          ensure! (fn [rt url version]
                    (let [k (str rt "|" url "|" version)]
                      (when-not (.containsKey targets k)
                        (clear-tx-resource! conn rt url version)
                        (when (= "ValueSet" rt)
                          (clear-valueset-members! conn url version))
                        (.put targets k [rt url version]))))]
      (loop []
        (when-let [item (take-fhir-data!)]
          (doseq [fd (if (map? item) [item] item)]
            (case (:type fd)
              :stream-error
              (throw (:ex fd))

              :codesystem-meta
              (let [url (v (:url fd)) version (v (:version fd))]
                (ensure! "CodeSystem" url version)
                (add-row! conn ps-cs b-cs (codesystem-meta-row fd)))

              :concept
              (let [url (v (:system fd)) version (v (:version fd))]
                (ensure! "CodeSystem" url version)
                (add-row! conn ps-c b-c (concept-row fd))
                (doseq [r (concept-parent-rows fd)]   (add-row! conn ps-cp  b-cp  r))
                (doseq [r (concept-property-rows fd)] (add-row! conn ps-cpp b-cpp r))
                (doseq [r (concept-designation-rows fd)] (add-row! conn ps-cd b-cd r)))

              :concept-designation
              (let [url (v (:system fd)) version (v (:version fd))]
                (ensure! "CodeSystem" url version)
                (add-row! conn ps-cd b-cd (standalone-designation-row fd)))

              :concept-property
              (let [url (v (:system fd)) version (v (:version fd))]
                (ensure! "CodeSystem" url version)
                (when-let [r (standalone-property-row fd)]
                  (add-row! conn ps-cpp b-cpp r)))

              :concept-parent
              (let [url (v (:system fd)) version (v (:version fd))]
                (ensure! "CodeSystem" url version)
                (when-let [r (standalone-parent-row fd)]
                  (add-row! conn ps-cp b-cp r)))

              :valueset
              (let [url (v (:url fd)) version (v (:version fd))
                    members (compose/extensional-members (:compose fd))]
                (ensure! "ValueSet" url version)
                (add-row! conn ps-vs b-vs
                          (valueset-row fd
                                        (when (seq members) (count members))
                                        (member-systems-json members)))
                (add-row! conn ps-vsr b-vsr (valueset-resource-row fd))
                (doseq [r (valueset-member-rows fd members)]
                  (add-row! conn ps-vsm b-vsm r)))

              :conceptmap
              (let [url (v (:url fd)) version (v (:version fd))]
                (ensure! "ConceptMap" url version)
                (add-row! conn ps-cm b-cm (conceptmap-row fd))
                (doseq [r (conceptmap-element-rows fd)]
                  (add-row! conn ps-cme b-cme r)))

              ;; OID/URN aliases — written directly (not batched): few
              ;; per import, and the upsert needs ON CONFLICT semantics.
              ;; Committed by the import's final `.commit conn`.
              :naming-system-id
              (db/upsert-naming-system-id! conn fd)

              ;; default — `:skipped` from FHIR JSON loader, etc.
              nil))
          (recur)))
      ;; Flush remaining buffers in dependency-friendly order.
      (flush-batch! conn ps-cs   b-cs)
      (flush-batch! conn ps-c    b-c)
      (flush-batch! conn ps-cp   b-cp)
      (flush-batch! conn ps-cpp  b-cpp)
      (flush-batch! conn ps-cd   b-cd)
      (flush-batch! conn ps-vs   b-vs)
      (flush-batch! conn ps-vsr  b-vsr)
      (flush-batch! conn ps-vsm  b-vsm)
      (flush-batch! conn ps-cm   b-cm)
      (flush-batch! conn ps-cme  b-cme)
      {:targets (into #{} (.values targets))})))

(defn- channel-taker [fhir-data-ch]
  #(async/<!! fhir-data-ch))

(defn- collection-taker [fhir-data]
  (let [it (.iterator ^Iterable fhir-data)]
    (fn []
      (when (.hasNext it)
        (.next it)))))

;; ---------------------------------------------------------------------------
;; Post-import cleanup: prune dangling parent edges
;;
;; FK enforcement is OFF during the streaming insert (see
;; `with-bulk-pragmas!`). Standalone `:concept-parent` entries can
;; therefore land rows whose child or parent code is not present in
;; `concept` — e.g. LOINC's `ComponentHierarchyBySystem.csv` references
;; roll-up LP- codes that are not published in `Part.csv`. Such edges
;; would silently expand the closure through phantom nodes, producing
;; subsumption results that point at codes the file doesn't define.
;;
;; We delete them on disk before commit, scoped to the targets touched
;; by this import so other CodeSystems already in the file are untouched.
;; ---------------------------------------------------------------------------

(defn- prune-dangling-parents!
  "Delete rows from `concept_parent` whose child or parent code has no
  matching `concept` row, restricted to the CodeSystem `(url, version)`
  tuples touched by this import. Returns the number of rows deleted."
  [^Connection conn cs-targets]
  (reduce
    (fn [n [url version]]
      (let [r (jdbc/execute-one! conn
                ["DELETE FROM concept_parent
                  WHERE cs_url=? AND cs_version=?
                    AND (NOT EXISTS (SELECT 1 FROM concept c
                                      WHERE c.cs_url=concept_parent.cs_url
                                        AND c.cs_version=concept_parent.cs_version
                                        AND c.code=concept_parent.code)
                      OR NOT EXISTS (SELECT 1 FROM concept c
                                      WHERE c.cs_url=concept_parent.cs_url
                                        AND c.cs_version=concept_parent.cs_version
                                        AND c.code=concept_parent.parent_code))"
                 url version])]
        (+ n (or (:next.jdbc/update-count r) 0))))
    0
    cs-targets))

;; ---------------------------------------------------------------------------
;; tx_resource catalogue update — driven by streaming counters
;; ---------------------------------------------------------------------------

(defn- count-rows
  "Count rows in `table` matching `(url-col, version-col) = (url, version)`.
  Used for `tx_resource.concept_count` so the catalogue reflects actual
  on-disk row counts after any per-CS dedup or layered overwrites."
  [^Connection conn table url-col version-col url version]
  (-> (jdbc/execute-one! conn
        [(str "SELECT COUNT(*) AS cnt FROM " table
              " WHERE " url-col "=? AND " version-col "=?")
         url version])
      :cnt
      long))

(defn- write-resource-catalogue!
  [^Connection conn target-set]
  (let [now (now-iso)]
    (doseq [[rt url version] target-set]
      (let [cnt (case rt
                  "CodeSystem" (count-rows conn "concept" "cs_url" "cs_version" url version)
                  "ValueSet"   (let [n (count-rows conn "valueset_member"
                                                   "vs_url" "vs_version" url version)]
                                 (when (pos? n) n))
                  "ConceptMap" (let [n (count-rows conn "conceptmap_element"
                                                   "cm_url" "cm_version" url version)]
                                 (when (pos? n) n))
                  nil)]
        (jdbc/execute! conn
          ["INSERT INTO tx_resource
              (resource_type, url, version, concept_count, imported_at)
            VALUES (?,?,?,?,?)"
           rt url version cnt now])))))

;; ---------------------------------------------------------------------------
;; Hierarchy closure — used by `index!`
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

(defn- list-codesystem-targets
  "Read `(url, version)` tuples for every CodeSystem in the file."
  [^Connection conn]
  (->> (jdbc/execute! conn ["SELECT url, version FROM codesystem_meta"])
       (mapv (fn [{:codesystem_meta/keys [url version]}] [url version]))))

(defn- rebuild-closure-for!
  [^Connection conn url version]
  (jdbc/execute! conn ["DELETE FROM concept_ancestor WHERE cs_url=? AND cs_version=?" url version])
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
        rows))))

(defn- rebuild-fts! [^Connection conn]
  ;; External-content FTS5 tables are populated by issuing the special
  ;; 'rebuild' command against them. Cheaper than per-row triggers
  ;; during bulk load.
  (jdbc/execute! conn ["INSERT INTO concept_fts(concept_fts) VALUES('rebuild')"])
  (jdbc/execute! conn ["INSERT INTO designation_fts(designation_fts) VALUES('rebuild')"])
  (jdbc/execute! conn ["INSERT INTO valueset_member_fts(valueset_member_fts) VALUES('rebuild')"]))

(defn- record-member-id-ranges!
  "Record each materialised ValueSet's `MIN`/`MAX` `valueset_member.id`.
  `id` is an INTEGER PRIMARY KEY, so VACUUM never renumbers it and the
  stored bounds stay valid. A filtered $expand bounds the FTS scan to
  `[member_id_lo, member_id_hi]` via `rowid BETWEEN`; the range covers every
  member (never under-returns) and the query's (vs_url, vs_version) filter
  discards any foreign rows in the range, so correctness is independent of
  whether the ids are contiguous."
  [^Connection conn]
  (jdbc/execute! conn
    ["UPDATE valueset
        SET member_id_lo = (SELECT MIN(m.id) FROM valueset_member m
                            WHERE m.vs_url = valueset.url AND m.vs_version = valueset.version),
            member_id_hi = (SELECT MAX(m.id) FROM valueset_member m
                            WHERE m.vs_url = valueset.url AND m.vs_version = valueset.version)
      WHERE member_count IS NOT NULL"]))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn import-fhir-data
  "Import fhir-data from `fhir-data-ch` into the SQLite container at
  `db-path`. Creates the file if absent (stamped with application_id +
  schema version). Update-in-place by `(resource_type, url, version)`.

  The supplied channel should contain fhir-data maps or bounded batches
  of fhir-data maps, then close. A terminal `{:type :stream-error :ex e}`
  item is rethrown. The channel is closed on sink failure so a producer
  blocked on `>!!` can stop.

  `opts`:
    :loader-type  optional string recorded in `tx_meta` (e.g. \"loinc-csv\")."
  [^String db-path fhir-data-ch {:keys [loader-type]}]
  (let [ds (db/create! db-path)]
    (try
      (let [{:keys [targets]}
            (with-open [conn (jdbc/get-connection ds)]
              (with-bulk-pragmas! conn
                (fn []
                  (.setAutoCommit conn false)
                  (try
                    (let [{:keys [targets] :as r}
                          (run-import! conn (channel-taker fhir-data-ch))
                          cs-targets (->> targets
                                          (filter #(= "CodeSystem" (first %)))
                                          (mapv (fn [[_ url version]] [url version])))]
                      (prune-dangling-parents! conn cs-targets)
                      (write-resource-catalogue! conn targets)
                      (.commit conn)
                      r)
                    (finally
                      (.setAutoCommit conn true))))))]
        (db/write-meta! ds (cond-> {:tx_schema_version (str db/schema-version)
                                    :built_at (now-iso)}
                             loader-type (assoc :loader_type loader-type)))
        {:db-path db-path
         :resources (->> targets
                         (mapv (fn [[rt url version]]
                                 {:resource-type rt
                                  :url url
                                  :version (when-not (= "" version) version)})))
         :totals {:targets (count targets)
                  :codesystem-targets (count (filter #(= "CodeSystem" (first %)) targets))}})
      (catch Throwable t
        (async/close! fhir-data-ch)
        (throw t))
      (finally
        (.close ds)))))

(defn build!
  "Stream a fhir-data seq into the SQLite container at `db-path`. Creates
  the file if absent (stamped with application_id + schema version).
  Update-in-place by `(resource_type, url, version)`.

  Writes raw rows only — the ancestor closure and FTS tables are built
  by `index!`, mirroring Hermes' `import` + `index` split. Callers that
  need a queryable file in one shot should call `index!` immediately
  after `build!`.

  `data-source` is either a 0-arg fn returning a fresh fhir-data seq,
  or a seq/coll. Either way it is walked once — single-pass.

  `opts`:
    :loader-type  optional string recorded in `tx_meta` (e.g. \"loinc-csv\").

  `build!` is a one-shot operation: it opens its own pooled datasource,
  runs the transaction, and closes the pool before returning. Callers
  query the file by reopening via `ftrm-provider/open` (or
  `db/open`)."
  [^String db-path data-source opts]
  (let [data-fn (if (fn? data-source) data-source (fn [] data-source))
        ds (db/create! db-path)]
    (try
      (let [{:keys [targets]}
            (with-open [conn (jdbc/get-connection ds)]
              (with-bulk-pragmas! conn
                (fn []
                  (.setAutoCommit conn false)
                  (try
                    (let [{:keys [targets] :as r}
                          (run-import! conn (collection-taker (data-fn)))
                          cs-targets (->> targets
                                          (filter #(= "CodeSystem" (first %)))
                                          (mapv (fn [[_ url version]] [url version])))]
                      (prune-dangling-parents! conn cs-targets)
                      (write-resource-catalogue! conn targets)
                      (.commit conn)
                      r)
                    (finally
                      (.setAutoCommit conn true))))))]
        (db/write-meta! ds (cond-> {:tx_schema_version (str db/schema-version)
                                    :built_at (now-iso)}
                             (:loader-type opts) (assoc :loader_type (:loader-type opts))))
        {:db-path db-path
         :resources (->> targets
                         (mapv (fn [[rt url version]]
                                 {:resource-type rt
                                  :url url
                                  :version (when-not (= "" version) version)})))
         :totals {:targets (count targets)
                  :codesystem-targets (count (filter #(= "CodeSystem" (first %)) targets))}})
      (finally
        (.close ds)))))

(defn index!
  "Rebuild derived structures for an existing terminology container:
  the ancestor closure (`concept_ancestor`) for every CodeSystem and
  the external-content FTS tables. Run after `build!` has streamed in
  the raw rows."
  [^String db-path]
  (let [ds (db/open db-path)]
    (try
      (with-open [conn (jdbc/get-connection ds)]
        (with-bulk-pragmas! conn
          (fn []
            (jdbc/with-transaction [tx conn]
              (doseq [[url version] (list-codesystem-targets tx)]
                (rebuild-closure-for! tx url version))
              (rebuild-fts! tx)
              (record-member-id-ranges! tx))))
        (jdbc/execute! conn ["ANALYZE"]))
      {:db-path db-path}
      (finally
        (.close ds)))))
