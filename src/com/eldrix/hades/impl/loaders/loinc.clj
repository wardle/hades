(ns com.eldrix.hades.impl.loaders.loinc
  "LOINC release directory → seq of fhir-data.

  Inputs (Core CSV release + AccessoryFiles):
    - LoincTableCore/LoincTableCore.csv → :codesystem-meta + one :concept
      per LOINC code. Axis columns (COMPONENT, PROPERTY, TIME_ASPCT,
      SYSTEM, SCALE_TYP, METHOD_TYP) are emitted as raw FHIR string
      properties.
    - LoincTable/MapTo.csv → one :conceptmap (LOINC → LOINC).
    - AccessoryFiles/AnswerFile/AnswerList.csv → one :valueset per LL-id
      plus one :concept per unique LA-id (so answer-list expansions
      resolve into the same http://loinc.org code system, matching the
      published FHIR LOINC convention).
    - AccessoryFiles/PartFile/Part.csv → one :concept per LP-* part
      (PartName as display, STATUS + PartTypeName as properties).
    - AccessoryFiles/PartFile/LoincPartLink_Primary.csv → one Coding
      property per (LOINC-num, axis), pointing at the LP-part. Surfaces
      e.g. `property=COMPONENT` to recover the canonical analyte concept.
    - AccessoryFiles/ComponentHierarchyBySystem/ComponentHierarchyBySystem.csv
      → :parents on each LP/LOINC-num concept, so $subsumes and
      `descendant-of` $expand work over LOINC's hierarchy.
    - AccessoryFiles/LinguisticVariants/LinguisticVariants.csv +
      <lang>LinguisticVariant.csv → multi-lingual `:designations`
      (display + COMPONENT/PROPERTY/SYSTEM…) on each LOINC-num concept.

  Not loaded: full Loinc.csv extra columns (ORDER_OBS,
  EXAMPLE_UCUM_UNITS, …), supplementary part-link properties
  (`LoincPartLink_Supplementary.csv`), Group/PanelsAndForms,
  ImagingDocuments, Updates, ChangeSnapshot, ConsumerName.

  Output flows through `index/memory` and `in-memory` unchanged."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

(def system-uri "http://loinc.org")

;; ---------------------------------------------------------------------------
;; Version detection
;; ---------------------------------------------------------------------------

(defn infer-version
  "Best-effort version from the release directory name, e.g.
  `Loinc_2.82` → `\"2.82\"`. Returns nil when no match. Tolerates
  trailing suffixes (`Loinc_2.82-rc1`)."
  [^File root]
  (let [n (.getName root)]
    (when-let [[_ v] (re-find #"(?i)Loinc[_-]?(\d+(?:\.\d+)*)" n)]
      v)))

;; ---------------------------------------------------------------------------
;; CSV → row maps
;; ---------------------------------------------------------------------------

(defn- read-csv-rows
  "Read a CSV file as a seq of `{header-string → cell-string}` maps. The
  caller owns the reader lifetime — pass a `Reader` and consume eagerly
  inside `with-open`."
  [^java.io.Reader r]
  (let [rows (charred/read-csv r)
        [header & body] rows
        hv (vec header)]
    (map (fn [row] (zipmap hv row)) body)))

(defn- with-csv [^File f f-rows]
  (when (.exists f)
    (with-open [r (io/reader f)]
      (doall (f-rows (read-csv-rows r))))))

;; ---------------------------------------------------------------------------
;; CodeSystem meta
;; ---------------------------------------------------------------------------

(def ^:private property-defs
  "CodeSystem.property[] declarations for every Core column surfaced as
  `:properties`, so the in-memory provider can echo them in $lookup.
  Axis columns are typed `string` (not `Coding`)."
  [{"code" "STATUS"               "type" "code"
    "uri"  "http://loinc.org/property/STATUS"
    "description" "Status of the term (ACTIVE, TRIAL, DISCOURAGED, DEPRECATED)."}
   {"code" "CLASS"                "type" "string"
    "uri"  "http://loinc.org/property/CLASS"
    "description" "An arbitrary classification of the terms for grouping related observations."}
   {"code" "CLASSTYPE"            "type" "string"
    "uri"  "http://loinc.org/property/CLASSTYPE"
    "description" "Class type — 1=Laboratory, 2=Clinical, 3=Claims attachments, 4=Surveys."}
   {"code" "COMPONENT"            "type" "string"
    "uri"  "http://loinc.org/property/COMPONENT"
    "description" "First major axis: the analyte/component."}
   {"code" "PROPERTY"             "type" "string"
    "uri"  "http://loinc.org/property/PROPERTY"
    "description" "Second major axis: the kind of property."}
   {"code" "TIME_ASPCT"           "type" "string"
    "uri"  "http://loinc.org/property/TIME_ASPCT"
    "description" "Third major axis: the time aspect."}
   {"code" "SYSTEM"               "type" "string"
    "uri"  "http://loinc.org/property/SYSTEM"
    "description" "Fourth major axis: the system/sample type."}
   {"code" "SCALE_TYP"            "type" "string"
    "uri"  "http://loinc.org/property/SCALE_TYP"
    "description" "Fifth major axis: the scale of measurement."}
   {"code" "METHOD_TYP"           "type" "string"
    "uri"  "http://loinc.org/property/METHOD_TYP"
    "description" "Sixth major axis: the method (when applicable)."}
   {"code" "SHORTNAME"            "type" "string"
    "uri"  "http://loinc.org/property/SHORTNAME"
    "description" "Short name for the LOINC term."}
   {"code" "EXTERNAL_COPYRIGHT_NOTICE" "type" "string"
    "uri"  "http://loinc.org/property/EXTERNAL_COPYRIGHT_NOTICE"
    "description" "Copyright notice for terms sourced from external organisations."}
   {"code" "VersionFirstReleased" "type" "string"
    "uri"  "http://loinc.org/property/VersionFirstReleased"
    "description" "LOINC release in which this term was first published."}
   {"code" "VersionLastChanged"   "type" "string"
    "uri"  "http://loinc.org/property/VersionLastChanged"
    "description" "Most recent LOINC release in which this term changed."}])

(defn- codesystem-meta [version source-path]
  {:type :codesystem-meta
   :url system-uri
   :version version
   :case-sensitive true
   :hierarchy-meaning nil
   :content "complete"
   :supplements-target nil
   :status "active"
   :experimental false
   :name "LOINC"
   :title "Logical Observation Identifiers, Names and Codes (LOINC)"
   :description "LOINC is a freely available terminology for identifying medical laboratory observations and other clinical observations."
   :standards-status nil
   :property-defs property-defs
   :filter-defs []
   :metadata nil
   :source-path source-path})

;; ---------------------------------------------------------------------------
;; LOINC core concepts
;; ---------------------------------------------------------------------------

(def ^:private core-property-cols
  "CSV columns we surface as concept :properties, in declaration order.
  Empty cells are dropped so $lookup doesn't echo blank values."
  ["STATUS" "CLASS" "CLASSTYPE"
   "COMPONENT" "PROPERTY" "TIME_ASPCT" "SYSTEM" "SCALE_TYP" "METHOD_TYP"
   "SHORTNAME" "EXTERNAL_COPYRIGHT_NOTICE"
   "VersionFirstReleased" "VersionLastChanged"])

(defn- core-row->properties
  "Build the FHIR-shaped (string-keyed) :properties vector for one LOINC
  Core row. STATUS is emitted as `valueCode`; everything else as
  `valueString`."
  [row]
  (into []
        (keep (fn [col]
                (let [v (get row col)]
                  (when (and v (not (str/blank? v)))
                    (cond-> {"code" col}
                      (= col "STATUS") (assoc "valueCode" v)
                      (not= col "STATUS") (assoc "valueString" v))))))
        core-property-cols))

(defn- core-row->concept [version source-path row]
  (let [code (get row "LOINC_NUM")
        long-name (get row "LONG_COMMON_NAME")
        short-name (get row "SHORTNAME")
        designations (cond-> []
                       (and short-name (not (str/blank? short-name)))
                       (conj {:value short-name
                              :use {:system "http://loinc.org"
                                    :code "SHORTNAME"
                                    :display "SHORTNAME"}}))]
    {:type :concept
     :system system-uri
     :version version
     :code code
     :display long-name
     :definition nil
     :designations designations
     :properties (core-row->properties row)
     :parent-code nil
     :source-path source-path}))

(defn- load-core-table [^File root version]
  (let [^File f (io/file root "LoincTableCore" "LoincTableCore.csv")
        path (.getPath f)]
    (when-not (.exists f)
      (throw (ex-info (str "LoincTableCore.csv not found: " path)
                      {:reason :missing-core-table :source-path path})))
    (with-csv f
      (fn [rows]
        (cons (codesystem-meta version path)
              (mapv #(core-row->concept version path %) rows))))))

;; ---------------------------------------------------------------------------
;; MapTo → ConceptMap
;; ---------------------------------------------------------------------------

(defn- map-to-row->element [row]
  (let [from (get row "LOINC")
        to (get row "MAP_TO")
        comment (get row "COMMENT")]
    (when (and from to (not (str/blank? from)) (not (str/blank? to)))
      {:code from
       :display nil
       :target [(cond-> {:code to
                         :display nil
                         :equivalence "equivalent"}
                  (and comment (not (str/blank? comment)))
                  (assoc :comment comment))]})))

(defn- load-map-to [^File root version]
  (let [^File f (io/file root "LoincTable" "MapTo.csv")
        path (.getPath f)]
    (when (.exists f)
      (with-csv f
        (fn [rows]
          (let [elements (vec (keep map-to-row->element rows))]
            (when (seq elements)
              [{:type :conceptmap
                :url (str system-uri "/maps/MapTo")
                :version version
                :source-uri system-uri
                :source-version version
                :target-uri system-uri
                :target-version version
                :metadata nil
                :groups [{:source system-uri
                          :source-version version
                          :target system-uri
                          :target-version version
                          :elements elements}]
                :source-path path}])))))))

;; ---------------------------------------------------------------------------
;; AnswerList → ValueSets + LA-concepts
;; ---------------------------------------------------------------------------

(defn- answer-row->contains [row]
  (let [code (get row "AnswerStringId")
        display (get row "DisplayText")]
    (when (and code (not (str/blank? code)))
      (cond-> {:code code :system system-uri}
        (and display (not (str/blank? display))) (assoc :display display)))))

(defn- answer-list->valueset [version source-path [list-id rows]]
  (let [name-col (some #(get % "AnswerListName") rows)
        contains (vec (keep answer-row->contains rows))
        include-concepts (mapv (fn [c]
                                 (cond-> {"code" (:code c)}
                                   (:display c) (assoc "display" (:display c))))
                               contains)
        compose {"include" [{"system" system-uri
                             "concept" include-concepts}]}]
    {:type :valueset
     :url (str system-uri "/vs/" list-id)
     :version version
     :metadata {"resourceType" "ValueSet"
                "url" (str system-uri "/vs/" list-id)
                "name" (or name-col list-id)
                "status" "active"
                "compose" compose}
     :compose compose
     :source-path source-path}))

(defn- la-concept [version source-path code display]
  {:type :concept
   :system system-uri
   :version version
   :code code
   :display display
   :definition nil
   :designations []
   :properties []
   :parent-code nil
   :source-path source-path})

(defn- load-answer-lists [^File root version]
  (let [^File f (io/file root "AccessoryFiles" "AnswerFile" "AnswerList.csv")
        path (.getPath f)]
    (when (.exists f)
      (with-csv f
        (fn [rows]
          (let [grouped (group-by #(get % "AnswerListId") rows)
                value-sets (mapv #(answer-list->valueset version path %) grouped)
                ;; LA-codes appear in possibly multiple lists; one
                ;; concept per unique (code, first-seen display).
                la-concepts (->> rows
                                 (keep (fn [row]
                                         (let [code (get row "AnswerStringId")
                                               display (get row "DisplayText")]
                                           (when (and code (not (str/blank? code)))
                                             [code display]))))
                                 (reduce (fn [m [code display]]
                                           (if (contains? m code) m (assoc m code display)))
                                         {})
                                 (mapv (fn [[code display]]
                                         (la-concept version path code display))))]
            (concat la-concepts value-sets)))))))

;; ---------------------------------------------------------------------------
;; Part.csv → LP-* concepts
;; ---------------------------------------------------------------------------

(defn- part-row->concept [version source-path row]
  (let [code (get row "PartNumber")
        display (or (some-> (get row "PartDisplayName") str/trim not-empty)
                    (get row "PartName"))
        type-name (get row "PartTypeName")
        status (get row "Status")]
    (when (and code (not (str/blank? code)))
      {:type :concept
       :system system-uri
       :version version
       :code code
       :display display
       :definition nil
       :designations []
       :properties (cond-> []
                     (and status (not (str/blank? status)))
                     (conj {"code" "STATUS" "valueCode" status})
                     (and type-name (not (str/blank? type-name)))
                     (conj {"code" "PartTypeName" "valueString" type-name}))
       :parents []
       :source-path source-path})))

(defn- load-parts [^File root version]
  (let [^File f (io/file root "AccessoryFiles" "PartFile" "Part.csv")
        path (.getPath f)]
    (when (.exists f)
      (with-csv f
        (fn [rows]
          (vec (keep #(part-row->concept version path %) rows)))))))

;; ---------------------------------------------------------------------------
;; LoincPartLink_Primary.csv → property links (LOINC-num → LP-part by axis)
;; ---------------------------------------------------------------------------

(defn- part-link-row->property [row]
  (let [loinc-num (get row "LoincNumber")
        part-num  (get row "PartNumber")
        type-name (get row "PartTypeName")
        part-name (get row "PartName")]
    (when (and loinc-num part-num type-name
               (not (str/blank? loinc-num))
               (not (str/blank? part-num))
               (not (str/blank? type-name)))
      {:loinc loinc-num
       :prop  (cond-> {"code" type-name
                       "valueCoding" (cond-> {"system" system-uri
                                              "code"   part-num}
                                       (and part-name (not (str/blank? part-name)))
                                       (assoc "display" part-name))})})))

(defn- load-part-links
  "Read the primary part-link table and return a `{loinc-num → [props]}`
  map, ready to be merged onto the per-concept entries built from
  LoincTableCore."
  [^File root]
  (let [^File f (io/file root "AccessoryFiles" "PartFile" "LoincPartLink_Primary.csv")]
    (if-not (.exists f)
      {}
      (with-csv f
        (fn [rows]
          (reduce (fn [m {:keys [loinc prop]}]
                    (if loinc (update m loinc (fnil conj []) prop) m))
                  {}
                  (keep part-link-row->property rows)))))))

;; ---------------------------------------------------------------------------
;; ComponentHierarchyBySystem.csv → parent edges
;; ---------------------------------------------------------------------------

(defn- load-component-hierarchy
  "Read `ComponentHierarchyBySystem.csv` and return a `{code → #{parent-code}}`
  map. A code can have multiple parents (LOINC's hierarchy is a DAG). The
  root rows have an empty `IMMEDIATE_PARENT`; we drop those — the indexer
  treats absence of a parent as 'top-level'."
  [^File root]
  (let [^File f (io/file root "AccessoryFiles" "ComponentHierarchyBySystem"
                         "ComponentHierarchyBySystem.csv")]
    (if-not (.exists f)
      {}
      (with-csv f
        (fn [rows]
          (reduce (fn [m row]
                    (let [code   (get row "CODE")
                          parent (get row "IMMEDIATE_PARENT")]
                      (if (and code parent
                               (not (str/blank? code))
                               (not (str/blank? parent))
                               (not= code parent))
                        (update m code (fnil conj #{}) parent)
                        m)))
                  {}
                  rows))))))

;; ---------------------------------------------------------------------------
;; LinguisticVariants/<lang>LinguisticVariant.csv → multi-lingual designations
;; ---------------------------------------------------------------------------

(def ^:private linguistic-cols
  ;; Columns in a per-language file we surface as a designation.
  ;; `LONG_COMMON_NAME` is treated as the localised display; the others
  ;; become typed designations whose `use.code` carries the column name.
  ["LONG_COMMON_NAME" "COMPONENT" "PROPERTY" "TIME_ASPCT" "SYSTEM"
   "SCALE_TYP" "METHOD_TYP" "CLASS" "SHORTNAME"
   "RELATEDNAMES2" "LinguisticVariantDisplayName"])

(defn- ling-row->designations [language row]
  (let [code (get row "LOINC_NUM")]
    (when (and code (not (str/blank? code)))
      (let [ds (keep (fn [col]
                       (let [v (get row col)]
                         (when (and v (not (str/blank? v)))
                           {:value v
                            :language language
                            :use {:system "http://loinc.org"
                                  :code col
                                  :display col}})))
                     linguistic-cols)]
        (when (seq ds) [code (vec ds)])))))

(defn- bcp47-tag
  "Map an ISO 639 + ISO 3166 pair to a BCP 47 tag, e.g. (\"de\" \"DE\") → \"de-DE\"."
  [lang country]
  (cond
    (and (not (str/blank? lang)) (not (str/blank? country)))
    (str (str/lower-case lang) "-" (str/upper-case country))
    (not (str/blank? lang))
    (str/lower-case lang)
    :else nil))

(defn- load-language-variant
  [^File f language]
  (with-csv f
    (fn [rows]
      (->> rows
           (keep #(ling-row->designations language %))
           (reduce (fn [m [code ds]]
                     (update m code (fnil into []) ds))
                   {})))))

(defn- load-linguistic-variants
  "Return a `{loinc-num → [designation ...]}` map collected across every
  per-language file referenced by `LinguisticVariants.csv`. Returns `{}`
  when the catalog is missing."
  [^File root]
  (let [^File catalog (io/file root "AccessoryFiles" "LinguisticVariants"
                               "LinguisticVariants.csv")]
    (if-not (.exists catalog)
      {}
      (with-csv catalog
        (fn [rows]
          (reduce
            (fn [acc row]
              (let [id (get row "ID")
                    lang (get row "ISO_LANGUAGE")
                    country (get row "ISO_COUNTRY")
                    tag (bcp47-tag lang country)
                    fname (when (and id lang country)
                            (str lang country id "LinguisticVariant.csv"))
                    ^File f (when fname
                              (io/file root "AccessoryFiles" "LinguisticVariants" fname))]
                (if (and tag f (.exists f))
                  (merge-with into acc (load-language-variant f tag))
                  acc)))
            {}
            rows))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- enrich-loinc-concept
  "Layer part-link Coding properties, hierarchy parents, and linguistic
  designations onto a base concept built from LoincTableCore."
  [c part-links hierarchy lingual]
  (let [code (:code c)
        extra-props (get part-links code)
        parents     (some-> (get hierarchy code) seq vec)
        ling-ds     (get lingual code)]
    (cond-> c
      (seq extra-props) (update :properties into extra-props)
      (seq parents)     (assoc :parents parents)
      (seq ling-ds)     (update :designations into ling-ds))))

(defn- enrich-part-concept
  "Layer hierarchy parents onto a Part-derived concept (LP-* code).
  Linguistic-variant files don't carry rows for LP- codes in the
  released LOINC datasets, so we only consult the hierarchy."
  [c hierarchy]
  (if-let [parents (some-> (get hierarchy (:code c)) seq vec)]
    (assoc c :parents parents)
    c))

(defn load-paths
  "Walk a LOINC release directory and return a flat seq of `fhir-data`.

  Options:
    :version   release version string. Falls back to inferring from the
               directory name (e.g. `Loinc_2.82` → `\"2.82\"`); throws
               `ex-info` if neither is available."
  ([root] (load-paths root nil))
  ([root {:keys [version]}]
   (let [^File canonical-root (.getCanonicalFile (io/file root))
         v (or version (infer-version canonical-root))]
     (when (str/blank? v)
       (throw (ex-info (str "LOINC version not provided and could not "
                            "be inferred from directory name: "
                            (.getName canonical-root))
                       {:reason :missing-version
                        :source-path (.getPath canonical-root)})))
     (let [;; Side tables — read once, kept as in-memory maps so we can
           ;; layer them onto the per-concept stream without re-scanning.
           part-links (load-part-links            canonical-root)
           hierarchy  (load-component-hierarchy   canonical-root)
           lingual    (load-linguistic-variants   canonical-root)
           core       (load-core-table            canonical-root v)
           parts      (or (load-parts             canonical-root v) [])
           ;; Enrich core LOINC concepts with part-links, hierarchy, ling.
           enriched-core (map (fn [e]
                                (if (= :concept (:type e))
                                  (enrich-loinc-concept e part-links hierarchy lingual)
                                  e))
                              core)
           ;; LP-* part concepts only need hierarchy; their displays come
           ;; from PartName / PartDisplayName already.
           enriched-parts (map #(enrich-part-concept % hierarchy) parts)
           answer-lists (load-answer-lists canonical-root v)
           map-to       (load-map-to       canonical-root v)]
       (concat enriched-core
               enriched-parts
               answer-lists
               map-to)))))
