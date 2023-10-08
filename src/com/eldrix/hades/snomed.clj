(ns com.eldrix.hades.snomed
  "Implementation of Hades protocols for SNOMED CT.
  A thin wrapper around the Hermes SNOMED terminology service"
  (:require [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (ca.uhn.fhir.rest.server.exceptions NotImplementedOperationException)
           (com.eldrix.hermes.snomed Description)
           (java.time.format DateTimeFormatter)))

(def snomed-system-uri "http://snomed.info/sct")

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
  (let [[_ _ edition _ version query] (re-matches #"http://snomed.info/sct(/(\d*))?(/version/(\d{8}))?\?fhir_vs(.*)" uri)]
    (cond
      (or edition version)
      (throw (NotImplementedOperationException. "Implicit value sets with edition/version not supported."))
      (nil? query)
      nil
      (= query "")
      {:query :all, :ecl "*"}
      (str/starts-with? query "=isa/")
      {:query :isa, :ecl (str "<" (subs query 5))}
      (= "=refset" query)
      {:query :refsets, :ecl (str "<" snomed/ReferenceSetConcept)}
      (str/starts-with? query "=refset/")
      {:query :in-refset, :ecl (str "^" (subs query 8))}
      (str/starts-with? query "=ecl/")
      {:query :ecl, :ecl (subs query 5)}
      :else
      (throw (NotImplementedOperationException. (str "Implicit valueset for '" uri "' not implemented"))))))


(deftype HermesService [svc]
  protos/CodeSystem
  (cs-resource [this params])
  (cs-lookup
    [this {:keys [code displayLanguage]}]
    (when-let [code' (parse-long code)]
      (let [result (hermes/extended-concept svc code')
            lang-refset-ids (hermes/match-locale svc displayLanguage true)
            preferred-description ^String (:term (hermes/preferred-synonym* svc code' lang-refset-ids))
            usage-descriptions {snomed/Synonym            (hermes/preferred-synonym* svc snomed/Synonym lang-refset-ids)
                                snomed/FullySpecifiedName (hermes/preferred-synonym* svc snomed/FullySpecifiedName lang-refset-ids)}
            core-release-information (first (hermes/release-information svc))]
        {"name"        (:term core-release-information)
         "version"     (str snomed-system-uri (:moduleId core-release-information) "/" (.format (DateTimeFormatter/BASIC_ISO_DATE) (:effectiveTime core-release-information))) ;; FIXME: version from module from the concept at hand?
         "display"     preferred-description
         "property"    (concat
                         [{:code  :inactive
                           :value (not (get-in result [:concept :active]))}
                          {:code  :sufficientlyDefined
                           :value (= snomed/Defined (get-in result [:concept :definitionStatusId]))}
                          {:code  :moduleId
                           :value (get-in result [:concept :moduleId])}]
                         (let [parents (get-in result [:direct-parent-relationships com.eldrix.hermes.snomed/IsA])]
                           (map #(hash-map :code :parent :value %) parents))
                         (let [children (get-in result [:direct-child-relationships com.eldrix.hermes.snomed/IsA])]
                           (map #(hash-map :code :child :value %) children))
                         (reduce (fn [acc {:keys [typeId relationshipGroup value]}]
                                   (conj acc {:code    typeId
                                              :display (:term (hermes/preferred-synonym* svc typeId lang-refset-ids))
                                              :value   value :group relationshipGroup})) [] (hermes/concrete-values svc code')))
         "designation" (map #(description->params % usage-descriptions) (:descriptions result))})))
  (cs-validate-code [this params])
  (cs-subsumes [this {:keys [systemA codeA systemB codeB]}]
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
  (cs-find-matches [this params])
  protos/ValueSet
  (vs-resource [this params])
  (vs-expand [this {:keys [url filter activeOnly]}]
    (when-let [{ecl :ecl} (parse-implicit-value-set url)]
      (->> (hermes/search svc (cond-> {:constraint         ecl
                                       :inactive-concepts? (if (false? activeOnly) true false)}
                                filter (assoc :s filter)))
           (map (fn [{:keys [conceptId term preferredTerm]}]
                  (hash-map :code (str conceptId)
                            :system snomed-system-uri
                            :display preferredTerm
                            :designations [term]))))))
  (vs-validate-code [this params])
  protos/ConceptMap
  (cm-resource [this params])
  (cm-translate [this params]))