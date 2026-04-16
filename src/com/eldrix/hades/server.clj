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
           (ca.uhn.fhir.rest.server.exceptions InternalErrorException ResourceNotFoundException UnprocessableEntityException)
           (ca.uhn.fhir.rest.server.provider ServerCapabilityStatementProvider)
           (jakarta.servlet Servlet)
           (jakarta.servlet.http HttpServletRequest HttpServletResponse)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.hl7.fhir.instance.model.api IBaseConformance)
           (org.hl7.fhir.r4.model CapabilityStatement CapabilityStatement$CapabilityStatementSoftwareComponent
                                   CodeSystem Coding ConceptMap
                                   Enumerations$PublicationStatus
                                   TerminologyCapabilities TerminologyCapabilities$TerminologyCapabilitiesCodeSystemComponent
                                   ValueSet ValueSet$ValueSetExpansionComponent)
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

(defn- operation-exception
  "Convert an exception to a HAPI server exception with OperationOutcome."
  ^Exception [^Throwable e]
  (if (instance? clojure.lang.ExceptionInfo e)
    (let [{:keys [type details-code]} (ex-data e)
          msg (ex-message e)
          issue-type (case type :invalid "invalid" :not-found "not-found"
                                :not-supported "not-supported" :processing "processing"
                                "exception")
          oo (fhir/build-operation-outcome [{:severity "error" :type issue-type
                                              :details-code (or details-code (name (or type :exception)))
                                              :text msg}])]
      (case type
        :invalid (UnprocessableEntityException. ^String msg oo)
        :not-found (ResourceNotFoundException. ^String msg oo)
        :processing (UnprocessableEntityException. ^String msg oo)
        (doto (InternalErrorException. ^String msg) (.setOperationOutcome oo))))
    (let [msg (or (ex-message e) "Internal server error")
          oo (fhir/build-operation-outcome [{:severity "error" :type "exception"
                                              :details-code "exception" :text msg}])]
      (doto (InternalErrorException. ^String msg ^Throwable e) (.setOperationOutcome oo)))))

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
          display-language (some (fn [p] (when (= "displayLanguage" (get p "name"))
                                           (or (get p "valueCode") (get p "valueString"))))
                                 params)
          system-versions (keep (fn [p] (when (= "system-version" (get p "name"))
                                          (or (get p "valueCanonical") (get p "valueUri"))))
                                params)
          force-system-versions (keep (fn [p] (when (= "force-system-version" (get p "name"))
                                                (or (get p "valueCanonical") (get p "valueUri"))))
                                      params)
          check-system-versions (keep (fn [p] (when (= "check-system-version" (get p "name"))
                                                (or (get p "valueCanonical") (get p "valueUri"))))
                                      params)
          request (cond-> {:lenient-display-validation (boolean lenient-val)}
                    (seq system-versions) (assoc :system-version (registry/parse-version-param system-versions))
                    (seq force-system-versions) (assoc :force-system-version (registry/parse-version-param force-system-versions))
                    (seq check-system-versions) (assoc :check-system-version (registry/parse-version-param check-system-versions))
                    vs-version (assoc :value-set-version vs-version)
                    display-language (assoc :display-language display-language))]
      {:tx-resources tx-resources :request request})
    (catch Exception _ {:tx-resources nil :extra {}})))

