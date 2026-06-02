(ns com.eldrix.hades.providers.in-memory.provider
  "In-memory provider deftypes that satisfy `CodeSystem`, `ValueSet`, and
  `ConceptMap` against indexed concept data.

  Three deftypes:

    `MemoryCodeSystem` — backs a CodeSystem and the implicit ValueSet
    of that CodeSystem from a `:codesystem-meta` plus the concepts
    belonging to it. A `content=\"supplement\"` instance also satisfies
    `supplement/SupplementSource`, yielding its augmentation table for
    the composite to wire onto the base it supplements.

    `MemoryValueSet` — backs a compose-driven ValueSet from a
    `:valueset` fhir-data entry.

    `MemoryConceptMap` — backs a ConceptMap with forward and reverse
    translation from a `:conceptmap` fhir-data entry.

  The convenience helper `from-fhir` exists for tests and ad-hoc
  construction; the production path goes through `loaders/fhir` +
  `index/memory`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.providers.common.display :as display]
            [com.eldrix.hades.providers.common.fhir-extract :as fhir-extract]
            [com.eldrix.hades.providers.common.issues :as issues]
            [com.eldrix.hades.providers.common.property-filter :as property-filter]
            [com.eldrix.hades.providers.common.search-filter :as search-filter]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.providers.common.supplement :as supplement]
            [com.eldrix.hades.providers.common.vs-validate :as vs-validate])
  (:import (com.google.re2j Pattern)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Concept-property accessors (operate on raw FHIR property maps)
;; ---------------------------------------------------------------------------

(defn- get-concept-property
  "Extract the value of a named property from a concept's :properties
  list. Returns ::not-found when the property is not present (distinct
  from a property whose value is false / 0 / nil)."
  [concept property-name]
  (reduce (fn [_ prop]
            (if (= property-name (get prop "code"))
              (reduced (fhir-extract/extract-property-value prop))
              ::not-found))
          ::not-found
          (:properties concept)))

(defn- ancestor?
  "Check whether `ancestor-code` is an ancestor of `code` by walking the
  parent map. Uses BFS with a visited set to handle cycles defensively."
  [parents-map code ancestor-code]
  (loop [queue (vec (get parents-map code))
         visited #{}]
    (when (seq queue)
      (let [current (peek queue)
            queue' (pop queue)]
        (cond
          (= current ancestor-code) true
          (visited current) (recur queue' visited)
          :else (recur (into queue' (get parents-map current))
                       (conj visited current)))))))

(defn- collect-ancestors
  [parents-map code]
  (loop [queue (vec (get parents-map code))
         visited #{}
         result []]
    (if (seq queue)
      (let [current (peek queue)
            queue' (pop queue)]
        (if (visited current)
          (recur queue' visited result)
          (recur (into queue' (get parents-map current))
                 (conj visited current)
                 (conj result current))))
      result)))

(defn- same-code?
  [case-sensitive? a b]
  (if case-sensitive?
    (= a b)
    (= (str/lower-case (str a)) (str/lower-case (str b)))))

(defn- code-in?
  [case-sensitive? values code]
  (if case-sensitive?
    (contains? values code)
    (contains? (set (map str/lower-case values))
               (str/lower-case (str code)))))

(defn- canonical-filter-code
  [code-index case-sensitive? code]
  (if case-sensitive?
    code
    (or (some (fn [actual]
                (when (same-code? false actual code) actual))
              (keys code-index))
        code)))

(defn- concept-matches-filter?
  [code-index _children-map parents-map concept {:keys [property op value]} case-sensitive?]
  (let [canonical-value (delay (canonical-filter-code code-index case-sensitive? value))]
  (case op
    "is-a" (or (same-code? case-sensitive? (:code concept) value)
               (some #(same-code? case-sensitive? % value)
                     (collect-ancestors parents-map (:code concept))))
    ("descendent-of" "descendant-of")
    (some #(same-code? case-sensitive? % value)
          (collect-ancestors parents-map (:code concept)))
    "is-not-a" (not (or (same-code? case-sensitive? (:code concept) value)
                        (some #(same-code? case-sensitive? % value)
                              (collect-ancestors parents-map (:code concept)))))
    "generalizes" (or (same-code? case-sensitive? (:code concept) value)
                      (some #(same-code? case-sensitive? % (:code concept))
                            (collect-ancestors parents-map @canonical-value)))
    "=" (if (= property "code")
          (same-code? case-sensitive? (:code concept) value)
          (let [pv (get-concept-property concept property)]
            (and (not= pv ::not-found) (= (str pv) value))))
    "regex" (let [^Pattern pat (Pattern/compile value)
                  pv (if (= property "code")
                       (:code concept)
                       (let [v (get-concept-property concept property)]
                         (when (not= v ::not-found) (str v))))]
              (boolean (when pv (.matches (.matcher pat ^CharSequence pv)))))
    "in" (let [vals (set (str/split value #","))]
           (if (= property "code")
             (code-in? case-sensitive? vals (:code concept))
             (let [pv (get-concept-property concept property)]
               (and (not= pv ::not-found) (contains? vals (str pv))))))
    "not-in" (let [vals (set (str/split value #","))]
               (if (= property "code")
                 (not (code-in? case-sensitive? vals (:code concept)))
                 (let [pv (get-concept-property concept property)]
                   (or (= pv ::not-found) (not (contains? vals (str pv)))))))
    "exists" (let [exists? (= "true" value)
                   pv (get-concept-property concept property)]
               (if exists?
                 (not= pv ::not-found)
                 (= pv ::not-found)))
    (throw (ex-info (str "The filter operation '" op "' is not supported")
                    {:type         :not-supported
                     :details-code "not-supported"
                     :issues       [{:severity     "error"
                                     :type         "not-supported"
                                     :details-code "not-supported"
                                     :text         (str "The filter operation '" op "' is not supported")}]})))))

;; FHIR concept-property statuses that mean "no longer to be used".
;; "deprecated" is *not* in this set — per FHIR the deprecated state
;; flags a concept being phased out but still in active use; the
;; expansion's `inactive` boolean and `cs-validate-code`'s `:inactive`
;; flag only fire for retired / inactive concepts.
(def ^:private inactive-statuses #{"retired" "inactive"})

(defn- concept-inactive?
  [concept]
  (let [inactive-prop (get-concept-property concept "inactive")
        status-prop (get-concept-property concept "status")]
    (or (and (not= inactive-prop ::not-found) (boolean inactive-prop))
        (and (not= status-prop ::not-found)
             (contains? inactive-statuses status-prop)))))

(defn- concept-inactive-status
  [concept]
  (let [status-prop (get-concept-property concept "status")]
    (if (and (not= status-prop ::not-found)
             (contains? inactive-statuses status-prop))
      status-prop
      "inactive")))

(def ^:private notSelectable-uri
  "http://hl7.org/fhir/concept-properties#notSelectable")

(defn- concept-abstract?
  [property-uri-map concept]
  (let [uri-code (get property-uri-map notSelectable-uri)
        codes (cond-> #{"notSelectable"}
                uri-code (conj uri-code))]
    (boolean
      (some (fn [code]
              (let [v (get-concept-property concept code)]
                (when (and (not= v ::not-found) v)
                  true)))
            codes))))

(defn- ci-lookup
  "Case-insensitive code lookup. Returns [actual-concept input-code-differs?]."
  [code-index ci-index code]
  (if-let [concept (get code-index code)]
    [concept false]
    (when-let [actual-code (get ci-index (str/lower-case code))]
      [(get code-index actual-code) true])))

;; ---------------------------------------------------------------------------
;; MemoryCodeSystem — implements CodeSystem + ValueSet (implicit VS).
;; ---------------------------------------------------------------------------

(defn- meta-language [meta]
  (get-in meta [:metadata "language"]))

(defn- cs-resource-from-meta
  [meta]
  (let [language (meta-language meta)]
    (cond-> {:url (:url meta)}
      (:version meta)              (assoc :version (:version meta))
      (:name meta)                 (assoc :name (:name meta))
      (:title meta)                (assoc :title (:title meta))
      (:status meta)               (assoc :status (:status meta))
      (some? (:experimental meta)) (assoc :experimental (boolean (:experimental meta)))
      (:description meta)          (assoc :description (:description meta))
      (:content meta)              (assoc :content (:content meta))
      (some? (:case-sensitive meta)) (assoc :case-sensitive (:case-sensitive meta))
      language                     (assoc :language language)
      (:standards-status meta)     (assoc :standards-status (:standards-status meta)))))

(deftype MemoryCodeSystem [meta code-index hierarchy property-uri-map case-sensitive? ci-index]
  protos/CodeSystem
  (cs-metadata [_ {url-q :url ver-q :version :as opts}]
    ;; Single-resource provider: skip allocation entirely when the
    ;; caller's filters don't match.
    (let [my-url (:url meta)
          my-ver (:version meta)]
      (when (and (or (nil? url-q) (= url-q my-url))
                 (or (nil? ver-q) (= ver-q my-ver))
                 (search-filter/matches-resource-filters?
                  (select-keys meta [:status :name :title :description]) opts))
        [(cond-> {:url my-url}
           my-ver                     (assoc :version my-ver)
           (:content meta)            (assoc :content (:content meta))
           (some? (:case-sensitive meta)) (assoc :case-sensitive (:case-sensitive meta))
           (:supplements-target meta) (assoc :supplements (:supplements-target meta)))])))

  (cs-resource [_ _params]
    (cs-resource-from-meta meta))

  (cs-lookup [_ {:keys [system code displayLanguage properties]}]
    (if-let [[concept _] (if case-sensitive?
                            (when-let [c (get code-index code)] [c false])
                            (ci-lookup code-index ci-index code))]
      (let [cs-name (:name meta)
            url     (:url meta)
            version (:version meta)
            actual-code (:code concept)
            {:keys [want? want-typed?]} (property-filter/parse properties)
            parents      (when (want? "parent")
                           (get (:parents hierarchy) actual-code))
            children     (when (want? "child")
                           (get (:children hierarchy) actual-code))
            props (:properties concept)
            inactive? (concept-inactive? concept)
            abstract? (concept-abstract? property-uri-map concept)
            display-langs (display/parse-display-language* displayLanguage)
            best-display (or (when (seq display-langs)
                               (display/find-display-for-language
                                 (:designations concept) display-langs))
                             (:display concept))]
        {:name        cs-name
         :version     version
         :display     best-display
         :system      url
         :code        (keyword actual-code)
         :definition  (:definition concept)
         :abstract    abstract?
         :properties  (concat
                        (when (want? "inactive")
                          [{:code :inactive :value (boolean inactive?)}])
                        (when parents
                          (map (fn [p] {:code :parent
                                        :value (keyword p)
                                        :description (:display (get code-index p))})
                               parents))
                        (when children
                          (map (fn [c] {:code :child
                                        :value (keyword c)
                                        :description (:display (get code-index c))})
                               children))
                        (when want-typed?
                          (keep (fn [prop]
                                  (let [pc (get prop "code")
                                        v (fhir-extract/typed-property-value prop)]
                                    (when (and pc (some? v) (not (#{"parent" "child"} pc))
                                               (want? pc))
                                      {:code (keyword pc) :value v})))
                                props)))
         :designations (when (want? "designation") (:designations concept))})
      (issues/unknown-code-lookup (or system (:url meta)) code)))

  (cs-validate-code [_ {:keys [code display displayLanguage lenient-display-validation]}]
    (let [url     (:url meta)
          version (:version meta)
          [concept case-differs?] (if case-sensitive?
                                     (when-let [c (get code-index code)] [c false])
                                     (ci-lookup code-index ci-index code))]
      (if concept
        (let [inactive? (concept-inactive? concept)
              actual-code (:code concept)
              display-langs (display/parse-display-language* displayLanguage)
              lang-display (when (seq display-langs)
                             (display/find-display-for-language (:designations concept) display-langs))
              best-display (or lang-display (:display concept))
              result (cond-> {:result  true
                              :display best-display
                              :code    (keyword code)
                              :system  url
                              :version version}
                       inactive? (assoc :inactive true
                                        :inactive-status (concept-inactive-status concept))
                       case-differs? (assoc :normalized-code (keyword actual-code)))
              case-issue (when case-differs?
                           {:severity     "information"
                            :type         "business-rule"
                            :details-code "code-rule"
                            :text         (issues/format-case-mismatch
                                           code actual-code url version)
                            :expression   ["Coding.code"]})
              disp (display/validate-display
                    {:display         display
                     :primary-display (:display concept)
                     :designations    (:designations concept)
                     :display-langs   display-langs
                     :displayLanguage displayLanguage
                     :system          url
                     :code            code
                     :cs-language     (meta-language meta)
                     :lenient?        lenient-display-validation})
              issues (filter some? [case-issue (:issue disp)])]
          (cond-> result
            disp         (assoc :result (:result disp) :message (:text (:issue disp)))
            (seq issues) (assoc :issues issues)))
        (let [fragment? (= "fragment" (:content meta))
              msg (if fragment?
                    (str "Unknown Code '" code "' in the CodeSystem '" url "' version '" version
                         "' - note that the code system is labeled as a fragment, so the code may be valid in some other fragment")
                    (str "Unknown code '" code "' in the CodeSystem '" url "' version '" version "'"))]
          (cond-> {:result  fragment?
                   :code    (keyword code)
                   :system  url
                   :version version
                   :issues  [(cond-> {:severity     (if fragment? "warning" "error")
                                      :type         "code-invalid"
                                      :details-code "invalid-code"
                                      :text         msg
                                      :expression   ["Coding.code"]}
                               (and (not fragment?) version) (assoc :message-id "Unknown_Code_in_Version"))]}
            (not fragment?) (assoc :message msg))))))

  (cs-subsumes [_ {:keys [codeA codeB]}]
    (let [url (:url meta)]
      (cond
        (not (contains? code-index codeA))
        (issues/unknown-code-subsumes url codeA "codeA")
        (not (contains? code-index codeB))
        (issues/unknown-code-subsumes url codeB "codeB")
        :else
        {:outcome
         (cond
           (= codeA codeB) "equivalent"
           (ancestor? (:parents hierarchy) codeA codeB) "subsumed-by"
           (ancestor? (:parents hierarchy) codeB codeA) "subsumes"
           :else "not-subsumed")})))

  (cs-expand* [_ query]
    (let [{:keys [filters displayLanguage properties max-hits text active-only]} query
          url     (:url meta)
          version (:version meta)
          all-concepts (vals code-index)
          children-map (:children hierarchy)
          parents-map (:parents hierarchy)
          display-langs (display/parse-display-language* displayLanguage)
          want-props (when (seq properties) (set properties))
          matching (if (seq filters)
                     (clojure.core/filter
                       (fn [c]
                         (every? #(concept-matches-filter? code-index children-map parents-map c % case-sensitive?) filters))
                       all-concepts)
                     all-concepts)
          matching (if active-only
                     (clojure.core/remove concept-inactive? matching)
                     matching)
          matching (if (str/blank? text)
                     matching
                     (let [f-lower (str/lower-case text)]
                       (clojure.core/filter
                         (fn [c]
                           (or (and (:display c)
                                    (str/includes? (str/lower-case (:display c)) f-lower))
                               (some (fn [d]
                                       (when-let [v (:value d)]
                                         (str/includes? (str/lower-case v) f-lower)))
                                     (:designations c))))
                         matching)))
          total-known (clojure.core/count matching)
          matching (cond->> matching
                     max-hits (take max-hits))
          concepts (map (fn [c]
                          (let [display (or (when (seq display-langs)
                                              (display/find-display-for-language (:designations c) display-langs))
                                            (:display c))
                                selected-props (when want-props
                                                 (keep (fn [prop]
                                                         (let [pc (get prop "code")
                                                               v (fhir-extract/typed-property-value prop)]
                                                           (when (and pc (some? v) (contains? want-props pc))
                                                             {:code (keyword pc) :value v})))
                                                       (:properties c)))]
                            (cond-> {:code    (:code c)
                                     :system  url
                                     :version version
                                     :display display
                                     :designations (:designations c)}
                              (concept-inactive? c) (assoc :inactive true
                                                           :inactive-status (concept-inactive-status c))
                              (concept-abstract? property-uri-map c) (assoc :abstract true)
                              (seq selected-props) (assoc :properties selected-props))))
                        matching)]
      (cond-> {:concepts concepts}
        total-known (assoc :total total-known))))

  protos/ValueSet
  ;; The implicit VS of a CodeSystem is not a discrete published resource —
  ;; it's a virtual reference target keyed off the CS URL. The composite
  ;; routes `?url=<cs>` to this provider via the URL-keyed valuesets map,
  ;; so resolution still works without advertising in the VS catalogue.
  (vs-metadata [_ _opts] [])

  (vs-resource [_ _params]
    (cond-> {:url (:url meta)}
      (:version meta)          (assoc :version (:version meta))
      (:name meta)             (assoc :name (:name meta))
      (:title meta)            (assoc :title (:title meta))
      (:status meta)           (assoc :status (:status meta))
      (:standards-status meta) (assoc :standards-status (:standards-status meta))))

  (vs-expand [_ _ctx {:keys [filter offset count displayLanguage]}]
    (let [url     (:url meta)
          version (:version meta)
          concepts (vals code-index)
          filtered (if (str/blank? filter)
                     concepts
                     (let [f (str/lower-case filter)]
                       (clojure.core/filter
                         (fn [c]
                           (or (and (:display c) (str/includes? (str/lower-case (:display c)) f))
                               (some (fn [d]
                                       (when-let [v (:value d)]
                                         (str/includes? (str/lower-case v) f)))
                                     (:designations c))))
                         concepts)))
          display-langs (display/parse-display-language* displayLanguage)
          all-concepts (mapv (fn [c]
                               (let [display (or (when (seq display-langs)
                                                   (display/find-display-for-language (:designations c) display-langs))
                                                 (:display c))]
                                 (cond-> {:code    (:code c)
                                          :system  url
                                          :version version
                                          :display display}
                                   (seq (:designations c)) (assoc :designations (:designations c))
                                   (concept-inactive? c) (assoc :inactive true
                                                                :inactive-status (concept-inactive-status c))
                                   (concept-abstract? property-uri-map c) (assoc :abstract true))))
                             filtered)
          offset' (or offset 0)
          paged (cond->> all-concepts
                  (pos? offset') (drop offset')
                  count (take count))
          cs-uri (if version (str url "|" version) url)]
      {:concepts         (vec paged)
       :total            (clojure.core/count all-concepts)
       :used-codesystems [{:uri    cs-uri
                           :status (:status meta)}]
       :compose-pins     []}))

  (vs-validate-code [_ _svc {:keys [code system display displayLanguage lenient-display-validation]}]
    (let [url     (:url meta)
          version (:version meta)]
      (when (or (nil? system) (= system url))
        (if-let [concept (get code-index code)]
          (let [inactive? (concept-inactive? concept)
                display-langs (display/parse-display-language* displayLanguage)
                lang-display (when (seq display-langs)
                               (display/find-display-for-language (:designations concept) display-langs))
                best-display (or lang-display (:display concept))
                result (cond-> {:result  true
                                :display best-display
                                :code    (keyword code)
                                :system  url
                                :version version}
                         inactive? (assoc :inactive true
                                          :inactive-status (concept-inactive-status concept)))
                disp (display/validate-display
                      {:display         display
                       :primary-display (:display concept)
                       :designations    (:designations concept)
                       :display-langs   display-langs
                       :displayLanguage displayLanguage
                       :system          url
                       :code            code
                       :cs-language     (meta-language meta)
                       :lenient?        lenient-display-validation})]
            (cond-> result
              disp (assoc :result  (:result disp)
                          :message (:text (:issue disp))
                          :issues  [(:issue disp)])))
          (let [fragment? (= "fragment" (:content meta))
                msg (if fragment?
                      (str "Unknown Code '" code "' in the CodeSystem '" url "' version '" version
                           "' - note that the code system is labeled as a fragment, so the code may be valid in some other fragment")
                      (str "Unknown code '" code "' in the CodeSystem '" url "' version '" version "'"))]
            (cond-> {:result  fragment?
                     :code    (keyword code)
                     :system  url
                     :version version
                     :issues  [(cond-> {:severity     (if fragment? "warning" "error")
                                        :type         "code-invalid"
                                        :details-code "invalid-code"
                                        :text         msg
                                        :expression   ["Coding.code"]}
                                 (and (not fragment?) version) (assoc :message-id "Unknown_Code_in_Version"))]}
              (not fragment?) (assoc :message msg)))))))

  supplement/SupplementSource
  (supplement-lookup-table [_]
    (when (= "supplement" (:content meta))
      (supplement/concepts->lookup (vals code-index)))))

(s/def ::parents  (s/map-of string? set?))
(s/def ::children (s/map-of string? set?))
(s/def ::hierarchy (s/keys :req-un [::parents ::children]))

(s/fdef memory-codesystem
  :args (s/cat :meta             ::protos/codesystem-meta-data
               :code-index       (s/map-of string? map?)
               :hierarchy        ::hierarchy
               :property-uri-map (s/map-of string? string?)
               :case-sensitive?  boolean?
               :ci-index         (s/nilable (s/map-of string? string?))))

(defn memory-codesystem
  "Build a `MemoryCodeSystem` provider from indexed data. Used by the
  indexer; clients normally go through `from-fhir`."
  [meta code-index hierarchy property-uri-map case-sensitive? ci-index]
  (->MemoryCodeSystem meta code-index hierarchy property-uri-map case-sensitive? ci-index))

;; ---------------------------------------------------------------------------
;; MemoryValueSet — compose-driven ValueSet.
;; ---------------------------------------------------------------------------


(deftype MemoryValueSet [vs-data]
  protos/ValueSet
  (vs-metadata [_ {url-q :url ver-q :version :as opts}]
    (let [my-url (:url vs-data)
          my-ver (:version vs-data)
          m      (:metadata vs-data)]
      (when (and (or (nil? url-q) (= url-q my-url))
                 (or (nil? ver-q) (= ver-q my-ver))
                 (search-filter/matches-resource-filters?
                  {:name (get m "name") :title (get m "title")
                   :status (get m "status") :description (get m "description")}
                  opts))
        [(cond-> {:url my-url}
           my-ver (assoc :version my-ver))])))

  (vs-resource [_ _params]
    (let [{:keys [url version metadata compose]} vs-data
          experimental (get metadata "experimental")
          standards    (fhir-extract/extract-standards-status metadata)
          supplements  (fhir-extract/extract-vs-supplements metadata)]
      (cond-> {:url url}
        version             (assoc :version version)
        (get metadata "name")   (assoc :name (get metadata "name"))
        (get metadata "title")  (assoc :title (get metadata "title"))
        (get metadata "status") (assoc :status (get metadata "status"))
        (some? experimental)    (assoc :experimental (boolean experimental))
        standards               (assoc :standards-status standards)
        (seq supplements)       (assoc :supplements supplements)
        (map? compose)          (assoc :compose compose))))

  (vs-expand [_ svc params]
    (let [{:keys [url compose]} vs-data
          expanding (conj (or (:expanding params) #{}) url)]
      (or (compose/stored-extensional-expand compose params)
          (compose/expand-compose svc compose
            (assoc params :expanding expanding :purpose :expand)))))

  (vs-validate-code [_ svc params]
    (vs-validate/validate-code svc vs-data params)))

(s/fdef memory-valueset
  :args (s/cat :vs-data ::protos/valueset-data))

(defn memory-valueset
  "Build a `MemoryValueSet` provider from a `:valueset` fhir-data
  entry."
  [vs-data]
  (->MemoryValueSet vs-data))

;; ---------------------------------------------------------------------------
;; MemoryConceptMap — backs a ConceptMap with forward + reverse translate.
;; ---------------------------------------------------------------------------

(def ^:private symmetric-equivalences
  #{"equal" "equivalent" "relatedto" "inexact" "disjoint"})

(def ^:private invert-equivalence
  "Invert a ConceptMapEquivalence value for reverse translation. Symmetric
  values invert to themselves; non-symmetric pairs invert across one
  another. `unmatched` is excluded from reverse results."
  {"wider"       "narrower"
   "narrower"    "wider"
   "subsumes"    "specializes"
   "specializes" "subsumes"})

(defn- forward-index [groups]
  ;; Keyed by (source-system, source-code) → list of {:system :code :display :version :equivalence}
  (reduce
    (fn [idx g]
      (let [src (:source g) tgt (:target g) tgt-ver (:target-version g)]
        (reduce (fn [idx el]
                  (let [src-code (:code el)]
                    (reduce (fn [idx t]
                              (update idx [src src-code]
                                      (fnil conj [])
                                      {:system tgt
                                       :code (:code t)
                                       :display (:display t)
                                       :version tgt-ver
                                       :equivalence (:equivalence t)}))
                            idx
                            (:target el))))
                idx
                (:elements g))))
    {} groups))

(defn- reverse-index [groups]
  ;; Keyed by (target-system, target-code) → list of {:system :code :display :version :equivalence}
  (reduce
    (fn [idx g]
      (let [src (:source g) src-ver (:source-version g) tgt (:target g)]
        (reduce (fn [idx el]
                  (let [src-code (:code el)
                        src-display (:display el)]
                    (reduce (fn [idx t]
                              (let [eq (:equivalence t)
                                    inv (cond
                                          (= eq "unmatched") nil
                                          (contains? symmetric-equivalences eq) eq
                                          :else (get invert-equivalence eq))]
                                (if inv
                                  (update idx [tgt (:code t)]
                                          (fnil conj [])
                                          {:system src
                                           :code src-code
                                           :display src-display
                                           :version src-ver
                                           :equivalence inv})
                                  idx)))
                            idx
                            (:target el))))
                idx
                (:elements g))))
    {} groups))

(deftype MemoryConceptMap [cm-data fwd-idx rev-idx]
  protos/ConceptMap
  (cm-metadata [_ {url-q :url ver-q :version}]
    (let [{:keys [url source-uri target-uri version groups]} cm-data
          pairs (->> groups
                     (keep (fn [{:keys [source target]}]
                             (when (and source target)
                               {:system source :target target})))
                     distinct
                     vec)]
      (when (and (or (nil? url-q) (= url-q url))
                 (or (nil? ver-q) (= ver-q version)))
        [(cond-> {:url    url
                  :system (or source-uri "")
                  :target (or target-uri "")}
           version     (assoc :version version)
           (seq pairs) (assoc :pairs pairs))])))

  (cm-resource [_ _params]
    (let [{:keys [url version source-uri target-uri metadata]} cm-data]
      (cond-> {:url url}
        version                      (assoc :version version)
        (get metadata "name")        (assoc :name (get metadata "name"))
        (get metadata "title")       (assoc :title (get metadata "title"))
        (get metadata "status")      (assoc :status (get metadata "status"))
        (get metadata "description") (assoc :description (get metadata "description"))
        source-uri                   (assoc :source-uri source-uri)
        target-uri                   (assoc :target-uri target-uri))))

  (cm-translate [_ {:keys [code system target] :as _params}]
    (let [{:keys [target-uri]} cm-data
          target-system? (not= target target-uri)
          matches-for-code (fn [idx]
                             (into []
                                   (mapcat (fn [[[idx-system idx-code] matches]]
                                             (when (and (= idx-code code)
                                                        (or (nil? system)
                                                            (= system idx-system)))
                                               matches)))
                                   idx))
          fwd     (cond
                    (and system code) (get fwd-idx [system code])
                    code              (matches-for-code fwd-idx))
          fwd     (cond->> fwd
                    (and target target-system?) (filter #(= target (:system %))))
          rev     (when (and system code)
                    (cond->> (get rev-idx [system code])
                      (and target target-system?) (filter #(= target (:system %)))))
          matches (cond
                    (seq fwd) fwd
                    (seq rev) rev
                    :else nil)]
      (if (seq matches)
        {:result true
         :matches (mapv (fn [m]
                          (cond-> {:equivalence (:equivalence m)
                                   :system      (:system m)
                                   :code        (:code m)}
                            (:display m) (assoc :display (:display m))
                            (:version m) (assoc :version (:version m))))
                        matches)}
        {:result false
         :message "No matches found"}))))

(s/fdef memory-conceptmap
  :args (s/cat :cm-data ::protos/conceptmap-data))

(defn memory-conceptmap
  "Build a `MemoryConceptMap` provider from a `:conceptmap` fhir-data
  entry. Pre-computes forward and reverse translation indices."
  [cm-data]
  (->MemoryConceptMap cm-data (forward-index (:groups cm-data)) (reverse-index (:groups cm-data))))

;; ---------------------------------------------------------------------------
;; MemoryNamingSystem — resolves OID/URN aliases to canonical URLs
;; ---------------------------------------------------------------------------

(deftype MemoryNamingSystem [index]
  protos/NamingService
  ;; The index map IS the resolver: `{id → {:url :kind}}`, callable directly.
  (naming-resolver [_] index))

(defn memory-naming-system
  "Construct a `MemoryNamingSystem` from an `{identifier → {:url :kind}}`
  index map."
  [index]
  (->MemoryNamingSystem index))
