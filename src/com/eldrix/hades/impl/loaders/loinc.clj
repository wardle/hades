(ns com.eldrix.hades.impl.loaders.loinc
  "LOINC release directory → fhir-data.

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
    - AccessoryFiles/PartFile/LoincPartLink_Primary.csv → one
      `:concept-property` fhir-data entry per (LOINC-num, axis), each
      carrying a Coding property pointing at the LP-part. Surfaces e.g.
      `property=COMPONENT` to recover the canonical analyte concept.
    - AccessoryFiles/ComponentHierarchyBySystem/ComponentHierarchyBySystem.csv
      → one `:concept-parent` fhir-data entry per row (for both LP- and
      LOINC-num children), so $subsumes and `descendant-of` $expand
      work over LOINC's hierarchy.
    - AccessoryFiles/LinguisticVariants/LinguisticVariants.csv +
      <lang>LinguisticVariant.csv → multi-lingual `:designations`
      (display + COMPONENT/PROPERTY/SYSTEM…) on each LOINC-num concept.

  Not loaded: full Loinc.csv extra columns (ORDER_OBS,
  EXAMPLE_UCUM_UNITS, …), supplementary part-link properties
  (`LoincPartLink_Supplementary.csv`), Group/PanelsAndForms,
  ImagingDocuments, Updates, ChangeSnapshot, ConsumerName.

  Large imports should use `stream-release`, which emits rows through a
  bounded channel."
  (:require [charred.api :as charred]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.eldrix.hades.impl.canonical :as canonical])
  (:import (java.io Closeable File)))

(set! *warn-on-reflection* true)

(def system-uri "http://loinc.org")

;; ---------------------------------------------------------------------------
;; CSV row maps
;; ---------------------------------------------------------------------------

(defn- reduce-csv-rows
  "Open `f` and reduce over `{header -> cell}` row maps.
  Returns `init` when absent. Row maps are short-lived and the channel
  carries bounded fhir-data batches, matching the Hermes importer shape."
  [rf init ^File f]
  (if-not (.exists f)
    init
    (let [^java.util.function.Supplier supplier
          (charred/read-csv-supplier f {:async? false})
          header (vec (.get supplier))]
      (try
        (loop [acc init]
          (if-let [row (.get supplier)]
            (recur (rf acc (zipmap header row)))
            acc))
        (finally
          (when (instance? Closeable supplier)
            (.close ^Closeable supplier)))))))

(defn- read-csv!
  "Open `f` and call `row-fn` with each row map.
  Returns nil when the file is absent."
  [^File f row-fn]
  (reduce-csv-rows (fn [_ row] (row-fn row) nil) nil f))

(defn- put-fhir-data! [ch fd]
  (when-not (async/>!! ch fd)
    (throw (ex-info "LOINC stream closed by consumer"
                    {:reason :stream-closed}))))

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

(defn- core-row->concept
  [version source-path {:strs [LOINC_NUM LONG_COMMON_NAME SHORTNAME] :as row}]
  (let [designations (cond-> []
                       (and SHORTNAME (not (str/blank? SHORTNAME)))
                       (conj {:value SHORTNAME
                              :use {:system "http://loinc.org"
                                    :code "SHORTNAME"
                                    :display "SHORTNAME"}}))]
    {:type :concept
     :system system-uri
     :version version
     :code LOINC_NUM
     :display LONG_COMMON_NAME
     :definition nil
     :designations designations
     :properties (core-row->properties row)
     :source-path source-path}))

;; ---------------------------------------------------------------------------
;; MapTo → ConceptMap
;; ---------------------------------------------------------------------------

(defn- map-to-row->element
  [{:strs [LOINC MAP_TO COMMENT]}]
  (when (and LOINC MAP_TO (not (str/blank? LOINC)) (not (str/blank? MAP_TO)))
    {:code LOINC
     :display nil
     :target [(cond-> {:code MAP_TO
                       :display nil
                       :equivalence "equivalent"}
                (and COMMENT (not (str/blank? COMMENT)))
                (assoc :comment COMMENT))]}))

(defn- stream-map-to!
  [ch ^File root version]
  (let [^File f (io/file root "LoincTable" "MapTo.csv")
        path (.getPath f)]
    (when (.exists f)
      (let [elements (reduce-csv-rows
                       (fn [elements row]
                         (if-let [el (map-to-row->element row)]
                           (conj elements el)
                           elements))
                       []
                       f)]
        (when (seq elements)
          (put-fhir-data! ch {:type :conceptmap
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
                              :source-path path}))))))

