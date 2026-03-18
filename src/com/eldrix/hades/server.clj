(ns com.eldrix.hades.server
  "Implementation of a FHIR terminology server.
  See https://hl7.org/fhir/terminology-service.html"
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.fhir :as fhir]
            [com.eldrix.hades.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.fhir-valueset :as fhir-vs]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry])
  (:import (ca.uhn.fhir.context FhirContext)
           (ca.uhn.fhir.parser LenientErrorHandler StrictErrorHandler)
           (ca.uhn.fhir.rest.annotation OperationParam)
           (ca.uhn.fhir.rest.api EncodingEnum)
           (ca.uhn.fhir.rest.api.server RequestDetails)
           (ca.uhn.fhir.rest.server RestfulServer IResourceProvider IServerConformanceProvider)
           (ca.uhn.fhir.rest.server.exceptions ResourceNotFoundException)
           (ca.uhn.fhir.rest.server.provider ServerCapabilityStatementProvider)
           (jakarta.servlet Servlet)
           (jakarta.servlet.http HttpServletRequest HttpServletResponse)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.hl7.fhir.instance.model.api IBaseConformance)
           (org.hl7.fhir.r4.model BooleanType CapabilityStatement CapabilityStatement$CapabilityStatementSoftwareComponent
                                   CodeSystem CodeType Coding ConceptMap
                                   Enumerations$PublicationStatus StringType
                                   TerminologyCapabilities TerminologyCapabilities$TerminologyCapabilitiesCodeSystemComponent
                                   UriType ValueSet ValueSet$ValueSetExpansionComponent
                                   ValueSet$ValueSetExpansionParameterComponent)
           (java.util Date)))

;; ---------------------------------------------------------------------------
;; tx-resource overlay
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *tx-ctx*
  "Request-scoped overlay context built from tx-resource parameters.
  Bound per-request in the servlet service method."
  nil)

(defn- resolve-display-language
  "Resolve the display language from the operation parameter and/or the
  Accept-Language HTTP header, following the same approach as Snowstorm."
  [^String display-language-param ^HttpServletRequest request]
  (or display-language-param
      (some-> request (.getHeader "Accept-Language"))))

