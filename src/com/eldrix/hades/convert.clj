(ns com.eldrix.hades.convert
  (:require [com.eldrix.hermes.service :as svc]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (org.hl7.fhir.r4.model Parameters Base Parameters$ParametersParameterComponent StringType BooleanType CodeableConcept Coding CodeType)
           (java.util List)
           (clojure.lang Keyword)
           (com.eldrix.hermes.service SnomedService)
           (com.eldrix.hermes.snomed Description)
           (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

(def snomed-system-uris
  #{"http://snomed.info/sct" "https://snomed.info/sct"})

(comment


  (require '[clojure.walk :as walk])
  (walk/postwalk-demo fake-parameters)

  (reduce-kv (fn [r k v] (.addParameter r (make-parameter-component k v))) (Parameters.) fake-parameters)
  )


(defn ^Parameters make-extended-concept-parameters
  [{:keys [concept descriptions preferred-description parent-relationships direct-parent-relationships refsets]}]
  (doto (Parameters.)
    (.setParameter "name" "SNOMED CT")                      ;;  TODO: get metadata from our index
    (.setParameter "version" "LATEST")                      ;; TODO: include version string http://snomed.info/sct/xxxxxxxx/version/yyyymmdd
    (.setParameter "display" ^String (:term preferred-description))
    (.addParameter (doto (Parameters$ParametersParameterComponent. (StringType. "property"))
                     (-> (.addPart)
                         (.setName "code")
                         (.setValue (CodeType. "parent")))
                     (-> (.addPart)
                         (.setName "value")
                         (.setValue (CodeType. "278844005")))))
    (.addParameter (doto (Parameters$ParametersParameterComponent. (StringType. "designation"))
                     (-> (.addPart)
                         (.setName "language")
                         (.setValue (CodeType. "en")))
                     (-> (.addPart)
                         (.setName "use")
                         (.setValue (Coding. "http://snomed.info/sct" "900000000000013009" "Synonym")))
                     (-> (.addPart)
                         (.setName "value")
                         (.setValue (StringType. "Gender")))
                     ))))

(def test-map {"name"        "SNOMED CT"
               "version"     "LATEST"
               "display"     "Gender"
               "property"    [{:code  :parent
                               :value :278844005}
                              {:code  :moduleId
                               :value :900000000000207008}
                              {:code  :sufficientlyDefined
                               :value true}]
               "designation" [{"language" :en
                               "use"      {:system "http://snomed.info/sct" :code "900001309" :display "Synonym"}
                               "display"  "Gender"}
                              {"language" :en
                               "use"      {:system "http://snomed.info/sct" :code "900001307" :display "Fully specified name"}
                               "display"  "Gender (Observable entity)"}]})

(defn- make-parameter-components
  [k v]
  (let [pc (Parameters$ParametersParameterComponent. (StringType. (name k)))]
    (cond
      (string? v)
      (.setValue pc (StringType. v))
      (number? v)
      (.setValue pc (StringType. (str v)))
      (boolean? v)
      (.setValue pc (BooleanType. ^Boolean v))
      (keyword? v)
      (.setValue pc (CodeType. (name v)))
      (and (map? v) (contains? v :code) (contains? v :system))
      (.setValue pc (Coding. (name (:system v)) (name (:code v)) (:display v)))
      (map? v)
      (let [parts (map (fn [[k2 v2]] (make-parameter-components k2 v2)) v)]
        (.setPart pc parts))
      (seqable? v)
      (let [parts (map (fn [m] (make-parameter-components k m)) v)]
        (.setPart pc parts)))))

(defn make-parameters
  "Turn a map into FHIR properties."
  [m]
  (let [params (Parameters.)]
    (doseq [pc (map (fn [[k v]] (make-parameter-components k v)) m)]
      (.addParameter params pc))
    params))


(defn description->params                                   ;;TODO: lookup display from the locale in the request rather than hard-coding.
  "Turn a SNOMED description into a parameter map."
  [^Description d]
  {"language" (:languageCode d)
   "use"      {:system  "http://snomed.info/sct"
               :code    (str (:typeId d))
               :display (cond
                          (= (:typeId d) snomed/Synonym) "Synonym"
                          (= (:typeId d) snomed/FullySpecifiedName) "Fully specified name")}
   "display"  (:term d)})

(defn lookup
  "Lookup a SNOMED code.
  Returns properties as per https://www.hl7.org/fhir/terminology-service.html#standard-props."
  [& {:keys [^SnomedService svc ^String system ^long code ^String displayLanguage]}]
  (when (contains? snomed-system-uris system)
    (let [result (svc/getExtendedConcept svc code)
          preferred-description ^String (:term (svc/getPreferredSynonym svc code (or (when displayLanguage displayLanguage) "en-GB")))
          core-release-information (first (svc/getReleaseInformation svc))]
      (make-parameters
        {"system"      (:term core-release-information)
         "version"     (str "http://snomed.info/sct/" (:moduleId core-release-information) "/" (.format (DateTimeFormatter/BASIC_ISO_DATE) (:effectiveTime core-release-information)))     ;; FIXME: version from module from the concept at hand?
         "display"     preferred-description
         "property"    (concat
                         [{:code  :inactive
                           :value (not (get-in result [:concept :active]))}
                          {:code  :sufficientlyDefined
                           :value (= snomed/Defined (get-in result [:concept :definitionStatusId]))}
                          {:code  :moduleId
                           :value (keyword (str (get-in result [:concept :moduleId])))}]
                         (let [parents (get-in result [:direct-parent-relationships com.eldrix.hermes.snomed/IsA])]
                           (map #(hash-map :code :parent :value %) parents))
                         (let [children (get-in result [:direct-child-relationships com.eldrix.hermes.snomed/IsA])]
                           (map #(hash-map :code :parent :value %) children)))
         "designation" (map description->params (:descriptions result))}))))



(comment
  (def svc (com.eldrix.hermes.terminology/open "/Users/mark/Dev/hermes/snomed.db"))
  (require '[com.eldrix.hermes.service :as svc])
  (svc/getExtendedConcept svc 24700007)
  (svc/getReleaseInformation svc)
  (svc/getConcept svc 163271000000103)
  (filter #(= (:moduleId (svc/getConcept svc 163271000000103)) (:moduleId %)) (svc/getReleaseInformation svc))
  )