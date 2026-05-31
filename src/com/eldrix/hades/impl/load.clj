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
            [com.eldrix.hades.providers.in-memory.index :as index]
            [com.eldrix.hades.providers.common.fhir-loader :as loaders]))

(declare from-fhir build-from-fhir-data)

(s/fdef from-fhir
  :args (s/cat :resource-map map?))

(defn from-fhir
  "Build a single in-memory provider from a parsed FHIR JSON resource
  map (string-keyed). Returns a `MemoryCodeSystem`, `MemoryValueSet`,
  or `MemoryConceptMap` according to `resourceType`; nil for any other
  resource."
  [resource-map]
  (let [store   {:fhir-data (vec (loaders/resource->fhir-data resource-map :tx-resource))}
        result  (index/index store nil)
        rt      (get resource-map "resourceType")
        url     (get resource-map "url")
        version (get resource-map "version")
        ;; Overlay is keyed by `url|version` (with version="" when
        ;; absent) so two registrations for the same URL at different
        ;; versions stay distinct.
        key     (str url "|" (or version ""))]
    (case rt
      "CodeSystem" (get-in result [:providers :codesystems key])
      "ValueSet"   (get-in result [:providers :valuesets   key])
      "ConceptMap" (some #(when (= url (get-in % [:description :url])) (:impl %))
                         (get-in result [:providers :conceptmaps]))
      nil)))

;; ---------------------------------------------------------------------------
;; Indexer consumer that orders providers and surfaces a load report.
;; ---------------------------------------------------------------------------


(s/fdef build-from-fhir-data
  :args (s/cat :fhir-data (s/coll-of map?)))

(defn build-from-fhir-data
  "Index a fhir-data seq into providers, ready to be passed to
  `core/open`. Distinct CodeSystem providers register under both
  `:codesystems` and `:valuesets` (the implicit ValueSet of a
  CodeSystem). A `content=\"supplement\"` CodeSystem is returned as an
  ordinary provider; `from-providers` detects and wires it. Returns:

    {:providers   [impl ...]                   ; distinct provider impls
     :skipped     [...]                        ; loader diagnostics
     :totals      {...}}

  When two source files publish the same `[resource-type url version]`,
  the later-loaded copy wins per row — concept rows for the same code
  overwrite, designations with novel `value`s accumulate. Last-wins
  is just `assoc` on the per-(url, version) provider map and on the
  per-code concept map; no special dedup phase is needed here."
  [fhir-data]
  (let [skipped (filterv #(= :skipped (:type %)) fhir-data)
        {:keys [providers]} (index/index {:fhir-data fhir-data} nil)
        cs-overlay (:codesystems providers)
        vs-overlay (:valuesets providers)
        ;; Walk fhir-data in original order and pull each impl out of
        ;; the overlay in turn — preserves registration order, which
        ;; the composite's first-registered tie-break depends on. The
        ;; overlay is keyed by `url|version`; nil/missing version
        ;; normalises to "" so unversioned providers stay reachable
        ;; even when another package later registers the same URL at
        ;; an explicit version.
        impl-for (fn [overlay {:keys [url version]}]
                   (get overlay (str url "|" (or version ""))))
        ordered-cs (distinct (keep #(when (= :codesystem-meta (:type %))
                                      (impl-for cs-overlay %))
                                   fhir-data))
        ordered-vs (distinct (keep #(when (= :valueset (:type %))
                                      (impl-for vs-overlay %))
                                   fhir-data))
        cm-impls (map :impl (:conceptmaps providers))
        naming (:naming-system providers)
        distinct-impls (distinct (concat ordered-cs ordered-vs cm-impls
                                         (when naming [naming])))]
    {:providers   distinct-impls
     :skipped     skipped
     :totals     {:codesystems (count (filter #(= :codesystem-meta (:type %)) fhir-data))
                  :valuesets   (count (filter #(= :valueset (:type %)) fhir-data))
                  :conceptmaps (count (filter #(= :conceptmap (:type %)) fhir-data))
                  :supplements (count (filter #(and (= :codesystem-meta (:type %))
                                                    (= "supplement" (:content %)))
                                              fhir-data))}}))

