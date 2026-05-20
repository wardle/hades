(ns com.eldrix.hades.providers.common.compose
  "Pure compose expansion engine for FHIR ValueSet compose definitions.

  Evaluates include/exclude/filter/valueSet-import to produce an expanded
  set of concepts. Compose treats `svc` as an opaque provider satisfying
  the protocols in `com.eldrix.hades.protocols`; cross-CodeSystem
  callbacks go through protocol methods on `svc`, which the composite
  dispatches to the right child provider."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.providers.common.canonical :as canonical]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.providers.common.display :as display]
            [com.eldrix.hades.protocols :as protos]))

(set! *warn-on-reflection* true)

(s/def ::filter (s/nilable string?))
(s/def ::activeOnly (s/nilable boolean?))
(s/def ::offset (s/nilable nat-int?))
(s/def ::count (s/nilable nat-int?))
(s/def ::expanding (s/nilable set?))
(s/def ::expand-params (s/keys :opt-un [::filter ::activeOnly ::offset ::count ::expanding]))

(defn- resolve-effective-version
  "Determine the effective version for a system in compose expansion.
   Priority: force-system-version > include version > system-version >
   check-system-version > nil. Request flags read flat from `params`."
  [svc system include-version params]
  (or (get (:force-system-version params) system)
      include-version
      (get (:system-version params) system)
      (when-let [check-pattern (get (:check-system-version params) system)]
        (composite/find-matching-version svc system check-pattern))))

(defn- parse-filters [filter-array]
  (mapv (fn [f]
          {:property (get f "property")
           :op       (get f "op")
           :value    (get f "value")})
        filter-array))

(defn- broken-filter-issue
  [system filter-array]
  (some (fn [f]
          (let [property (get f "property")
                op       (get f "op")
                value    (get f "value")]
            (when (and op
                       (not= "exists" op)
                       (or (nil? value) (and (string? value) (str/blank? value))))
              {:severity     "error"
               :type         "invalid"
               :details-code "vs-invalid"
               :text         (str "The system " system " filter with property = "
                                  (or property "?") ", op = " op " has no value")})))
        filter-array))

(defn- safe-lowercase-includes?
  "Nil-safe case-insensitive substring containment: true when `substring`
  appears in `s` after both are lower-cased. False when either is
  nil — call sites can thread potentially-missing strings through
  without their own nil guards."
  [s substr]
  (and s substr
       (str/includes? (str/lower-case s) (str/lower-case substr))))

(defn- designations-match?
  "True when any designation in `designations` has a `:value` that
  matches search string `s` (via `safe-lowercase-includes?`)."
  [designations s]
  (some (fn [{:keys [value]}] (safe-lowercase-includes? value s)) designations))

(defn- concept-designations
  [concept]
  (or (:designations concept)
      (get concept "designation")))

(defn- normalise-designation
  [designation]
  (if (contains? designation :value)
    designation
    (let [use-map (get designation "use")]
      (cond-> {:value (get designation "value")}
        (get designation "language")
        (assoc :language (keyword (get designation "language")))
        use-map
        (assoc :use (cond-> {:system (get use-map "system")
                             :code   (get use-map "code")}
                      (get use-map "display")
                      (assoc :display (get use-map "display"))))))))

(defn- concept-code
  [concept]
  (or (:code concept) (get concept "code")))

(defn- concept-display
  [concept]
  (or (:display concept) (get concept "display")))

(defn- stored-extensional-compose?
  "True when `compose` is exactly stored membership: explicit
  include.concept entries only, no filters, imported ValueSets, or
  excludes. This is the safe shape where expansion can page/filter the
  stored membership before doing any provider work."
  [compose]
  (let [includes (get compose "include")]
    (and (seq includes)
         (empty? (get compose "exclude"))
         (every? (fn [include]
                   (and (seq (get include "concept"))
                        (empty? (get include "filter"))
                        (empty? (get include "valueSet"))))
                 includes))))

(defn- stored-extensional-answerable?
  [compose {:keys [activeOnly properties]}]
  (and (stored-extensional-compose? compose)
       (not activeOnly)
       (empty? properties)
       (every? (fn [include]
                 (every? concept-display (get include "concept")))
               (get compose "include"))))

(defn- stored-concept-matches-filter?
  [concept text]
  (or (str/blank? text)
      (safe-lowercase-includes? (concept-code concept) text)
      (safe-lowercase-includes? (concept-display concept) text)))

(defn- stored-concept-row
  [display-langs include concept]
  (let [designations (concept-designations concept)
        designations (mapv normalise-designation designations)
        display (or (when (seq display-langs)
                      (display/find-display-for-language designations display-langs))
                    (concept-display concept))
        version (or (get concept "version") (get include "version"))
        system  (or (get concept "system") (get include "system"))]
    (cond-> {:code    (concept-code concept)
             :system  system
             :display display}
      version            (assoc :version version)
      (seq designations) (assoc :designations designations))))