;; ---------------------------------------------------------------------------
;; AnswerList → ValueSets + LA-concepts
;; ---------------------------------------------------------------------------

(defn- answer-row->contains
  [{:strs [AnswerStringId DisplayText]}]
  (when (and AnswerStringId (not (str/blank? AnswerStringId)))
    (cond-> {:code AnswerStringId :system system-uri}
      (and DisplayText (not (str/blank? DisplayText))) (assoc :display DisplayText))))

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

(defn- stream-answer-lists!
  [ch ^File root version]
  (let [^File f (io/file root "AccessoryFiles" "AnswerFile" "AnswerList.csv")
        path (.getPath f)]
    (when (.exists f)
      (let [{:keys [lists la]}
            (reduce-csv-rows
              (fn [{:keys [la] :as acc}
                   {:strs [AnswerListId AnswerListName AnswerStringId DisplayText] :as row}]
                (let [acc (cond-> acc
                            (and AnswerStringId
                                 (not (str/blank? AnswerStringId))
                                 (not (contains? la AnswerStringId)))
                            (assoc-in [:la AnswerStringId] DisplayText))]
                  (if-let [contains (answer-row->contains row)]
                    (update-in acc [:lists AnswerListId]
                               (fn [{:keys [name rows]}]
                                 {:name (or name AnswerListName AnswerListId)
                                  :rows (conj (or rows []) contains)}))
                    acc)))
              {:lists {} :la {}}
              f)]
        (doseq [[code display] la]
          (put-fhir-data! ch (la-concept version path code display)))
        (doseq [[list-id {:keys [name rows]}] lists]
          (let [include-concepts (mapv (fn [c]
                                         (cond-> {"code" (:code c)}
                                           (:display c) (assoc "display" (:display c))))
                                       rows)
                compose {"include" [{"system" system-uri
                                     "concept" include-concepts}]}]
            (put-fhir-data! ch {:type :valueset
                                :url (str system-uri "/vs/" list-id)
                                :version version
                                :metadata {"resourceType" "ValueSet"
                                           "url" (str system-uri "/vs/" list-id)
                                           "name" (or name list-id)
                                           "status" "active"
                                           "compose" compose}
                                :compose compose
                                :source-path path})))))))

;; ---------------------------------------------------------------------------
;; Part.csv → LP-* concepts
;; ---------------------------------------------------------------------------

(defn- part-row->concept
  [version source-path {:strs [PartNumber PartDisplayName PartName PartTypeName Status]}]
  (let [display (or (some-> PartDisplayName str/trim not-empty) PartName)]
    (when (and PartNumber (not (str/blank? PartNumber)))
      {:type :concept
       :system system-uri
       :version version
       :code PartNumber
       :display display
       :definition nil
       :designations []
       :properties (cond-> []
                     (and Status (not (str/blank? Status)))
                     (conj {"code" "STATUS" "valueCode" Status})
                     (and PartTypeName (not (str/blank? PartTypeName)))
                     (conj {"code" "PartTypeName" "valueString" PartTypeName}))
       :source-path source-path})))

;; ---------------------------------------------------------------------------
;; LoincPartLink_Primary.csv → standalone :concept-property entries
;;
;; Each row carries (LOINC-num, axis, LP-part). The SQLite indexer routes
;; standalone `:concept-property` entries straight into `concept_property`
;; so we emit one per row instead of building a `{code → [link…]}` map up
;; front (~100–200 MB on modern releases).
;; ---------------------------------------------------------------------------

(defn- part-link-row->property
  [version source-path {:strs [LoincNumber PartNumber PartTypeName PartName]}]
  (when (and LoincNumber PartNumber PartTypeName
             (not (str/blank? LoincNumber))
             (not (str/blank? PartNumber))
             (not (str/blank? PartTypeName)))
      {:type :concept-property
       :system system-uri
       :version version
       :code LoincNumber
       :property {"code" PartTypeName
                  "valueCoding"
                  (cond-> {"system" system-uri
                           "code"   PartNumber}
                    (and PartName (not (str/blank? PartName)))
                    (assoc "display" PartName))}
       :source-path source-path}))

(defn- stream-part-links!
  [ch ^File root version]
  (let [^File f (io/file root "AccessoryFiles" "PartFile" "LoincPartLink_Primary.csv")
        path (.getPath f)]
    (when (.exists f)
      (read-csv! f
        (fn [row]
          (when-let [fd (part-link-row->property version path row)]
            (put-fhir-data! ch fd)))))))

;; ---------------------------------------------------------------------------
;; ComponentHierarchyBySystem.csv → standalone :concept-parent entries
;;
;; Same pattern as part-links: one fhir-data entry per row. Both LOINC-num
;; and LP-* parents flow through the same emission path; the SQLite
;; indexer doesn't care which is which.
;; ---------------------------------------------------------------------------