(defn- parse-post-params
  "Parse a POST body (bytes) as FHIR Parameters JSON. Returns a map with:
    :tx-resources — seq of resource maps from tx-resource entries
    :extra        — map of additional params not in the HAPI interface"
  [^bytes body]
  (try
    (let [parsed (json/read-str (String. body "UTF-8"))
          params (get parsed "parameter")
          tx-resources (keep (fn [p]
                               (when (= "tx-resource" (get p "name"))
                                 (get p "resource")))
                             params)
          has-lenient? (some #(= "lenient-display-validation" (get % "name")) params)
          lenient-val (when has-lenient?
                        (some (fn [p] (when (= "lenient-display-validation" (get p "name"))
                                        (get p "valueBoolean")))
                              params))
          vs-version (some (fn [p] (when (= "valueSetVersion" (get p "name"))
                                     (get p "valueString")))
                           params)
          extra (cond-> {:lenient-display-validation (boolean lenient-val)}
                  vs-version (assoc :valueSetVersion vs-version))]
      {:tx-resources tx-resources :extra extra})
    (catch Exception _ {:tx-resources nil :extra {}})))

(defn- build-tx-ctx
  "Build an overlay ctx from a seq of resource maps (plain Clojure maps).
  Two-pass: CodeSystems first, then ValueSets, since a ValueSet may reference
  a CodeSystem from the same tx-resource batch."
  [resource-maps]
  (when (seq resource-maps)
    (let [code-systems (filter #(= "CodeSystem" (get % "resourceType")) resource-maps)
          value-sets (filter #(= "ValueSet" (get % "resourceType")) resource-maps)
          ctx (reduce (fn [ctx m]
                        (let [url (get m "url")
                              version (get m "version")
                              fcs (fhir-cs/make-fhir-code-system m)]
                          (cond-> (-> ctx
                                      (assoc-in [:codesystems url] fcs)
                                      (assoc-in [:valuesets url] fcs))
                            version (-> (assoc-in [:codesystems (str url "|" version)] fcs)
                                        (assoc-in [:valuesets (str url "|" version)] fcs)))))
                      {} code-systems)]
      (reduce (fn [ctx m]
                (let [url (get m "url")
                      version (get m "version")
                      fvs (fhir-vs/make-fhir-value-set m)]
                  (cond-> (assoc-in ctx [:valuesets url] fvs)
                    version (assoc-in [:valuesets (str url "|" version)] fvs))))
              ctx value-sets))))

(defn- make-buffered-input-stream
  "Create a ServletInputStream backed by a byte array."
  ^jakarta.servlet.ServletInputStream [^bytes body]
  (let [bais (java.io.ByteArrayInputStream. body)]
    (proxy [jakarta.servlet.ServletInputStream] []
      (read
        ([] (.read bais))
        ([b] (.read bais b))
        ([b off len] (.read bais b off len)))
      (isReady [] true)
      (isFinished [] (zero? (.available bais)))
      (setReadListener [_])
      (available [] (.available bais))
      (close [] (.close bais)))))

(defn- wrap-request
  "Wrap an HttpServletRequest with a buffered copy of the body so the
  original InputStream can be re-read by HAPI after we parse it."
  ^HttpServletRequest [^HttpServletRequest request ^bytes body]
  (proxy [jakarta.servlet.http.HttpServletRequestWrapper] [request]
    (getInputStream [] (make-buffered-input-stream body))
    (getReader [] (java.io.BufferedReader.
                    (java.io.InputStreamReader.
                      (java.io.ByteArrayInputStream. body) "UTF-8")))))

;; ---------------------------------------------------------------------------
;; HAPI operation interfaces
;; ---------------------------------------------------------------------------

(definterface LookupCodeSystemOperation
  (^org.hl7.fhir.r4.model.Parameters lookup [^org.hl7.fhir.r4.model.CodeType code
                                             ^org.hl7.fhir.r4.model.UriType system
                                             ^org.hl7.fhir.r4.model.StringType version
                                             ^org.hl7.fhir.r4.model.Coding coding
                                             ^org.hl7.fhir.r4.model.StringType displayLanguage
                                             ^org.hl7.fhir.r4.model.CodeType property
                                             ^jakarta.servlet.http.HttpServletRequest request]))

(definterface SubsumesCodeSystemOperation
  (^org.hl7.fhir.r4.model.Parameters subsumes [^org.hl7.fhir.r4.model.CodeType codeA
                                               ^org.hl7.fhir.r4.model.CodeType codeB
                                               ^org.hl7.fhir.r4.model.UriType system
                                               ^org.hl7.fhir.r4.model.StringType version
                                               ^org.hl7.fhir.r4.model.Coding codingA
                                               ^org.hl7.fhir.r4.model.Coding codingB]))

(definterface ExpandValueSetOperation
  (^org.hl7.fhir.r4.model.ValueSet expand [^org.hl7.fhir.r4.model.UriType url
                                           ^org.hl7.fhir.r4.model.UriType context
                                           ^org.hl7.fhir.r4.model.CodeType contextDirection
                                           ^org.hl7.fhir.r4.model.UriType filter
                                           ^org.hl7.fhir.r4.model.DateTimeType date
                                           ^org.hl7.fhir.r4.model.IntegerType offset
                                           ^org.hl7.fhir.r4.model.IntegerType count
                                           ^org.hl7.fhir.r4.model.BooleanType includeDesignations
                                           ^org.hl7.fhir.r4.model.StringType designation
                                           ^org.hl7.fhir.r4.model.BooleanType includeDefinition
                                           ^org.hl7.fhir.r4.model.BooleanType activeOnly
                                           ^org.hl7.fhir.r4.model.BooleanType excludeNested
                                           ^org.hl7.fhir.r4.model.BooleanType excludeNotForUI
                                           ^org.hl7.fhir.r4.model.BooleanType excludePostCoordinated
                                           ^org.hl7.fhir.r4.model.StringType displayLanguage
                                           ^jakarta.servlet.http.HttpServletRequest request]))

(definterface ValidateCodeValueSetOperation
  (^org.hl7.fhir.r4.model.Parameters validateCode [^org.hl7.fhir.r4.model.UriType url
                                                    ^org.hl7.fhir.r4.model.CodeType code
                                                    ^org.hl7.fhir.r4.model.UriType system
                                                    ^org.hl7.fhir.r4.model.StringType systemVersion
                                                    ^org.hl7.fhir.r4.model.StringType display
                                                    ^org.hl7.fhir.r4.model.Coding coding
                                                    ^org.hl7.fhir.r4.model.CodeableConcept codeableConcept
                                                    ^org.hl7.fhir.r4.model.StringType displayLanguage
                                                    ^jakarta.servlet.http.HttpServletRequest request]))

(definterface ValidateCodeCodeSystemOperation
  (^org.hl7.fhir.r4.model.Parameters validateCode [^org.hl7.fhir.r4.model.UriType url
                                                    ^org.hl7.fhir.r4.model.CodeType code
                                                    ^org.hl7.fhir.r4.model.StringType display
                                                    ^org.hl7.fhir.r4.model.Coding coding
                                                    ^org.hl7.fhir.r4.model.StringType version
                                                    ^org.hl7.fhir.r4.model.UriType system
                                                    ^org.hl7.fhir.r4.model.StringType displayLanguage
                                                    ^jakarta.servlet.http.HttpServletRequest request]))

;; see https://github.com/hapifhir/hapi-fhir/blob/cbb16ce3affd3fc53dcbfe98dd3181644fe68604/hapi-fhir-jpaserver-base/src/main/java/ca/uhn/fhir/jpa/provider/r4/BaseJpaResourceProviderConceptMapR4.java
(definterface TranslateConceptMapOperation
  (^org.hl7.fhir.r4.model.Parameters translate [^org.hl7.fhir.r4.model.UriType url
                                                ^org.hl7.fhir.r4.model.StringType conceptMapVersion
                                                ^org.hl7.fhir.r4.model.CodeType code
                                                ^org.hl7.fhir.r4.model.UriType system
                                                ^org.hl7.fhir.r4.model.StringType version
                                                ^org.hl7.fhir.r4.model.UriType source
                                                ^org.hl7.fhir.r4.model.Coding coding
                                                ^org.hl7.fhir.r4.model.CodeableConcept codeableConcept
                                                ^org.hl7.fhir.r4.model.UriType target
                                                ^org.hl7.fhir.r4.model.UriType targetSystem
                                                ^org.hl7.fhir.r4.model.BooleanType reverse]))

;; ---------------------------------------------------------------------------
;; Resource providers
;; ---------------------------------------------------------------------------

(deftype CodeSystemResourceProvider [svc]
  IResourceProvider
  (getResourceType [_this] CodeSystem)
  ;;;;;;;;;;;;;;;;;;;;;;;;;
  LookupCodeSystemOperation
  (^{:tag                                  org.hl7.fhir.r4.model.Parameters
     ca.uhn.fhir.rest.annotation.Operation {:name "lookup" :idempotent true}}
    lookup [_this
            ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "code"}} code
            ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "system"}} system
            ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "version"}} version
            ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "coding"}} coding
            ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "displayLanguage"}} displayLanguage
            ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "property"}} property
            ^{:tag jakarta.servlet.http.HttpServletRequest} request]
    (log/debug "codesystem/$lookup: " {:code code :system system :version version :coding coding :lang displayLanguage :properties property})
    (let [ctx *tx-ctx*
          code' (or (some-> code .getValue) (some-> coding .getCode))
          system' (or (some-> system .getValue) (some-> coding .getSystem))
          result (registry/codesystem-lookup ctx {:system         system'
                                                   :code           code'
                                                   :version        (some-> version .getValue)
                                                   :displayLanguage (resolve-display-language (some-> displayLanguage .getValue) request)})]
      (when-not result
        (if (registry/codesystem ctx system')
          (throw (ResourceNotFoundException. (str "Unknown code '" code' "' in code system '" system' "'")))
          (throw (ResourceNotFoundException. (str "Unknown code system: " system')))))
      (fhir/map->parameters result)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ValidateCodeCodeSystemOperation
  (^{:tag                                  org.hl7.fhir.r4.model.Parameters
     ca.uhn.fhir.rest.annotation.Operation {:name "validate-code" :idempotent true}}
    validateCode [_this
                  ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "url"}} url
                  ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "code"}} code
                  ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "display"}} display
                  ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "coding"}} coding
                  ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "version"}} version
                  ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "system"}} system
                  ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "displayLanguage"}} displayLanguage
                  ^{:tag jakarta.servlet.http.HttpServletRequest} request]
    (log/debug "codesystem/$validate-code:" {:url url :code code :display display :coding coding :system system})
    (let [ctx *tx-ctx*
          coding? (and coding (some-> coding .getCode))
          system' (or (some-> url .getValue)
                      (some-> system .getValue)
                      (when coding? (.getSystem coding)))
          code' (or (some-> code .getValue) (when coding? (.getCode coding)))
          result (registry/codesystem-validate-code ctx {:system         system'
                                                          :code           code'
                                                          :display        (or (some-> display .getValue) (when coding? (.getDisplay coding)))
                                                          :version        (some-> version .getValue)
                                                          :displayLanguage (resolve-display-language (some-> displayLanguage .getValue) request)
                                                          :input-mode     (if coding? :coding :code)})]
      (fhir/map->parameters result)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;
  SubsumesCodeSystemOperation
  (^{:tag                                  org.hl7.fhir.r4.model.Parameters
     ca.uhn.fhir.rest.annotation.Operation {:name "subsumes" :idempotent true}}
    subsumes [_this
              ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "codeA"}} codeA
              ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "codeB"}} codeB
              ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "system"}} system
              ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "version"}} version
              ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "codingA"}} codingA
              ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "codingB"}} codingB]
    (log/debug "codesystem/$subsumes: " {:codeA codeA :codeB codeB :system system :version version :codingA codingA :codingB codingB})
    (let [ctx *tx-ctx*]
      (fhir/map->parameters
        (cond
          (and codeA codeB system)
          (registry/codesystem-subsumes ctx {:systemA (.getValue system) :codeA (.getValue codeA) :systemB (.getValue system) :codeB (.getValue codeB)})
          (and codingA codingB)
          (registry/codesystem-subsumes ctx {:systemA (.getSystem codingA) :codeA (.getCode codingA) :systemB (.getSystem codingB) :codeB (.getCode codingB)}))))))

