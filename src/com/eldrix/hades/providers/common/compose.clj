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
            [com.eldrix.hades.protocols :as protos])
  (:import (java.text Normalizer Normalizer$Form)))

(set! *warn-on-reflection* true)

(s/def ::filter (s/nilable string?))
(s/def ::activeOnly (s/nilable boolean?))
(s/def ::offset (s/nilable nat-int?))
(s/def ::count (s/nilable nat-int?))
(s/def ::expanding (s/nilable set?))
(s/def ::displayLanguage (s/nilable string?))
(s/def ::properties (s/nilable coll?))
(s/def ::max-hits (s/nilable nat-int?))
(s/def ::purpose #{:validate :expand})
;; Per-system version overrides ({system -> version-or-pattern}), read flat
;; by `resolve-effective-version`.
(s/def ::force-system-version (s/nilable (s/map-of string? string?)))
(s/def ::system-version (s/nilable (s/map-of string? string?)))
(s/def ::check-system-version (s/nilable (s/map-of string? string?)))

;; The input contract for `expand-compose`: every key the engine reads.
(s/def ::expand-params
  (s/keys :opt-un [::filter ::activeOnly ::offset ::count ::expanding
                   ::displayLanguage ::properties ::max-hits ::purpose
                   ::force-system-version ::system-version ::check-system-version]))

(defn- distinct-by
  "Lazily drop elements whose `(f x)` key has already been seen, keeping
  the first occurrence. Stays lazy so a downstream `take` short-circuits
  enrichment of the unrealised tail."
  [f coll]
  (letfn [(step [xs seen]
            (lazy-seq
             (when-let [s (seq xs)]
               (let [x (first s) k (f x)]
                 (if (contains? seen k)
                   (step (rest s) seen)
                   (cons x (step (rest s) (conj seen k))))))))]
    (step coll #{})))

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

(defn- fold
  "Lower-case and strip diacritics (NFD decompose, drop combining marks) so
  filtering matches the FTS `unicode61 remove_diacritics` folding — \"café\"
  and \"cafe\" compare equal."
  [^String s]
  (-> (Normalizer/normalize s Normalizer$Form/NFD)
      (.replaceAll "\\p{M}+" "")
      str/lower-case))

(defn- word-tokens
  "Folded Unicode letter/digit word tokens of `s`; nil → empty."
  [s]
  (when s (remove str/blank? (str/split (fold s) #"[^\p{L}\p{Nd}]+"))))

(defn- text-matcher
  "Compile the `$expand` `filter` into a predicate `(fn [s] -> boolean)`:
  true when every whitespace token of `filter` is a (case-insensitive)
  prefix of some word in `s` — the autocomplete-style token-prefix semantic
  the SNOMED/Hermes provider uses and the FHIR `filter` 'left matching'
  example describes (\"acut ast\" matches \"acute asthma\"). The filter is
  tokenised once here, not per candidate; the returned predicate only
  re-tokenises each candidate `s`. Returns nil for a blank filter (no
  filtering)."
  [filter]
  (when-let [needles (seq (word-tokens filter))]
    (fn [s]
      (boolean
       (when-let [words (word-tokens s)]
         (every? (fn [needle] (some #(str/starts-with? % needle) words)) needles))))))

(defn- designations-match?
  "True when any designation's `:value` satisfies the compiled `match?`."
  [match? designations]
  (some (fn [{:keys [value]}] (match? value)) designations))

(defn- concept-designations
  [concept]
  (or (:designations concept)
      (get concept "designation")))

(defn normalise-designation
  "Coerce a designation — either already internal (`:value`-keyed) or a raw
  FHIR designation map (string-keyed) — into the internal
  `{:value :language :use}` shape."
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
  "True when the enumerated membership *is* the complete semantics of
  `compose`: explicit include.concept entries only — no filters,
  imported ValueSets, or excludes — and no compose-level semantics the
  enumeration can't carry. `inactive = false` requires each member's
  activity status from its (possibly foreign) CodeSystem, and
  compose-level extensions (e.g. a default displayLanguage expansion
  parameter) alter expansion output; either disqualifies. Only this
  shape may bypass the compose engine."
  [compose]
  (let [includes (get compose "include")]
    (and (seq includes)
         (empty? (get compose "exclude"))
         (not (false? (get compose "inactive")))
         (empty? (get compose "extension"))
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
  fall back to `expand-compose`.

  Interim (Phase 0b): a shape-limited bypass of the general path. Since
  `expand-compose` now pages lazily and derives metadata structurally,
  this exists only to skip lookups entirely for the displays-present
  stored shape; it is no longer the sole guard against the enrichment
  cliff."
  [compose {:keys [offset displayLanguage] :as params}]
  (when (stored-extensional-answerable? compose params)
    (let [display-langs (display/parse-display-language* displayLanguage)
          offset' (or offset 0)
          limit   (:count params)
          match?  (text-matcher (:filter params))
          matched (cond->> (stored-extensional-pairs compose)
                    match? (filter (fn [[_ concept]]
                                     (or (match? (concept-code concept))
                                         (match? (concept-display concept))))))
          total   (count matched)
          concepts (cond->> matched
                     (pos? offset') (drop offset')
                     limit          (take limit)
                     true           (map (fn [[include concept]]
                                           (stored-concept-row display-langs include concept))))]
      (cond-> {:concepts              (vec concepts)
               :total                 total
               :used-codesystems      (stored-used-codesystems compose)
               :compose-pins          (stored-compose-pins compose)
               :multi-version-systems #{}}
        (seq display-langs) (assoc :display-language displayLanguage)))))

(defn extensional-members
  "When `compose` is a pure stored-extensional definition with a display on
  every concept — the shape `stored-extensional-expand` answers without
  CodeSystem enrichment — return an ordered seq of member maps
  `{:system :version :code :display :designations}` (`:designations` is the
  raw FHIR designation array or nil). Otherwise nil.

  This is the compiler contract — the single definition of
  \"materialisable membership\". A backend indexer calls it to explode
  membership into queryable rows; non-nil means those rows (plus their
  include-level system/version skeleton) are the *complete* semantics of
  the document, so a request path may answer expansion or membership
  questions from them without consulting the compose. Anything the rows
  cannot represent (compose-level `inactive`/extensions, filters,
  imports, excludes, missing displays) must compile to nil here and take
  the compose-engine path instead — never special-case downstream."
  [compose]
  (when (and (stored-extensional-compose? compose)
             (every? (fn [include] (every? concept-display (get include "concept")))
                     (get compose "include")))
    (map (fn [[include concept]]
           {:system       (or (get concept "system")  (get include "system"))
            :version      (or (get concept "version") (get include "version"))
            :code         (concept-code concept)
            :display      (concept-display concept)
            :designations (concept-designations concept)})
         (stored-extensional-pairs compose))))

(defn- expand-include-concepts
  "Expand a concept-driven include (`include.concept[]`) into the
  expansion. Each candidate is looked up via `cs-lookup` to enrich
  display / designations / inactive / abstract, then gated by
  `:activeOnly` and (when present) the `$expand` `:filter` text. The
  filter matches against the looked-up display AND every designation
  value — the same surface a match-driven `cs-expand*` would consider —
  so concept-driven includes honour `filter` without compose having to
  post-filter the merged expansion."
  [svc system version concepts {:keys [activeOnly displayLanguage properties max-hits] text-filter :filter}]
  (let [match?        (text-matcher text-filter)
        display-langs (display/parse-display-language* displayLanguage)]
   (cond->>
   (keep (fn [{:strs [code] provided-display "display"}]
          (let [raw       (when system
                            (protos/cs-lookup svc (cond-> {:system system :code code}
                                                    version (assoc :version version))))
                looked-up (when-not (:not-found raw) raw)]
            ;; `include.concept` lists explicit codes that are by FHIR
            ;; semantics part of the value set: emit them even when no
            ;; provider serves the system, using the provided display.
            (when (or looked-up (nil? system) provided-display)
              (let [lang-display (when (and (seq display-langs) looked-up)
                                   (display/find-display-for-language (:designations looked-up) display-langs))
                    display (or provided-display lang-display (:display looked-up))
                    result-version (or (:version looked-up) version)
                    props      (:properties looked-up)
                    inactive?  (some (fn [{:keys [code value]}]
                                       (and (= :inactive code) value))
                                     props)
                    ;; the specific status (e.g. "retired") drives the precise
                    ;; inactive warning; fall back to generic "inactive".
                    status-value (some (fn [{:keys [code value]}]
                                         (when (= :status code) value))
                                       props)
                    designations (:designations looked-up)]
                (when (and (not (and activeOnly inactive?))
                           (or (nil? match?)
                               (match? display)
                               (designations-match? match? designations)))
                  (cond-> {:code    code
                           :system  system
                           :display display}
                    result-version        (assoc :version result-version)
                    (seq designations)    (assoc :designations designations)
                    (:abstract looked-up) (assoc :abstract true)
                    inactive?             (assoc :inactive true
                                                 :inactive-status (or (some-> status-value name) "inactive"))
                    (and (seq properties) looked-up)
                    (assoc :properties
                           (let [want (set properties)]
                             (filterv (fn [{k :code}]
                                        (contains? want (if (keyword? k) (name k) (str k))))
                                      (:properties looked-up))))))))))
         concepts)
    max-hits (take max-hits))))

(defn- build-query
  [system version filters params]
  (cond-> {:system system}
    version                    (assoc :version version)
    (seq filters)              (assoc :filters (parse-filters filters))
    (:displayLanguage params)  (assoc :displayLanguage (:displayLanguage params))
    (seq (:properties params)) (assoc :properties (:properties params))
    (:max-hits params)         (assoc :max-hits (:max-hits params))
    (:filter params)           (assoc :text (:filter params))
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
  `{:concepts :issues :total :used-systems}`. The element may
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
     ;; Systems (and resolved versions) this include contributes, derived
     ;; structurally so downstream metadata never walks the lazy concept
     ;; seq. The system include carries its resolved version; vs-refs
     ;; expand eagerly, so reading their concepts' systems is cheap.
     :used-systems (cond-> #{}
                     system            (conj [system version])
                     (seq vs-concepts) (into (map (fn [c] [(:system c) (:version c)])) vs-concepts))
     :concepts (if (and (some? system-results) (seq vs-concepts))
                 (let [vs-set (set (map concept-key vs-concepts))]
                   (filter (fn [c] (contains? vs-set (concept-key c))) system-results))
                 (concat system-results vs-concepts))}))

(defn- resolve-used-codesystem
  "Resolve a `[system version]` pair to `{:system :version :entry}`,
  fetching `cs-meta` for status / experimental / standards-status and the
  concrete version when the include left it unpinned. `:version` is the
  resolved concrete version (so multi-version detection sees the real
  version an unpinned include expands to, not nil); `:entry` is the
  `used-codesystem` wire row."
  [svc [sys ver]]
  (let [meta (when sys
               (or (when ver (composite/cs-meta svc (canonical/versioned-uri sys ver)))
                   (composite/cs-meta svc sys)))
        ver' (or ver (:version meta))
        uri (if ver' (str sys "|" ver') sys)]
    {:system  sys
     :version ver'
     :entry   (cond-> {:uri uri}
                (:status meta) (assoc :status (:status meta))
                (some? (:experimental meta)) (assoc :experimental (:experimental meta))
                (:standards-status meta) (assoc :standards-status (:standards-status meta)))}))

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
        includes (get compose "include")
        excludes (get compose "exclude")
        inactive-allowed (get compose "inactive" true)
        active-only? (or (:activeOnly params) (false? inactive-allowed))
        include-params (cond-> (assoc params :activeOnly active-only?)
                         (:count params)
                         (assoc :max-hits (+ (:count params) (or (:offset params) 0))))
        include-results (mapv #(expand-include svc % include-params) includes)
        include-issues (mapcat :issues include-results)
        excluded (when (seq excludes)
                   (let [exclude-results (mapv #(expand-include svc % params) excludes)]
                     (set (map concept-key (mapcat :concepts exclude-results)))))
        ;; Lazy, first-wins dedup over the concatenated include concepts,
        ;; then lazy exclude removal. Text filtering is the provider's job
        ;; for match-driven includes (`cs-expand*` honours `:text`) and
        ;; `expand-include-concepts`' job for concept-driven includes;
        ;; compose neither re-filters nor materialises. Keeping this lazy
        ;; lets `take count` below pull only a page's worth of enrichment.
        deduped (distinct-by concept-key (mapcat :concepts include-results))
        filtered (if excluded
                   (remove (fn [c] (contains? excluded (concept-key c))) deduped)
                   deduped)
        ;; Expansion metadata is derived structurally from the include
        ;; systems/versions, never by walking the lazy concept seq. Each
        ;; pair is resolved to its concrete version so multi-version
        ;; detection sees the version an unpinned include actually
        ;; expands to.
        resolved-cs (mapv #(resolve-used-codesystem svc %)
                          (sort-by str (into #{} (mapcat :used-systems) include-results)))
        used-cs (into [] (distinct) (map :entry resolved-cs))
        sys->versions (reduce (fn [acc {:keys [system version]}]
                                (if (and system version)
                                  (update acc system (fnil conj #{}) version)
                                  acc))
                              {} resolved-cs)
        multi-version (into #{} (keep (fn [[sys vers]]
                                        (when (> (count vers) 1) sys)))
                            sys->versions)
        offset' (or (:offset params) 0)
        paged (cond->> filtered
                (pos? offset')  (drop offset')
                (:count params) (take (:count params)))
        ;; `:total` (FHIR `expansion.total`) is optional on the wire, so
        ;; we supply it only when it is cheap and omit it otherwise. Cheap
        ;; means: a single provider-backed include returns its own total
        ;; (`singleton-total`), or a full expansion (`:count` absent)
        ;; realises every member anyway so counting is free. With `:count`
        ;; present, counting would force the whole — possibly
        ;; enrichment-bound — seq, so we leave `:total` off.
        singleton-total (when (and (= 1 (count include-results)) (empty? excludes))
                          (:total (first include-results)))
        total (cond
                singleton-total        singleton-total
                (nil? (:count params)) (count filtered)
                :else                  nil)]
    (cond-> {:concepts              (vec paged)
             :used-codesystems      used-cs
             :compose-pins          (extract-compose-pins compose)
             :multi-version-systems multi-version}
      total                     (assoc :total total)
      (:displayLanguage params) (assoc :display-language (:displayLanguage params))
      (seq include-issues)      (assoc :issues include-issues))))
