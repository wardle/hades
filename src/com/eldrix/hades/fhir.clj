(ns com.eldrix.hades.fhir
  (:import
    (org.hl7.fhir.r4.model CodeType Coding Parameters Parameters$ParametersParameterComponent StringType ValueSet$ConceptReferenceDesignationComponent ValueSet$ValueSetExpansionContainsComponent BooleanType)))

(defn parse-fhir-boolean
  "Parse a FHIR boolean from a string but with an optional default.
  See https://www.hl7.org/fhir/codesystem-data-types.html#data-types-boolean"
  [s & {:keys [default strict] :or {strict true}}]
  (cond
    (.equalsIgnoreCase "true" s) true
    (.equalsIgnoreCase "false" s) false
    (and strict (not (nil? s))) (throw (IllegalArgumentException. (str "invalid boolean '" s "' : must be 'true' or 'false'")))
    :else default))

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

(defn map->parameters
  "Turn a map into FHIR properties."
  [m]
  (when m
    (let [params (Parameters.)]
      (doseq [pc (map (fn [[k v]] (make-parameter-components k v)) m)]
        (.addParameter params pc))
      params)))



(defn map->vs-expansion
  "Create a FHIR ValueSetExpansion Component from a plain map"
  ^ValueSet$ValueSetExpansionContainsComponent [{:keys [system code display designations]}]
  (doto (ValueSet$ValueSetExpansionContainsComponent.)
    (.setCode code)
    (.setSystem system)
    (.setDisplay display)
    (.setDesignation
      (mapv #(ValueSet$ConceptReferenceDesignationComponent. (StringType. %)) designations))))

(comment
  (def svc (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (hermes/get-extended-concept svc 24700007)
  (hermes/get-release-information svc)
  (hermes/get-concept svc 163271000000103)
  (hermes/get-preferred-synonym svc 900000000000013009 "en-GB")

  (hermes/search svc {:constraint "<<50043002:<<263502005=<<19939008"})
  (= {:query :in-refset :ecl "^123"} (parse-implicit-value-set "http://snomed.info/sct?fhir_vs=refset/123"))
  (= {:query :isa :ecl "<24700007"} (parse-implicit-value-set "http://snomed.info/sct?fhir_vs=isa/24700007"))
  (= {:query :all :ecl "*"} (parse-implicit-value-set "http://snomed.info/sct?fhir_vs"))
  (= nil (parse-implicit-value-set "http://snomed.info/sct?fhirvs=refset/123"))
  (= {:query :ecl :ecl "<<50043002:<<263502005=<<19939008"}
     (parse-implicit-value-set "http://snomed.info/sct?fhir_vs=ecl/<<50043002:<<263502005=<<19939008"))
  (hermes/get-preferred-synonym svc 19939008 "en")

  (def ctx (ca.uhn.fhir.context.FhirContext/forR4))
  (def parser (doto (.newJsonParser ctx)
                (.setPrettyPrint true)))
  (.encodeResourceToString parser (map->parameters test-map)))