(defn- hierarchy-row->parent
  [version source-path {:strs [CODE IMMEDIATE_PARENT]}]
  (when (and CODE IMMEDIATE_PARENT
             (not (str/blank? CODE))
             (not (str/blank? IMMEDIATE_PARENT))
             (not= CODE IMMEDIATE_PARENT))
      {:type :concept-parent
       :system system-uri
       :version version
       :code CODE
       :parent IMMEDIATE_PARENT
       :source-path source-path}))

(defn- stream-hierarchy!
  [ch ^File root version]
  (let [^File f (io/file root "AccessoryFiles" "ComponentHierarchyBySystem"
                         "ComponentHierarchyBySystem.csv")
        path (.getPath f)]
    (when (.exists f)
      (read-csv! f
        (fn [row]
          (when-let [fd (hierarchy-row->parent version path row)]
            (put-fhir-data! ch fd)))))))

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

(defn- ling-row->designation-entries
  "Yield zero or more `:concept-designation` fhir-data entries for one
  per-language row. Each entry stands alone — the SQLite indexer routes
  them straight into `concept_designation` without per-code joining,
  so the lingual stream never materialises into a giant
  `{code → [designations]}` map."
  [version source-path language {:strs [LOINC_NUM] :as row}]
  (when (and LOINC_NUM (not (str/blank? LOINC_NUM)))
    (keep (fn [col]
            (let [v (get row col)]
              (when (and v (not (str/blank? v)))
                {:type :concept-designation
                 :system system-uri
                 :version version
                 :code LOINC_NUM
                 :value v
                 :language language
                 :use {:system "http://loinc.org"
                       :code col
                       :display col}
                 :source-path source-path})))
          linguistic-cols)))

(defn- bcp47-tag
  "Map an ISO 639 + ISO 3166 pair to a BCP 47 tag, e.g. (\"de\" \"DE\") → \"de-DE\"."
  [lang country]
  (cond
    (and (not (str/blank? lang)) (not (str/blank? country)))
    (str (str/lower-case lang) "-" (str/upper-case country))
    (not (str/blank? lang))
    (str/lower-case lang)
    :else nil))

(defn- linguistic-variant-files
  "Eager seq of `[language file]` tuples for every per-language file
  referenced by `LinguisticVariants.csv`. The catalog itself is small
  (~22 rows). Returns nil when the catalog is missing."
  [^File root]
  (let [^File catalog (io/file root "AccessoryFiles" "LinguisticVariants"
                               "LinguisticVariants.csv")]
    (when (.exists catalog)
      (reduce-csv-rows
        (fn [files {:strs [ID ISO_LANGUAGE ISO_COUNTRY]}]
          (let [tag     (bcp47-tag ISO_LANGUAGE ISO_COUNTRY)
                fname   (when (and ID ISO_LANGUAGE ISO_COUNTRY)
                          (str ISO_LANGUAGE ISO_COUNTRY ID "LinguisticVariant.csv"))
                ^File f (when fname
                          (io/file root "AccessoryFiles" "LinguisticVariants" fname))]
            (if (and tag f (.exists f))
              (conj files [tag f])
              files)))
        []
        catalog))))

