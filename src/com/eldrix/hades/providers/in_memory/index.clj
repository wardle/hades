(ns com.eldrix.hades.providers.in-memory.index
  "Single-phase indexer that turns accumulated `fhir-data` into provider
  values plus diagnostics. The pluggable seam is the provider protocol
  (impl/in_memory); intermediate indexed data stays private here.

  Public API:
    (empty-store)            → fresh accumulator
    (add store fd)           → store with one fhir-data accumulated
    (add-all store fds)      → repeated add
    (index store opts)       → {:providers ... :diagnostics ...}

  Output shape:
    :providers
      {:codesystems    {url|version → provider, url → provider}
       :valuesets      {url|version → provider, url → provider}
       :conceptmaps    [{:impl provider :description {...}} ...]}
    :diagnostics      {:skipped [...]}

  CodeSystem-backed providers register against both :codesystems and
  :valuesets so the implicit ValueSet of a CodeSystem can answer
  `vs-validate-code` and `vs-expand`.

  Supplement composition is *not* performed by the indexer. A supplement
  CodeSystem is built like any other provider — it carries its
  `content`/`supplements` metadata and yields its augmentation table via
  `supplement/SupplementSource`. The composite detects and wires
  supplements onto their bases at registration."
  (:require [clojure.string :as str]
            [com.eldrix.hades.providers.common.fhir-extract :as fhir-extract]
            [com.eldrix.hades.providers.in-memory.provider :as in-memory]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Store accumulator
;; ---------------------------------------------------------------------------

(defn empty-store
  "Return a fresh, empty accumulator for fhir-data."
  []
  {:fhir-data []})

(defn add
  "Append one fhir-data entry to the store."
  [store fd]
  (update store :fhir-data conj fd))

(defn add-all
  "Append a seq of fhir-data entries to the store."
  [store fds]
  (update store :fhir-data into fds))

;; ---------------------------------------------------------------------------
;; CodeSystem grouping helpers
;; ---------------------------------------------------------------------------

(defn- group-key [meta]
  [(:url meta) (:version meta)])

(defn- build-code-index
  "Build a {code-string -> concept-map} index from a flat concept seq."
  [concepts]
  (reduce (fn [idx c] (assoc idx (:code c) c)) {} concepts))

(defn- ci-index-from
  "Lower-case lookup for case-insensitive code systems."
  [code-index]
  (reduce (fn [m code] (assoc m (str/lower-case code) code)) {} (keys code-index)))

(defn- build-hierarchy
  "Build bidirectional parent/child maps from flat concepts. Uses both
  structural nesting (parent-code from flatten-concepts) and explicit
  parent/child properties on the concepts themselves."
  [flat-concepts]
  (let [add-edge (fn [m from to] (update m from (fnil conj #{}) to))]
    (reduce
      (fn [{:keys [parents children]} c]
        (let [code (:code c)
              p0 (mapv (fn [p] {:parent p}) (:parents c))
              p1 (when (:parent-code c) [{:parent (:parent-code c)}])
              props (:properties c)
              p2 (keep (fn [prop]
                         (when (= "parent" (get prop "code"))
                           {:parent (fhir-extract/extract-property-value prop)}))
                       props)
              c2 (keep (fn [prop]
                         (when (= "child" (get prop "code"))
                           {:child (fhir-extract/extract-property-value prop)}))
                       props)
              all-parents  (->> (concat p0 p1 p2)
                                (map :parent)
                                (remove #(or (nil? %) (= % code))))
              all-children (map :child c2)]
          {:parents  (reduce (fn [m p] (add-edge m code p)) parents all-parents)
           :children (reduce (fn [m ch] (-> (add-edge m code ch)
                                            (add-edge ch code)))
                             (reduce (fn [m p] (add-edge m p code)) children all-parents)
                             all-children)}))
      {:parents {} :children {}}
      flat-concepts)))

(defn- meta->cs-map
  "Reconstruct a partial FHIR CodeSystem map (string-keyed) for the
  property-uri-map helper. Only the property-defs are needed."
  [meta]
  {"property" (:property-defs meta)})

;; ---------------------------------------------------------------------------
;; Provider construction
;; ---------------------------------------------------------------------------

(defn- build-codesystem-provider
  "Construct an in-memory CodeSystem/ValueSet provider for one
  (url, version) group and the concepts that belong to it."
  [meta concepts]
  (let [code-index   (build-code-index concepts)
        hierarchy    (build-hierarchy concepts)
        prop-uri-map (fhir-extract/build-property-uri-map (meta->cs-map meta))
        cs?          (boolean (:case-sensitive meta))
        ci-idx       (when-not cs? (ci-index-from code-index))]
    (in-memory/memory-codesystem
      meta code-index hierarchy prop-uri-map cs? ci-idx)))

(defn- build-valueset-provider
  [vs-data]
  (in-memory/memory-valueset vs-data))

(defn- build-conceptmap-provider
  [cm-data]
  (in-memory/memory-conceptmap cm-data))

;; ---------------------------------------------------------------------------
;; Indexing
;; ---------------------------------------------------------------------------

(defn- versioned-key
  "Compose the registry key for `(url, version)`. nil/missing version
  becomes `\"\"` so an unversioned resource gets a distinct key
  (`\"url|\"`) — important when another package later registers the
  same URL at an explicit version."
  [url version]
  (str url "|" (or version "")))

(defn- assoc-versioned
  "Register `provider` against `url|version`. The bare-URL form is
  resolved in the composite layer (`bare-url-binding`) — registering
  an additional bare-URL alias here would let a later versioned
  registration overwrite an earlier unversioned one for the same URL,
  hiding it from `cs-metadata` enumeration."
  [overlay url version provider]
  (assoc overlay (versioned-key url version) provider))

(defn- pick-cs-meta
  "Of the `:codesystem-meta` rows that share a `(url, version)`, return
  the single meta to register. A `content: \"not-present\"` row is a
  stub — a package may ship one purely so the URL is registerable for
  downstream VS expansion / validation, intending the actual concepts
  to come from a real terminology service. A stub must never override
  a non-stub row, regardless of load order. Among same-rank rows,
  last-wins."
  [metas]
  (let [non-stub (filterv #(not= "not-present" (:content %)) metas)]
    (or (last non-stub) (last metas))))

(defn- index-codesystems
  "Group concepts by their parent CS meta entry. Build a provider for
  every CodeSystem (regular AND supplement) and register them all in
  the overlay under their own URLs. Supplement providers carry their
  `content`/`supplements` metadata; the composite detects and wires
  them via `supplement/SupplementSource` at registration."
  [fhir-data]
  (let [metas    (filterv #(= :codesystem-meta (:type %)) fhir-data)
        designations-by-concept
        (reduce (fn [m d]
                  (update m [(:system d) (:version d) (:code d)]
                          (fnil conj [])
                          (select-keys d [:language :use :value :extension])))
                {}
                (filter #(= :concept-designation (:type %)) fhir-data))
        concepts (mapv (fn [c]
                         (if-let [ds (seq (get designations-by-concept
                                                [(:system c) (:version c) (:code c)]))]
                           (update c :designations into ds)
                           c))
                       (filter #(= :concept (:type %)) fhir-data))
        by-parent (group-by (fn [c] [(:system c) (:version c)]) concepts)
        ;; Collapse duplicate `(url, version)` metas via content-precedence
        ;; before building providers — concept rows already merge under the
        ;; shared parent key.
        collapsed-metas (->> metas
                             (group-by group-key)
                             vals
                             (mapv pick-cs-meta))
        cs-overlay (reduce
                     (fn [acc meta]
                       (let [k (group-key meta)
                             provider (build-codesystem-provider meta (get by-parent k []))]
                         (assoc-versioned acc (:url meta) (:version meta) provider)))
                     {} collapsed-metas)]
    {:cs-overlay cs-overlay}))

(defn- index-valuesets
  [fhir-data]
  (let [vses (filterv #(= :valueset (:type %)) fhir-data)]
    (reduce (fn [acc vs]
              (let [provider (build-valueset-provider vs)]
                (assoc-versioned acc (:url vs) (:version vs) provider)))
            {} vses)))

(defn- index-conceptmaps
  "Build providers for every distinct (url, version) ConceptMap. When
  two source files publish the same canonical (url, version), the
  later-loaded copy wins — `into {}` is right-fold so a duplicate key
  overwrites. Mirrors the SQLite indexer's `INSERT OR REPLACE` on
  `conceptmap`."
  [fhir-data]
  (->> fhir-data
       (filter #(= :conceptmap (:type %)))
       (reduce (fn [m cm] (assoc m [(:url cm) (:version cm)] cm)) {})
       vals
       (mapv (fn [cm]
               {:impl (build-conceptmap-provider cm)
                :description (cond-> {:url (:url cm)}
                               (:source-uri cm) (assoc :system (:source-uri cm))
                               (:target-uri cm) (assoc :target (:target-uri cm))
                               (:version cm)    (assoc :version (:version cm)))}))))

(defn index
  "Build providers and diagnostics from the accumulated fhir-data.

  Returns:
    {:providers   {:codesystems {...} :valuesets {...} :conceptmaps [...]}
     :diagnostics {:skipped [...]}}

  CodeSystem-backed providers are registered under both :codesystems and
  :valuesets so a CodeSystem can answer the implicit-ValueSet form of
  `vs-validate-code` / `vs-expand`. ValueSet-only providers are merged
  on top, so a ValueSet declared at the same URL as a CodeSystem wins.

  `opts` is reserved for future use (strict mode etc.) and currently
  ignored."
  ([store] (index store nil))
  ([{:keys [fhir-data]} _opts]
   (let [{:keys [cs-overlay]} (index-codesystems fhir-data)
         vs-overlay (index-valuesets fhir-data)
         cm-providers (index-conceptmaps fhir-data)]
     {:providers {:codesystems cs-overlay
                  :valuesets   (merge cs-overlay vs-overlay)
                  :conceptmaps cm-providers}
      :diagnostics {:skipped [] :duplicates []}})))