(defn- stored-extensional-pairs
  [compose]
  (mapcat (fn [include]
            (map (fn [concept] [include concept])
                 (get include "concept")))
          (get compose "include")))

(defn- stored-used-codesystems
  [compose]
  (into []
        (comp
         (keep (fn [include]
                 (when-let [system (get include "system")]
                   (let [version (get include "version")]
                     {:uri (if version (str system "|" version) system)}))))
         (distinct))
        (get compose "include")))

(defn- stored-compose-pins
  [compose]
  (into []
        (keep (fn [include]
                (when-let [version (get include "version")]
                  (when-let [system (get include "system")]
                    {:system system :version version}))))
        (get compose "include")))

(s/fdef stored-extensional-expand
  :args (s/cat :compose map? :params ::expand-params))

(defn stored-extensional-expand
  "Expand a stored explicit-concept ValueSet directly from membership
  data when the request can be answered without live CodeSystem
  enrichment. Returns nil for complex/intensional cases so callers can
  fall back to `expand-compose`."
  [compose {:keys [offset count filter displayLanguage] :as params}]
  (when (stored-extensional-answerable? compose params)
    (let [display-langs (display/parse-display-language displayLanguage)
          offset' (or offset 0)
          pairs (stored-extensional-pairs compose)
          filtered-pairs (if (str/blank? filter)
                           pairs
                           (clojure.core/filter
                            (fn [[_ concept]]
                              (stored-concept-matches-filter? concept filter))
                            pairs))
          total (clojure.core/count filtered-pairs)
          concepts (cond->> filtered-pairs
                     (pos? offset') (drop offset')
                     count          (take count)
                     true           (map (fn [[include concept]]
                                           (stored-concept-row display-langs include concept))))]
      (cond-> {:concepts              (vec concepts)
               :total                 total
               :used-codesystems      (stored-used-codesystems compose)
               :compose-pins          (stored-compose-pins compose)
               :multi-version-systems #{}}
        (seq display-langs) (assoc :display-language displayLanguage)))))

(defn- expand-include-concepts
  "Expand a concept-driven include (`include.concept[]`) into the
  expansion. Each candidate is looked up via `cs-lookup` to enrich
  display / designations / inactive / abstract, then gated by
  `:activeOnly` and (when present) the `$expand` `:text` filter. The
  filter matches against the looked-up display AND every designation
  value — the same surface a match-driven `cs-expand*` would consider —
  so concept-driven includes honour `filter` without compose having to
  post-filter the merged expansion."
  [svc system version concepts {:keys [activeOnly displayLanguage text properties]}]
  (keep (fn [{:strs [code] provided-display "display"}]
          (let [raw       (when system
                            (protos/cs-lookup svc (cond-> {:system system :code code}
                                                    version (assoc :version version))))
                looked-up (when-not (:not-found raw) raw)]
            ;; `include.concept` lists explicit codes that are by FHIR
            ;; semantics part of the value set: emit them even when no
            ;; provider serves the system, using the provided display.
            (when (or looked-up (nil? system) provided-display)
              (let [display-langs (display/parse-display-language displayLanguage)
                    lang-display (when (and (seq display-langs) looked-up)
                                   (display/find-display-for-language (:designations looked-up) display-langs))
                    display (or provided-display lang-display (:display looked-up))
                    result-version (or (:version looked-up) version)
                    inactive? (when looked-up
                                (some (fn [{:keys [code value]}]
                                        (and (= :inactive code) value))
                                      (:properties looked-up)))
                    designations (:designations looked-up)]
                (when (and (not (and activeOnly inactive?))
                           (or (nil? text)
                               (safe-lowercase-includes? display text)
                               (designations-match? designations text)))
                  (cond-> {:code    code
                           :system  system
                           :display display}
                    result-version        (assoc :version result-version)
                    (seq designations)    (assoc :designations designations)
                    (:abstract looked-up) (assoc :abstract true)
                    inactive?             (assoc :inactive true)
                    (and (seq properties) looked-up)
                    (assoc :properties
                           (let [want (set properties)]
                             (filterv (fn [{k :code}]
                                        (contains? want (if (keyword? k) (name k) (str k))))
                                      (:properties looked-up))))))))))
        concepts))

(defn- build-query
  [system version filters params]
  (cond-> {:system system}
    version                    (assoc :version version)
    (seq filters)              (assoc :filters (parse-filters filters))
    (:displayLanguage params)  (assoc :displayLanguage (:displayLanguage params))
    (seq (:properties params)) (assoc :properties (:properties params))
    (:max-hits params)         (assoc :max-hits (:max-hits params))
    (:text params)             (assoc :text (:text params))
    (:activeOnly params)       (assoc :active-only (:activeOnly params))))

(defn- expand-valueset-refs
  [svc valueset-urls expanding params]
  (reduce (fn [acc vs-url]
            (when (contains? expanding vs-url)
              (throw (ex-info (str "Circular ValueSet reference: " vs-url)
                              {:type :processing :details-code "vs-invalid" :url vs-url})))
            (if-let [result (protos/vs-expand svc svc
                                              (-> params
                                                  (assoc :url vs-url :expanding expanding)
                                                  (dissoc :valueSetVersion)))]
              (update acc :concepts into (or (:concepts result) []))
              (let [msg (str "A definition for the value Set '" vs-url "' could not be found")]
                (update acc :issues conj
                        {:severity "error" :type "not-found"
                         :details-code "not-found" :text msg}))))
          {:concepts [] :issues []}
          valueset-urls))

(defn- concept-key [c]
  [(:system c) (:version c) (:code c)])

(defn- expand-include
  "Expand one element of `compose.include[]` into a sub-result
  `{:concepts :issues :total :fully-materialised?}`. The element may
  specify a CodeSystem (`include.system` — match-driven when no
  explicit `concept[]` is given; concept-driven when it is) and / or a
  list of imported ValueSets (`include.valueSet[]`). When both system
  and valueSet are present the results intersect; otherwise they
  concatenate. Surfaces issues for unknown system, version-pin
  mismatch, broken filter clauses, and propagates provider-emitted
  issues from `cs-expand*` verbatim. `expanding` tracks in-flight VS
  URLs so cycles raise rather than recurse."
  [svc include {:keys [expanding purpose]
                :or   {expanding #{} purpose :validate}
                :as   params}]
  (let [system (get include "system")
        include-version (get include "version")
        raw-version (resolve-effective-version svc system include-version params)
        version (or (composite/find-matching-version svc system raw-version) raw-version)
        concepts (get include "concept")
        filters (get include "filter")
        vs-urls (get include "valueSet")
        unknown-version-issue
        (when (and system include-version
                   (composite/find-codesystem svc system)
                   (not (composite/find-codesystem svc (canonical/versioned-uri system version))))
          (composite/unknown-version-issue svc system version purpose))
        unknown-system-issue
        (when (and system (= :expand purpose)
                   (not (composite/find-codesystem svc system))
                   (not (and version
                             (composite/find-codesystem svc (canonical/versioned-uri system version)))))
          {:severity     "warning"
           :type         "not-found"
           :details-code "not-found"
           :text         (str "A definition for CodeSystem '" system
                              "' could not be found, so the value set cannot be fully expanded")})
        bad-filter-issue (when (seq filters) (broken-filter-issue system filters))
        match-result (when (and system (not concepts))
                       (protos/cs-expand* svc (build-query system version filters params)))
        match-issues (:issues match-result)
        system-results (cond
                         concepts (expand-include-concepts svc system version concepts params)
                         system   (:concepts match-result)
                         :else    nil)
        vs-ref (when (seq vs-urls)
                 (expand-valueset-refs svc vs-urls expanding params))
        vs-concepts (:concepts vs-ref)
        vs-issues (:issues vs-ref)]
    {:issues (cond-> []
               (seq vs-issues)       (into vs-issues)
               unknown-version-issue (conj unknown-version-issue)
               unknown-system-issue  (conj unknown-system-issue)
               bad-filter-issue      (conj bad-filter-issue)
               (seq match-issues)    (into match-issues))
     :total (:total match-result)
     ;; True when this include's concepts are fully materialised — no
     ;; truncation by `:count` is possible. Concept-driven includes
     ;; ignore `max-hits`; vs-refs recurse with the same params and may
     ;; paginate, so we conservatively flag those as unsafe.
     :fully-materialised? (and (some? concepts) (empty? vs-urls))
     :concepts (if (and (some? system-results) (seq vs-concepts))
                 (let [vs-set (set (map concept-key vs-concepts))]
                   (filter (fn [c] (contains? vs-set (concept-key c))) system-results))
                 (concat system-results vs-concepts))}))

(defn- collect-used-codesystems
  [svc compose concepts]
  (let [concept-pairs (into #{} (keep (fn [c] (when-let [sys (:system c)] [sys (:version c)]))) concepts)
        concept-systems (into #{} (map first) concept-pairs)
        include-pairs (into #{}
                            (comp
                             (keep (fn [inc]
                                     (when-let [sys (get inc "system")]
                                       (when-not (contains? concept-systems sys)
                                         [sys (get inc "version")])))))
                            (get compose "include"))
        pairs (into concept-pairs include-pairs)]
    (mapv (fn [[sys ver]]
            (let [meta (when sys
                         (or (when ver (composite/cs-meta svc (canonical/versioned-uri sys ver)))
                             (composite/cs-meta svc sys)))
                  ver' (or ver (:version meta))
                  uri (if ver' (str sys "|" ver') sys)]
              (cond-> {:uri uri}
                (:status meta) (assoc :status (:status meta))
                (some? (:experimental meta)) (assoc :experimental (:experimental meta))
                (:standards-status meta) (assoc :standards-status (:standards-status meta)))))
          pairs)))

(defn- extract-compose-pins
  [compose]
  (into []
        (keep (fn [inc]
                (when-let [ver (get inc "version")]
                  {:system (get inc "system") :version ver})))
        (get compose "include")))

(defn- extract-compose-display-language
  [compose]
  (some (fn [ext]
          (when (= "http://hl7.org/fhir/StructureDefinition/valueset-expansion-parameter"
                   (get ext "url"))
            (let [parts (get ext "extension")
                  name-val (some #(when (= "name" (get % "url")) (get % "valueCode")) parts)]
              (when (= "displayLanguage" name-val)
                (some #(when (= "value" (get % "url")) (or (get % "valueCode") (get % "valueString"))) parts)))))
        (get compose "extension")))

(s/fdef expand-compose
  :args (s/cat :svc some? :compose map? :params ::expand-params))

(defn expand-compose
  "Expand a FHIR ValueSet compose definition into an expansion result.
   `svc` is a TerminologyService satisfying the protocols. Compose calls
   protocol methods on `svc` for cross-CodeSystem lookups; the composite
   dispatches to the right leaf provider."
  [svc compose params]
  (let [compose-lang (extract-compose-display-language compose)
        params (if (and compose-lang (not (:displayLanguage params)))
                 (assoc params :displayLanguage compose-lang)
                 params)
        params (if-let [text (:filter params)]
                 (assoc params :text text)
                 params)
        includes (get compose "include")
        excludes (get compose "exclude")
        inactive-allowed (get compose "inactive" true)
        active-only? (or (:activeOnly params) (false? inactive-allowed))
        include-params (cond-> (assoc params :activeOnly active-only?)
                         (:count params)
                         (assoc :max-hits (+ (:count params) (or (:offset params) 0))))
        include-results (mapv #(expand-include svc % include-params) includes)
        include-issues (mapcat :issues include-results)
        included (into {} (map (fn [c] [(concept-key c) c]))
                       (mapcat :concepts include-results))
        excluded (when (seq excludes)
                   (let [exclude-results (mapv #(expand-include svc % params) excludes)]
                     (set (map concept-key (mapcat :concepts exclude-results)))))
        after-exclude (if excluded
                        (remove (fn [[k _]] (contains? excluded k)) included)
                        included)
        ;; Text filtering is the provider's responsibility for
        ;; match-driven includes (`cs-expand*` honours `:text`,
        ;; matching display + designations via the per-terminology
        ;; index) and `expand-include-concepts`' responsibility for
        ;; concept-driven includes (it gates emission on display +
        ;; designations). Compose neither re-filters nor materialises.
        filtered (map second after-exclude)
        used-cs (collect-used-codesystems svc compose filtered)
        sys->versions (reduce (fn [acc c]
                                (if-let [sys (:system c)]
                                  (update acc sys (fnil conj #{}) (:version c))
                                  acc))
                              {} filtered)
        multi-version (into #{} (keep (fn [[sys vers]]
                                        (when (> (count vers) 1) sys)))
                            sys->versions)
        offset' (or (:offset params) 0)
        paged (cond->> filtered
                (pos? offset')  (drop offset')
                (:count params) (take (:count params)))
        singleton-total (when (and (= 1 (count include-results)) (empty? excludes))
                          (:total (first include-results)))
        ;; Counting `filtered` is only safe when no include can have
        ;; been silently truncated by `:count`. That holds for include
        ;; results explicitly flagged fully-materialised (concept-driven
        ;; with no vs-refs) and when no paging is in effect.
        all-materialised? (every? :fully-materialised? include-results)
        total (cond
                singleton-total                        singleton-total
                (or all-materialised?
                    (nil? (:count params)))            (count filtered)
                :else                                  nil)]
    (cond-> {:concepts              (vec paged)
             :used-codesystems      used-cs
             :compose-pins          (extract-compose-pins compose)
             :multi-version-systems multi-version}
      total                     (assoc :total total)
      (:displayLanguage params) (assoc :display-language (:displayLanguage params))
      (seq include-issues)      (assoc :issues include-issues))))
