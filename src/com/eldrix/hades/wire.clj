(ns com.eldrix.hades.wire
  "Pure FHIR JSON map builders. Outputs are string-keyed maps matching FHIR
  JSON property names; they serialise directly via `charred/write-json-str`.

  Protocol implementations return keyword-keyed result maps; this namespace
  translates those shapes into FHIR-shaped wire output."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Parameter value helpers
;; ---------------------------------------------------------------------------

(defn- as-code [v]
  (cond (keyword? v) (name v) (string? v) v :else (str v)))

(defn- typed-value
  "Infer the FHIR typed value key from a Clojure value. Returns
  {\"valueXxx\" v}."
  [value]
  (cond
    (boolean? value) {"valueBoolean" value}
    (keyword? value) {"valueCode"    (name value)}
    (string? value)  {"valueString"  value}
    (integer? value) {"valueInteger" value}
    (number? value)  {"valueString"  (str value)}
    :else            {"valueString"  (str value)}))

(defn- named [^String param-name value-map]
  (assoc value-map "name" param-name))

(defn- param-auto
  "Add a parameter with type inferred from value."
  [^String param-name value]
  (named param-name (typed-value value)))

(defn- param-string [name s] (named name {"valueString" s}))
(defn- param-code [name v] (named name {"valueCode" (as-code v)}))
(defn- param-uri [name s] (named name {"valueUri" s}))
(defn- param-canonical [name s] (named name {"valueCanonical" s}))
(defn- param-boolean [name b] (named name {"valueBoolean" (boolean b)}))
(defn- param-resource [name resource] (named name {"resource" resource}))

(defn- coding-map
  "Build a string-keyed FHIR Coding map from {:system :code :display}."
  [{:keys [system code display]}]
  (cond-> {}
    system  (assoc "system" system)
    code    (assoc "code" (as-code code))
    display (assoc "display" display)))

(defn- codeable-concept-map
  "Accepts either an existing string-keyed map or one with :coding keyword.
  Returns a string-keyed FHIR CodeableConcept map."
  [cc]
  (cond
    (nil? cc) nil
    (contains? cc "coding") cc
    (contains? cc :coding) {"coding" (mapv coding-map (:coding cc))}
    :else cc))

;; ---------------------------------------------------------------------------
;; OperationOutcome
;; ---------------------------------------------------------------------------

(def ^:private valid-issue-codes
  #{"code-invalid" "invalid" "not-found" "not-supported" "business-rule"
    "exception" "informational" "too-costly" "processing"
    "fatal" "security" "login" "unknown" "expired" "forbidden" "suppressed"
    "structure" "required" "value" "duplicate" "multiple-matches"
    "conflict" "transient" "lock-error" "no-store" "timeout" "incomplete"
    "throttled" "extension" "deleted" "too-long"})

(defn- issue-code
  "Map :type → FHIR issue code. Unknown types default to \"processing\"."
  [t]
  (cond
    (nil? t)                        "processing"
    (contains? valid-issue-codes t) t
    :else                           "processing"))

(defn- issue-map
  "Build a string-keyed OperationOutcome.issue map from a keyword-keyed
  issue: {:severity :type :details-code :text :expression}."
  [{:keys [severity type details-code text expression]}]
  (let [details (cond-> {}
                  text         (assoc "text" text)
                  details-code (assoc "coding"
                                      [{"system" "http://hl7.org/fhir/tools/CodeSystem/tx-issue-type"
                                        "code"   details-code}]))]
    (cond-> {"severity" (or severity "error")
             "code"     (issue-code type)}
      (seq details)    (assoc "details" details)
      (seq expression) (assoc "expression" (vec expression)))))

(defn operation-outcome
  "Build a FHIR OperationOutcome map from a seq of keyword-keyed issue maps."
  [issues]
  {"resourceType" "OperationOutcome"
   "issue"        (mapv issue-map issues)})

;; ---------------------------------------------------------------------------
;; Issue expression adjustment
;; ---------------------------------------------------------------------------

(defn adjust-issue-expressions
  "Adjust FHIRPath expressions in issues for the input mode.
  Canonical Coding.* expressions are rewritten:
    :code            → strip 'Coding.' prefix (bare 'code', 'display', ...)
    :coding          → keep as-is ('Coding.code', 'Coding.display')
    :codeableConcept → replace 'Coding.' with 'CodeableConcept.coding[N].'"
  [issues input-mode coding-index]
  (let [coding-index (or coding-index 0)]
    (case input-mode
      :code
      (mapv (fn [i]
              (update i :expression
                      (fn [exprs]
                        (mapv (fn [e]
                                (cond
                                  (str/starts-with? e "Coding.") (subs e 7)
                                  (= e "Coding")                 "code"
                                  :else                          e))
                              exprs))))
            issues)

      :codeableConcept
      (mapv (fn [i]
              (let [idx    (or (:coding-index i) coding-index)
                    prefix (str "CodeableConcept.coding[" idx "]")]
                (-> (update i :expression
                            (fn [exprs]
                              (mapv (fn [e]
                                      (cond
                                        (str/starts-with? e "Coding.") (str prefix "." (subs e 7))
                                        (= e "Coding")                 prefix
                                        :else                          e))
                                    exprs)))
                    (dissoc :coding-index))))
            issues)

      issues)))

;; ---------------------------------------------------------------------------
;; Parameters — validate, lookup, subsumes
;; ---------------------------------------------------------------------------

(defn- parameters [params]
  {"resourceType" "Parameters"
   "parameter"    (vec params)})

(defn- property-param
  "Build a nested 'property' parameter with code/value/description parts."
  [{:keys [code value description code-display]}]
  (let [code-str (as-code code)
        value-part (cond
                     (boolean? value) {"name" "value" "valueBoolean" value}
                     (keyword? value) {"name" "value" "valueCode" (name value)}
                     (string? value)  {"name" "value" "valueString" value}
                     (number? value)  {"name" "value" "valueString" (str value)}
                     :else            {"name" "value" "valueString" (str value)})]
    {"name" "property"
     "part" (cond-> [{"name" "code" "valueCode" code-str}
                     value-part]
              description  (conj {"name" "description" "valueString" (str description)})
              code-display (conj {"name" "display" "valueString" (str code-display)}))}))

(defn- designation-param
  [{:keys [language value use]}]
  {"name" "designation"
   "part" (cond-> []
            language
            (conj {"name" "language" "valueCode" (as-code language)})
            use
            (conj {"name" "use" "valueCoding" (coding-map use)})
            :always
            (conj {"name" "value" "valueString" (str value)}))})

(defn validate->parameters
  "Convert a ::protos/validate-result to a FHIR Parameters map."
  [{:keys [result code system version display message
           inactive inactive-status normalized-code issues
           x-caused-by-unknown-system x-unknown-system
           codeableConcept]}]
  (parameters
    (cond-> []
      code (conj (param-auto "code" code))
      codeableConcept (conj (named "codeableConcept"
                                   {"valueCodeableConcept" (codeable-concept-map codeableConcept)}))
      display (conj (param-string "display" display))
      (some? inactive) (conj (param-boolean "inactive" inactive))
      (seq issues) (conj (param-resource "issues" (operation-outcome issues)))
      message (conj (param-string "message" message))
      normalized-code (conj (param-auto "normalized-code" normalized-code))
      :always (conj (param-boolean "result" (boolean result)))
      (and inactive-status (not= "inactive" inactive-status))
      (conj (param-code "status" inactive-status))
      system (conj (param-uri "system" system))
      version (conj (param-string "version" version))
      x-caused-by-unknown-system
      (conj (param-canonical "x-caused-by-unknown-system" x-caused-by-unknown-system))
      x-unknown-system
      (conj (param-canonical "x-unknown-system" x-unknown-system)))))

(defn lookup->parameters
  "Convert a ::protos/lookup-result to a FHIR Parameters map."
  [{:keys [name version display system code definition abstract
           properties designations]}]
  (let [base (cond-> []
               name       (conj (param-string "name" name))
               version    (conj (param-string "version" version))
               display    (conj (param-string "display" display))
               system     (conj (param-uri "system" system))
               code       (conj (param-auto "code" code))
               definition (conj (param-string "definition" definition))
               (some? abstract) (conj (param-boolean "abstract" abstract)))
        with-props (into base (map property-param) properties)
        all (into with-props (map designation-param) designations)]
    (parameters all)))

(defn subsumes->parameters
  "Convert a subsumes result to a FHIR Parameters map."
  [{:keys [outcome]}]
  (parameters [(param-auto "outcome" outcome)]))

(defn- translate-match-param
  "Build a single $translate match entry as a Parameters.parameter part."
  [{:keys [equivalence system code display version]}]
  {"name" "match"
   "part" (cond-> []
            equivalence (conj (named "equivalence" {"valueCode" equivalence}))
            :always (conj (named "concept"
                                 {"valueCoding" (coding-map
                                                  (cond-> {:system system :code code}
                                                    display (assoc :display display)
                                                    version (assoc :version version)))})))})

(defn translate->parameters
  "Convert a ::protos/translate-result to a FHIR Parameters map."
  [{:keys [result message matches issues]}]
  (parameters
    (cond-> [(param-boolean "result" (boolean result))]
      message        (conj (param-string "message" message))
      (seq matches)  (into (map translate-match-param matches))
      (seq issues)   (conj (param-resource "issues" (operation-outcome issues))))))

;; ---------------------------------------------------------------------------
;; ValueSet expansion
;; ---------------------------------------------------------------------------

(defn- contains-extension-for-property
  "Build the R5 property-on-contains extension for R4."
  [{:keys [code value]}]
  (let [code-str (as-code code)
        val-ext  (cond
                   (boolean? value) {"url" "value" "valueBoolean" value}
                   (keyword? value) {"url" "value" "valueCode" (name value)}
                   (string? value)  {"url" "value" "valueString" value}
                   (number? value)  {"url" "value" "valueInteger" (int value)}
                   :else            {"url" "value" "valueString" (str value)})]
    {"url"       "http://hl7.org/fhir/5.0/StructureDefinition/extension-ValueSet.expansion.contains.property"
     "extension" [{"url" "code" "valueCode" code-str}
                  val-ext]}))

(defn- designation-entry
  [{:keys [value language use] :as d}]
  (cond-> {"value" (str (or value d))}
    language (assoc "language" (as-code language))
    use      (assoc "use" (coding-map use))))

(defn concept->contains
  "Build a ValueSet.expansion.contains entry from a concept map.
  Designations are included only when :include-designations? is true."
  [{:keys [system version code display designations abstract inactive properties]}
   {:keys [include-designations?]}]
  (cond-> {}
    code                (assoc "code" (as-code code))
    system              (assoc "system" system)
    display             (assoc "display" display)
    version             (assoc "version" version)
    abstract            (assoc "abstract" true)
    inactive            (assoc "inactive" true)
    (and include-designations? (seq designations))
    (assoc "designation" (mapv (fn [d]
                                 (if (map? d)
                                   (designation-entry d)
                                   {"value" (str d)}))
                               designations))
    (seq properties)
    (assoc "extension" (mapv contains-extension-for-property properties))))

;; --- expansion parameter component builders ---

(defn- exp-param [name value-map]
  (merge {"name" name} value-map))

(defn build-version-echo-params
  "Build expansion parameter entries echoing force-system-version /
  system-version / check-system-version. system-version and
  check-system-version are suppressed for systems already pinned by the
  compose definition."
  [{:keys [force-system-version system-version check-system-version compose-pinned]}]
  (cond-> []
    force-system-version
    (into (map (fn [[sys ver]]
                 (exp-param "force-system-version"
                            {"valueUri" (str sys "|" ver)})))
          force-system-version)
    system-version
    (into (keep (fn [[sys ver]]
                  (when-not (contains? compose-pinned sys)
                    (exp-param "system-version"
                               {"valueUri" (str sys "|" ver)}))))
          system-version)
    check-system-version
    (into (keep (fn [[sys ver]]
                  (when-not (contains? compose-pinned sys)
                    (exp-param "check-system-version"
                               {"valueUri" (str sys "|" ver)}))))
          check-system-version)))

(defn build-cs-warning-params
  "Build expansion warning parameters for CodeSystem status."
  [used-codesystems]
  (mapcat (fn [{:keys [uri status experimental standards-status]}]
            (cond-> []
              experimental
              (conj (exp-param "warning-experimental" {"valueUri" uri}))
              (or (= "draft" status) (= "draft" standards-status))
              (conj (exp-param "warning-draft" {"valueUri" uri}))
              (or (= "retired" status) (= "deprecated" standards-status))
              (conj (exp-param "warning-deprecated" {"valueUri" uri}))
              (= "withdrawn" standards-status)
              (conj (exp-param "warning-withdrawn" {"valueUri" uri}))))
          used-codesystems))

(defn build-vs-warning-params
  [vs-meta vs-version-uri]
  (let [{:keys [status standards-status]} vs-meta]
    (cond-> []
      (or (= "retired" status) (= "deprecated" standards-status))
      (conj (exp-param "warning-deprecated" {"valueUri" vs-version-uri}))
      (= "withdrawn" standards-status)
      (conj (exp-param "warning-withdrawn" {"valueUri" vs-version-uri})))))

(defn build-used-codesystem-params
  [used-codesystems]
  (mapv (fn [{:keys [uri]}]
          (exp-param "used-codesystem" {"valueUri" uri}))
        used-codesystems))

(defn build-issues-param
  "Build an expansion 'issues' parameter wrapping an OperationOutcome when
  issues are present; otherwise an empty vector."
  [issues]
  (if (seq issues)
    [{"name"     "issues"
      "resource" (operation-outcome issues)}]
    []))

(defn build-echo-params
  "Build echo expansion parameters (displayLanguage, excludeNested, etc.).
  :exclude-nested-present? indicates whether the caller supplied excludeNested
  (so we echo the resolved value); :filter-value / :count-value / :offset-value
  are the underlying raw values (nil when absent)."
  [{:keys [display-language exclude-nested-present? exclude-nested?
           include-designations? active-only? filter-value count-value offset-value]}]
  (cond-> []
    (some? display-language)
    (conj (exp-param "displayLanguage" {"valueCode" display-language}))
    exclude-nested-present?
    (conj (exp-param "excludeNested" {"valueBoolean" (boolean exclude-nested?)}))
    (some? include-designations?)
    (conj (exp-param "includeDesignations" {"valueBoolean" (boolean include-designations?)}))
    (some? active-only?)
    (conj (exp-param "activeOnly" {"valueBoolean" (boolean active-only?)}))
    (some? filter-value)
    (conj (exp-param "filter" {"valueString" filter-value}))
    (some? count-value)
    (conj (exp-param "count" {"valueInteger" (int count-value)}))
    (some? offset-value)
    (conj (exp-param "offset" {"valueInteger" (int offset-value)}))))

;; --- ValueSet assembly ---

(defn expansion->valueset
  "Build a full FHIR ValueSet map with an expansion component.
  Inputs:
    - result: an ::expansion-result (keyword-keyed) from the provider
    - opts:   echo/context data gathered by the response interceptor:
        :vs-meta, :url, :offset-value, :count-value, :include-designations?,
        :expansion-params (already-built vector of parameter maps)"
  [{:keys [concepts total multi-version-systems] :as _result}
   {:keys [vs-meta url offset-value expansion-params include-designations?]}]
  (let [resolved-url (or (:url vs-meta) url)
        prep (fn [c]
               (if (or (nil? (:system c))
                       (contains? multi-version-systems (:system c)))
                 c
                 (dissoc c :version)))
        contains-entries (mapv (fn [c] (concept->contains (prep c)
                                                          {:include-designations? include-designations?}))
                               concepts)
        expansion (cond-> {"identifier" (str "urn:uuid:" (java.util.UUID/randomUUID))
                           "timestamp"  (str (java.time.Instant/now))
                           "parameter"  (vec expansion-params)
                           "contains"   contains-entries}
                    total        (assoc "total" (int total))
                    offset-value (assoc "offset" (int offset-value)))]
    (cond-> {"resourceType" "ValueSet"
             "url"          resolved-url
             "status"       (or (:status vs-meta) "unknown")
             "expansion"    expansion}
      (:version vs-meta)      (assoc "version" (:version vs-meta))
      (:name vs-meta)         (assoc "name" (:name vs-meta))
      (:title vs-meta)        (assoc "title" (:title vs-meta))
      (some? (:experimental vs-meta))
      (assoc "experimental" (boolean (:experimental vs-meta))))))
