(ns com.eldrix.hades.fhir-codesystem
  "Generic file-backed CodeSystem and ValueSet provider.

  Creates providers from FHIR CodeSystem JSON (as Clojure maps). Used both for
  loading code systems from the filesystem and for the tx-resource mechanism."
  (:require [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry]))

(def ^:private value-keys
  ["valueCode" "valueCoding" "valueString" "valueInteger"
   "valueBoolean" "valueDecimal" "valueDateTime"])

(defn- extract-property-value
  "Extract the value from a FHIR concept property, which uses one of several
  typed keys: valueCode, valueCoding, valueString, valueInteger, etc."
  [prop]
  (reduce (fn [_ k]
            (let [v (get prop k ::not-found)]
              (if (not= v ::not-found)
                (reduced v)
                nil)))
          nil value-keys))

(defn- flatten-concepts
  "Recursively flatten nested FHIR CodeSystem concepts into a flat sequence,
  tracking the parent code for hierarchy building."
  ([concepts] (flatten-concepts concepts nil))
  ([concepts parent-code]
   (mapcat (fn [c]
             (let [code (get c "code")
                   entry {:code        code
                          :display     (get c "display")
                          :definition  (get c "definition")
                          :designations (mapv (fn [d]
                                               (let [use-map (get d "use")]
                                                 (cond-> {:value (get d "value")}
                                                   (get d "language") (assoc :language (keyword (get d "language")))
                                                   use-map (assoc :use (cond-> {:system (get use-map "system")
                                                                                     :code   (get use-map "code")}
                                                                              (get use-map "display")
                                                                              (assoc :display (get use-map "display")))))))
                                             (get c "designation"))
                          :properties  (get c "property")
                          :parent-code parent-code}
                   children (get c "concept")]
               (cons entry (when children (flatten-concepts children code)))))
           concepts)))

(defn- build-code-index
  "Build a {code-string -> concept-map} index from a flat concept sequence."
  [flat-concepts]
  (reduce (fn [idx c] (assoc idx (:code c) c))
          {} flat-concepts))