(deftype ValueSetResourceProvider [svc]
  IResourceProvider
  (getResourceType [_this] ValueSet)
  ;;;;;;;;;;;;;;;;;;;;;;;
  ValidateCodeValueSetOperation
  (^{:tag                                  org.hl7.fhir.r4.model.Parameters
     ca.uhn.fhir.rest.annotation.Operation {:name "validate-code" :idempotent true}}
    validateCode [_this
                  ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "url"}} url
                  ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "code"}} code
                  ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "system"}} system
                  ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "systemVersion"}} systemVersion
                  ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "display"}} display
                  ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "coding"}} coding
                  ^{:tag org.hl7.fhir.r4.model.CodeableConcept OperationParam {:name "codeableConcept"}} codeableConcept
                  ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "displayLanguage"}} displayLanguage
                  ^{:tag jakarta.servlet.http.HttpServletRequest} request]
    (log/debug "valueset/$validate-code:" {:url url :code code :system system :coding coding :cc codeableConcept})
    (let [ctx *tx-ctx*
          display-lang (resolve-display-language (some-> displayLanguage .getValue) request)
          url' (some-> url .getValue)
          cc? (and codeableConcept (seq (.getCoding codeableConcept)))
          coding? (and coding (some-> coding .getCode))
          result
          (if cc?
            ;; CodeableConcept: iterate all codings, find valid one
            (let [codings (vec (.getCoding codeableConcept))
                  per-coding (map-indexed
                               (fn [idx ^Coding c]
                                 (registry/valueset-validate-code ctx
                                   {:url             url'
                                    :system          (.getSystem c)
                                    :code            (.getCode c)
                                    :display         (.getDisplay c)
                                    :version         (some-> systemVersion .getValue)
                                    :valueSetVersion (:valueSetVersion ctx)
                                    :displayLanguage display-lang
                                    :input-mode      :codeableConcept
                                    :coding-index    idx}))
                               codings)
                  valid (last (filter #(get % "result") per-coding))
                  invalid (remove #(get % "result") per-coding)
                  all-issues (vec (mapcat #(get % "issues") invalid))]
              (let [vs-not-found? (some #(and (= "not-found" (:details-code %))
                                              (nil? (:expression %)))
                                         all-issues)]
                (if vs-not-found?
                  (let [nf-issue (first (filter #(= "not-found" (:details-code %)) all-issues))]
                    {"result" false
                     "message" (:text nf-issue)
                     "issues" [nf-issue]})
                  (let [cs-error-msgs (distinct (keep (fn [i] (when (= "invalid-code" (:details-code i)) (:text i)))
                                                        all-issues))
                        error-msg (first cs-error-msgs)]
                    (if valid
                      (cond-> valid
                        (seq invalid) (assoc "result" false)
                        (seq all-issues) (update "issues" (fnil into []) all-issues)
                        error-msg (assoc "message" error-msg)
                        true (assoc "codeableConcept" codeableConcept))
                      (let [vs-impl (registry/valueset ctx url')
                            vs-ver (when vs-impl (get (protos/vs-resource vs-impl {}) "version"))
                            vs-url-ver (if vs-ver (str url' "|" vs-ver) url')
                            no-valid-msg (str "No valid coding was found for the value set '" vs-url-ver "'")
                            combined-msg (if (seq cs-error-msgs)
                                           (str no-valid-msg "; " (str/join "; " cs-error-msgs))
                                           no-valid-msg)
                            no-valid-issue {:severity "error" :type "code-invalid"
                                            :details-code "not-in-vs" :text no-valid-msg}]
                        {"result" false
                         "codeableConcept" codeableConcept
                         "message" combined-msg
                         "issues" (into [no-valid-issue] all-issues)}))))))
            ;; Single code or Coding
            (let [system' (or (some-> system .getValue)
                              (when coding? (.getSystem coding)))
                  code' (or (some-> code .getValue)
                            (when coding? (.getCode coding)))
                  input-mode (if coding? :coding :code)]
              (registry/valueset-validate-code ctx
                {:url             url'
                 :system          system'
                 :code            code'
                 :display         (or (some-> display .getValue)
                                      (when coding? (.getDisplay coding)))
                 :version         (some-> systemVersion .getValue)
                 :valueSetVersion (:valueSetVersion ctx)
                 :displayLanguage display-lang
                 :input-mode      input-mode})))
          vs-not-found? (some #(and (= "not-found" (:details-code %))
                                    (nil? (:expression %)))
                              (get result "issues"))]
      (if vs-not-found?
        (throw (ResourceNotFoundException.
                 ^String (get result "message")
                 (fhir/build-operation-outcome (get result "issues"))))
        (fhir/map->parameters result))))
  ;;;;;;;;;;;;;;;;;;;;;;;
  ExpandValueSetOperation
  (^{:tag                                  org.hl7.fhir.r4.model.ValueSet
     ca.uhn.fhir.rest.annotation.Operation {:name "expand" :idempotent true}}
    expand [_this
            ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "url"}} url
            ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "context"}} context
            ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "contextDirection"}} contextDirection
            ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "filter"}} param-filter
            ^{:tag org.hl7.fhir.r4.model.DateTimeType OperationParam {:name "date"}} date
            ^{:tag org.hl7.fhir.r4.model.IntegerType OperationParam {:name "offset"}} offset
            ^{:tag org.hl7.fhir.r4.model.IntegerType OperationParam {:name "count"}} param-count
            ^{:tag org.hl7.fhir.r4.model.BooleanType OperationParam {:name "includeDesignations"}} includeDesignations
            ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "designation"}} designation
            ^{:tag org.hl7.fhir.r4.model.BooleanType OperationParam {:name "includeDefinition"}} includeDefinition
            ^{:tag org.hl7.fhir.r4.model.BooleanType OperationParam {:name "activeOnly"}} activeOnly
            ^{:tag org.hl7.fhir.r4.model.BooleanType OperationParam {:name "excludeNested"}} excludeNested
            ^{:tag org.hl7.fhir.r4.model.BooleanType OperationParam {:name "excludeNotForUI"}} excludeNotForUI
            ^{:tag org.hl7.fhir.r4.model.BooleanType OperationParam {:name "excludePostCoordinated"}} excludePostCoordinated
            ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "displayLanguage"}} displayLanguage
            ^{:tag jakarta.servlet.http.HttpServletRequest} request]
    (log/debug "valueset/$expand:" {:url url :filter param-filter :activeOnly activeOnly :displayLanguage displayLanguage})
    (let [ctx *tx-ctx*
          include-desig? (some-> includeDesignations .getValue)
          url' (some-> url .getValue)
          active-only? (some-> activeOnly .getValue)
          exclude-nested? (if excludeNested (.getValue excludeNested) true)
          display-lang (resolve-display-language (some-> displayLanguage .getValue) request)]
      (if-let [results (registry/valueset-expand ctx {:url             url'
                                                       :activeOnly      active-only?
                                                       :filter          (some-> param-filter .getValue)
                                                       :displayLanguage display-lang})]
        (let [vs-impl (registry/valueset ctx url')
              vs-meta (when vs-impl (protos/vs-resource vs-impl {}))
              system-versions (reduce (fn [m c]
                                        (if (:system c)
                                          (update m (:system c) #(or % (:version c)))
                                          m))
                                      {} results)
              cs-infos (map (fn [[sys ver-from-results]]
                              (let [cs (registry/codesystem ctx sys)
                                    meta (when cs (protos/cs-resource cs {}))
                                    ver (or ver-from-results (get meta "version"))
                                    uri (if ver (str sys "|" ver) sys)]
                                {:uri uri :meta meta}))
                            system-versions)
              vs-version-uri (let [v (get vs-meta "version")]
                               (if v (str url' "|" v) url'))
              cs-warning-params (mapcat (fn [{:keys [uri meta]}]
                                          (cond-> []
                                            (get meta "experimental")
                                            (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                                    (.setName "warning-experimental")
                                                    (.setValue (UriType. ^String uri))))
                                            (= "draft" (get meta "status"))
                                            (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                                    (.setName "warning-draft")
                                                    (.setValue (UriType. ^String uri))))
                                            (= "retired" (get meta "status"))
                                            (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                                    (.setName "warning-deprecated")
                                                    (.setValue (UriType. ^String uri))))))
                                        cs-infos)
              vs-warning-params (cond-> []
                                  (= "retired" (get vs-meta "status"))
                                  (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                          (.setName "warning-withdrawn")
                                          (.setValue (UriType. ^String vs-version-uri)))))
              expansion-params (-> (cond-> []
                                     (some? display-lang)
                                     (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                             (.setName "displayLanguage")
                                             (.setValue (CodeType. ^String display-lang))))
                                     excludeNested
                                     (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                             (.setName "excludeNested")
                                             (.setValue (BooleanType. (boolean exclude-nested?)))))
                                     (some? include-desig?)
                                     (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                             (.setName "includeDesignations")
                                             (.setValue (BooleanType. (boolean include-desig?)))))
                                     (some? active-only?)
                                     (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                             (.setName "activeOnly")
                                             (.setValue (BooleanType. (boolean active-only?)))))
                                     (some-> param-filter .getValue)
                                     (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                             (.setName "filter")
                                             (.setValue (StringType. ^String (.getValue param-filter))))))
                                   (into (map (fn [{:keys [uri]}]
                                                (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                                  (.setName "used-codesystem")
                                                  (.setValue (UriType. ^String uri))))
                                              cs-infos))
                                   (into cs-warning-params)
                                   (into vs-warning-params))]
          (let [vs (doto (ValueSet.)
                     (.setUrl (or (get vs-meta "url") (first (registry/parse-versioned-uri url'))))
                     (.setVersion (get vs-meta "version"))
                     (.setName (get vs-meta "name"))
                     (.setTitle (get vs-meta "title"))
                     (.setStatus (case (get vs-meta "status")
                                   "active" Enumerations$PublicationStatus/ACTIVE
                                   "draft" Enumerations$PublicationStatus/DRAFT
                                   "retired" Enumerations$PublicationStatus/RETIRED
                                   Enumerations$PublicationStatus/UNKNOWN))
                     (.setExpansion (doto (ValueSet$ValueSetExpansionComponent.)
                             (.setIdentifier (str "urn:uuid:" (java.util.UUID/randomUUID)))
                             (.setTimestamp (Date.))
                             (.setParameter expansion-params)
                             (.setTotal (count results))
                             (.setContains (map #(fhir/map->vs-expansion % :include-designations include-desig?) results)))))]
            (when (some? (get vs-meta "experimental"))
              (.setExperimental vs (boolean (get vs-meta "experimental"))))
            vs))))))

(deftype ConceptMapResourceProvider [svc]
  IResourceProvider
  (getResourceType [_this] ConceptMap)
  ;;;;;;;;;;;;;;;;;;;;;;;
  TranslateConceptMapOperation
  (^{:tag                                  org.hl7.fhir.r4.model.Parameters
     ca.uhn.fhir.rest.annotation.Operation {:name "translate" :idempotent true}}
    translate [_this
               ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "url"}} url
               ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "conceptMapVersion"}} conceptMapVersion
               ^{:tag org.hl7.fhir.r4.model.CodeType OperationParam {:name "code"}} code
               ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "system"}} system
               ^{:tag org.hl7.fhir.r4.model.StringType OperationParam {:name "version"}} version
               ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "source"}} source
               ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "coding"}} coding
               ^{:tag org.hl7.fhir.r4.model.CodeableConcept OperationParam {:name "codeableConcept"}} codeableConcept
               ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "target"}} target
               ^{:tag org.hl7.fhir.r4.model.UriType OperationParam {:name "targetSystem"}} targetSystem
               ^{:tag org.hl7.fhir.r4.model.BooleanType OperationParam {:name "reverse"}} reverse]
    (log/debug "conceptmap/$translate:" {:url url :code code :system system :version version :source source :coding coding :codeableConcept codeableConcept :target target :targetSystem targetSystem :reverse reverse})
    (fhir/map->parameters {:operation :translate :url url})))