(defn- stream-linguistic-designations!
  [ch ^File root version]
  (doseq [[language ^File f] (linguistic-variant-files root)]
    (let [path (.getPath f)]
      (read-csv! f
        (fn [row]
          (doseq [entry (ling-row->designation-entries version path language row)]
            (put-fhir-data! ch entry)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- stream-core-table!
  [ch ^File root version]
  (let [^File f (io/file root "LoincTableCore" "LoincTableCore.csv")
        path (.getPath f)]
    (when-not (.exists f)
      (throw (ex-info (str "LoincTableCore.csv not found: " path)
                      {:reason :missing-core-table :source-path path})))
    (put-fhir-data! ch (codesystem-meta version path))
    (read-csv! f
      (fn [row]
        (put-fhir-data! ch (core-row->concept version path row))))))

(defn- stream-parts!
  [ch ^File root version]
  (let [^File f (io/file root "AccessoryFiles" "PartFile" "Part.csv")
        path (.getPath f)]
    (when (.exists f)
      (read-csv! f
        (fn [row]
          (when-let [concept (part-row->concept version path row)]
            (put-fhir-data! ch concept)))))))

(defn read-version
  "Return the highest `VersionLastChanged` value across all rows of
  `LoincTableCore.csv` under `canonical-root`. Every release bumps at
  least one concept's last-changed marker, so `max(VersionLastChanged)`
  is the release version itself — content-derived, immune to
  operator-renamed unzip directories. Returns nil when the file is
  absent or carries no recognisable version values."
  [^File canonical-root]
  (let [^File f (io/file canonical-root "LoincTableCore" "LoincTableCore.csv")]
    (when (.exists f)
      (->> (reduce-csv-rows
             (fn [acc row]
               (let [v (get row "VersionLastChanged")]
                 (cond-> acc (not (str/blank? v)) (conj v))))
             #{} f)
           (sort canonical/semver-compare)
           last))))

(defn- release-version [^File canonical-root version]
  (let [v (or version (read-version canonical-root))]
    (when (str/blank? v)
      (throw (ex-info (str "LOINC version could not be derived from "
                           "LoincTableCore.csv under: "
                           (.getPath canonical-root))
                      {:reason :missing-version
                       :source-path (.getPath canonical-root)})))
    v))

(defn- stream-release* [root {:keys [version]} ch]
  (let [^File canonical-root (.getCanonicalFile (io/file root))
        v (release-version canonical-root version)]
    (stream-core-table! ch canonical-root v)
    (stream-parts! ch canonical-root v)
    (stream-part-links! ch canonical-root v)
    (stream-hierarchy! ch canonical-root v)
    (stream-answer-lists! ch canonical-root v)
    (stream-map-to! ch canonical-root v)
    (stream-linguistic-designations! ch canonical-root v)))

(defn stream-release
  "Return a channel that streams bounded batches of fhir-data maps for one LOINC release.
  The channel is closed when the release is fully streamed. On failure
  the final item is `{:type :stream-error :ex e}`."
  ([root] (stream-release root nil))
  ([root {:keys [buffer-size batch-size] :as opts}]
   (let [ch (async/chan (or buffer-size 8) (partition-all (or batch-size 1000)))]
     (async/thread
       (try
         (stream-release* root opts ch)
         (catch Throwable t
           (async/>!! ch {:type :stream-error :ex t}))
         (finally
           (async/close! ch))))
     ch)))

(defn- stream-error?
  "True when a fhir-data item (or batch of items) carries a stream
  failure raised by a per-release producer."
  [fd]
  (cond
    (and (map? fd) (= :stream-error (:type fd))) true
    (sequential? fd) (some #(= :stream-error (:type %)) fd)
    :else false))

(defn stream-releases
  "Return a channel multiplexing batched fhir-data from every LOINC
  release in `roots`. Each release is streamed via `stream-release`;
  outputs are forwarded onto the returned channel in order. The
  returned channel is closed when all releases finish, when a
  release's `:stream-error` item is propagated and aborts streaming,
  or when the consumer closes the channel.

  When a producer-side error closes the input channel mid-stream, the
  source channel is also closed so the LOINC producer thread parked
  on `>!!` to its bounded buffer unblocks rather than grinding through
  the rest of the release into nothing."
  ([roots] (stream-releases roots nil))
  ([roots opts]
   (let [out-ch (async/chan 256)]
     (async/thread
       (try
         (loop [[root & more] (seq roots)]
           (when root
             (let [src-ch  (stream-release root opts)
                   outcome (loop []
                             (if-let [fd (async/<!! src-ch)]
                               (cond
                                 (stream-error? fd)
                                 (do (async/>!! out-ch fd) :error)

                                 (async/>!! out-ch fd) (recur)
                                 :else :closed)
                               :done))]
               (case outcome
                 :done   (recur more)
                 :closed (async/close! src-ch)
                 :error  (async/close! src-ch)))))
         (catch Throwable t
           (async/>!! out-ch {:type :stream-error :ex t}))
         (finally
           (async/close! out-ch))))
     out-ch)))

;; ---------------------------------------------------------------------------
;; Recogniser
;; ---------------------------------------------------------------------------

(defn- loinc-recognise
  "Anchor a LOINC release on `LoincTableCore.csv` — distinctive enough
  to identify a release on its own. The release root is the parent
  (flat layout) or grandparent (`<root>/LoincTableCore/LoincTableCore.csv`).
  Version resolution is the loader's concern; this stays cheap."
  [^File f _probe?]
  (when (and (.isFile f) (= "LoincTableCore.csv" (.getName f)))
    (let [parent (.getParentFile f)
          root   (if (= "LoincTableCore" (.getName parent))
                   (.getParentFile parent)
                   parent)]
      {:dir root})))

(def loinc-recogniser
  {:id          :loinc
   :importable? true
   :database?   false
   :recognise   loinc-recognise})
