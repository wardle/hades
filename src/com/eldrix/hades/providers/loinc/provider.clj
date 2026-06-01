(ns com.eldrix.hades.providers.loinc.provider
  "Native LOINC SQLite provider."
  (:require [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.providers.common.display :as display]
            [com.eldrix.hades.providers.common.search-filter :as search-filter]
            [com.eldrix.hades.providers.common.issues :as issues]
            [com.eldrix.hades.providers.common.property-filter :as property-filter]
            [com.eldrix.hades.providers.loinc.model :as model]
            [com.eldrix.hades.providers.loinc.store :as store]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.io Closeable)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def loinc-url "http://loinc.org")

(def ^:private designation-use-system "http://loinc.org")

(def ^:private loinc-oid "2.16.840.1.113883.6.1")

(def ^:private loinc-systems
  "Every URL the LoincProvider answers to — the canonical and its OID
  aliases (bare and urn:oid: form). All other system params get
  rejected as `unknown-system`."
  #{loinc-url loinc-oid (str "urn:oid:" loinc-oid)})

(def ^:private loinc-naming-resolver
  "Fixed `NamingService` resolver for LOINC's OID aliases. LOINC's
  identity is immutable, so this is a constant map (the resolver IFn)."
  (let [target {:url loinc-url :kind :codesystem}]
    {loinc-oid target (str "urn:oid:" loinc-oid) target}))

(defn- loinc-system? [system]
  (contains? loinc-systems system))

(def ^:private loinc-vs-prefix "http://loinc.org/vs/")

(def ^:private implicit-loinc-vs-url "http://loinc.org/vs")

(defn- row-builder []
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- sqlite-true? [v]
  (or (true? v)
      (and (number? v) (not (zero? v)))))

(defn- canonical-code [code]
  (some-> code str (.toUpperCase Locale/ROOT)))

(defn- meta-value [ds k]
  (:value (jdbc/execute-one! ds
                             ["SELECT value FROM meta WHERE key = ?" (name k)]
                             (row-builder))))

(defn- code-row [conn code]
  (jdbc/execute-one! conn
                     ["SELECT *
                       FROM loinc
                       WHERE loinc_num = ?"
                      code]
                     (row-builder)))

(defn- answer-list-id->url [id]
  (str loinc-vs-prefix id))

(defn- loinc-vs-id [url pattern]
  (when (string? url)
    (second (re-matches pattern url))))

(defn- answer-list-id [url]
  (loinc-vs-id url #"http://loinc\.org/vs/(LL\d+-\d+)"))

(defn- group-id->url [id]
  (str loinc-vs-prefix id))

(defn- group-id [url]
  (loinc-vs-id url #"http://loinc\.org/vs/(LG\d+-\d+)"))

(defn- part-id [url]
  (loinc-vs-id url #"http://loinc\.org/vs/(LP\d+-\d+)"))

(defn- implicit-loinc-vs? [url]
  (= implicit-loinc-vs-url url))

(defn- locale-tag [{:keys [iso_language iso_country]}]
  (cond
    (and (seq iso_language) (seq iso_country)) (str iso_language "-" iso_country)
    (seq iso_language) iso_language))

(defn- variant-value [row column]
  (let [v (get row column)]
    (when-not (str/blank? v) v)))

(def ^:private display-columns
  [[:long_common_name "LONG_COMMON_NAME"]
   [:linguistic_variant_display_name "LinguisticVariantDisplayName"]
   [:shortname "SHORTNAME"]
   [:component "COMPONENT"]
   [:related_names_2 "RELATEDNAMES2"]])

(defn- variant-designations [rows]
  (vec
   (for [row rows
         :let [language (locale-tag row)]
         [column use-code] display-columns
         :let [value (variant-value row column)]
         :when value]
     {:language language
      :use {:system designation-use-system :code use-code}
      :value value})))

(defn- fetch-variant-rows
  ([conn code] (fetch-variant-rows conn code nil))
  ([conn code languages]
   (let [language-set (some->> languages
                               (map #(subs % 0 (min 2 (count %))))
                               (remove str/blank?)
                               set)
         rows (jdbc/execute! conn
                             ["SELECT r.variant_id, c.iso_language, c.iso_country,
                                      r.long_common_name,
                                      r.linguistic_variant_display_name,
                                      r.shortname, r.component, r.related_names_2
                               FROM linguistic_variant_row r
                               LEFT JOIN linguistic_variant_catalog c ON c.id = r.variant_id
                               WHERE r.loinc_num = ?"
                              code]
                             (row-builder))]
     (if (seq language-set)
       (filterv #(contains? language-set (:iso_language %)) rows)
       rows))))

(defn- preferred-designation
  [designations display-langs]
  (some (fn [{:keys [lang]}]
          (some (fn [[_column use-code]]
                  (some (fn [{:keys [language use value]}]
                          (when (and value
                                     (= use-code (:code use))
                                     (display/language-matches? language lang))
                            value))
                        designations))
                display-columns))
        display-langs))

(defn- fts-tokens
  [text]
  (when-not (str/blank? text)
    (seq (->> (str/split text #"\s+")
              (map #(str/replace % #"[\"]" ""))
              (map #(str/replace % #"[^\p{L}\p{Nd}_-]" ""))
              (remove str/blank?)))))

(defn- fts-query
  [tokens]
  (when (seq tokens)
    (str/join " " (map #(str \" % \") tokens))))

(defn- locale-where [display-langs]
  (when (seq display-langs)
    (let [langs (->> display-langs
                     (map :lang)
                     (map #(subs % 0 (min 2 (count %))))
                     distinct
                     vec)]
      {:sql (str " AND vf.iso_language IN (" (str/join "," (repeat (count langs) "?")) ")")
       :params langs})))

(def ^:private filter-columns
  {"code" "loinc_num"
   "display" "long_common_name"
   "COMPONENT" "component"
   "PROPERTY" "property"
   "TIME_ASPCT" "time_aspct"
   "SYSTEM" "system"
   "SCALE_TYP" "scale_typ"
   "METHOD_TYP" "method_typ"
   "CLASS" "class"
   "STATUS" "status"
   "CLASSTYPE" "classtype"
   "ORDER_OBS" "order_obs"
   "copyright" "external_copyright_notice"})

(defn- placeholders [n]
  (str/join "," (repeat n "?")))

(defn- hierarchy-token-clause [column]
  (str "(" column " = ? OR path_to_root = ?"
       " OR path_to_root LIKE ?"
       " OR path_to_root LIKE ?"
       " OR path_to_root LIKE ?)"))

(defn- hierarchy-token-params [value]
  [value value (str value ".%") (str "%." value) (str "%." value ".%")])

(defn- filter-clause [{:keys [property op value]}]
  (case op
    "="
    (cond
      (= "parent" property)
      {:sql "EXISTS (SELECT 1 FROM component_hierarchy_by_system h WHERE h.code = loinc_num AND h.immediate_parent = ?)"
       :params [value]}

      (= "child" property)
      {:sql "EXISTS (SELECT 1 FROM component_hierarchy_by_system h WHERE h.code = ? AND h.immediate_parent = loinc_num)"
       :params [value]}

      (filter-columns property)
      {:sql (str (filter-columns property) " = ? COLLATE NOCASE")
       :params [value]})

    "in"
    (when-let [column (filter-columns property)]
      (let [parts (str/split value #",")]
        {:sql (str column " COLLATE NOCASE IN (" (placeholders (count parts)) ")")
         :params parts}))

    "not-in"
    (when-let [column (filter-columns property)]
      (let [parts (str/split value #",")]
        {:sql (str column " COLLATE NOCASE NOT IN (" (placeholders (count parts)) ")")
         :params parts}))

    "exists"
    (when-let [column (filter-columns property)]
      {:sql (if (= "true" value)
              (str column " IS NOT NULL AND " column " <> ''")
              (str "(" column " IS NULL OR " column " = '')"))
       :params []})

    "regex"
    (when-let [column (filter-columns property)]
      {:sql (str column " IS NOT NULL AND " column " <> ''")
       :params []
       :post-filter (let [pattern (try (re-pattern value) (catch Exception _ nil))]
                      (if pattern
                        #(boolean (re-find pattern (str (get % (keyword column)))))
                        (constantly false)))})

    "is-a"
    (when (= "concept" property)
      {:sql (str "(loinc_num = ? COLLATE NOCASE OR EXISTS"
                 " (SELECT 1 FROM component_hierarchy_by_system h"
                 " WHERE h.code = loinc_num AND " (hierarchy-token-clause "h.immediate_parent") "))")
       :params (into [value] (hierarchy-token-params value))})

    ("descendent-of" "descendant-of")
    (when (= "concept" property)
      {:sql (str "EXISTS (SELECT 1 FROM component_hierarchy_by_system h"
                 " WHERE h.code = loinc_num AND " (hierarchy-token-clause "h.immediate_parent") ")")
       :params (hierarchy-token-params value)})

    "is-not-a"
    (when (= "concept" property)
      {:sql (str "loinc_num <> ? COLLATE NOCASE AND NOT EXISTS"
                 " (SELECT 1 FROM component_hierarchy_by_system h"
                 " WHERE h.code = loinc_num AND " (hierarchy-token-clause "h.immediate_parent") ")")
       :params (into [value] (hierarchy-token-params value))})

    "generalizes"
    (when (= "concept" property)
      {:sql (str "EXISTS (SELECT 1 FROM component_hierarchy_by_system h"
                 " WHERE h.code = ? AND " (hierarchy-token-clause "loinc_num") ")")
       :params (into [value] (hierarchy-token-params value))})

    nil))

(defn- unsupported-filter-issue [{:keys [property op value]}]
  {:severity "error"
   :type "invalid"
   :details-code "vs-invalid"
   :text (str "Unsupported LOINC filter"
              " property=" (pr-str property)
              ", op=" (pr-str op)
              ", value=" (pr-str value))})

(defn- filter-where [filters]
  (let [filters (seq filters)
        clauses (mapv filter-clause filters)]
    (cond
      (nil? filters) nil

      (some nil? clauses)
      {:sql " WHERE 0"
       :params []
       :post-filters []
       :issues (mapv unsupported-filter-issue
                      (keep-indexed (fn [idx f]
                                      (when (nil? (nth clauses idx)) f))
                                    filters))}

      (seq clauses)
      {:sql (str " WHERE " (str/join " AND " (map :sql clauses)))
       :params (mapcat :params clauses)
       :post-filters (keep :post-filter clauses)})))

(defn- search-sql [text display-langs filters active-only max-hits]
  (let [tokens (fts-tokens text)
        q (fts-query tokens)
        text? (not (str/blank? text))
        locale (locale-where display-langs)
        filters (filter-where (cond-> (vec filters)
                                active-only (conj {:property "STATUS" :op "not-in" :value "DEPRECATED"})))
        token-count (count tokens)
        limit (or max-hits 50)]
    (cond
      q
      {:sql (str "SELECT *, MIN(rank) AS rank FROM ("
                 " SELECT l.*, f.rank AS rank"
                 " FROM loinc_fts f JOIN loinc l ON l.rowid = f.rowid"
                 " WHERE loinc_fts MATCH ?"
                 " UNION ALL"
                 " SELECT l.*, vf.rank AS rank"
                 " FROM loinc_variant_fts vf JOIN loinc l ON l.loinc_num = vf.loinc_num"
                 " WHERE loinc_variant_fts MATCH ?" (or (:sql locale) "")
                 ")" (or (:sql filters) "")
                 " GROUP BY loinc_num"
                 (if (> token-count 2)
                   " ORDER BY rank, CASE WHEN common_test_rank > 0 THEN 0 ELSE 1 END, common_test_rank LIMIT ?"
                   " ORDER BY CASE WHEN common_test_rank > 0 THEN 0 ELSE 1 END, common_test_rank, CASE WHEN common_order_rank > 0 THEN 0 ELSE 1 END, common_order_rank, rank LIMIT ?"))
       :params (concat [q q] (:params locale) (:params filters) [limit])
       :post-filters (:post-filters filters)
       :issues (:issues filters)}

      text?
      {:sql "SELECT *, 0.0 AS rank FROM loinc WHERE 0 LIMIT ?"
       :params [limit]
       :post-filters (:post-filters filters)
       :issues (:issues filters)}

      :else
      {:sql (str "SELECT *, 0.0 AS rank"
                 " FROM loinc"
                 (or (:sql filters) "")
                 " ORDER BY loinc_num LIMIT ?")
       :params (concat (:params filters) [limit])
       :post-filters (:post-filters filters)
       :issues (:issues filters)})))

(defn- concept-from-row [conn display-langs row]
  (let [code (:loinc_num row)
        variant-rows (when (seq display-langs)
                       (fetch-variant-rows conn code (mapv :lang display-langs)))
        designations (when (seq variant-rows) (variant-designations variant-rows))
        lang-display (when (seq designations)
                       (preferred-designation designations display-langs))]
    (cond-> {:system loinc-url
             :code code
             :display (or lang-display (:long_common_name row))
             :inactive (= "DEPRECATED" (:status row))}
      (seq designations) (assoc :designations designations))))

(def ^:private coded-property-types
  {:COMPONENT "COMPONENT"
   :PROPERTY "PROPERTY"
   :TIME_ASPCT "TIME"
   :SYSTEM "SYSTEM"
   :SCALE_TYP "SCALE"
   :METHOD_TYP "METHOD"
   :CLASS "CLASS"})

(def ^:private lookup-properties
  [[:COMPONENT :component true]
   [:PROPERTY :property true]
   [:TIME_ASPCT :time_aspct true]
   [:SYSTEM :system true]
   [:SCALE_TYP :scale_typ true]
   [:METHOD_TYP :method_typ true]
   [:CLASS :class true]
   [:STATUS :status false]
   [:CLASSTYPE :classtype false]
   [:ORDER_OBS :order_obs false]
   [:copyright :external_copyright_notice false]])

(defn- property-entry [row part-by-type [code column coded?]]
  (let [value (get row column)]
    (when-not (str/blank? value)
      {:code code
       :value (if (and coded? (part-by-type (coded-property-types code)))
                (let [{:keys [part_number part_name]} (part-by-type (coded-property-types code))]
                  {:system loinc-url :code part_number :display part_name})
                value)})))

(defn- fetch-primary-parts [conn {:keys [loinc_num class]}]
  (let [part-rows (jdbc/execute! conn
                                 ["SELECT part_number, part_name, part_type_name
                                   FROM part_link_primary
                                   WHERE loinc_number = ?"
                                  loinc_num]
                                 (row-builder))
        class-row (jdbc/execute-one! conn
                                     ["SELECT part_number, part_name, part_type_name
                                       FROM part
                                       WHERE part_type_name = 'CLASS'
                                         AND part_name = ?
                                       LIMIT 1"
                                      class]
                                     (row-builder))]
    (into {}
          (map (juxt :part_type_name identity))
          (cond-> part-rows class-row (conj class-row)))))

(defn- fetch-parents [conn code]
  (jdbc/execute! conn
                 ["SELECT DISTINCT h.immediate_parent AS code, COALESCE(p.code_text, h.immediate_parent) AS display
                   FROM component_hierarchy_by_system h
                   LEFT JOIN component_hierarchy_by_system p ON p.code = h.immediate_parent
                   WHERE h.code = ?
                   ORDER BY h.sequence, h.immediate_parent"
                  code]
                 (row-builder)))

(defn- fetch-children [conn code]
  (jdbc/execute! conn
                 ["SELECT DISTINCT code, code_text AS display
                   FROM component_hierarchy_by_system
                   WHERE immediate_parent = ?
                   ORDER BY sequence, code"
                  code]
                 (row-builder)))

(defn- code-exists?
  "True when `code` resolves to a LOINC term or a part code. LOINC's
  subsumption hierarchy spans both, so existence-checking subsumes
  inputs must consider both tables."
  [conn code]
  (let [code (canonical-code code)]
    (boolean
     (or (jdbc/execute-one! conn
                            ["SELECT 1 FROM loinc WHERE loinc_num = ?" code])
         (jdbc/execute-one! conn
                            ["SELECT 1 FROM component_hierarchy_by_system
                              WHERE code = ? OR immediate_parent = ? LIMIT 1" code code])))))

(defn- hierarchy-related? [conn ancestor descendant include-self?]
  (let [ancestor (canonical-code ancestor)
        descendant (canonical-code descendant)]
    (or (and include-self? (= ancestor descendant))
        (boolean
         (jdbc/execute-one! conn
                            [(str "SELECT 1 FROM component_hierarchy_by_system"
                                  " WHERE code = ? AND "
                                  (hierarchy-token-clause "immediate_parent")
                                  " LIMIT 1")
                             descendant ancestor ancestor (str ancestor ".%")
                             (str "%." ancestor) (str "%." ancestor ".%")]
                            (row-builder))))))

(defn- wanted-properties [row part-by-type {:keys [want? want-typed?]}]
  (when want-typed?
    (keep (fn [[code :as property]]
            (when (want? (name code))
              (property-entry row part-by-type property)))
          lookup-properties)))

(defn- answer-code-row [conn code]
  (jdbc/execute-one! conn
                     ["SELECT answer_string_id, display_text
                       FROM answer_list
                       WHERE answer_string_id = ?
                       LIMIT 1"
                      code]
                     (row-builder)))

(defn- part-code-row [conn code]
  (jdbc/execute-one! conn
                     ["SELECT part_number, part_name, part_display_name, status
                       FROM part
                       WHERE part_number = ?
                       LIMIT 1"
                      code]
                     (row-builder)))

(defn- group-code-row [conn code]
  (jdbc/execute-one! conn
                     ["SELECT group_id, group_name, status
                       FROM loinc_group
                       WHERE group_id = ?
                       LIMIT 1"
                      code]
                     (row-builder)))

(defn- answer-lookup-result [{:keys [display_text answer_string_id]} version]
  {:name "LOINC"
   :version version
   :display display_text
   :system loinc-url
   :code answer_string_id
   :abstract false
   :properties []
   :designations []})

(defn- part-lookup-result [{:keys [status part_display_name part_name part_number]} version]
  (cond-> {:name "LOINC"
           :version version
           :display (or part_display_name part_name)
           :system loinc-url
           :code part_number
           :abstract false
           :properties []
           :designations []}
    (= "DEPRECATED" status) (assoc :inactive true :inactive-status "inactive")))

(defn- group-lookup-result [{:keys [status group_name group_id]} version]
  (cond-> {:name "LOINC"
           :version version
           :display group_name
           :system loinc-url
           :code group_id
           :abstract false
           :properties []
           :designations []}
    (= "Deprecated" status) (assoc :inactive true :inactive-status "inactive")))

(defn- display-issue [system code concept {:keys [display displayLanguage]}]
  (let [display-langs (display/parse-display-language displayLanguage)]
    (when (and display
               (not (display/display-matches? concept display display-langs)))
      (let [{:keys [text message-id]} (issues/format-display-mismatch
                                       display system code (:display concept) (:designations concept)
                                       displayLanguage nil)]
        (cond-> {:severity "error"
                 :type "invalid"
                 :details-code "invalid-display"
                 :text text
                 :expression ["Coding.display"]}
          message-id (assoc :message-id message-id))))))


(defn- answer-list-meta-row [ds id]
  (jdbc/execute-one! ds
                     ["SELECT answer_list_id, answer_list_name, answer_list_oid
                       FROM answer_list
                       WHERE answer_list_id = ?
                       LIMIT 1"
                      id]
                     (row-builder)))

(defn- answer-list-metadata [ds id]
  (when-let [row (answer-list-meta-row ds id)]
    [row]))

(defn- answer-list-resource [version {:keys [answer_list_id answer_list_name answer_list_oid]}]
  (cond-> {:url (answer-list-id->url answer_list_id)
           :name answer_list_name
           :title answer_list_name
           :status "active"}
    version (assoc :version version)
    answer_list_oid (assoc :identifier [{:system "urn:ietf:rfc:3986"
                                         :value (str "urn:oid:" answer_list_oid)}])))

(defn- implicit-loinc-resource [version]
  (cond-> {:url implicit-loinc-vs-url
           :name "LOINC"
           :title "All LOINC codes"
           :status "active"}
    version (assoc :version version)))

(defn- group-meta-row [ds id]
  (jdbc/execute-one! ds
                     ["SELECT group_id, group_name, status
                       FROM loinc_group
                       WHERE group_id = ?
                       LIMIT 1"
                      id]
                     (row-builder)))

(defn- group-vs-status [status]
  (if (#{"Deprecated" "DEPRECATED"} status) "retired" "active"))

(defn- group-resource [version {:keys [group_id group_name status]}]
  (cond-> {:url (group-id->url group_id)
           :name group_name
           :title group_name
           :status (group-vs-status status)}
    version (assoc :version version)))

(defn- hierarchy-root-row [ds id]
  (jdbc/execute-one! ds
                     ["SELECT code, code_text
                       FROM component_hierarchy_by_system
                       WHERE code = ? OR immediate_parent = ?
                       ORDER BY CASE WHEN code = ? THEN 0 ELSE 1 END
                       LIMIT 1"
                      id id id]
                     (row-builder)))

(defn- hierarchy-resource [version {:keys [code code_text]}]
  (cond-> {:url (group-id->url code)
           :name code_text
           :title code_text
           :status "active"}
    version (assoc :version version)))

(defn- answer-filter-where [filter-text]
  (when-not (str/blank? filter-text)
    {:sql " AND (answer_string_id LIKE ? COLLATE NOCASE
                 OR display_text LIKE ? COLLATE NOCASE
                 OR local_answer_code LIKE ? COLLATE NOCASE)"
     :params (repeat 3 (str "%" filter-text "%"))}))

(defn- answer-concept [{:keys [answer_string_id display_text ext_code_display_name local_answer_code]}]
  {:system loinc-url
   :code answer_string_id
   :display (or display_text ext_code_display_name local_answer_code)})

(defn- answer-list-total [ds id filter-text]
  (let [{:keys [sql params]} (answer-filter-where filter-text)]
    (:total (jdbc/execute-one! ds
                               (into [(str "SELECT COUNT(*) AS total FROM answer_list"
                                           " WHERE answer_list_id = ?"
                                           (or sql ""))]
                                     (concat [id] params))
                               (row-builder)))))

(defn- answer-list-concepts [ds id {:keys [filter offset count]}]
  (let [{:keys [sql params]} (answer-filter-where filter)
        offset (or offset 0)
        limit (or count -1)]
    (mapv answer-concept
          (jdbc/execute! ds
                         (into [(str "SELECT answer_string_id, local_answer_code, display_text,"
                                     " ext_code_display_name"
                                     " FROM answer_list"
                                     " WHERE answer_list_id = ?"
                                     (or sql "")
                                     " ORDER BY sequence_number, answer_string_id"
                                     " LIMIT ? OFFSET ?")]
                               (concat [id] params [limit offset]))
                         (row-builder)))))

(defn- group-total
  ([ds id] (group-total ds id false))
  ([ds id active-only?]
  (:total (jdbc/execute-one! ds
                             [(str "SELECT COUNT(*) AS total"
                                   " FROM group_loinc_term g"
                                   " LEFT JOIN loinc l ON l.loinc_num = g.loinc_number"
                                   " WHERE g.group_id = ?"
                                   (when active-only?
                                     " AND (l.status IS NULL OR l.status <> 'DEPRECATED')"))
                              id]
                             (row-builder)))))

(defn- group-concepts [ds id {:keys [offset count activeOnly]}]
  (let [offset (or offset 0)
        limit (or count -1)]
    (mapv (fn [{:keys [loinc_number long_common_name status]}]
            (cond-> {:system loinc-url
                     :code loinc_number
                     :display long_common_name}
              (= "DEPRECATED" status) (assoc :inactive true)))
          (jdbc/execute! ds
                         [(str "SELECT g.loinc_number, g.long_common_name, l.status"
                               " FROM group_loinc_term g"
                               " LEFT JOIN loinc l ON l.loinc_num = g.loinc_number"
                               " WHERE g.group_id = ?"
                               (when activeOnly
                                 " AND (l.status IS NULL OR l.status <> 'DEPRECATED')")
                               " ORDER BY g.loinc_number"
                               " LIMIT ? OFFSET ?")
                          id limit offset]
                         (row-builder)))))

(defn- hierarchy-total
  ([ds part] (hierarchy-total ds part false))
  ([ds part active-only?]
  (:total (jdbc/execute-one! ds
                             [(str "SELECT COUNT(DISTINCT l.loinc_num) AS total"
                                   " FROM component_hierarchy_by_system h"
                                   " JOIN loinc l ON l.loinc_num = h.code"
                                   " WHERE " (hierarchy-token-clause "h.immediate_parent")
                                   (when active-only? " AND l.status <> 'DEPRECATED'"))
                              part part (str part ".%") (str "%." part) (str "%." part ".%")]
                             (row-builder)))))

(defn- hierarchy-concepts [conn part {:keys [offset count displayLanguage activeOnly]}]
  (let [display-langs (display/parse-display-language displayLanguage)
        offset (or offset 0)
        limit (or count -1)]
    (mapv #(concept-from-row conn display-langs %)
          (jdbc/execute! conn
                         [(str "SELECT DISTINCT l.*"
                               " FROM component_hierarchy_by_system h"
                               " JOIN loinc l ON l.loinc_num = h.code"
                               " WHERE " (hierarchy-token-clause "h.immediate_parent")
                               (when activeOnly " AND l.status <> 'DEPRECATED'")
                               " ORDER BY l.loinc_num"
                               " LIMIT ? OFFSET ?")
                          part part (str part ".%") (str "%." part) (str "%." part ".%") limit offset]
                         (row-builder)))))

(defn- loinc-count
  ([ds] (loinc-count ds false))
  ([ds active-only?]
   (:total (jdbc/execute-one! ds
                              [(str "SELECT COUNT(*) AS total FROM loinc"
                                    (when active-only? " WHERE status <> 'DEPRECATED'"))]
                              (row-builder)))))

(defn- answer-code-count [ds]
  (:total (jdbc/execute-one! ds
                             ["SELECT COUNT(DISTINCT answer_string_id) AS total FROM answer_list"]
                             (row-builder))))

(defn- part-code-count
  ([ds] (part-code-count ds false))
  ([ds active-only?]
   (:total (jdbc/execute-one! ds
                              [(str "SELECT COUNT(*) AS total FROM part"
                                    (when active-only? " WHERE status <> 'DEPRECATED'"))]
                              (row-builder)))))

(defn- group-code-count
  ([ds] (group-code-count ds false))
  ([ds active-only?]
   (:total (jdbc/execute-one! ds
                              [(str "SELECT COUNT(*) AS total FROM loinc_group"
                                    (when active-only? " WHERE status NOT IN ('Deprecated', 'DEPRECATED')"))]
                              (row-builder)))))

(defn- implicit-loinc-count
  ([ds] (implicit-loinc-count ds false))
  ([ds active-only?]
   (+ (loinc-count ds active-only?)
      (answer-code-count ds)
      (part-code-count ds active-only?)
      (group-code-count ds active-only?))))

(defn- validation-result [system code concept version params]
  (let [issue (display-issue system code concept params)]
    (cond-> {:result (nil? issue)
             :system system
             :code code
             :display (:display concept)
             :version version}
      issue (assoc :message (:text issue)
                   :issues [issue]))))

(defn- loinc-code-validation [provider {:keys [system code] :as params}]
  (if (not (loinc-system? system))
    {:result false
     :system system
     :code code
     :message (str "CodeSystem '" system "' is not valid for LOINC")}
    (protos/cs-validate-code provider params)))

(defn- code-like [filter-text]
  (when-not (str/blank? filter-text)
    (str "%" filter-text "%")))

(defn- non-loinc-filter-clause [filter-text columns]
  (when-let [pattern (code-like filter-text)]
    {:sql (str " WHERE (" (str/join " OR " (map #(str % " LIKE ? COLLATE NOCASE") columns)) ")")
     :params (repeat (count columns) pattern)}))

(defn- count-query [ds sql params]
  (:total (jdbc/execute-one! ds (into [sql] params) (row-builder))))

(defn- non-loinc-code-count [ds filter-text table columns active-clause]
  (let [pattern (code-like filter-text)
        text-clause (when pattern
                      (str "(" (str/join " OR " (map #(str % " LIKE ? COLLATE NOCASE") columns)) ")"))
        clauses (cond-> []
                  text-clause (conj text-clause)
                  active-clause (conj active-clause))]
    (count-query ds
                 (str "SELECT COUNT(*) AS total FROM ("
                      " SELECT 1 FROM " table
                      (when (seq clauses)
                        (str " WHERE " (str/join " AND " clauses)))
                      " GROUP BY " (first columns)
                      ")")
                 (if pattern (repeat (count columns) pattern) []))))

(defn- filtered-loinc-count [ds filter-text display-langs activeOnly]
  (let [tokens (fts-tokens filter-text)
        q (fts-query tokens)
        locale (locale-where display-langs)]
    (cond
      (str/blank? filter-text)
      (loinc-count ds activeOnly)

      (nil? q)
      0

      :else
      (count-query ds
                   (str "SELECT COUNT(DISTINCT loinc_num) AS total FROM ("
                        " SELECT l.loinc_num, l.status"
                        " FROM loinc_fts f JOIN loinc l ON l.rowid = f.rowid"
                        " WHERE loinc_fts MATCH ?"
                        " UNION ALL"
                        " SELECT l.loinc_num, l.status"
                        " FROM loinc_variant_fts vf JOIN loinc l ON l.loinc_num = vf.loinc_num"
                        " WHERE loinc_variant_fts MATCH ?" (or (:sql locale) "")
                        ")"
                        (when activeOnly
                          " WHERE status <> 'DEPRECATED'"))
                   (concat [q q] (:params locale))))))

(defn- filtered-implicit-loinc-count [ds {:keys [filter displayLanguage activeOnly]}]
  (let [display-langs (display/parse-display-language displayLanguage)]
    (+ (filtered-loinc-count ds filter display-langs activeOnly)
       (non-loinc-code-count ds filter "answer_list"
                             ["answer_string_id" "display_text" "local_answer_code"]
                             nil)
       (non-loinc-code-count ds filter "part"
                             ["part_number" "part_name" "part_display_name"]
                             (when activeOnly
                               "status <> 'DEPRECATED'"))
       (non-loinc-code-count ds filter "loinc_group"
                             ["group_id" "group_name"]
                             (when activeOnly
                               "status NOT IN ('Deprecated', 'DEPRECATED')")))))

(defn- answer-code-concepts [ds {:keys [filter offset count]}]
  (let [{:keys [sql params]} (non-loinc-filter-clause filter ["answer_string_id" "display_text" "local_answer_code"])
        offset (or offset 0)
        limit (or count -1)]
    (mapv (fn [{:keys [answer_string_id display_text local_answer_code]}]
            {:system loinc-url
             :code answer_string_id
             :display (or display_text local_answer_code)})
          (jdbc/execute! ds
                         (into [(str "SELECT answer_string_id, display_text, local_answer_code"
                                     " FROM answer_list"
                                     (or sql "")
                                     " GROUP BY answer_string_id"
                                     " ORDER BY answer_string_id"
                                     " LIMIT ? OFFSET ?")]
                               (concat params [limit offset]))
                         (row-builder)))))

(defn- part-code-concepts [ds {:keys [filter offset count activeOnly]}]
  (let [{:keys [sql params]} (non-loinc-filter-clause filter ["part_number" "part_name" "part_display_name"])
        active-sql (when activeOnly
                     (if sql " AND status <> 'DEPRECATED'" " WHERE status <> 'DEPRECATED'"))
        offset (or offset 0)
        limit (or count -1)]
    (mapv (fn [{:keys [part_number part_name part_display_name status]}]
            (cond-> {:system loinc-url
                     :code part_number
                     :display (or part_display_name part_name)}
              (= "DEPRECATED" status) (assoc :inactive true)))
          (jdbc/execute! ds
                         (into [(str "SELECT part_number, part_name, part_display_name, status"
                                     " FROM part"
                                     (or sql "")
                                     (or active-sql "")
                                     " ORDER BY part_number"
                                     " LIMIT ? OFFSET ?")]
                               (concat params [limit offset]))
                         (row-builder)))))

(defn- group-code-concepts [ds {:keys [filter offset count activeOnly]}]
  (let [{:keys [sql params]} (non-loinc-filter-clause filter ["group_id" "group_name"])
        active-sql (when activeOnly
                     (if sql
                       " AND status NOT IN ('Deprecated', 'DEPRECATED')"
                       " WHERE status NOT IN ('Deprecated', 'DEPRECATED')"))
        offset (or offset 0)
        limit (or count -1)]
    (mapv (fn [{:keys [group_id group_name status]}]
            (cond-> {:system loinc-url
                     :code group_id
                     :display group_name}
              (= "Deprecated" status) (assoc :inactive true)))
          (jdbc/execute! ds
                         (into [(str "SELECT group_id, group_name, status"
                                     " FROM loinc_group"
                                     (or sql "")
                                     (or active-sql "")
                                     " ORDER BY group_id"
                                     " LIMIT ? OFFSET ?")]
                               (concat params [limit offset]))
                         (row-builder)))))

(defn- implicit-concept-from-row [conn display-langs {:keys [kind code display inactive]}]
  (if (= "LNC" kind)
    (let [variant-rows (when (seq display-langs)
                         (fetch-variant-rows conn code (mapv :lang display-langs)))
          designations (when (seq variant-rows) (variant-designations variant-rows))
          lang-display (when (seq designations)
                         (preferred-designation designations display-langs))]
      (cond-> {:system loinc-url
               :code code
               :display (or lang-display display)
               :inactive (sqlite-true? inactive)}
        (seq designations) (assoc :designations designations)))
    (cond-> {:system loinc-url
             :code code
             :display display}
      (sqlite-true? inactive) (assoc :inactive true))))

(def ^:private implicit-loinc-page-sql
  ;; Canonical ordering for http://loinc.org/vs: primary LOINC terms,
  ;; answer codes, parts, then groups. Keep paging in SQLite so
  ;; activeOnly and LIMIT/OFFSET are applied to one logical stream.
  "SELECT kind, code, display, inactive
   FROM (
     SELECT 0 AS family,
            'LNC' AS kind,
            loinc_num AS code,
            long_common_name AS display,
            status = 'DEPRECATED' AS inactive
     FROM loinc
     WHERE (? = 0 OR status <> 'DEPRECATED')
     UNION ALL
     SELECT 1 AS family,
            'LA' AS kind,
            answer_string_id AS code,
            COALESCE(MIN(display_text), MIN(local_answer_code)) AS display,
            0 AS inactive
     FROM answer_list
     GROUP BY answer_string_id
     UNION ALL
     SELECT 2 AS family,
            'LP' AS kind,
            part_number AS code,
            COALESCE(NULLIF(part_display_name, ''), part_name) AS display,
            status = 'DEPRECATED' AS inactive
     FROM part
     WHERE (? = 0 OR status <> 'DEPRECATED')
     UNION ALL
     SELECT 3 AS family,
            'LG' AS kind,
            group_id AS code,
            group_name AS display,
            status IN ('Deprecated', 'DEPRECATED') AS inactive
     FROM loinc_group
     WHERE (? = 0 OR status NOT IN ('Deprecated', 'DEPRECATED'))
   )
   ORDER BY family, code
   LIMIT ? OFFSET ?")

(defn- active-only-flag [activeOnly]
  (if activeOnly 1 0))

(defn- implicit-loinc-page-rows [conn {:keys [offset count activeOnly]}]
  (let [offset (or offset 0)
        limit (or count 50)
        active-only? (active-only-flag activeOnly)]
    (jdbc/execute! conn
                   [implicit-loinc-page-sql active-only? active-only? active-only? limit offset]
                   (row-builder))))

(defn- unfiltered-implicit-loinc-concepts [conn {:keys [offset count displayLanguage activeOnly]}]
  (let [display-langs (display/parse-display-language displayLanguage)]
    (mapv #(implicit-concept-from-row conn display-langs %)
          (implicit-loinc-page-rows conn {:offset offset
                                          :count count
                                          :activeOnly activeOnly}))))

(defn- filtered-concept-rank [filter-text concept]
  (let [needle (some-> filter-text str/lower-case str/trim)
        display (some-> (:display concept) str/lower-case)]
    (cond
      (str/blank? needle) 0
      (= display needle) 0
      (and display (str/starts-with? display needle)) 1
      :else 2)))

(defn- implicit-loinc-concepts [conn {:keys [filter offset count displayLanguage activeOnly] :as params}]
  (if (str/blank? filter)
    (unfiltered-implicit-loinc-concepts conn params)
    (let [display-langs (display/parse-display-language displayLanguage)
          lnc-limit (+ (or count 50) (or offset 0))
          {:keys [sql post-filters]
           sql-params :params} (search-sql filter display-langs nil activeOnly lnc-limit)
          rows (cond->> (jdbc/execute! conn (into [sql] sql-params) (row-builder))
                 (seq post-filters) (filter #(every? (fn [f] (f %)) post-filters)))
          lnc (mapv #(concept-from-row conn display-langs %) rows)
          concepts (concat lnc
                           (answer-code-concepts conn {:filter filter})
                           (part-code-concepts conn {:filter filter :activeOnly activeOnly})
                           (group-code-concepts conn {:filter filter :activeOnly activeOnly}))]
      (->> (sort-by (juxt #(filtered-concept-rank filter %)
                          #(str (:code %)))
                    concepts)
           (drop (or offset 0))
           (take (or count 50))
           vec))))

(defn- requested-version [{:keys [version valueSetVersion]}]
  (or valueSetVersion version))

(defn- version-matches? [actual requested]
  (or (nil? requested) (= actual requested)))

(defn- vs-meta-match?
  "Whether a synthesised LOINC ValueSet with `name`/`title`/`status`
  satisfies the `opts` search filters."
  [opts name title status]
  (search-filter/matches-resource-filters? {:name name :title title :status status} opts))


(def ^:private symmetric-equivalences
  #{"equivalent" "equal" "relatedto" "related-to" "inexact" "unmatched"
    "disjoint"})

(def ^:private inverted-equivalences
  {"wider"       "narrower"
   "narrower"    "wider"
   "subsumes"    "specializes"
   "specializes" "subsumes"
   "broader"     "narrower"})

(defn- invert-equivalence [equivalence]
  (or (when (contains? symmetric-equivalences equivalence) equivalence)
      (get inverted-equivalences equivalence)
      equivalence))

(defn- cm-meta
  [{:keys [url system target title]} version]
  (cond-> {:url url
           :system system
           :target target
           :title title}
    version (assoc :version version)))

(defn- part-related-target-systems [ds]
  (mapv :ext_code_system
        (jdbc/execute! ds
                       ["SELECT DISTINCT ext_code_system
                         FROM part_related_code_mapping
                         WHERE ext_code_system IS NOT NULL AND ext_code_system <> ''
                         ORDER BY ext_code_system"]
                       (row-builder))))

(defn- part-related-meta [ext-code-system version]
  (let [{:keys [system title-prefix]} (model/conceptmap :part-related)]
    (cm-meta {:url (model/part-related-conceptmap-url ext-code-system)
              :system system
              :target ext-code-system
              :title (str title-prefix " to " ext-code-system)}
             version)))

(defn- static-conceptmap-metas [version]
  (mapv #(cm-meta (model/conceptmap %) version)
        [:map-to :ieee-medical-device :rsna-rid :rsna-rpid]))

(defn- all-conceptmap-metas [ds version]
  (into (static-conceptmap-metas version)
        (map #(part-related-meta % version))
        (part-related-target-systems ds)))

(defn- requested-part-related-target [{:keys [url system target]}]
  (or (model/part-related-conceptmap-target url)
      (when (loinc-system? system) target)
      (when (loinc-system? target) system)))

(defn- conceptmap-kind [{:keys [url system target] :as params}]
  (let [map-to (model/conceptmap :map-to)
        ieee (model/conceptmap :ieee-medical-device)
        rid (model/conceptmap :rsna-rid)
        rpid (model/conceptmap :rsna-rpid)]
    (cond
      (or (= url (:url map-to))
          (and (nil? url) (= system loinc-url) (= target loinc-url)))
      {:kind :map-to :meta map-to}

      (or (= url (:url ieee))
          (and (nil? url)
               (#{loinc-url (:target ieee)} system)
               (#{loinc-url (:target ieee)} target)))
      {:kind :ieee :meta ieee}

      (or (= url (:url rid))
          (and (nil? url)
               (#{loinc-url (:target rid)} system)
               (#{loinc-url (:target rid)} target)))
      {:kind :rsna-rid :meta rid}

      (or (= url (:url rpid))
          (and (nil? url)
               (#{loinc-url (:target rpid)} system)
               (#{loinc-url (:target rpid)} target)))
      {:kind :rsna-rpid :meta rpid}

      (requested-part-related-target params)
      {:kind :part-related
       :target-system (requested-part-related-target params)})))

(defn- forward? [{:keys [system target]} default-target]
  (or (nil? system)
      (= system loinc-url)
      (and (= target default-target) (not= system default-target))))

(defn- matches-or-empty [rows f]
  (if (seq rows)
    {:result true :matches (mapv f rows)}
    {:result false :message "No matches found"}))

(defn- map-to-replacements [ds code]
  (jdbc/execute! ds
                 ["SELECT m.map_to, t.long_common_name, t.status
                   FROM map_to m
                   LEFT JOIN loinc t ON t.loinc_num = m.map_to
                   WHERE m.loinc = ?
                   ORDER BY m.map_to"
                  (canonical-code code)]
                 (row-builder)))

(defn- translate-map-to [ds code]
  (let [rows (map-to-replacements ds code)]
    (matches-or-empty
     rows
     (fn [{:keys [map_to long_common_name status]}]
       (cond-> {:equivalence "relatedto"
                :system loinc-url
                :code map_to}
         long_common_name (assoc :display long_common_name)
         (= "DEPRECATED" status) (assoc :inactive true))))))

(defn- translate-part-related [ds {:keys [system code]} target-system]
  (if (forward? {:system system} target-system)
    (matches-or-empty
     (jdbc/execute! ds
                    ["SELECT ext_code_id, ext_code_display_name, equivalence, ext_code_system_version
                      FROM part_related_code_mapping
                      WHERE ext_code_system = ?
                        AND part_number = ?
                      ORDER BY ext_code_id"
                     target-system (canonical-code code)]
                    (row-builder))
     (fn [{:keys [ext_code_id ext_code_display_name equivalence ext_code_system_version]}]
       (cond-> {:equivalence equivalence
                :system target-system
                :code ext_code_id}
         ext_code_display_name (assoc :display ext_code_display_name)
         ext_code_system_version (assoc :version ext_code_system_version))))
    (matches-or-empty
     (jdbc/execute! ds
                    ["SELECT part_number, part_name, equivalence
                      FROM part_related_code_mapping
                      WHERE ext_code_system = ?
                        AND ext_code_id = ? COLLATE NOCASE
                      ORDER BY part_number"
                     target-system code]
                    (row-builder))
     (fn [{:keys [part_number part_name equivalence]}]
       (cond-> {:equivalence (invert-equivalence equivalence)
                :system loinc-url
                :code part_number}
         part_name (assoc :display part_name))))))

(defn- translate-ieee [ds {:keys [system code]}]
  (let [target (:target (model/conceptmap :ieee-medical-device))]
    (if (forward? {:system system} target)
      (matches-or-empty
       (jdbc/execute! ds
                      ["SELECT ieee_cf_code10, ieee_refid, equivalence
                        FROM loinc_ieee_medical_device_mapping
                        WHERE loinc_num = ?
                        ORDER BY ieee_cf_code10, ieee_refid"
                       (canonical-code code)]
                      (row-builder))
       (fn [{:keys [ieee_cf_code10 ieee_refid equivalence]}]
         (cond-> {:equivalence equivalence
                  :system target
                  :code ieee_cf_code10}
           ieee_refid (assoc :display ieee_refid))))
      (matches-or-empty
       (jdbc/execute! ds
                      ["SELECT loinc_num, loinc_long_common_name, equivalence
                        FROM loinc_ieee_medical_device_mapping
                        WHERE ieee_cf_code10 = ? COLLATE NOCASE
                           OR ieee_refid = ? COLLATE NOCASE
                        ORDER BY loinc_num"
                       code code]
                      (row-builder))
       (fn [{:keys [loinc_num loinc_long_common_name equivalence]}]
         (cond-> {:equivalence (invert-equivalence equivalence)
                  :system loinc-url
                  :code loinc_num}
           loinc_long_common_name (assoc :display loinc_long_common_name)))))))

(defn- translate-rsna-rid [ds {:keys [system code]}]
  (let [target (:target (model/conceptmap :rsna-rid))]
    (if (forward? {:system system} target)
      (matches-or-empty
       (jdbc/execute! ds
                      ["SELECT DISTINCT rid, preferred_name
                        FROM loinc_rsna_radiology_playbook
                        WHERE loinc_number = ?
                          AND rid IS NOT NULL AND rid <> ''
                        ORDER BY rid"
                       (canonical-code code)]
                      (row-builder))
       (fn [{:keys [rid preferred_name]}]
         (cond-> {:equivalence "relatedto"
                  :system target
                  :code rid}
           preferred_name (assoc :display preferred_name))))
      (matches-or-empty
       (jdbc/execute! ds
                      ["SELECT DISTINCT loinc_number, long_common_name
                        FROM loinc_rsna_radiology_playbook
                        WHERE rid = ? COLLATE NOCASE
                        ORDER BY loinc_number"
                       code]
                      (row-builder))
       (fn [{:keys [loinc_number long_common_name]}]
         (cond-> {:equivalence "relatedto"
                  :system loinc-url
                  :code loinc_number}
           long_common_name (assoc :display long_common_name)))))))

(defn- translate-rsna-rpid [ds {:keys [system code]}]
  (let [target (:target (model/conceptmap :rsna-rpid))]
    (if (forward? {:system system} target)
      (matches-or-empty
       (jdbc/execute! ds
                      ["SELECT DISTINCT rpid, long_name
                        FROM loinc_rsna_radiology_playbook
                        WHERE loinc_number = ?
                          AND rpid IS NOT NULL AND rpid <> ''
                        ORDER BY rpid"
                       (canonical-code code)]
                      (row-builder))
       (fn [{:keys [rpid long_name]}]
         (cond-> {:equivalence "relatedto"
                  :system target
                  :code rpid}
           long_name (assoc :display long_name))))
      (matches-or-empty
       (jdbc/execute! ds
                      ["SELECT DISTINCT loinc_number, long_common_name
                        FROM loinc_rsna_radiology_playbook
                        WHERE rpid = ? COLLATE NOCASE
                        ORDER BY loinc_number"
                       code]
                      (row-builder))
       (fn [{:keys [loinc_number long_common_name]}]
         (cond-> {:equivalence "relatedto"
                  :system loinc-url
                  :code loinc_number}
           long_common_name (assoc :display long_common_name)))))))


(defrecord LoincProvider [ds version]
  Closeable
  (close [_] (store/close! ds))

  protos/NamingService
  (naming-resolver [_] loinc-naming-resolver)

  protos/CodeSystem
  (cs-metadata [this {:keys [url version] :as opts}]
    (when (and (or (nil? url) (loinc-system? url))
               (or (nil? version) (= (:version this) version))
               (search-filter/matches-resource-filters?
                {:name "LOINC" :title "Logical Observation Identifiers Names and Codes"
                 :status "active"} opts))
      [(cond-> {:url loinc-url
                :name "LOINC"
                :title "Logical Observation Identifiers Names and Codes"
                :status "active"
                :content "complete"
                :case-sensitive false}
         (:version this) (assoc :version (:version this)))]))

  (cs-resource [this params]
    (first (protos/cs-metadata this params)))

  (cs-lookup [_ {:keys [code displayLanguage properties]}]
    (with-open [conn (jdbc/get-connection ds)]
      (let [code' (canonical-code code)]
        (if-let [row (code-row conn code')]
            (let [{:keys [want?] :as property-filter} (property-filter/parse properties)
                  display-langs (display/parse-display-language displayLanguage)
                  variant-rows (when (or (want? "designation")
                                         (seq display-langs))
                                 (fetch-variant-rows conn (:loinc_num row)
                                                     (when (seq display-langs)
                                                       (mapv :lang display-langs))))
                  designations (variant-designations variant-rows)
                  lang-display (preferred-designation designations display-langs)
                  parents (when (want? "parent")
                            (fetch-parents conn (:loinc_num row)))
                  children (when (want? "child")
                             (fetch-children conn (:loinc_num row)))
                  part-by-type (when (:want-typed? property-filter)
                                 (fetch-primary-parts conn row))
                  inactive? (= "DEPRECATED" (:status row))]
              (cond-> {:name "LOINC"
                       :version version
                       :display (or lang-display (:long_common_name row))
                       :system loinc-url
                       :code (:loinc_num row)
                       :definition (variant-value row :definition_description)
                       :abstract false
                       :properties (vec
                                    (concat
                                     (wanted-properties row part-by-type property-filter)
                                     (when (and inactive? (want? "inactive"))
                                       [{:code :inactive :value true}])
                                     (map (fn [{:keys [code display]}]
                                            {:code :parent
                                             :value {:system loinc-url
                                                     :code code
                                                     :display display}})
                                          parents)
                                     (map (fn [{:keys [code display]}]
                                            {:code :child
                                             :value {:system loinc-url
                                                     :code code
                                                     :display display}})
                                          children)))
                       :designations (if (want? "designation")
                                       designations
                                       [])}
                inactive? (assoc :inactive true :inactive-status "inactive")))
            (if-let [row (answer-code-row conn code')]
              (answer-lookup-result row version)
              (if-let [row (part-code-row conn code')]
                (part-lookup-result row version)
                (if-let [row (group-code-row conn code')]
                  (group-lookup-result row version)
                  (issues/unknown-code-lookup loinc-url code))))))))

  (cs-validate-code [this {:keys [system code display displayLanguage]}]
    (if-let [r (protos/cs-lookup this {:system system :code code :displayLanguage displayLanguage
                                       :properties (when display ["designation"])})]
      (if (:not-found r)
        (if (= :unknown-system (:not-found-reason r))
          (issues/unknown-system-validate system code)
          {:result false
           :system system
           :code code
           :message (:message r)
           :issues (:issues r)})
        (let [display-issue (display-issue loinc-url code r
                                           {:display display
                                            :displayLanguage displayLanguage})]
          (cond-> {:result (nil? display-issue)
                   :system loinc-url
                   :code (:code r)
                   :display (:display r)
                   :version version}
            (:inactive r) (assoc :inactive true)
            (:inactive-status r) (assoc :inactive-status (:inactive-status r))
            display-issue (assoc :message (:text display-issue)
                                 :issues [display-issue]))))
      {:result false :system system :code code}))

  (cs-subsumes [_ {:keys [codeA codeB]}]
    (with-open [conn (jdbc/get-connection ds)]
      (let [codeA' (canonical-code codeA)
            codeB' (canonical-code codeB)]
        (cond
          (not (code-exists? conn codeA'))
          (issues/unknown-code-subsumes loinc-url codeA "codeA")
          (not (code-exists? conn codeB'))
          (issues/unknown-code-subsumes loinc-url codeB "codeB")
          :else
          {:outcome
           (cond
             (= codeA' codeB') "equivalent"
             (hierarchy-related? conn codeA' codeB' false) "subsumes"
             (hierarchy-related? conn codeB' codeA' false) "subsumed-by"
             :else "not-subsumed")}))))

  (cs-expand* [_ {:keys [text displayLanguage filters active-only max-hits]
                  requested-version :version}]
    (if (and requested-version (not= version requested-version))
      {:concepts []}
      (let [display-langs (display/parse-display-language displayLanguage)
            {:keys [sql params post-filters issues]} (search-sql text display-langs filters active-only max-hits)]
        (with-open [conn (jdbc/get-connection ds)]
          (let [rows (cond->> (jdbc/execute! conn (into [sql] params) (row-builder))
                       (seq post-filters) (filter #(every? (fn [f] (f %)) post-filters)))]
            (cond-> {:concepts (mapv #(concept-from-row conn display-langs %) rows)}
              (seq issues) (assoc :issues issues)))))))

  protos/ValueSet
  (vs-metadata [_ {url-q :url version-q :version include-implicit? :include-implicit?
                   :or {include-implicit? true} :as opts}]
    (let [tuple    (fn [url] (cond-> {:url url} version (assoc :version version)))
          implicit (fn [] (when (vs-meta-match? opts "LOINC" "All LOINC codes" "active")
                            [(tuple implicit-loinc-vs-url)]))
          answers  (fn [rows] (keep (fn [{:keys [answer_list_id answer_list_name]}]
                                      (when (vs-meta-match? opts answer_list_name answer_list_name "active")
                                        (tuple (answer-list-id->url answer_list_id))))
                                    rows))
          groups   (fn [rows] (keep (fn [{:keys [group_id group_name status]}]
                                      (when (vs-meta-match? opts group_name group_name (group-vs-status status))
                                        (tuple (group-id->url group_id))))
                                    rows))
          roots    (fn [rows] (keep (fn [{:keys [code code_text]}]
                                      (when (vs-meta-match? opts code_text code_text "active")
                                        (tuple (group-id->url code))))
                                    rows))]
      (when (version-matches? version version-q)
        (cond
          (implicit-loinc-vs? url-q)
          (implicit)

          ;; A specific URL is resolved on demand — this is how every LOINC
          ;; implicit ValueSet (answer list, group, multi-axial part) is
          ;; routed and expanded. They are never enumerated.
          url-q
          (cond
            (answer-list-id url-q) (answers (answer-list-metadata ds (answer-list-id url-q)))
            (group-id url-q)       (groups (some-> (group-meta-row ds (group-id url-q)) vector))
            (part-id url-q)        (roots (some-> (hierarchy-root-row ds (part-id url-q)) vector))
            :else nil)

          ;; Catalogue enumeration. Every LOINC ValueSet is implicit, so the
          ;; listing carries only the `http://loinc.org/vs` anchor — and only
          ;; when implicit entries are wanted (the routing index; not browse).
          include-implicit?
          (implicit)))))

  (vs-resource [_ {:keys [url] :as params}]
    (when (version-matches? version (requested-version params))
      (cond
        (implicit-loinc-vs? url)
        (implicit-loinc-resource version)

        (answer-list-id url)
        (some->> url answer-list-id (answer-list-meta-row ds) (answer-list-resource version))

        (group-id url)
        (some->> url group-id (group-meta-row ds) (group-resource version))

        (part-id url)
        (some->> url part-id (hierarchy-root-row ds) (hierarchy-resource version)))))

  (vs-expand [_ _svc {:keys [url filter activeOnly] :as params}]
    (when (version-matches? version (requested-version params))
      ;; One pooled connection + read transaction for the whole expand: the
      ;; discovery probe, the total and the concept page share a single
      ;; check-out and a single SQLite read-lock instead of 2-4 of each.
      (with-open [conn (jdbc/get-connection ds)]
        (jdbc/with-transaction [tx conn]
          (cond
            (implicit-loinc-vs? url)
            {:url url
             :version version
             :total (if (str/blank? filter)
                      (implicit-loinc-count tx activeOnly)
                      (filtered-implicit-loinc-count tx params))
             :concepts (implicit-loinc-concepts tx params)}

            (part-id url)
            (let [id (part-id url)]
              (when (hierarchy-root-row tx id)
                {:url url
                 :version version
                 :total (hierarchy-total tx id activeOnly)
                 :concepts (hierarchy-concepts tx id params)}))

            (group-id url)
            (let [id (group-id url)]
              (when (group-meta-row tx id)
                {:url url
                 :version version
                 :total (group-total tx id activeOnly)
                 :concepts (group-concepts tx id params)}))

            :else
            (when-let [id (answer-list-id url)]
              (when (answer-list-meta-row tx id)
                {:url url
                 :version version
                 :total (answer-list-total tx id filter)
                 :concepts (answer-list-concepts tx id params)})))))))

  (vs-validate-code [this _svc {:keys [url system code] :as params}]
    (when (version-matches? version (requested-version params))
      (cond
        (implicit-loinc-vs? url)
        (loinc-code-validation this params)

        (part-id url)
        (let [id (part-id url)]
          (if (not (loinc-system? system))
            {:result false
             :system system
             :code code
             :message (str "CodeSystem '" system "' is not valid for LOINC hierarchy " id)}
            (let [code' (canonical-code code)
                  result (hierarchy-related? ds id code' false)]
              (cond-> {:result result
                       :system loinc-url
                       :code code'
                       :version version}
                (not result) (assoc :message (str "Code '" code "' is not in LOINC hierarchy " id))))))

        (group-id url)
        (let [id (group-id url)]
          (if (not (loinc-system? system))
            {:result false
             :system system
             :code code
             :message (str "CodeSystem '" system "' is not valid for LOINC group " id)}
            (let [row (jdbc/execute-one! ds
                                         ["SELECT loinc_number, long_common_name
                                           FROM group_loinc_term
                                           WHERE group_id = ?
                                             AND loinc_number = ?"
                                          id (canonical-code code)]
                                         (row-builder))]
              (if row
                (validation-result loinc-url (:loinc_number row) {:display (:long_common_name row)}
                                   version params)
                {:result false
                 :system loinc-url
                 :code code
                 :message (str "Code '" code "' is not in LOINC group " id)}))))

        :else
        (when-let [id (answer-list-id url)]
          (if (not (loinc-system? system))
            {:result false
             :system system
             :code code
             :message (str "CodeSystem '" system "' is not valid for LOINC answer list " id)}
            (if-let [row (jdbc/execute-one! ds
                                            ["SELECT answer_string_id, display_text
                                              FROM answer_list
                                              WHERE answer_list_id = ?
                                                AND answer_string_id = ?"
                                             id (canonical-code code)]
                                            (row-builder))]
              (validation-result loinc-url (:answer_string_id row) {:display (:display_text row)}
                                 version params)
              {:result false
               :system loinc-url
               :code code
               :message (str "Code '" code "' is not in LOINC answer list " id)}))))))

  protos/ConceptMap
  (cm-metadata [_ {url-q :url version-q :version}]
    (when (version-matches? version version-q)
      (filterv (fn [{:keys [url]}]
                 (or (nil? url-q) (= url-q url)))
               (all-conceptmap-metas ds version))))

  (cm-resource [this params]
    (when-let [m (first (protos/cm-metadata this params))]
      (cond-> {:url (:url m) :status "active"}
        (:version m) (assoc :version (:version m))
        (:title m)   (assoc :title (:title m))
        (:system m)  (assoc :source-uri (:system m))
        (:target m)  (assoc :target-uri (:target m)))))

  (cm-translate [_ {:keys [code] :as params}]
    (if-let [{:keys [kind target-system]} (conceptmap-kind params)]
      (case kind
        :map-to (translate-map-to ds code)
        :part-related (translate-part-related ds params target-system)
        :ieee (translate-ieee ds params)
        :rsna-rid (translate-rsna-rid ds params)
        :rsna-rpid (translate-rsna-rpid ds params))
      {:result false :message "Unsupported LOINC ConceptMap request"})))

(defn open
  "Open a LOINC SQLite container and return the `LoincProvider`. The
  provider is `Closeable` and satisfies CodeSystem + ValueSet +
  ConceptMap. The provider's `cs-metadata` returns one entry whose
  `:identifiers` set carries the LOINC OID aliases (bare and
  `urn:oid:` form); the composite indexes those alongside the
  canonical URL so lookups against any of them route here."
  [path]
  (let [ds (store/open path)
        version (meta-value ds :loinc_version)]
    (->LoincProvider ds version)))
