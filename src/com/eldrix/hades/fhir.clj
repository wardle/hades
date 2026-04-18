(ns com.eldrix.hades.fhir
  (:require [clojure.data.json :as json])
  (:import
    (ca.uhn.fhir.context FhirContext)
    (org.hl7.fhir.instance.model.api IBaseResource)
    (org.hl7.fhir.r4.model BooleanType CanonicalType CodeType CodeableConcept Coding
                            IntegerType
                            OperationOutcome OperationOutcome$IssueSeverity OperationOutcome$IssueType
                            OperationOutcome$OperationOutcomeIssueComponent
                            Parameters Parameters$ParametersParameterComponent
                            StringType UriType
                            ValueSet$ConceptReferenceDesignationComponent
                            ValueSet$ValueSetExpansionContainsComponent
                            ValueSet$ValueSetExpansionParameterComponent)))

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
    "informational" OperationOutcome$IssueType/INFORMATIONAL
    "too-costly"    OperationOutcome$IssueType/TOOCOSTLY
    OperationOutcome$IssueType/PROCESSING))

(defn- build-issue-component
  ^OperationOutcome$OperationOutcomeIssueComponent
  [{:keys [severity type details-code text expression]}]
  (let [ic (OperationOutcome$OperationOutcomeIssueComponent.)
        cc (doto (CodeableConcept.)
             (.setText text))]
    (when details-code
      (.addCoding cc (doto (Coding.)
                       (.setSystem "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type")
                       (.setCode ^String details-code))))
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
;; Issue expression adjustment
;; ---------------------------------------------------------------------------

(defn adjust-issue-expressions
  "Adjust FHIRPath expressions in issues for the input mode.
  Protocol impls use canonical Coding.* expressions. This adjusts them:
    :code            → strip 'Coding.' prefix (bare 'code', 'display', etc.)
    :coding          → keep as-is ('Coding.code', 'Coding.display')
    :codeableConcept → replace 'Coding.' with 'CodeableConcept.coding[N].'
  Only adjusts expressions starting with 'Coding' — already-adjusted paths
  are left unchanged."
  [issues input-mode coding-index]
  (let [coding-index (or coding-index 0)]
    (case input-mode
      :code (mapv (fn [i]
                    (update i :expression
                            (fn [exprs]
                              (mapv (fn [e]
                                      (cond
                                        (clojure.string/starts-with? e "Coding.")
                                        (subs e 7)
                                        (= e "Coding") "code"
                                        :else e))
                                    exprs))))
                  issues)
      :codeableConcept (mapv (fn [i]
                              (let [idx (or (:coding-index i) coding-index)
                                    prefix (str "CodeableConcept.coding[" idx "]")]
                                (-> (update i :expression
                                            (fn [exprs]
                                              (mapv (fn [e]
                                                      (cond
                                                        (clojure.string/starts-with? e "Coding.")
                                                        (str prefix "." (subs e 7))
                                                        (= e "Coding") prefix
                                                        :else e))
                                                    exprs)))
                                    (dissoc :coding-index))))
                            issues)
      issues)))

;; ---------------------------------------------------------------------------
;; Parameters building — explicit converters per result type
;; ---------------------------------------------------------------------------

(defn- add-param
  "Add a named parameter to a Parameters resource."
  [^Parameters params ^String param-name value]
  (let [pc (Parameters$ParametersParameterComponent. (StringType. param-name))]
    (cond
      (instance? IBaseResource value) (.setResource pc value)
      (instance? org.hl7.fhir.r4.model.Type value) (.setValue pc value)
      (string? value) (.setValue pc (StringType. ^String value))
      (boolean? value) (.setValue pc (BooleanType. ^Boolean value))
      (keyword? value) (.setValue pc (CodeType. (name value))))
    (.addParameter params pc))
  params)

(defn- add-uri-param [^Parameters params ^String param-name ^String value]
  (let [pc (Parameters$ParametersParameterComponent. (StringType. param-name))]
    (.setValue pc (UriType. value))
    (.addParameter params pc))
  params)

(defn- add-canonical-param [^Parameters params ^String param-name ^String value]
  (let [pc (Parameters$ParametersParameterComponent. (StringType. param-name))]
    (.setValue pc (CanonicalType. value))
    (.addParameter params pc))
  params)

(defn- add-property-param
  "Add a property parameter with code/value/description parts."
  [^Parameters params {:keys [code value description code-display]}]
  (let [pc (Parameters$ParametersParameterComponent. (StringType. "property"))
        code-part (doto (Parameters$ParametersParameterComponent. (StringType. "code"))
                    (.setValue (CodeType. (name code))))
        value-part (let [vp (Parameters$ParametersParameterComponent. (StringType. "value"))]
                     (cond
                       (boolean? value) (.setValue vp (BooleanType. ^Boolean value))
                       (keyword? value) (.setValue vp (CodeType. (name value)))
                       (string? value) (.setValue vp (StringType. ^String value))
                       (number? value) (.setValue vp (StringType. (str value))))
                     vp)]
    (.addPart pc code-part)
    (.addPart pc value-part)
    (when description
      (.addPart pc (doto (Parameters$ParametersParameterComponent. (StringType. "description"))
                     (.setValue (StringType. ^String (str description))))))
    (when code-display
      (.addPart pc (doto (Parameters$ParametersParameterComponent. (StringType. "display"))
                     (.setValue (StringType. ^String (str code-display))))))
    (.addParameter params pc))
  params)

(defn- add-designation-param
  "Add a designation parameter with language/value/use parts."
  [^Parameters params {:keys [language value use]}]
  (let [pc (Parameters$ParametersParameterComponent. (StringType. "designation"))]
    (when language
      (.addPart pc (doto (Parameters$ParametersParameterComponent. (StringType. "language"))
                     (.setValue (CodeType. (name language))))))
    (when use
      (.addPart pc (doto (Parameters$ParametersParameterComponent. (StringType. "use"))
                     (.setValue (Coding. (str (:system use)) (str (:code use)) (:display use))))))
    (.addPart pc (doto (Parameters$ParametersParameterComponent. (StringType. "value"))
                   (.setValue (StringType. ^String (str value)))))
    (.addParameter params pc))
  params)

(defn validate-result->parameters
  "Convert a ::protos/validate-result to HAPI Parameters."
  ^Parameters [{:keys [result code system version display message
                        inactive inactive-status normalized-code issues
                        x-caused-by-unknown-system x-unknown-system
                        codeableConcept]}]
  (let [params (Parameters.)]
    (when code (add-param params "code" code))
    (when codeableConcept (add-param params "codeableConcept" codeableConcept))
    (when display (add-param params "display" display))
    (when inactive (add-param params "inactive" inactive))
    (when (seq issues) (add-param params "issues" (build-operation-outcome issues)))
    (when message (add-param params "message" message))
    (when normalized-code (add-param params "normalized-code" normalized-code))
    (add-param params "result" result)
    (when (and inactive-status (not= "inactive" inactive-status))
      (add-param params "status" (keyword inactive-status)))
    (when system (add-uri-param params "system" system))
    (when version (add-param params "version" version))
    (when x-caused-by-unknown-system
      (add-canonical-param params "x-caused-by-unknown-system" x-caused-by-unknown-system))
    (when x-unknown-system
      (add-canonical-param params "x-unknown-system" x-unknown-system))
    params))

(defn lookup-result->parameters
  "Convert a ::protos/lookup-result to HAPI Parameters."
  ^Parameters [{:keys [name version display system code definition abstract
                        properties designations]}]
  (let [params (Parameters.)]
    (when name (add-param params "name" name))
    (when version (add-param params "version" version))
    (when display (add-param params "display" display))
    (when system (add-uri-param params "system" system))
    (when code (add-param params "code" code))
    (when definition (add-param params "definition" definition))
    (when (some? abstract) (add-param params "abstract" abstract))
    (doseq [p properties] (add-property-param params p))
    (doseq [d designations] (add-designation-param params d))
    params))

(defn subsumes-result->parameters
  "Convert a subsumes result to HAPI Parameters."
  ^Parameters [{:keys [outcome]}]
  (doto (Parameters.)
    (add-param "outcome" outcome)))


(defn map->vs-expansion
  "Create a FHIR ValueSetExpansion Component from a plain map.
  Options:
    :include-designations — when true, include designation list"
  ^ValueSet$ValueSetExpansionContainsComponent [{:keys [system version code display designations abstract inactive properties]}
                                                & {:keys [include-designations]}]
  (let [comp (doto (ValueSet$ValueSetExpansionContainsComponent.)
               (.setCode code)
               (.setSystem system)
               (.setDisplay display))]
    (when version (.setVersion comp ^String version))
    (when abstract (.setAbstract comp true))
    (when inactive (.setInactive comp true))
    (when (and include-designations (seq designations))
      (.setDesignation comp
        (mapv (fn [{:keys [value language use] :as d}]
                (let [dc (ValueSet$ConceptReferenceDesignationComponent.)]
                  (.setValue dc (str (or value d)))
                  (when language (.setLanguage dc (if (keyword? language) (name language) (str language))))
                  (when use
                    (.setUse dc (Coding. (str (:system use)) (str (:code use)) (:display use))))
                  dc))
              designations)))
    (when (seq properties)
      (doseq [{:keys [code value]} properties]
        (let [ext (org.hl7.fhir.r4.model.Extension.
                    "http://hl7.org/fhir/5.0/StructureDefinition/extension-ValueSet.expansion.contains.property")
              code-str (if (keyword? code) (name code) (str code))]
          (.addExtension ext (doto (org.hl7.fhir.r4.model.Extension. "code")
                               (.setValue (CodeType. ^String code-str))))
          (let [val-ext (org.hl7.fhir.r4.model.Extension. "value")]
            (cond
              (boolean? value) (.setValue val-ext (BooleanType. ^Boolean value))
              (keyword? value) (.setValue val-ext (CodeType. (name value)))
              (string? value) (.setValue val-ext (StringType. ^String value))
              (number? value) (.setValue val-ext (IntegerType. (int value))))
            (.addExtension ext val-ext))
          (.addExtension comp ext))))
    comp))

(defn- expansion-param [^String name value]
  (doto (ValueSet$ValueSetExpansionParameterComponent.)
    (.setName name)
    (.setValue value)))

(defn build-version-echo-params
  "Build expansion parameter components for version-echo params.
  Echoes force-system-version always; system-version/check-system-version
  only for systems not pinned by the compose."
  [{:keys [force-system-version system-version check-system-version compose-pinned]}]
  (cond-> []
    force-system-version
    (into (map (fn [[sys ver]]
                 (expansion-param "force-system-version" (UriType. ^String (str sys "|" ver)))))
          force-system-version)
    system-version
    (into (keep (fn [[sys ver]]
                  (when-not (contains? compose-pinned sys)
                    (expansion-param "system-version" (UriType. ^String (str sys "|" ver))))))
          system-version)
    check-system-version
    (into (keep (fn [[sys ver]]
                  (when-not (contains? compose-pinned sys)
                    (expansion-param "check-system-version" (UriType. ^String (str sys "|" ver))))))
          check-system-version)))

(defn build-cs-warning-params
  "Build expansion warning parameters for CodeSystem status.
  Emits warning-experimental / warning-draft / warning-deprecated /
  warning-withdrawn based on the resource status and the
  structuredefinition-standards-status extension."
  [used-codesystems]
  (mapcat (fn [{:keys [uri status experimental standards-status]}]
            (cond-> []
              experimental
              (conj (expansion-param "warning-experimental" (UriType. ^String uri)))
              (or (= "draft" status) (= "draft" standards-status))
              (conj (expansion-param "warning-draft" (UriType. ^String uri)))
              (or (= "retired" status) (= "deprecated" standards-status))
              (conj (expansion-param "warning-deprecated" (UriType. ^String uri)))
              (= "withdrawn" standards-status)
              (conj (expansion-param "warning-withdrawn" (UriType. ^String uri)))))
          used-codesystems))

(defn build-vs-warning-params
  "Build expansion warning parameters for ValueSet status.
  Draws on both resource status and the structuredefinition-standards-status
  extension."
  [vs-meta vs-version-uri]
  (let [{:keys [status standards-status]} vs-meta]
    (cond-> []
      (or (= "retired" status) (= "deprecated" standards-status))
      (conj (expansion-param "warning-deprecated" (UriType. ^String vs-version-uri)))
      (= "withdrawn" standards-status)
      (conj (expansion-param "warning-withdrawn" (UriType. ^String vs-version-uri))))))

(defn build-used-codesystem-params
  "Build used-codesystem expansion parameters."
  [used-codesystems]
  (mapv (fn [{:keys [uri]}]
          (expansion-param "used-codesystem" (UriType. ^String uri)))
        used-codesystems))

(defn build-echo-params
  "Build echo expansion parameters (displayLanguage, excludeNested, etc.)."
  [{:keys [display-lang excludeNested exclude-nested? include-desig? active-only?
           param-filter param-count offset]}]
  (cond-> []
    (some? display-lang)
    (conj (expansion-param "displayLanguage" (CodeType. ^String display-lang)))
    excludeNested
    (conj (expansion-param "excludeNested" (BooleanType. (boolean exclude-nested?))))
    (some? include-desig?)
    (conj (expansion-param "includeDesignations" (BooleanType. (boolean include-desig?))))
    (some? active-only?)
    (conj (expansion-param "activeOnly" (BooleanType. (boolean active-only?))))
    (some-> param-filter .getValue)
    (conj (expansion-param "filter" (StringType. ^String (.getValue param-filter))))
    param-count
    (conj (expansion-param "count" (IntegerType. (.getValue param-count))))
    offset
    (conj (expansion-param "offset" (IntegerType. (.getValue offset))))))

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

