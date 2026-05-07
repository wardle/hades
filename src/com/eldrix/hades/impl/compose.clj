(ns com.eldrix.hades.impl.compose
  "Pure compose expansion engine for FHIR ValueSet compose definitions.

  Evaluates include/exclude/filter/valueSet-import to produce an expanded
  set of concepts. Compose treats `svc` as an opaque provider satisfying
  the protocols in `com.eldrix.hades.impl.protocols`; cross-CodeSystem
  callbacks go through protocol methods on `svc`, which the composite
  dispatches to the right child provider."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.impl.canonical :as canonical]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.display :as display]
            [com.eldrix.hades.impl.protocols :as protos]))

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

(defn- expand-include-concepts
  [svc system version concepts params]
  (let [active-only? (:activeOnly params)]
    (keep (fn [c]
            (let [code (get c "code")
                  provided-display (get c "display")
                  raw       (when system
                              (protos/cs-lookup svc (cond-> {:system system :code code}
                                                      version (assoc :version version))))
                  looked-up (when-not (:not-found raw) raw)]
              ;; `include.concept` lists explicit codes that are by FHIR
              ;; semantics part of the value set: emit them even when no
              ;; provider serves the system, using the provided display.
              (when (or looked-up (nil? system) provided-display)
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
  [svc include params]
  (let [system (get include "system")
        include-version (get include "version")
        raw-version (resolve-effective-version svc system include-version params)
        version (or (composite/find-matching-version svc system raw-version) raw-version)
        concepts (get include "concept")
        filters (get include "filter")
        vs-urls (get include "valueSet")
        expanding (or (:expanding params) #{})
        unknown-version-issue
        (when (and system include-version
                   (composite/find-codesystem svc system)
                   (not (composite/find-codesystem svc (canonical/versioned-uri system version))))
          (composite/unknown-version-issue svc system version (:purpose params :validate)))
        unknown-system-issue
        (when (and system (= :expand (:purpose params))
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
                       (protos/cs-find-matches svc (build-query system version filters params)))
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
            (let [cs (when sys
                       (or (when ver (composite/find-codesystem svc (canonical/versioned-uri sys ver)))
                           (composite/find-codesystem svc sys)))
                  meta (when cs (protos/cs-resource cs {}))
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
                 (-> params (dissoc :filter) (assoc :text text))
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
        merged-concepts (map second after-exclude)
        ;; `expand-include-concepts` returns explicit-concept lists
        ;; verbatim (it has no FTS to consult). Apply the filter text
        ;; here so concept-driven includes honour `$expand`'s `filter`
        ;; param. Match-driven includes pre-filter via `cs-find-matches`,
        ;; so this post-filter is a no-op for them.
        filter-text (:text params)
        filtered (if filter-text
                   (let [needle (str/lower-case filter-text)]
                     (filter (fn [c]
                               (when-let [d (:display c)]
                                 (str/includes? (str/lower-case d) needle)))
                             merged-concepts))
                   merged-concepts)
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

