(ns com.eldrix.hades.fhir
  (:require [clojure.data.json :as json])
  (:import
    (ca.uhn.fhir.context FhirContext)
    (org.hl7.fhir.instance.model.api IBaseResource)
    (org.hl7.fhir.r4.model CanonicalType CodeType CodeableConcept Coding
                            OperationOutcome OperationOutcome$IssueSeverity OperationOutcome$IssueType
                            OperationOutcome$OperationOutcomeIssueComponent
                            Parameters Parameters$ParametersParameterComponent
                            StringType UriType
                            ValueSet$ConceptReferenceDesignationComponent
                            ValueSet$ValueSetExpansionContainsComponent BooleanType)))

(defonce ^:private r4-context (delay (FhirContext/forR4)))

(defn resource->map
  "Convert a HAPI IBaseResource to a plain Clojure map with string keys."
  [^IBaseResource resource]
  (json/read-str (.encodeResourceToString (.newJsonParser ^FhirContext @r4-context) resource)))

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

;; ---------------------------------------------------------------------------
;; OperationOutcome building (for validate-code issues)
;; ---------------------------------------------------------------------------

(defn- issue-severity ^OperationOutcome$IssueSeverity [s]
  (case s
    "fatal"       OperationOutcome$IssueSeverity/FATAL
    "error"       OperationOutcome$IssueSeverity/ERROR
    "warning"     OperationOutcome$IssueSeverity/WARNING
    "information" OperationOutcome$IssueSeverity/INFORMATION))

(defn- issue-type ^OperationOutcome$IssueType [t]
  (case t
    "code-invalid"  OperationOutcome$IssueType/CODEINVALID
    "invalid"       OperationOutcome$IssueType/INVALID
    "not-found"     OperationOutcome$IssueType/NOTFOUND
    "not-supported" OperationOutcome$IssueType/NOTSUPPORTED
    "business-rule" OperationOutcome$IssueType/BUSINESSRULE
    "exception"     OperationOutcome$IssueType/EXCEPTION
    OperationOutcome$IssueType/PROCESSING))

(defn- build-issue-component
  ^OperationOutcome$OperationOutcomeIssueComponent
  [{:keys [severity type details-code text expression]}]
  (let [ic (OperationOutcome$OperationOutcomeIssueComponent.)
        cc (doto (CodeableConcept.)
             (.addCoding (doto (Coding.)
                           (.setSystem "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                           (.setCode ^String details-code)))
             (.setText text))]
    (.setSeverity ic (issue-severity severity))
    (.setCode ic (issue-type type))
    (.setDetails ic cc)
    (doseq [e expression]
      (.addExpression ic e))
    ic))

(defn build-operation-outcome
  "Build a HAPI OperationOutcome from a sequence of issue maps.
  Each issue: {:severity :type :details-code :text :expression}"
  ^OperationOutcome [issues]
  (let [oo (OperationOutcome.)]
    (doseq [issue issues]
      (.addIssue oo (build-issue-component issue)))
    oo))

;; ---------------------------------------------------------------------------
;; Parameters building
;; ---------------------------------------------------------------------------

(def ^:private uri-parameter-names
  #{"system" "url" "source" "target" "targetSystem"})

(def ^:private canonical-parameter-names
  #{"x-caused-by-unknown-system"})

(defn- make-parameter-component
  [k v]
  (let [pc (Parameters$ParametersParameterComponent. (StringType. (name k)))]
    (cond
      (instance? IBaseResource v)
      (.setResource pc v)
      (instance? org.hl7.fhir.r4.model.Type v)
      (.setValue pc v)
      (and (string? v) (contains? canonical-parameter-names (name k)))
      (.setValue pc (CanonicalType. ^String v))
      (and (string? v) (contains? uri-parameter-names (name k)))
      (.setValue pc (UriType. v))
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
      (let [parts (map (fn [[k2 v2]] (make-parameter-component k2 v2)) v)]
        (.setPart pc parts)))
    pc))

(defn- expand-parameter
  "Expand a key-value pair into a sequence of parameter components.
  Sequences produce one component per element; scalars produce one."
  [[k v]]
  (if (sequential? v)
    (map (fn [item] (make-parameter-component k item)) v)
    [(make-parameter-component k v)]))

(defn map->parameters
  "Turn a map into FHIR parameters.
  If the map contains an \"issues\" key with a sequential value (vector of issue
  maps), converts it to a HAPI OperationOutcome resource before serialisation."
  [m]
  (when m
    (let [issues (get m "issues")
          m' (if (sequential? issues)
               (assoc m "issues" (build-operation-outcome issues))
               m)
          params (Parameters.)]
      (doseq [pc (mapcat expand-parameter m')]
        (.addParameter params pc))
      params)))


(defn map->vs-expansion
  "Create a FHIR ValueSetExpansion Component from a plain map.
  Options:
    :include-designations — when true, include designation list"
  ^ValueSet$ValueSetExpansionContainsComponent [{:keys [system code display designations abstract inactive]}
                                                & {:keys [include-designations]}]
  (let [comp (doto (ValueSet$ValueSetExpansionContainsComponent.)
               (.setCode code)
               (.setSystem system)
               (.setDisplay display))]
    (when abstract (.setAbstract comp true))
    (when inactive (.setInactive comp true))
    (when (and include-designations (seq designations))
      (.setDesignation comp
        (mapv (fn [d]
                (let [dc (ValueSet$ConceptReferenceDesignationComponent.)]
                  (if (map? d)
                    (do (.setValue dc (str (get d "value")))
                        (when-let [lang (get d "language")]
                          (.setLanguage dc (str lang)))
                        dc)
                    (doto dc (.setValue (str d))))))
              designations)))
    comp))

(comment
  ;; import using plain ol' data
  (require '[clojure.java.io :as io])
  (def fhir-valuesets (json/read (io/reader (io/file "/Users/mark/Downloads/definitions/valuesets.json")) :key-fn keyword))
  (def entries (reduce (fn [acc {:keys [fullUrl] :as entry}] (assoc acc fullUrl entry) ) {} (:entry fhir-valuesets)))
  (get entries "http://hl7.org/fhir/CodeSystem/unit-of-presentation")
  (into #{} (map #(get-in % [:resource :resourceType]) (vals entries)))
  (def codesystems (filter (fn [entry]
                             (= "CodeSystem" (get-in entry [:resource :resourceType]))) (vals entries)))
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