;; ---------------------------------------------------------------------------
;; Conformance / metadata
;; ---------------------------------------------------------------------------

;; TODO: get from build
(def ^:private hades-version "1.4.0-SNAPSHOT")

(defn- make-terminology-capabilities
  ^IBaseConformance []
  (let [tc (doto (proxy [TerminologyCapabilities IBaseConformance] [])
              (.setStatus Enumerations$PublicationStatus/ACTIVE)
              (.setDate (Date.))
              (.setVersion hades-version)
              (.setName "Hades")
              (.setTitle "Hades FHIR Terminology Server")
              (.setKind (org.hl7.fhir.r4.model.TerminologyCapabilities$CapabilityStatementKind/INSTANCE)))
        expansion (.getExpansion tc)]
    (doseq [uri (keys @registry/codesystems)]
      (.addCodeSystem tc
        (doto (TerminologyCapabilities$TerminologyCapabilitiesCodeSystemComponent.)
          (.setUri uri))))
    (doseq [p ["activeOnly" "check-system-version" "count" "displayLanguage"
               "excludeNested" "force-system-version" "includeDefinition"
               "includeDesignations" "offset" "property" "system-version" "tx-resource"]]
      (.addParameter expansion
        (doto (org.hl7.fhir.r4.model.TerminologyCapabilities$TerminologyCapabilitiesExpansionParameterComponent.)
          (.setName p))))
    tc))

