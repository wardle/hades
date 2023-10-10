(ns com.eldrix.hades.fhir
  (:require [clojure.data.json :as json])
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
  ;; import using plain ol' data
  (require '[clojure.data.json :as json])
  (require '[clojure.java.io :as io])
  (def fhir-valuesets (json/read (io/reader (io/file "/Users/mark/Downloads/definitions/valuesets.json")) :key-fn keyword))
  (def entries (reduce (fn [acc {:keys [fullUrl] :as entry}] (assoc acc fullUrl entry) ) {} (:entry fhir-valuesets)))

  ;; import using HAPI (r4)
  (import org.hl7.fhir.r4.model.Bundle)
  (def ctx (ca.uhn.fhir.context.FhirContext/forR4))
  (def parser (.newJsonParser ctx))

  (def bundle (.parseResource parser Bundle (io/reader (io/file "/Users/mark/Downloads/definitions/valuesets.json"))))

;; import using HAPI (r5)
  (import org.hl7.fhir.r5.model.Bundle)
  (def ctx (ca.uhn.fhir.context.FhirContext/forR5))
  (def parser (.newJsonParser ctx))

  (def bundle (.parseResource parser Bundle (io/reader (io/file "/Users/mark/Downloads/definitions/valuesets.json")))))

