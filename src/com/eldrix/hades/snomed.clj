(ns com.eldrix.hades.snomed
  "Implementation of Hades protocols for SNOMED CT.
  A thin wrapper around the Hermes SNOMED terminology service"
  (:require [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [lambdaisland.uri :as uri])
  (:import (com.eldrix.hermes.snomed Description)
           (java.time.format DateTimeFormatter)))

(def snomed-system-uri "http://snomed.info/sct")

(defn- version-uri [svc]
  (let [ri (first (hermes/release-information svc))]
    (str snomed-system-uri "/" (:moduleId ri) "/version/"
         (.format DateTimeFormatter/BASIC_ISO_DATE (:effectiveTime ri)))))

(def default-use-terms
  {snomed/Synonym            {:term "Synonym"}
   snomed/FullySpecifiedName {:term "Fully specified name"}})

(defn description->params
  "Turn a SNOMED description into a parameter map.
  Parameters:
   - d             : SNOMED CT description
   - use-terms-map : A map of descriptions by typeId

   It would be usual to specify the preferred text for a particular locale,
   but this will fallback to english if not provided."
  ([^Description d]
   (description->params d default-use-terms))
  ([^Description d use-terms-map]
   {"language" (:languageCode d)
    "use"      {:system  snomed-system-uri
                :code    (str (:typeId d))
                :display (:term (get use-terms-map (:typeId d)))}
    "display"  (:term d)}))


(defn ^:private parse-snomed-uri
  "Parses a SNOMED uri into a map of properties."
  [uri]
  (let [parsed (uri/parse uri)
        query-map (uri/query-string->map (:query parsed))
        [_ _ edition-refset-id _ _ version] (re-matches #"/sct(/(\d*))?(/(version)/(\d{8}))?" (:path parsed))]
    (assoc (into {} parsed)
      :query query-map
      :edition edition-refset-id
      :version version)))

(defn parse-implicit-value-set
  "Parse a FHIR value set from a single URL into an expression constraint.
  If parsing was successful, returns a map containing:
     |- :query  : one of [:all :isa :refsets :in-refset :ecl]
     |- :ecl    : an ECL constraint
  Returns nil if the URL could not be parsed.
  FHIR provides implicit value sets to reference a value set based on reference
  set or by subsumption. See https://www.hl7.org/fhir/snomedct.html#implicit
  The URI may contain specific *edition* information.
  - http://snomed.info/sct   - latest edition
  - http://snomed.info/sct/900000000000207008 - International edition
  - http://snomed.info/sct/900000000000207008/version/20130731 - International edition, 2013-07-31
  but these examples use the latest:
  - http://snomed.info/sct?fhir_vs  - all concepts
  - http://snomed.info/sct?fhir_vs=isa/[sctid] - all concepts subsumed by specified concept
  - http://snomed.info/sct?fhir_vs=refset - all installed reference sets
  - http://snomed.info/sct?fhir_vs=refset/[sctid] - all concepts in the reference set
  - http://snomed.info/sct?fhir_vs=ecl/[ecl] - all concepts matching the ECL."
  [^String uri]
  (let [{:keys [edition version query]} (parse-snomed-uri uri)
        fhir-vs (:fhir_vs query)]
    (cond
      (or edition version)
      {:error   :not-implemented
       :message "Implicit value sets with edition/version not supported."}
      (nil? fhir-vs)
      nil
      (= fhir-vs "")
      {:query :all, :ecl "*"}
      (str/starts-with? fhir-vs "isa/")
      {:query :isa, :ecl (str "<" (subs fhir-vs 4))}
      (= "refset" fhir-vs)
      {:query :refsets, :ecl (str "<" snomed/ReferenceSetConcept)}
      (str/starts-with? fhir-vs "refset/")
      {:query :in-refset, :ecl (str "^" (subs fhir-vs 7))}
      (str/starts-with? fhir-vs "ecl/")
      {:query :ecl, :ecl (subs fhir-vs 4)}
      :else
      {:error   :not-implemented
       :message (str "Implicit valueset for '" uri "' not implemented")})))

(defn- expression?
  "Returns true if code contains SNOMED expression syntax."
  [code]
  (and code (or (str/includes? code ":") (str/includes? code "{"))))

(deftype HermesService
  [svc]
  protos/CodeSystem
  (cs-resource [_ _params])
  (cs-lookup
    [_ {:keys [code version displayLanguage]}]
    (if (expression? code)
      (let [lang-ids (hermes/match-locale svc displayLanguage true)
            rendered (try (hermes/render-expression* svc code
                            {:terms :update :definition-status :auto
                             :language-refset-ids lang-ids})
                          (catch Exception _ nil))]
        (when rendered
          {:name    "SNOMED CT"
           :version (or version (version-uri svc))
           :display rendered
           :system  snomed-system-uri
           :code    (keyword code)}))
      (when-let [code' (parse-long code)]
        (when-let [ec (hermes/extended-concept svc code')]
          (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                preferred (:term (hermes/preferred-synonym* svc code' lang-refset-ids))
                usage {snomed/Synonym            (hermes/preferred-synonym* svc snomed/Synonym lang-refset-ids)
                       snomed/FullySpecifiedName (hermes/preferred-synonym* svc snomed/FullySpecifiedName lang-refset-ids)}
                ver (or version (version-uri svc))
                parents (get-in ec [:directParentRelationships snomed/IsA])
                children (hermes/child-relationships-of-type svc code' snomed/IsA)
                attrs (dissoc (:directParentRelationships ec) snomed/IsA)]
            {:name         "SNOMED CT"
             :version      ver
             :display      preferred
             :system       snomed-system-uri
             :code         (keyword code)
             :properties   (concat
                             [{:code :inactive :value (not (get-in ec [:concept :active]))}
                              {:code :sufficientlyDefined :value (= snomed/Defined (get-in ec [:concept :definitionStatusId]))}]
                             (map (fn [pid] {:code        :parent
                                             :value       (keyword (str pid))
                                             :description (:term (hermes/preferred-synonym* svc pid lang-refset-ids))})
                                  parents)
                             (map (fn [cid] {:code        :child
                                             :value       (keyword (str cid))
                                             :description (:term (hermes/preferred-synonym* svc cid lang-refset-ids))})
                                  children)
                             (mapcat (fn [[type-id target-ids]]
                                       (map (fn [tid] {:code         (keyword (str type-id))
                                                       :code-display (:term (hermes/preferred-synonym* svc type-id lang-refset-ids))
                                                       :value        (keyword (str tid))
                                                       :description  (:term (hermes/preferred-synonym* svc tid lang-refset-ids))})
                                            target-ids))
                                     attrs)
                             (map (fn [{:keys [typeId value]}]
                                    {:code         (keyword (str typeId))
                                     :code-display (:term (hermes/preferred-synonym* svc typeId lang-refset-ids))
                                     :value        value})
                                  (hermes/concrete-values svc code')))
             :designations (map (fn [d] {:language (keyword (:languageCode d))
                                         :use      {:system  snomed-system-uri
                                                    :code    (str (:typeId d))
                                                    :display (:term (get usage (:typeId d)))}
                                         :value    (:term d)})
                                (:descriptions ec))})))))

  (cs-validate-code [_ {:keys [code display version displayLanguage]}]
    (let [ver (or version (version-uri svc))]
      (if (expression? code)
        ;; Post-coordinated expression: full validation including MRCM
        ;; Structural errors (concept-not-found, attribute-invalid) → result: false
        ;; MRCM constraint issues (attribute-not-in-domain, value-out-of-range) → result: true, informational
        (let [all-errors (hermes/validate-expression svc code)
              structural-types #{:concept-not-found :concept-inactive :attribute-invalid :syntax-error}
              structural (filter #(structural-types (:error %)) all-errors)
              mrcm-issues (remove #(structural-types (:error %)) all-errors)
              lang-ids (hermes/match-locale svc displayLanguage true)
              rendered (try (hermes/render-expression* svc code
                              {:terms :update :definition-status :auto
                               :language-refset-ids lang-ids})
                            (catch Exception _ nil))]
          (if (seq structural)
            (let [msg (str "Unknown code '" code "' in the CodeSystem '" snomed-system-uri "' version '" ver "'")]
              {:result  false
               :code    (keyword code)
               :system  snomed-system-uri
               :version ver
               :message msg
               :issues  (into [{:severity "error" :type "code-invalid" :details-code "invalid-code"
                                :text msg :expression ["code"]}]
                              (map (fn [e]
                                     (let [detail (case (:error e)
                                                    :attribute-invalid
                                                    (str "Concept " (:concept-id e)
                                                         " is not valid in this context (must be a descendent of one of 410662002,106237007)")
                                                    (:message e))]
                                       {:severity "information" :type "code-invalid"
                                        :details-code "invalid-code"
                                        :text (str "Not a valid expression: " detail)
                                        :expression ["code"]})))
                              structural)})
            {:result  true
             :code    (keyword code)
             :system  snomed-system-uri
             :version ver
             :display (or rendered code)
             :issues  [{:severity "information" :type "informational" :details-code "process-note"
                        :text (if (seq mrcm-issues)
                                "The expression is grammatically correct and the concepts are valid, but the expression has not been checked against the SNOMED CT concept model (MRCM)"
                                "The expression is grammatically correct and the concepts are valid")
                        :expression ["code"]}]}))
        ;; Simple concept code validation
        (when-let [code' (parse-long code)]
          (if-let [concept (hermes/concept svc code')]
            (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                  preferred (:term (hermes/preferred-synonym* svc code' lang-refset-ids))
                  inactive? (not (:active concept))
                  result (cond-> {:result true :display preferred :code (keyword code)
                                  :system snomed-system-uri :version ver}
                           inactive? (assoc :inactive true))]
              (if (and display (not= display preferred))
                (let [descriptions (:descriptions (hermes/extended-concept svc code'))
                      matching-desc (first (filter #(= display (:term %)) descriptions))
                      active-displays (->> descriptions
                                           (filter :active)
                                           (map :term)
                                           distinct)
                      lang (or displayLanguage "--")]
                  (if (and matching-desc (not (:active matching-desc)))
                    (let [quoted (str/join "," (map #(str "\"" % "\"") active-displays))
                          msg (str "'" display "' is no longer considered a correct display for code '"
                                   code "' (status = inactive). The correct display is one of " quoted ".")]
                      (assoc result :message msg
                                    :issues [{:severity     "warning"
                                              :type         "invalid"
                                              :details-code "display-comment"
                                              :text         msg
                                              :expression   ["display"]}]))
                    (let [msg (str "Wrong Display Name '" display "' for " snomed-system-uri "#" code
                                   " - should be '" preferred "'"
                                   " (for the language(s) '" lang "')")]
                      (assoc result :result false
                                    :message msg
                                    :issues [{:severity     "error"
                                              :type         "invalid"
                                              :details-code "invalid-display"
                                              :text         msg
                                              :expression   ["display"]}]))))
                result))
            (let [msg (str "Unknown code '" code "' in the CodeSystem '" snomed-system-uri "' version '" ver "'")]
              {:result  false
               :code    (keyword code)
               :system  snomed-system-uri
               :version ver
               :message msg
               :issues  [{:severity     "error"
                          :type         "code-invalid"
                          :details-code "invalid-code"
                          :text         msg
                          :expression   ["code"]}]}))))))

  (cs-subsumes
    [_ {:keys [systemA codeA systemB codeB]}]
    (let [codeA' (Long/parseLong codeA)
          codeB' (Long/parseLong codeB)]
      {:outcome
       (cond
         ;; simple if they are the same
         (and (= systemA systemB) (= codeA' codeB')) "equivalent"
         (hermes/subsumed-by? svc codeA' codeB') "subsumed-by" ;; A is subsumed by B
         (hermes/subsumed-by? svc codeB' codeA') "subsumes" ;; A subsumes B
         ;; look up historical associations, and check for equivalence  - this catches SAME-AS and REPLACED-BY etc.
         (and (= systemA systemB) ((hermes/with-historical svc codeA') codeB')) "equivalent"
         :else "not-subsumed")}))
  (cs-find-matches [_ query]
    (let [{:keys [version filters max-hits text active-only]} query
          ver (or version (version-uri svc))
          ecl (if (seq filters)
                (->> filters
                     (keep (fn [{:keys [property op value]}]
                             (when (= property "concept")
                               (case op
                                 "is-a" (str "<< " value)
                                 ;; FHIR spec code is "descendent-of" (with 'e');
                                 ;; "descendant-of" accepted for natural-spelling clients.
                                 ("descendent-of" "descendant-of") (str "< " value)
                                 "is-not-a" (str "* MINUS << " value)
                                 "generalizes" (str ">> " value)
                                 nil))))
                     (str/join " AND "))
                "*")
          search-params (cond-> {:constraint ecl}
                          max-hits                (assoc :max-hits max-hits)
                          (not (str/blank? text)) (assoc :s text)
                          (not active-only)       (assoc :inactive-concepts? true))
          concepts (when-not (str/blank? ecl)
                     (map (fn [{:keys [conceptId preferredTerm]}]
                            {:code    (str conceptId)
                             :system  snomed-system-uri
                             :version ver
                             :display preferredTerm})
                          (hermes/search svc search-params)))]
      {:concepts (or concepts [])}))
  protos/ValueSet
  (vs-resource [_ _params])
  (vs-expand [_ _ctx {:keys [url filter activeOnly] cnt :count ofs :offset}]
    (when-let [{:keys [ecl error message]} (parse-implicit-value-set url)]
      (if error
        {:concepts [] :total 0
         :issues   [{:severity     "error" :type "not-supported"
                     :details-code "not-implemented" :text message}]}
        (let [ver      (version-uri svc)
              ofs'     (or ofs 0)
              max-hits (when cnt (+ cnt ofs'))
              search-params (cond-> {:constraint         ecl
                                     :inactive-concepts? (if (false? activeOnly) true false)}
                              filter   (assoc :s filter)
                              max-hits (assoc :max-hits max-hits))
              results  (cond->> (hermes/search svc search-params)
                         (pos? ofs') (drop ofs'))
              concepts (mapv (fn [{:keys [conceptId term preferredTerm]}]
                               {:code         (str conceptId)
                                :system       snomed-system-uri
                                :version      ver
                                :display      preferredTerm
                                :designations [{:value term}]})
                             results)]
          (cond-> {:concepts         concepts
                   :used-codesystems [{:uri (str snomed-system-uri "|" ver) :status "active"}]
                   :compose-pins     []}
            (nil? cnt) (assoc :total (count concepts)))))))
  (vs-validate-code [_ _ctx {:keys [url code system display displayLanguage]}]
    (when (= system snomed-system-uri)
      (let [code' (parse-long code)
            ver (version-uri svc)
            {:keys [ecl query error message]} (parse-implicit-value-set url)]
        (when (or code' (expression? code))
          (cond
            error
            {:result  false :code (keyword code) :system system
             :message message
             :issues  [{:severity     "error" :type "not-supported"
                        :details-code "not-implemented" :text message}]}

            ecl
            (let [;; Validate that the implicit VS root concept/refset exists
                  vs-valid? (case query
                              :isa (let [root (parse-long (subs ecl 1))]
                                     (and root (hermes/concept svc root)))
                              :in-refset (let [refset-id (parse-long (subs ecl 1))]
                                           (and refset-id
                                                (hermes/concept svc refset-id)
                                                (hermes/subsumed-by? svc refset-id snomed/ReferenceSetConcept)))
                              true)]
              (if-not vs-valid?
                (let [msg (str "A definition for the value Set '" url "' could not be found")]
                  {:result    false
                   :not-found true
                   :code      (keyword code)
                   :system    system
                   :message   msg
                   :issues    [{:severity "error" :type "not-found" :details-code "not-found"
                                :text msg}]})
                (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                      focus-id (if code'
                                 code'
                                 (some-> (try (hermes/parse-expression svc code) (catch Exception _ nil))
                                         (get-in [:subExpression :focusConcepts])
                                         first :conceptId))
                      preferred (when focus-id (:term (hermes/preferred-synonym* svc focus-id lang-refset-ids)))
                      expr-display (when (expression? code)
                                     (try (hermes/render-expression* svc code
                                            {:terms :update :definition-status :auto
                                             :language-refset-ids lang-refset-ids})
                                          (catch Exception _ nil)))
                      display-val (or expr-display preferred)
                      member? (when focus-id (seq (hermes/intersect-ecl svc [focus-id] ecl)))]
                  (if member?
                    (let [result {:result true :display display-val :code (keyword code)
                                  :system system :version ver}]
                      (if (and display (not= display display-val))
                        (let [lang (or displayLanguage "--")
                              msg (str "Wrong Display Name '" display "' for " system "#" code
                                       " - should be '" display-val "'"
                                       " (for the language(s) '" lang "')")]
                          (assoc result :message msg
                                        :issues [{:severity     "warning"
                                                  :type         "invalid"
                                                  :details-code "invalid-display"
                                                  :text         msg
                                                  :expression   ["display"]}]))
                        result))
                    (let [code-ref (cond-> (str system "#" code)
                                     display (str " ('" display "')"))
                          msg (str "The provided code '" code-ref "' was not found in the value set '" url "'")]
                      {:result  false
                       :code    (keyword code)
                       :system  system
                       :version ver
                       :display display-val
                       :message msg
                       :issues  [{:severity     "error"
                                  :type         "code-invalid"
                                  :details-code "not-in-vs"
                                  :text         msg
                                  :expression   ["code"]}]})))))

            :else
            (let [msg (str "Cannot parse value set URL: " url)]
              {:result  false
               :message msg
               :issues  [{:severity     "error"
                          :type         "not-supported"
                          :details-code "not-found"
                          :text         msg
                          :expression   ["url"]}]}))))))
  protos/ConceptMap
  (cm-resource [_ _params])
  (cm-translate [_ _params]))