(defn- build-hierarchy
  "Build bidirectional parent/child maps from flat concepts.
  Uses both structural nesting (parent-code from flatten-concepts) and explicit
  'parent'/'child' properties."
  [flat-concepts]
  (let [add-edge (fn [m from to]
                   (update m from (fnil conj #{}) to))]
    (reduce
      (fn [{:keys [parents children]} c]
        (let [code (:code c)
              ;; structural parent from nesting
              p1 (when (:parent-code c)
                   [{:parent (:parent-code c)}])
              ;; explicit parent/child properties
              props (:properties c)
              p2 (keep (fn [prop]
                         (when (= "parent" (get prop "code"))
                           {:parent (extract-property-value prop)}))
                       props)
              c2 (keep (fn [prop]
                         (when (= "child" (get prop "code"))
                           {:child (extract-property-value prop)}))
                       props)
              all-parents (map :parent (concat p1 p2))
              all-children (map :child c2)]
          {:parents  (reduce (fn [m p] (add-edge m code p)) parents all-parents)
           :children (reduce (fn [m ch] (-> (add-edge m code ch)
                                            (add-edge ch code)))
                             (reduce (fn [m p] (add-edge m p code)) children all-parents)
                             all-children)}))
      {:parents {} :children {}}
      flat-concepts)))

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
  "Collect all ancestor codes of `code` via BFS on parents map."
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

(defn- get-concept-property
  "Extract the value of a named property from a concept's :properties list.
  Returns ::not-found when the property is not present (as distinct from
  a property with a falsy value like false or 0)."
  [concept property-name]
  (reduce (fn [_ prop]
            (if (= property-name (get prop "code"))
              (reduced (extract-property-value prop))
              ::not-found))
          ::not-found
          (:properties concept)))

(defn- concept-matches-filter?
  "Test whether a concept matches a single FHIR filter criterion.
  Returns true if the concept passes the filter."
  [_code-index _children-map parents-map concept {:keys [property op value]}]
  (case op
    "is-a" (or (= (:code concept) value)
               (ancestor? parents-map (:code concept) value))
    "descendant-of" (ancestor? parents-map (:code concept) value)
    "is-not-a" (not (or (= (:code concept) value)
                        (ancestor? parents-map (:code concept) value)))
    "generalizes" (or (= (:code concept) value)
                      (contains? (set (collect-ancestors parents-map value)) (:code concept)))
    "=" (if (= property "code")
          (= (:code concept) value)
          (let [pv (get-concept-property concept property)]
            (and (not= pv ::not-found) (= (str pv) value))))
    "regex" (let [pat (re-pattern value)
                  pv (if (= property "code")
                       (:code concept)
                       (let [v (get-concept-property concept property)]
                         (when (not= v ::not-found) (str v))))]
              (boolean (when pv (re-matches pat pv))))
    "in" (let [vals (set (str/split value #","))]
           (if (= property "code")
             (contains? vals (:code concept))
             (let [pv (get-concept-property concept property)]
               (and (not= pv ::not-found) (contains? vals (str pv))))))
    "not-in" (let [vals (set (str/split value #","))]
               (if (= property "code")
                 (not (contains? vals (:code concept)))
                 (let [pv (get-concept-property concept property)]
                   (or (= pv ::not-found) (not (contains? vals (str pv)))))))
    "exists" (let [exists? (= "true" value)
                   pv (get-concept-property concept property)]
               (if exists?
                 (not= pv ::not-found)
                 (= pv ::not-found)))
    (throw (ex-info (str "The filter operation '" op "' is not supported")
                    {:type :invalid}))))

(defn- display-matches?
  "Check whether a display string matches the concept, checking against the
  primary display and all designations. Always case-insensitive for display
  matching per FHIR terminology service spec."
  [concept display]
  (let [display-lower (str/lower-case display)
        primary (:display concept)
        designations (:designations concept)]
    (or (and primary (= display-lower (str/lower-case primary)))
        (some (fn [d]
                (when-let [v (:value d)]
                  (= display-lower (str/lower-case v))))
              designations))))

(defn- concept-inactive?
  "Check whether a concept is inactive based on its properties.
  Checks both 'status' property (retired/inactive/deprecated) and
  'inactive' boolean property."
  [concept]
  (let [inactive-prop (get-concept-property concept "inactive")
        status-prop (get-concept-property concept "status")]
    (or (and (not= inactive-prop ::not-found) (boolean inactive-prop))
        (and (not= status-prop ::not-found)
             (contains? #{"retired" "inactive" "deprecated"} status-prop)))))

(defn- concept-inactive-status
  "Return the specific inactive status label for a concept.
  Returns 'retired', 'deprecated', or 'inactive' depending on the concept's properties."
  [concept]
  (let [status-prop (get-concept-property concept "status")]
    (if (and (not= status-prop ::not-found)
             (contains? #{"retired" "inactive" "deprecated"} status-prop))
      status-prop
      "inactive")))

(def ^:private notSelectable-uri
  "http://hl7.org/fhir/concept-properties#notSelectable")

(defn- concept-abstract?
  "Check whether a concept has the notSelectable/abstract property.
  Checks both the standard code 'notSelectable' and any code mapped to
  the standard notSelectable URI in the CodeSystem's property definitions."
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

(defn- format-display-mismatch
  "Build a display-mismatch message following the FHIR spec format.
  Uses designations and the CS language to include language information."
  [given-display system code primary-display designations display-language cs-language]
  (let [prefix (str "Wrong Display Name '" given-display "' for " system "#" code ". ")
        lang (or display-language "--")
        ;; The primary display's language comes from CS language or an explicit designation
        primary-lang (or (some (fn [d] (when (= (:value d) primary-display)
                                         (when-let [l (:language d)] (name l))))
                                designations)
                         cs-language)
        ;; Build all valid display choices with language tags
        all-choices (cond-> []
                      primary-display
                      (conj {:display primary-display :lang primary-lang})
                      (seq designations)
                      (into (keep (fn [d]
                                    (when (and (:value d) (not= (:value d) primary-display))
                                      {:display (:value d)
                                       :lang (when-let [l (:language d)] (name l))})))
                            designations))
        has-lang-info? (some :lang all-choices)
        ;; Filter by display-language if specified
        lang-filtered (if display-language
                        (filter #(= (:lang %) display-language) all-choices)
                        all-choices)
        unique-choices (distinct lang-filtered)]
    (cond
      ;; displayLanguage specified, language info exists, but no matches
      (and display-language (empty? unique-choices) has-lang-info?)
      (str prefix "There are no valid display names found for language(s) '" lang
           "'. Default display is '" primary-display "'")

      ;; Multiple valid displays
      (> (count unique-choices) 1)
      (let [formatted (map (fn [{:keys [display lang]}]
                             (if lang (str "'" display "' (" lang ")") (str "'" display "'")))
                           unique-choices)]
        (str prefix "Valid display is one of " (count unique-choices) " choices: "
             (str/join " or " formatted) " (for the language(s) '" lang "')"))

      ;; Single valid display with language tag
      (and (= 1 (count unique-choices)) (:lang (first unique-choices)))
      (let [{:keys [display lang]} (first unique-choices)]
        (str prefix "Valid display is '" display "' (" lang ") (for the language(s) '" lang "')"))

      ;; Default: simple format
      :else
      (str prefix "Valid display is '" primary-display "' (for the language(s) '" lang "')"))))

(defn- parse-display-language
  "Parse a displayLanguage string (potentially Accept-Language format) into
  a seq of language codes ordered by preference. E.g.:
    'de'         → ['de']
    'de,*; q=0'  → ['de']
    'en, de;q=0.5' → ['en' 'de']"
  [s]
  (when s
    (let [parts (str/split s #",")
          parsed (keep (fn [p]
                         (let [trimmed (str/trim p)
                               [lang q] (str/split trimmed #";")
                               lang (str/trim lang)]
                           (when (and (seq lang) (not= lang "*"))
                             {:lang lang
                              :q (if q
                                   (let [m (re-find #"q\s*=\s*([0-9.]+)" q)]
                                     (if m (Double/parseDouble (second m)) 1.0))
                                   1.0)})))
                       parts)]
      (map :lang (sort-by :q #(compare %2 %1) parsed)))))

(defn- language-matches?
  "Check if a designation language matches a requested language using prefix matching.
  'de' matches 'de', 'de-CH', 'de-AT'. Case-insensitive."
  [designation-lang requested-lang]
  (when (and designation-lang requested-lang)
    (let [d (str/lower-case (if (keyword? designation-lang) (name designation-lang) (str designation-lang)))
          r (str/lower-case requested-lang)]
      (or (= d r) (str/starts-with? d (str r "-"))))))

(defn- find-display-for-language
  "Find the best display for a set of designations given language preferences."
  [designations display-languages]
  (some (fn [lang]
          (some (fn [d]
                  (when (language-matches? (:language d) lang)
                    (:value d)))
                designations))
        display-languages))

(deftype FhirCodeSystem [url version metadata code-index hierarchy property-uri-map case-sensitive? ci-index]
  protos/CodeSystem
  (cs-resource [_ _params]
    {:url          url
     :version      version
     :name         (get metadata "name")
     :title        (get metadata "title")
     :status       (get metadata "status")
     :experimental (get metadata "experimental")
     :description  (get metadata "description")
     :content      (get metadata "content")
     :language     (get metadata "language")})

  (cs-lookup [_ {:keys [code]}]
    (when-let [[concept _] (if case-sensitive?
                              (when-let [c (get code-index code)] [c false])
                              (ci-lookup code-index ci-index code))]
      (let [cs-name (get metadata "name")
            actual-code (:code concept)
            parents (get (:parents hierarchy) actual-code)
            children (get (:children hierarchy) actual-code)
            props (:properties concept)
            inactive? (concept-inactive? concept)
            abstract? (concept-abstract? property-uri-map concept)]
        {:name        cs-name
         :version     version
         :display     (:display concept)
         :system      url
         :code        (keyword actual-code)
         :definition  (:definition concept)
         :abstract    abstract?
         :properties  (concat
                        [{:code :inactive :value (boolean inactive?)}]
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
                        (keep (fn [prop]
                                (let [pc (get prop "code")
                                      v (extract-property-value prop)]
                                  (when (and pc v (not (#{"parent" "child"} pc)))
                                    {:code (keyword pc) :value v})))
                              props))
         :designations (:designations concept)})))

  (cs-validate-code [_ {:keys [code display displayLanguage]}]
    (let [[concept case-differs?] (if case-sensitive?
                                     (when-let [c (get code-index code)] [c false])
                                     (ci-lookup code-index ci-index code))]
      (if concept
        (let [inactive? (concept-inactive? concept)
              actual-code (:code concept)
              result (cond-> {:result  true
                              :display (:display concept)
                              :code    (keyword code)
                              :system  url
                              :version version}
                       inactive? (assoc :inactive true
                                        :inactive-status (concept-inactive-status concept))
                       case-differs? (assoc :normalized-code (keyword actual-code)))]
          (let [case-issue (when case-differs?
                             {:severity     "information"
                              :type         "business-rule"
                              :details-code "code-rule"
                              :text         (str "The code '" code "' differs from the correct code '"
                                                 actual-code "' by case. Although the code system '"
                                                 url "|" version "' is case insensitive, implementers "
                                                 "are strongly encouraged to use the correct case anyway")
                              :expression   ["Coding.code"]})
                display-issue (when (and display (not (display-matches? concept display)))
                                (let [msg (format-display-mismatch display url code
                                            (:display concept) (:designations concept) displayLanguage
                                            (get metadata "language"))]
                                  {:severity     "error"
                                   :type         "invalid"
                                   :details-code "invalid-display"
                                   :text         msg
                                   :expression   ["Coding.display"]}))
                issues (filterv some? [case-issue display-issue])]
            (cond-> result
              display-issue (assoc :result false :message (:text display-issue))
              (seq issues) (assoc :issues issues))))
        (let [fragment? (= "fragment" (get metadata "content"))
              msg (if fragment?
                    (str "Unknown Code '" code "' in the CodeSystem '" url "' version '" version
                         "' - note that the code system is labeled as a fragment, so the code may be valid in some other fragment")
                    (str "Unknown code '" code "' in the CodeSystem '" url "' version '" version "'"))]
          (cond-> {:result  (boolean fragment?)
                   :code    (keyword code)
                   :system  url
                   :version version
                   :issues  [{:severity     (if fragment? "warning" "error")
                              :type         "code-invalid"
                              :details-code "invalid-code"
                              :text         msg
                              :expression   ["Coding.code"]}]}
            (not fragment?) (assoc :message msg))))))

  (cs-subsumes [_ {:keys [codeA codeB]}]
    {:outcome
     (cond
       (= codeA codeB) "equivalent"
       (ancestor? (:parents hierarchy) codeA codeB) "subsumed-by"
       (ancestor? (:parents hierarchy) codeB codeA) "subsumes"
       :else "not-subsumed")})

  (cs-find-matches [_ {:keys [filters displayLanguage]}]
    (let [all-concepts (vals code-index)
          children-map (:children hierarchy)
          parents-map (:parents hierarchy)
          display-langs (parse-display-language displayLanguage)
          matching (if (seq filters)
                     (clojure.core/filter
                       (fn [c]
                         (every? #(concept-matches-filter? code-index children-map parents-map c %) filters))
                       all-concepts)
                     all-concepts)]
      (map (fn [c]
             (let [display (or (when (seq display-langs)
                                 (find-display-for-language (:designations c) display-langs))
                               (:display c))]
               (cond-> {:code    (:code c)
                        :system  url
                        :version version
                        :display display
                        :designations (:designations c)}
                 (concept-inactive? c) (assoc :inactive true
                                              :inactive-status (concept-inactive-status c))
                 (concept-abstract? property-uri-map c) (assoc :abstract true))))
           matching)))

  protos/ValueSet
  (vs-resource [_ _params]
    {:url     url
     :version version
     :name    (get metadata "name")
     :title   (get metadata "title")
     :status  (get metadata "status")})

  (vs-expand [_ _ctx {:keys [filter offset count displayLanguage]}]
    (let [concepts (vals code-index)
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
          display-langs (parse-display-language displayLanguage)
          all-concepts (mapv (fn [c]
                               (let [display (or (when (seq display-langs)
                                                   (find-display-for-language (:designations c) display-langs))
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
                           :status (get metadata "status")}]
       :compose-pins     []}))

  (vs-validate-code [_ ctx {:keys [code system display displayLanguage]}]
    (when (or (nil? system) (= system url))
      (if-let [concept (get code-index code)]
        (let [inactive? (concept-inactive? concept)
              {:keys [lenient-display-validation]} (merge registry/default-request (:request ctx))
              result (cond-> {:result  true
                              :display (:display concept)
                              :code    (keyword code)
                              :system  url
                              :version version}
                       inactive? (assoc :inactive true
                                        :inactive-status (concept-inactive-status concept)))]
          (if (and display (not (display-matches? concept display)))
            (let [lenient? lenient-display-validation
                  msg (format-display-mismatch display url code
                        (:display concept) (:designations concept) displayLanguage
                        (get metadata "language"))]
              (assoc result :result (boolean lenient?)
                            :message msg
                            :issues [{:severity     (if lenient? "warning" "error")
                                      :type         "invalid"
                                      :details-code "invalid-display"
                                      :text         msg
                                      :expression   ["display"]}]))
            result))
        (let [fragment? (= "fragment" (get metadata "content"))
              msg (if fragment?
                    (str "Unknown Code '" code "' in the CodeSystem '" url "' version '" version
                         "' - note that the code system is labeled as a fragment, so the code may be valid in some other fragment")
                    (str "Unknown code '" code "' in the CodeSystem '" url "' version '" version "'"))]
          (cond-> {:result  (boolean fragment?)
                   :code    (keyword code)
                   :system  url
                   :version version
                   :issues  [{:severity     (if fragment? "warning" "error")
                              :type         "code-invalid"
                              :details-code "invalid-code"
                              :text         msg
                              :expression   ["Coding.code"]}]}
            (not fragment?) (assoc :message msg)))))))

(defn- build-property-uri-map
  "Build a {uri → concept-property-code} map from the CodeSystem's property definitions.
  This allows resolving standard FHIR property URIs to the local property codes
  used by this CodeSystem's concepts (which may differ from the standard codes)."
  [cs-map]
  (reduce (fn [m prop-def]
            (let [uri (get prop-def "uri")
                  code (get prop-def "code")]
              (if (and uri code)
                (assoc m uri code)
                m)))
          {}
          (get cs-map "property")))

(defn make-fhir-code-system
  "Create a FhirCodeSystem from a parsed FHIR CodeSystem JSON map (string keys).
  The map must contain at minimum a \"url\" and \"concept\" array."
  [cs-map]
  (let [url (get cs-map "url")
        version (get cs-map "version")
        flat (flatten-concepts (get cs-map "concept"))
        code-idx (build-code-index flat)
        hier (build-hierarchy flat)
        metadata (select-keys cs-map ["resourceType" "name" "title" "status"
                                      "description" "experimental" "hierarchyMeaning"
                                      "content" "caseSensitive" "compositional" "versionNeeded"
                                      "count" "language"])
        prop-uri-map (build-property-uri-map cs-map)
        cs? (get cs-map "caseSensitive" true)
        ci-idx (when-not cs?
                 (reduce (fn [m code] (assoc m (str/lower-case code) code))
                         {} (keys code-idx)))]
    (->FhirCodeSystem url version metadata code-idx hier prop-uri-map cs? ci-idx)))

(defn apply-supplement
  "Apply a supplement CodeSystem map to a base FhirCodeSystem.
  Merges supplement designations and properties into existing concept entries."
  [^FhirCodeSystem base supplement-map]
  (let [supp-concepts (flatten-concepts (get supplement-map "concept"))
        base-idx (.-code-index base)
        merged-index (reduce
                       (fn [idx sc]
                         (if-let [existing (get idx (:code sc))]
                           (assoc idx (:code sc)
                             (cond-> existing
                               (seq (:designations sc))
                               (update :designations (fn [d] (into (or d []) (:designations sc))))
                               (seq (:properties sc))
                               (update :properties (fn [p] (into (or p []) (:properties sc))))))
                           idx))
                       base-idx supp-concepts)]
    (->FhirCodeSystem (.-url base) (.-version base) (.-metadata base)
                      merged-index (.-hierarchy base) (.-property-uri-map base)
                      (.-case-sensitive? base) (.-ci-index base))))

(defn register!
  "Register a FhirCodeSystem with the global registry under its canonical URL."
  [^FhirCodeSystem fcs]
  (let [url (.-url fcs)]
    (registry/register-codesystem url fcs)
    (registry/register-valueset url fcs)))