(definterface IConformanceProvider
  (^org.hl7.fhir.instance.model.api.IBaseConformance getMetadataResource
    [^jakarta.servlet.http.HttpServletRequest request
     ^ca.uhn.fhir.rest.api.server.RequestDetails requestDetails]))

(deftype HadesConformanceProvider [^RestfulServer restful-server]
  IServerConformanceProvider
  (setRestfulServer [_this _server])
  (getServerConformance [this request requestDetails]
    (.getMetadataResource this request requestDetails))
  IConformanceProvider
  (^{:tag                                         org.hl7.fhir.instance.model.api.IBaseConformance
     ca.uhn.fhir.rest.annotation.Metadata {:cacheMillis 0}}
    getMetadataResource [_this ^HttpServletRequest request ^RequestDetails requestDetails]
    (if (= "terminology" (.getParameter request "mode"))
      (make-terminology-capabilities)
      (let [^CapabilityStatement cs (.getServerConformance (ServerCapabilityStatementProvider. restful-server)
                                                           request requestDetails)]
        (doto cs
          (.setSoftware (doto (CapabilityStatement$CapabilityStatementSoftwareComponent.)
                          (.setName "Hades")
                          (.setVersion hades-version)
                          (.setReleaseDate (Date.)))))))))

;; ---------------------------------------------------------------------------
;; Servlet and server
;; ---------------------------------------------------------------------------