(defn build-tx-ctx
  "Build an overlay ctx from a seq of resource maps (plain Clojure maps).
  Three-pass: regular CodeSystems first, then supplements, then ValueSets.

  Use from the REPL for direct testing with overlays:
    (def ctx (server/build-tx-ctx [cs-map vs-map ...]))
    (registry/valueset-validate-code ctx {:url ... :code ... :system ...})
    (registry/valueset-expand ctx {:url ...})"
  [resource-maps]
  (when (seq resource-maps)
    (let [code-systems (filter #(= "CodeSystem" (get % "resourceType")) resource-maps)
          value-sets (filter #(= "ValueSet" (get % "resourceType")) resource-maps)
          supplements (filter #(= "supplement" (get % "content")) code-systems)
          regular-cs (remove #(= "supplement" (get % "content")) code-systems)
          ;; Pass 1: regular CodeSystems
          ctx (reduce (fn [ctx m]
                        (let [url (get m "url")
                              version (get m "version")
                              fcs (fhir-cs/make-fhir-code-system m)]
                          (cond-> (-> ctx
                                      (assoc-in [:codesystems url] fcs)
                                      (assoc-in [:valuesets url] fcs))
                            version (-> (assoc-in [:codesystems (str url "|" version)] fcs)
                                        (assoc-in [:valuesets (str url "|" version)] fcs)))))
                      {} regular-cs)
          ;; Pass 2: apply supplements to base CodeSystems
          ctx (reduce (fn [ctx supp]
                        (let [base-url (get supp "supplements")]
                          (if-let [base-cs (or (get-in ctx [:codesystems base-url])
                                               (registry/codesystem base-url))]
                            (let [merged (fhir-cs/apply-supplement base-cs supp)
                                  url (get supp "supplements")
                                  version (get supp "version")]
                              (cond-> (-> ctx
                                          (assoc-in [:codesystems url] merged)
                                          (assoc-in [:valuesets url] merged))
                                version (-> (assoc-in [:codesystems (str url "|" version)] merged)
                                            (assoc-in [:valuesets (str url "|" version)] merged))))
                            ctx)))
                      ctx supplements)]
      ;; Pass 3: ValueSets
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
          dl (or (resolve-display-language (some-> displayLanguage .getValue) request)
                 (get-in ctx [:request :display-language]))
          result (registry/codesystem-lookup ctx (cond-> {:system system' :code code'}
                                                   (some-> version .getValue) (assoc :version (.getValue version))
                                                   dl (assoc :displayLanguage dl)))]
      (when-not result
        (if (registry/codesystem ctx system')
          (throw (ResourceNotFoundException. (str "Unknown code '" code' "' in code system '" system' "'")))
          (throw (ResourceNotFoundException. (str "Unknown code system: " system')))))
      (fhir/lookup-result->parameters result)))

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
          display' (or (some-> display .getValue) (when coding? (.getDisplay coding)))
          version' (some-> version .getValue)
          display-lang (or (resolve-display-language (some-> displayLanguage .getValue) request)
                           (get-in ctx [:request :display-language]))
          result (registry/codesystem-validate-code ctx
                   (cond-> {:system system' :code code'}
                     display' (assoc :display display')
                     version' (assoc :version version')
                     display-lang (assoc :displayLanguage display-lang)))
          input-mode (if coding? :coding :code)
          result (if (:issues result)
                   (assoc result :issues (fhir/adjust-issue-expressions
                                           (:issues result) input-mode nil))
                   result)]
      (fhir/validate-result->parameters result)))

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
      (fhir/subsumes-result->parameters
        (cond
          (and codeA codeB system)
          (registry/codesystem-subsumes ctx {:systemA (.getValue system) :codeA (.getValue codeA) :systemB (.getValue system) :codeB (.getValue codeB)})
          (and codingA codingB)
          (registry/codesystem-subsumes ctx {:systemA (.getSystem codingA) :codeA (.getCode codingA) :systemB (.getSystem codingB) :codeB (.getCode codingB)}))))))

(deftype ValueSetResourceProvider [svc max-expansion-size]
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
    (try
      (let [ctx *tx-ctx*
            value-set-version (get-in ctx [:request :value-set-version])
            display-lang (or (resolve-display-language (some-> displayLanguage .getValue) request)
                             (get-in ctx [:request :display-language]))
            url' (some-> url .getValue)
            cc? (and codeableConcept (seq (.getCoding codeableConcept)))
            coding? (and coding (some-> coding .getCode))
            ;; Extract HAPI params into plain maps and call registry
            result
            (if cc?
              (let [codings (mapv (fn [^Coding c]
                                   (let [sv (some-> systemVersion .getValue)]
                                     (cond-> {:system (.getSystem c) :code (.getCode c)}
                                       (.getDisplay c) (assoc :display (.getDisplay c))
                                       (or sv (.getVersion c))
                                       (assoc :version (or sv (.getVersion c))))))
                                 (.getCoding codeableConcept))
                    result (registry/valueset-validate-codeableconcept ctx codings
                             (cond-> {:url url'}
                               value-set-version (assoc :valueSetVersion value-set-version)
                               display-lang (assoc :displayLanguage display-lang)))]
                (assoc result :codeableConcept codeableConcept))
              (let [system' (or (some-> system .getValue)
                                (when coding? (.getSystem coding)))
                    code' (or (some-> code .getValue)
                              (when coding? (.getCode coding)))
                    display' (or (some-> display .getValue)
                                 (when coding? (.getDisplay coding)))
                    version' (or (some-> systemVersion .getValue)
                                 (when coding? (.getVersion coding)))]
                (registry/valueset-validate-code ctx
                  (cond-> {:url url' :system system' :code code'}
                    display' (assoc :display display')
                    version' (assoc :version version')
                    value-set-version (assoc :valueSetVersion value-set-version)
                    display-lang (assoc :displayLanguage display-lang)))))
            ;; Adjust canonical Coding.* expressions for the input mode
            input-mode (cond cc? :codeableConcept coding? :coding :else :code)
            result (if (:issues result)
                     (assoc result :issues (fhir/adjust-issue-expressions
                                             (:issues result) input-mode nil))
                     result)]
        ;; VS not found → 4xx
        (if (:not-found result)
          (throw (ResourceNotFoundException.
                   ^String (:message result)
                   (fhir/build-operation-outcome (:issues result))))
          (fhir/validate-result->parameters result)))
      (catch Exception e
        (if (or (instance? ResourceNotFoundException e)
                (instance? UnprocessableEntityException e))
          (throw e)
          (throw (operation-exception e))))))
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
    (try
      (let [ctx *tx-ctx*
            include-desig? (some-> includeDesignations .getValue)
            url' (some-> url .getValue)
            active-only? (some-> activeOnly .getValue)
            exclude-nested? (if excludeNested (.getValue excludeNested) true)
            display-lang (or (resolve-display-language (some-> displayLanguage .getValue) request)
                             (get-in ctx [:request :display-language]))
            count' (some-> param-count .getValue)
            offset' (some-> offset .getValue)
            result (registry/valueset-expand ctx {:url             url'
                                                   :activeOnly      active-only?
                                                   :filter          (some-> param-filter .getValue)
                                                   :displayLanguage display-lang})]
        (when-not result
          (throw (ResourceNotFoundException.
                   (str "A definition for the value Set '" url' "' could not be found"))))
        (when (and max-expansion-size (nil? count') (> (:total result) max-expansion-size))
          (let [msg (str "The value set '" url' "' expansion has too many codes to display (>"
                         max-expansion-size ")")
                issue {:severity "error" :type "too-costly" :text msg}]
            (throw (UnprocessableEntityException.
                     ^String msg (fhir/build-operation-outcome [issue])))))
        (let [vs-impl (registry/valueset ctx url')
              vs-meta (when vs-impl (protos/vs-resource vs-impl {}))
              used-cs (:used-codesystems result)
              compose-pinned (into #{} (keep :system) (:compose-pins result))
              vs-version-uri (let [v (:version vs-meta)]
                               (if v (str url' "|" v) url'))
              {:keys [check-system-version force-system-version system-version]} (:request ctx)
              _ (when check-system-version
                  (doseq [{cs-uri :uri} used-cs]
                    (let [[sys resolved] (registry/parse-versioned-uri cs-uri)]
                      (when-let [check-pattern (get check-system-version sys)]
                        (when (and resolved (not (registry/version-matches? check-pattern resolved)))
                          (let [issue {:severity "error" :type "exception"
                                       :details-code "version-error"
                                       :text (str "The version '" resolved "' is not allowed for system '"
                                                  sys "': required to be '" check-pattern
                                                  "' by a version-check parameter")}]
                            (throw (UnprocessableEntityException.
                                     ^String (:text issue)
                                     (fhir/build-operation-outcome [issue])))))))))
              expansion-params (-> (fhir/build-version-echo-params
                                     {:force-system-version  force-system-version
                                      :system-version        system-version
                                      :check-system-version  check-system-version
                                      :compose-pinned        compose-pinned})
                                   (into (fhir/build-cs-warning-params used-cs))
                                   (into (fhir/build-vs-warning-params vs-meta vs-version-uri))
                                   (into (fhir/build-used-codesystem-params used-cs))
                                   (into (fhir/build-echo-params
                                           {:display-lang    display-lang
                                            :excludeNested   excludeNested
                                            :exclude-nested? exclude-nested?
                                            :include-desig?  include-desig?
                                            :active-only?    active-only?
                                            :param-filter    param-filter
                                            :param-count     param-count
                                            :offset          offset})))]
          (let [vs (doto (ValueSet.)
                     (.setUrl (or (:url vs-meta) (first (registry/parse-versioned-uri url'))))
                     (.setVersion (:version vs-meta))
                     (.setName (:name vs-meta))
                     (.setTitle (:title vs-meta))
                     (.setStatus (case (:status vs-meta)
                                   "active" Enumerations$PublicationStatus/ACTIVE
                                   "draft" Enumerations$PublicationStatus/DRAFT
                                   "retired" Enumerations$PublicationStatus/RETIRED
                                   Enumerations$PublicationStatus/UNKNOWN))
                     (.setExpansion (let [exp (doto (ValueSet$ValueSetExpansionComponent.)
                                             (.setIdentifier (str "urn:uuid:" (java.util.UUID/randomUUID)))
                                             (.setTimestamp (Date.))
                                             (.setParameter expansion-params)
                                             (.setTotal (:total result))
                                             (.setContains (let [paged (cond->> (:concepts result)
                                                                         offset' (drop offset')
                                                                         count' (take count'))]
                                                             (map #(fhir/map->vs-expansion % :include-designations include-desig?) paged))))]
                                         (when offset' (.setOffset exp (int offset')))
                                         exp)))]
            (when (some? (:experimental vs-meta))
              (.setExperimental vs (boolean (:experimental vs-meta))))
            vs)))
      (catch Exception e
        (if (or (instance? ResourceNotFoundException e)
                (instance? UnprocessableEntityException e))
          (throw e)
          (throw (operation-exception e)))))))

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
    (org.hl7.fhir.r4.model.Parameters.)))

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

(defn make-r4-servlet ^Servlet [svc {:keys [max-expansion-size]}]
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
                                     (ValueSetResourceProvider. svc max-expansion-size)
                                     (ConceptMapResourceProvider. svc)])
        (.setServerConformanceProvider this (HadesConformanceProvider. this))
        (log/debug "Resource providers:" (seq (.getResourceProviders this)))))
    (service [^HttpServletRequest request ^HttpServletResponse response]
      (if (= "POST" (.getMethod request))
        (let [body (.readAllBytes (.getInputStream request))
              {parsed-request :request
               :keys [tx-resources]} (parse-post-params body)
              overlay (build-tx-ctx tx-resources)
              ctx (assoc overlay
                    :request (merge registry/default-request parsed-request))
              wrapped (wrap-request request body)]
          (binding [*tx-ctx* ctx]
            (proxy-super service wrapped response)))
        (let [sys-vers (seq (.getParameterValues request "system-version"))
              force-vers (seq (.getParameterValues request "force-system-version"))
              check-vers (seq (.getParameterValues request "check-system-version"))
              request-params (cond-> {}
                               sys-vers (assoc :system-version (registry/parse-version-param sys-vers))
                               force-vers (assoc :force-system-version (registry/parse-version-param force-vers))
                               check-vers (assoc :check-system-version (registry/parse-version-param check-vers)))
              ctx (when (or sys-vers force-vers check-vers)
                    {:request (merge registry/default-request request-params)})]
          (binding [*tx-ctx* ctx]
            (proxy-super service request response)))))))

(defn make-server ^Server [svc {:keys [port max-expansion-size] :or {max-expansion-size 10000}}]
  (let [servlet-holder (ServletHolder. ^Servlet (make-r4-servlet svc {:max-expansion-size max-expansion-size}))
        handler (doto (ServletContextHandler. ServletContextHandler/SESSIONS)
                  (.setContextPath "/")
                  (.addServlet servlet-holder "/fhir/*"))
        server (doto (Server.)
                 (.setHandler handler))
        connector (doto (ServerConnector. server)
                    (.setPort (or port 8080)))]
    (.addConnector server connector)
    server))
