(ns com.eldrix.hades.snomed
  "Implementation of Hades protocols for SNOMED CT.
  A thin wrapper around the Hermes SNOMED terminology service"
  (:require [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [lambdaisland.uri :as uri]
            [lambdaisland.uri.normalize :as normalize])
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
      {:error :not-implemented
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
      {:error :not-implemented
       :message (str "Implicit valueset for '" uri "' not implemented")})))

(deftype HermesService
  [svc]
  protos/CodeSystem
  (cs-resource [this params])
  (cs-lookup
    [_ {:keys [code displayLanguage]}]
    (when-let [code' (parse-long code)]
      (when-let [ec (hermes/extended-concept svc code')]
        (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
            preferred (:term (hermes/preferred-synonym* svc code' lang-refset-ids))
            usage {snomed/Synonym            (hermes/preferred-synonym* svc snomed/Synonym lang-refset-ids)
                   snomed/FullySpecifiedName  (hermes/preferred-synonym* svc snomed/FullySpecifiedName lang-refset-ids)}
            ver (version-uri svc)
            parents (get-in ec [:directParentRelationships snomed/IsA])
            children (hermes/child-relationships-of-type svc code' snomed/IsA)
            attrs (dissoc (:directParentRelationships ec) snomed/IsA)]
        {:name    "SNOMED CT"
         :version ver
         :display preferred
         :system  snomed-system-uri
         :code    (keyword code)
         :properties (concat
                     [{:code :inactive :value (not (get-in ec [:concept :active]))}
                      {:code :sufficientlyDefined :value (= snomed/Defined (get-in ec [:concept :definitionStatusId]))}]
                     (map (fn [pid] {:code :parent
                                     :value (keyword (str pid))
                                     :description (:term (hermes/preferred-synonym* svc pid lang-refset-ids))})
                          parents)
                     (map (fn [cid] {:code :child
                                     :value (keyword (str cid))
                                     :description (:term (hermes/preferred-synonym* svc cid lang-refset-ids))})
                          children)
                     (mapcat (fn [[type-id target-ids]]
                               (map (fn [tid] {:code (keyword (str type-id))
                                               :code-display (:term (hermes/preferred-synonym* svc type-id lang-refset-ids))
                                               :value (keyword (str tid))
                                               :description (:term (hermes/preferred-synonym* svc tid lang-refset-ids))})
                                    target-ids))
                             attrs)
                     (map (fn [{:keys [typeId value]}]
                            {:code (keyword (str typeId))
                             :code-display (:term (hermes/preferred-synonym* svc typeId lang-refset-ids))
                             :value value})
                          (hermes/concrete-values svc code')))
         :designations (map (fn [d] {:language (keyword (:languageCode d))
                                     :use {:system snomed-system-uri
                                           :code (str (:typeId d))
                                           :display (:term (get usage (:typeId d)))}
                                     :value (:term d)})
                           (:descriptions ec))}))))
  (cs-validate-code [_ {:keys [code display displayLanguage]}]
    (when-let [code' (parse-long code)]
      (let [ver (version-uri svc)]
        (if-let [concept (hermes/concept svc code')]
          (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                preferred (:term (hermes/preferred-synonym* svc code' lang-refset-ids))
                inactive? (not (:active concept))
                result (cond-> {:result true :display preferred :code (keyword code)
                                :system snomed-system-uri :version ver}
                         inactive? (assoc :inactive true))]
            (if (and display (not= display preferred))
              (let [msg (str "Display '" display "' not found for code '" code "'")]
                (assoc result :result false
                              :message msg
                              :issues [{:severity     "error"
                                        :type         "invalid"
                                        :details-code "invalid-display"
                                        :text         msg
                                        :expression   ["display"]}]))
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
                        :expression   ["code"]}]})))))

  (cs-subsumes
    [this {:keys [systemA codeA systemB codeB]}]
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
  (cs-find-matches [_ {:keys [filters]}]
    (let [ver (version-uri svc)
          ecl (if (seq filters)
                (->> filters
                     (keep (fn [{:keys [property op value]}]
                             (when (= property "concept")
                               (case op
                                 "is-a" (str "<< " value)
                                 "descendant-of" (str "< " value)
                                 "is-not-a" (str "* MINUS << " value)
                                 "generalizes" (str ">> " value)
                                 nil))))
                     (str/join " AND "))
                "*")]
      (when-not (str/blank? ecl)
        (->> (hermes/search svc {:constraint ecl})
             (map (fn [{:keys [conceptId preferredTerm]}]
                    {:code    (str conceptId)
                     :system  snomed-system-uri
                     :version ver
                     :display preferredTerm}))))))
  protos/ValueSet
  (vs-resource [this params])
  (vs-expand [this _ctx {:keys [url filter activeOnly]}]
    (when-let [{:keys [ecl error message]} (parse-implicit-value-set url)]
      (if error
        {:concepts [] :total 0
         :issues [{:severity "error" :type "not-supported"
                   :details-code "not-implemented" :text message}]}
        (let [ver (version-uri svc)
              concepts (mapv (fn [{:keys [conceptId term preferredTerm]}]
                               (hash-map :code (str conceptId)
                                         :system snomed-system-uri
                                         :version ver
                                         :display preferredTerm
                                         :designations [term]))
                             (hermes/search svc (cond-> {:constraint         ecl
                                                         :inactive-concepts? (if (false? activeOnly) true false)}
                                                 filter (assoc :s filter))))]
          {:concepts         concepts
           :total            (count concepts)
           :used-codesystems [{:uri (str snomed-system-uri "|" ver) :status "active"}]
           :compose-pins     []}))))
  (vs-validate-code [_ _ctx {:keys [url code system display displayLanguage]}]
    (when (= system snomed-system-uri)
      (when-let [code' (parse-long code)]
        (let [ver (version-uri svc)
              {:keys [ecl error message]} (parse-implicit-value-set url)]
          (cond
            error
            {:result  false :code (keyword code) :system system
             :message message
             :issues  [{:severity "error" :type "not-supported"
                        :details-code "not-implemented" :text message}]}

            ecl
            (let [lang-refset-ids (hermes/match-locale svc displayLanguage true)
                  preferred (:term (hermes/preferred-synonym* svc code' lang-refset-ids))
                  member? (seq (hermes/intersect-ecl svc [code'] ecl))]
              (if member?
                (let [result {:result true :display preferred :code (keyword code)
                              :system system :version ver}]
                  (if (and display (not= display preferred))
                    (let [msg (str "Display '" display "' differs from preferred '" preferred "'")]
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
                   :message msg
                   :issues  [{:severity     "error"
                              :type         "code-invalid"
                              :details-code "not-in-vs"
                              :text         msg
                              :expression   ["code"]}]})))

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
  (cm-resource [this params])
  (cm-translate [this params]))