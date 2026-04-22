(ns com.eldrix.hades.impl.compose
  "Pure compose expansion engine for FHIR ValueSet compose definitions.

  Evaluates include/exclude/filter/valueSet-import to produce an expanded
  set of concepts. No HAPI, no atoms, no mutable state."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.impl.display :as display]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.registry :as registry]))

(s/def ::filter (s/nilable string?))
(s/def ::activeOnly (s/nilable boolean?))
(s/def ::offset (s/nilable nat-int?))
(s/def ::count (s/nilable nat-int?))
(s/def ::expanding (s/nilable set?))
(s/def ::expand-params (s/keys :opt-un [::filter ::activeOnly ::offset ::count ::expanding]))

(defn- resolve-effective-version
  "Determine the effective version for a system in compose expansion.
   Priority: force-system-version > include version > system-version > check-system-version > nil"
  [ctx system include-version]
  (let [request (:request ctx)]
    (or (get (:force-system-version request) system)
        include-version
        (get (:system-version request) system)
        (when-let [check-pattern (get (:check-system-version request) system)]
          (registry/find-matching-version ctx system check-pattern)))))

(defn- parse-filters
  "Parse a FHIR compose include/exclude filter array into internal format."
  [filter-array]
  (mapv (fn [f]
          {:property (get f "property")
           :op       (get f "op")
           :value    (get f "value")})
        filter-array))

(defn- broken-filter-issue
  "Return a vs-invalid issue when a compose filter is missing required parts.
  The `op = exists` filter is the only one that doesn't require a value;
  every other op needs a non-empty value."
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

(defn- expand-include-concepts
  "Expand an include element that has an explicit concept list.
  Enriches each concept with display from CodeSystem lookup when available.
  Concepts that don't exist in the CodeSystem are excluded.
  Concept enumeration is compose-owned (no cs-find-matches delegation),
  so compose applies :activeOnly here directly."
  [ctx system version concepts params]
  (let [active-only? (:activeOnly params)]
    (keep (fn [c]
            (let [code (get c "code")
                  provided-display (get c "display")
                  looked-up (when system
                              (registry/codesystem-lookup ctx (cond-> {:system system :code code}
                                                                version (assoc :version version))))]
              (when (or looked-up (nil? system))
                (let [display-langs (display/parse-display-language (:displayLanguage params))
                      lang-display (when (and (seq display-langs) looked-up)
                                     (display/find-display-for-language (:designations looked-up) display-langs))
                      display (or provided-display lang-display (:display looked-up))
                      result-version (or (:version looked-up) version)
                      inactive? (when looked-up
                                  (some (fn [p] (and (= :inactive (:code p)) (:value p)))
                                        (:properties looked-up)))
                      abstract? (:abstract looked-up)
                      designations (:designations looked-up)]
                  (when-not (and active-only? inactive?)
                    (cond-> {:code    code
                             :system  system
                             :display display}
                      result-version (assoc :version result-version)
                      (seq designations) (assoc :designations designations)
                      abstract? (assoc :abstract true)
                      inactive? (assoc :inactive true)
                      (and (seq (:properties params)) looked-up)
                      (assoc :properties
                             (let [want (set (:properties params))]
                               (filterv (fn [p]
                                          (let [k (:code p)]
                                            (contains? want (if (keyword? k) (name k) (str k)))))
                                        (:properties looked-up))))))))))
          concepts)))

(defn- build-query
  "Build the provider-facing ::query map from compose-include state and
  request params. The provider honours all constraints (filters, text,
  max-hits, properties, active-only). Offset is not part of the query —
  compose applies it after dedup/exclude, since only compose has the
  correct post-merge view for multi-include expansions."
  [system version filters params]
  (cond-> {:system system}
    version                 (assoc :version version)
    (seq filters)           (assoc :filters (parse-filters filters))
    (:displayLanguage params) (assoc :displayLanguage (:displayLanguage params))
    (seq (:properties params)) (assoc :properties (:properties params))
    (:max-hits params)      (assoc :max-hits (:max-hits params))
    (:text params)          (assoc :text (:text params))
    (:activeOnly params)    (assoc :active-only (:activeOnly params))))

(defn- find-matches
  "Call cs-find-matches and return the raw ::match-result map."
  [ctx query]
  (registry/codesystem-find-matches ctx query))

(defn- expand-valueset-refs
  "Expand referenced ValueSets, checking for circular references.
  Returns {:concepts [...] :issues [...]} — issues are populated when a
  referenced VS cannot be found (a predictable state, not an exception)."
  [ctx valueset-urls expanding]
  (reduce (fn [acc vs-url]
            (when (contains? expanding vs-url)
              (throw (ex-info (str "Circular ValueSet reference: " vs-url)
                              {:type :processing :details-code "vs-invalid" :url vs-url})))
            (if-let [result (registry/valueset-expand ctx {:url vs-url :expanding expanding})]
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
  "Expand a single include element from a compose definition.
  Returns {:concepts [...] :issues [...]}."
  [ctx include params]
  (let [system (get include "system")
        include-version (get include "version")
        raw-version (resolve-effective-version ctx system include-version)
        version (or (registry/find-matching-version ctx system raw-version) raw-version)
        concepts (get include "concept")
        filters (get include "filter")
        vs-urls (get include "valueSet")
        expanding (or (:expanding params) #{})
        ;; Detect an include referencing an unknown CS version. The CS itself
        ;; must exist (otherwise this is not a version problem but an unknown
        ;; system), and the (system, version) combo must not be registered.
        ;; The :purpose param (set by the caller) selects validate vs expand
        ;; wording; defaults to :validate for compose-level callers that
        ;; produce a validate-code result.
        unknown-version-issue
        (when (and system include-version
                   (registry/codesystem ctx system)
                   (not (registry/codesystem ctx (registry/versioned-uri system version))))
          (registry/unknown-version-issue ctx system version (:purpose params :validate)))
        ;; When the include references a CodeSystem we don't know about at
        ;; all, $expand should warn rather than silently drop the branch
        ;; (FHIR $expand: include a warning issue when part of the compose
        ;; cannot be satisfied). Limited to the expand purpose so that
        ;; validate-code continues to rely on its own unknown-system path.
        unknown-system-issue
        (when (and system (= :expand (:purpose params))
                   (not (registry/codesystem ctx system))
                   (not (and version
                             (registry/codesystem ctx (registry/versioned-uri system version)))))
          {:severity     "warning"
           :type         "not-found"
           :details-code "not-found"
           :text         (str "A definition for CodeSystem '" system
                              "' could not be found, so the value set cannot be fully expanded")})
        bad-filter-issue (when (seq filters) (broken-filter-issue system filters))
        match-result (when (and system (not concepts))
                       (find-matches ctx (build-query system version filters params)))
        match-issues (:issues match-result)
        system-results (cond
                         concepts (expand-include-concepts ctx system version concepts params)
                         system   (:concepts match-result)
                         :else    nil)
        vs-ref (when (seq vs-urls)
                 (expand-valueset-refs ctx vs-urls expanding))
        vs-concepts (:concepts vs-ref)
        vs-issues (:issues vs-ref)]
    {:issues (cond-> (vec vs-issues)
               unknown-version-issue (conj unknown-version-issue)
               unknown-system-issue (conj unknown-system-issue)
               bad-filter-issue (conj bad-filter-issue)
               (seq match-issues) (into match-issues))
     :total (:total match-result)
     :concepts (if (and (some? system-results) (seq vs-concepts))
                 (let [vs-set (set (map concept-key vs-concepts))]
                   (filter (fn [c] (contains? vs-set (concept-key c))) system-results))
                 (concat system-results vs-concepts))}))

(defn- collect-used-codesystems
  "Collect used-codesystem metadata for each distinct (system, version) pair
  consulted during expansion. Concept-level pairs (with resolved versions)
  take precedence; fall back to include-level pairs only for systems whose
  include returned no concepts (e.g. a bounded expansion with count=0)."
  [ctx compose concepts]
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
            (let [cs (when sys
                       (or (when ver (registry/codesystem ctx (registry/versioned-uri sys ver)))
                           (registry/codesystem ctx sys)))
                  meta (when cs (protos/cs-resource cs {}))
                  ver' (or ver (:version meta))
                  uri (if ver' (str sys "|" ver') sys)]
              (cond-> {:uri uri}
                (:status meta) (assoc :status (:status meta))
                (some? (:experimental meta)) (assoc :experimental (:experimental meta))
                (:standards-status meta) (assoc :standards-status (:standards-status meta)))))
          pairs)))

(defn- extract-compose-pins
  "Extract compose pins — systems with explicit versions in include definitions."
  [compose]
  (into []
        (keep (fn [inc]
                (when-let [ver (get inc "version")]
                  {:system (get inc "system") :version ver})))
        (get compose "include")))

(s/fdef expand-compose
  :args (s/cat :ctx ::registry/ctx :compose map? :params ::expand-params)
  :ret ::protos/expansion-result)

(defn- extract-compose-display-language
  "Extract displayLanguage from compose extension if present."
  [compose]
  (some (fn [ext]
          (when (= "http://hl7.org/fhir/StructureDefinition/valueset-expansion-parameter"
                   (get ext "url"))
            (let [parts (get ext "extension")
                  name-val (some #(when (= "name" (get % "url")) (get % "valueCode")) parts)]
              (when (= "displayLanguage" name-val)
                (some #(when (= "value" (get % "url")) (or (get % "valueCode") (get % "valueString"))) parts)))))
        (get compose "extension")))

(defn expand-compose
  "Expand a FHIR ValueSet compose definition into an expansion result.

  Compose is a pure orchestrator: it builds a ::query from each include's
  state plus request params, asks each CodeSystem provider to satisfy
  that query in full, and then merges, dedups and excludes the results.
  Providers are responsible for filter/text/active-only/max-hits —
  compose does not post-process those. Compose does apply :offset
  locally, since only compose sees the post-dedup/exclude view."
  [ctx compose params]
  (let [compose-lang (extract-compose-display-language compose)
        params (if (and compose-lang (not (:displayLanguage params)))
                 (assoc params :displayLanguage compose-lang)
                 params)
        ;; Request-level filter is a free-text search; rename to :text
        ;; for the provider query so it's not confused with include.filter.
        params (cond-> params
                 (:filter params) (-> (assoc :text (:filter params))
                                      (dissoc :filter)))
        includes (get compose "include")
        excludes (get compose "exclude")
        inactive-allowed (get compose "inactive" true)
        ;; Inactive-not-allowed at compose level is equivalent to
        ;; activeOnly for the provider query.
        active-only? (or (:activeOnly params) (false? inactive-allowed))
        ;; Push max-hits = count + offset so compose has enough concepts
        ;; left after dedup + exclude + offset-slice.
        include-params (cond-> (assoc params :activeOnly active-only?)
                         (:count params)
                         (assoc :max-hits (+ (:count params) (or (:offset params) 0))))
        include-results (mapv #(expand-include ctx % include-params) includes)
        include-issues (vec (mapcat :issues include-results))
        included (into {} (map (fn [c] [(concept-key c) c]))
                        (mapcat :concepts include-results))
        excluded (when (seq excludes)
                   (let [exclude-results (mapv #(expand-include ctx % params) excludes)]
                     (set (map concept-key (mapcat :concepts exclude-results)))))
        after-exclude (if excluded
                        (remove (fn [[k _]] (contains? excluded k)) included)
                        included)
        merged-concepts (vec (map second after-exclude))
        used-cs (collect-used-codesystems ctx compose merged-concepts)
        sys->versions (reduce (fn [acc c]
                                (if-let [sys (:system c)]
                                  (update acc sys (fnil conj #{}) (:version c))
                                  acc))
                              {} merged-concepts)
        multi-version (into #{} (keep (fn [[sys vers]]
                                        (when (> (count vers) 1) sys)))
                            sys->versions)
        offset' (or (:offset params) 0)
        paged (cond->> merged-concepts
                (pos? offset')  (drop offset')
                (:count params) (take (:count params)))]
    (cond-> {:concepts              (vec paged)
             :used-codesystems      used-cs
             :compose-pins          (extract-compose-pins compose)
             :multi-version-systems multi-version}
      ;; :total — prefer a value the provider already computed (cheap for
      ;; in-memory CSes; Hermes will follow once ecl-count lands). For a
      ;; single-include expansion that's the authoritative total; for
      ;; multi-include the dedup across sources makes the sum unreliable,
      ;; so we only emit total when the caller didn't bound the expansion.
      true (as-> r
             (let [singleton-total (when (and (= 1 (count include-results))
                                              (empty? excludes))
                                     (:total (first include-results)))]
               (cond-> r
                 singleton-total              (assoc :total singleton-total)
                 (and (nil? singleton-total)
                      (nil? (:count params))) (assoc :total (count merged-concepts)))))
      (:displayLanguage params) (assoc :display-language (:displayLanguage params))
      (seq include-issues)      (assoc :issues include-issues))))