(defn make-r4-servlet ^Servlet [svc]
  (proxy [RestfulServer] [(FhirContext/forR4)]
    (initialize []
      (log/info "Initialising HL7 FHIR R4 server; providers: CodeSystem ValueSet ConceptMap")
      ;; Hybrid parser error handler: strict by default, lenient for unknown elements.
      ;; Conformance test payloads may include elements from newer FHIR versions or
      ;; extensions that HAPI's R4 model doesn't know about.
      (let [^RestfulServer this this
            lenient (LenientErrorHandler.)
            ^FhirContext fhir-ctx (.getFhirContext this)]
        (.setParserErrorHandler fhir-ctx
          (proxy [StrictErrorHandler] []
            (unknownAttribute [_loc _name] (.unknownAttribute lenient _loc _name))
            (unknownElement [_loc _name] (.unknownElement lenient _loc _name))
            (unknownReference [_loc _ref] (.unknownReference lenient _loc _ref)))))
      (let [^RestfulServer this this]
        (.setDefaultResponseEncoding this EncodingEnum/JSON)
        (.setResourceProviders this [(CodeSystemResourceProvider. svc)
                                     (ValueSetResourceProvider. svc)
                                     (ConceptMapResourceProvider. svc)])
        (.setServerConformanceProvider this (HadesConformanceProvider. this))
        (log/debug "Resource providers:" (seq (.getResourceProviders this)))))
    (service [^HttpServletRequest request ^HttpServletResponse response]
      (if (= "POST" (.getMethod request))
        (let [body (.readAllBytes (.getInputStream request))
              {:keys [tx-resources extra]} (parse-post-params body)
              ctx (merge (build-tx-ctx tx-resources) extra)
              wrapped (wrap-request request body)]
          (binding [*tx-ctx* ctx]
            (proxy-super service wrapped response)))
        (proxy-super service request response)))))

(defn make-server ^Server [svc {:keys [port]}]
  (let [servlet-holder (ServletHolder. ^Servlet (make-r4-servlet svc))
        handler (doto (ServletContextHandler. ServletContextHandler/SESSIONS)
                  (.setContextPath "/")
                  (.addServlet servlet-holder "/fhir/*"))
        server (doto (Server.)
                 (.setHandler handler))
        connector (doto (ServerConnector. server)
                    (.setPort (or port 8080)))]
    (.addConnector server connector)
    server))
