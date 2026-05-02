(ns com.eldrix.hades.impl.load
  "Convenience constructors that drive the full
  loaders/fhir → index/memory → in-memory pipeline.

  Lives outside `impl/in_memory.clj` so the deftype-defining ns has no
  inbound dependencies on loaders or indexers — the load pipeline runs
  in one direction only:

      loaders/fhir → index/memory → in-memory + supplement
                                            ↑
                                    impl/load (this ns) wires
                                    them up for callers."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.impl.index.memory :as index]
            [com.eldrix.hades.impl.loaders.fhir :as loaders]))

(declare from-fhir from-fhir-resources build-from-fhir-data)

(s/fdef from-fhir
  :args (s/cat :resource-map map?))

(defn from-fhir
  "Build a single in-memory provider from a parsed FHIR JSON resource
  map (string-keyed). Returns a `MemoryCodeSystem`, `MemoryValueSet`,
  or `MemoryConceptMap` according to `resourceType`; nil for any other
  resource."
  [resource-map]
  (let [store  {:fhir-data (vec (loaders/resource->fhir-data resource-map :tx-resource))}
        result (index/index store nil)
        rt     (get resource-map "resourceType")
        url    (get resource-map "url")]
    (case rt
      "CodeSystem" (get-in result [:providers :codesystems url])
      "ValueSet"   (get-in result [:providers :valuesets   url])
      "ConceptMap" (some #(when (= url (get-in % [:description :url])) (:impl %))
                         (get-in result [:providers :conceptmaps]))
      nil)))

(s/fdef from-fhir-resources
  :args (s/cat :resources (s/coll-of map?)))

(defn from-fhir-resources
  "Build a map keyed by resource type of in-memory providers, given a
  seq of parsed FHIR JSON resources."
  [resources]
  (let [store  {:fhir-data (vec (loaders/resources->fhir-data resources :tx-resource))}
        result (index/index store nil)]
    {:codesystems (get-in result [:providers :codesystems])
     :valuesets   (get-in result [:providers :valuesets])
     :conceptmaps (get-in result [:providers :conceptmaps])
     :supplements (:supplements result)}))

;; ---------------------------------------------------------------------------
;; Indexer consumer that orders providers and surfaces a load report.
;; ---------------------------------------------------------------------------

(defn- detect-duplicates
  "Find duplicate `[resource-type url version]` tuples across the
  fhir-data. Returns a seq of `{:resource-type :url :version :source-paths}`."
  [fhir-data]
  (->> fhir-data
       (keep (fn [{:keys [type url version source-path] :as fd}]
               (case type
                 :codesystem-meta [[:CodeSystem url version] [source-path (:supplements-target fd)]]
                 :valueset        [[:ValueSet   url version] [source-path nil]]
                 :conceptmap      [[:ConceptMap url version] [source-path nil]]
                 nil)))
       (reduce (fn [acc [k v]] (update acc k (fnil conj []) v)) {})
       (keep (fn [[[rt url version] entries]]
               (when (> (count entries) 1)
                 {:resource-type rt
                  :url           url
                  :version       version
                  :source-paths  (mapv first entries)})))))

(s/fdef build-from-fhir-data
  :args (s/cat :fhir-data (s/coll-of map?)))

(defn build-from-fhir-data
  "Index a fhir-data seq into providers, ready to be passed to
  `core/open`. Distinct CodeSystem providers register under both
  `:codesystems` and `:valuesets` (the implicit ValueSet of a
  CodeSystem). Returns:

    {:providers   [impl ...]                   ; distinct provider impls
     :supplements [{:meta :lookup} ...]        ; for `core/open` opts
     :loaded      [{:resource-type :url :default?} ...]
     :skipped     [...]                        ; loader diagnostics
     :duplicates  []
     :supplements-resolved [...]
     :totals      {...}}

  Throws `ex-info` with `:reason :duplicate-resource` when two
  fhir-data entries share `[resource-type url version]`."
  [fhir-data]
  (let [data       (vec fhir-data)
        skipped    (filterv #(= :skipped (:type %)) data)
        duplicates (detect-duplicates data)]
    (when (seq duplicates)
      (throw (ex-info (str "Duplicate FHIR resources: " (count duplicates))
                      {:reason :duplicate-resource :duplicates duplicates})))
    (let [{:keys [providers supplements]} (index/index {:fhir-data data} nil)
          cs-overlay (:codesystems providers)
          vs-overlay (:valuesets providers)
          ;; Walk fhir-data in original order and pull each impl out of
          ;; the overlay in turn — preserves registration order, which
          ;; the composite's first-registered tie-break depends on.
          impl-for (fn [overlay {:keys [url version]}]
                     (or (get overlay (str url "|" version))
                         (get overlay url)))
          ordered-cs (distinct (keep #(when (= :codesystem-meta (:type %))
                                        (impl-for cs-overlay %))
                                     data))
          ordered-vs (distinct (keep #(when (= :valueset (:type %))
                                        (impl-for vs-overlay %))
                                     data))
          cm-impls (map :impl (:conceptmaps providers))
          distinct-impls (distinct (concat ordered-cs ordered-vs cm-impls))
          loaded (for [[k _] cs-overlay
                       :when (not (str/index-of k "|"))]
                   {:resource-type :CodeSystem :url k :default? true})]
      {:providers   distinct-impls
       :supplements (vec supplements)
       :loaded      loaded
       :skipped     skipped
       :duplicates  []
       :supplements-resolved
                    (mapv (fn [{:keys [meta]}]
                            {:supplement-url (:url meta)
                             :supplement-version (:version meta)
                             :base (:supplements-target meta)})
                          supplements)
       :totals     {:codesystems (count (filter #(= :codesystem-meta (:type %)) data))
                    :valuesets   (count (filter #(= :valueset (:type %)) data))
                    :conceptmaps (count (filter #(= :conceptmap (:type %)) data))
                    :supplements (count supplements)}})))
