(ns com.eldrix.hades.compose
  "Pure compose expansion engine for FHIR ValueSet compose definitions.

  Evaluates include/exclude/filter/valueSet-import to produce an expanded
  set of concepts. No HAPI, no atoms, no mutable state."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry]))

(s/def ::filter (s/nilable string?))
(s/def ::activeOnly (s/nilable boolean?))
(s/def ::offset (s/nilable nat-int?))
(s/def ::count (s/nilable nat-int?))
(s/def ::expanding (s/nilable set?))
(s/def ::expand-params (s/keys :opt-un [::filter ::activeOnly ::offset ::count ::expanding]))

(defn- parse-display-language
  "Parse displayLanguage (Accept-Language format) into preferred language codes."
  [s]
  (when s
    (let [parts (str/split s #",")]
      (->> parts
           (keep (fn [p]
                   (let [trimmed (str/trim p)
                         [lang q] (str/split trimmed #";")
                         lang (str/trim lang)]
                     (when (and (seq lang) (not= lang "*"))
                       {:lang lang
                        :q (if q
                             (let [m (re-find #"q\s*=\s*([0-9.]+)" q)]
                               (if m (Double/parseDouble (second m)) 1.0))
                             1.0)}))))
           (sort-by :q #(compare %2 %1))
           (map :lang)))))

(defn- language-matches? [d-lang r-lang]
  (when (and d-lang r-lang)
    (let [d (str/lower-case (if (keyword? d-lang) (name d-lang) (str d-lang)))
          r (str/lower-case r-lang)]
      (or (= d r) (str/starts-with? d (str r "-"))))))

(defn- find-display-for-language [designations display-langs]
  (some (fn [lang]
          (some (fn [d] (when (language-matches? (:language d) lang) (:value d)))
                designations))
        display-langs))

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
  Concepts that don't exist in the CodeSystem are excluded."
  [ctx system version concepts params]
  (keep (fn [c]
          (let [code (get c "code")
                provided-display (get c "display")
                looked-up (when system
                            (registry/codesystem-lookup ctx (cond-> {:system system :code code}
                                                              version (assoc :version version))))]
            (when (or looked-up (nil? system))
              (let [display-langs (parse-display-language (:displayLanguage params))
                    lang-display (when (and (seq display-langs) looked-up)
                                   (find-display-for-language (:designations looked-up) display-langs))
                    display (or provided-display lang-display (:display looked-up))
                    result-version (or (:version looked-up) version)
                    inactive? (when looked-up
                                (some (fn [p] (and (= :inactive (:code p)) (:value p)))
                                      (:properties looked-up)))
                    abstract? (:abstract looked-up)
                    designations (:designations looked-up)]
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
                                    (:properties looked-up)))))))))
        concepts))

(defn- expand-include-filters
  "Expand an include element that has filters, delegating to cs-find-matches."
  [ctx system version filters params]
  (registry/codesystem-find-matches ctx {:system system :version version
                                          :filters (parse-filters filters)
                                          :displayLanguage (:displayLanguage params)
                                          :properties (:properties params)}))

(defn- expand-include-all
  "Expand an include element with just a system (no concept list, no filters).
  Returns all concepts from the CodeSystem."
  [ctx system version params]
  (registry/codesystem-find-matches ctx {:system system :version version :filters nil
                                          :displayLanguage (:displayLanguage params)
                                          :properties (:properties params)}))

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
        bad-filter-issue (when (seq filters) (broken-filter-issue system filters))
        system-results (cond
                         concepts (expand-include-concepts ctx system version concepts params)
                         filters (expand-include-filters ctx system version filters params)
                         system (expand-include-all ctx system version params)
                         :else nil)
        vs-ref (when (seq vs-urls)
                 (expand-valueset-refs ctx vs-urls expanding))
        vs-concepts (:concepts vs-ref)
        vs-issues (:issues vs-ref)]
    {:issues (cond-> (vec vs-issues)
               unknown-version-issue (conj unknown-version-issue)
               bad-filter-issue (conj bad-filter-issue))
     :concepts (if (and (some? system-results) (seq vs-concepts))
                 (let [vs-set (set (map concept-key vs-concepts))]
                   (filter (fn [c] (contains? vs-set (concept-key c))) system-results))
                 (concat system-results vs-concepts))}))

(defn- collect-used-codesystems
  "Collect used-codesystem metadata for each distinct (system, version) pair
  in the concepts. When a concept has no :version, the CodeSystem's own
  version is used (if any)."
  [ctx concepts]
  (let [pairs (into #{} (keep (fn [c] (when-let [sys (:system c)] [sys (:version c)]))) concepts)]
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

  Returns an ::expansion-result map with :concepts, :total, :used-codesystems,
  and :compose-pins. Parameters:
  - ctx     — overlay context (::registry/ctx)
  - compose — parsed compose map (string keys: \"include\", \"exclude\", \"inactive\")
  - params  — {:filter :activeOnly :offset :count :expanding}"
  [ctx compose params]
  (let [;; Merge compose-level displayLanguage if not overridden by request
        compose-lang (extract-compose-display-language compose)
        params (if (and compose-lang (not (:displayLanguage params)))
                 (assoc params :displayLanguage compose-lang)
                 params)
        includes (get compose "include")
        excludes (get compose "exclude")
        inactive-allowed (get compose "inactive" true)
        include-results (mapv #(expand-include ctx % params) includes)
        include-issues (vec (mapcat :issues include-results))
        included (into {} (map (fn [c] [(concept-key c) c]))
                        (mapcat :concepts include-results))
        excluded (when (seq excludes)
                   (let [exclude-results (mapv #(expand-include ctx % params) excludes)]
                     (set (map concept-key (mapcat :concepts exclude-results)))))
        after-exclude (if excluded
                        (remove (fn [[k _]] (contains? excluded k)) included)
                        included)
        concepts (map second after-exclude)
        after-inactive (if (or (false? inactive-allowed) (:activeOnly params))
                         (remove :inactive concepts)
                         concepts)
        after-filter (if-let [f (:filter params)]
                       (let [f-lower (str/lower-case f)]
                         (filter (fn [c]
                                   (or (and (:display c)
                                            (str/includes? (str/lower-case (:display c)) f-lower))
                                       (and (:code c)
                                            (str/includes? (str/lower-case (:code c)) f-lower))))
                                 after-inactive))
                       after-inactive)
        all-concepts (vec after-filter)
        used-cs (collect-used-codesystems ctx all-concepts)
        ;; Systems that appear at multiple distinct versions in the
        ;; expansion (overload). Rendering uses this to decide whether to
        ;; emit :version on each concept.
        sys->versions (reduce (fn [acc c]
                                (if-let [sys (:system c)]
                                  (update acc sys (fnil conj #{}) (:version c))
                                  acc))
                              {} all-concepts)
        multi-version (into #{} (keep (fn [[sys vers]]
                                        (when (> (count vers) 1) sys)))
                            sys->versions)
        offset' (or (:offset params) 0)
        paged (cond->> all-concepts
                (pos? offset') (drop offset')
                (:count params) (take (:count params)))]
    (cond-> {:concepts              (vec paged)
             :total                 (count all-concepts)
             :used-codesystems      used-cs
             :compose-pins          (extract-compose-pins compose)
             :multi-version-systems multi-version}
      (:displayLanguage params) (assoc :display-language (:displayLanguage params))
      (seq include-issues) (assoc :issues include-issues))))
