(ns com.eldrix.hades.server
  "Implementation of a FHIR terminology server.
  See https://hl7.org/fhir/terminology-service.html"
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.fhir :as fhir]
            [com.eldrix.hades.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.fhir-valueset :as fhir-vs]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry])
  (:import (ca.uhn.fhir.context FhirContext)
           (ca.uhn.fhir.rest.annotation OperationParam)
           (ca.uhn.fhir.rest.api.server RequestDetails)
           (ca.uhn.fhir.rest.server RestfulServer IResourceProvider IServerConformanceProvider)
           (ca.uhn.fhir.rest.server.exceptions ResourceNotFoundException)
           (ca.uhn.fhir.rest.server.provider ServerCapabilityStatementProvider)
           (jakarta.servlet Servlet)
           (jakarta.servlet.http HttpServletRequest HttpServletResponse)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.hl7.fhir.instance.model.api IBaseConformance)
           (org.hl7.fhir.r4.model CapabilityStatement CapabilityStatement$CapabilityStatementSoftwareComponent
                                   BooleanType CodeSystem StringType UriType
                                   ValueSet ValueSet$ValueSetExpansionComponent ValueSet$ValueSetExpansionParameterComponent
                                   ConceptMap
                                   TerminologyCapabilities TerminologyCapabilities$TerminologyCapabilitiesCodeSystemComponent
                                   Enumerations$PublicationStatus)
           (java.util Date)))

;; ---------------------------------------------------------------------------
;; tx-resource overlay
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *tx-ctx*
  "Request-scoped overlay context built from tx-resource parameters.
  Bound per-request in the servlet service method."
  nil)

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
  (^org.hl7.fhir.r4.model.Parameters lookup [^ca.uhn.fhir.rest.param.StringParam code
                                             ^ca.uhn.fhir.rest.param.UriParam system
                                             ^ca.uhn.fhir.rest.param.StringParam version
                                             ^ca.uhn.fhir.rest.param.TokenParam coding
                                             ^ca.uhn.fhir.rest.param.StringParam displayLanguage
                                             ^ca.uhn.fhir.rest.param.StringAndListParam property]))

(definterface SubsumesCodeSystemOperation
  (^org.hl7.fhir.r4.model.Parameters subsumes [^ca.uhn.fhir.rest.param.StringParam codeA
                                               ^ca.uhn.fhir.rest.param.StringParam codeB
                                               ^ca.uhn.fhir.rest.param.UriParam system
                                               ^ca.uhn.fhir.rest.param.StringParam version
                                               ^ca.uhn.fhir.rest.param.TokenParam codingA
                                               ^ca.uhn.fhir.rest.param.TokenParam codingB]))

(definterface ExpandValueSetOperation
  (^org.hl7.fhir.r4.model.ValueSet expand [^ca.uhn.fhir.rest.param.UriParam url
                                           ^ca.uhn.fhir.rest.param.UriParam context
                                           ^ca.uhn.fhir.rest.param.TokenParam contextDirection
                                           ^ca.uhn.fhir.rest.param.StringParam filter
                                           ^ca.uhn.fhir.rest.param.DateParam date
                                           ^ca.uhn.fhir.rest.param.NumberParam offset
                                           ^ca.uhn.fhir.rest.param.NumberParam count
                                           ^ca.uhn.fhir.rest.param.StringParam includeDesignations
                                           ^ca.uhn.fhir.rest.param.StringParam designation
                                           ^ca.uhn.fhir.rest.param.StringParam includeDefinition
                                           ^ca.uhn.fhir.rest.param.StringParam activeOnly
                                           ^ca.uhn.fhir.rest.param.StringParam excludeNested
                                           ^ca.uhn.fhir.rest.param.StringParam excludeNotForUI
                                           ^ca.uhn.fhir.rest.param.StringParam excludePostCoordinated
                                           ^ca.uhn.fhir.rest.param.TokenParam displayLanguage]))

(definterface ValidateCodeValueSetOperation
  (^org.hl7.fhir.r4.model.Parameters validateCode [^ca.uhn.fhir.rest.param.UriParam url
                                                    ^ca.uhn.fhir.rest.param.StringParam code
                                                    ^ca.uhn.fhir.rest.param.UriParam system
                                                    ^ca.uhn.fhir.rest.param.StringParam systemVersion
                                                    ^ca.uhn.fhir.rest.param.StringParam display
                                                    ^org.hl7.fhir.r4.model.Coding coding
                                                    ^org.hl7.fhir.r4.model.CodeableConcept codeableConcept
                                                    ^ca.uhn.fhir.rest.param.StringParam displayLanguage]))

(definterface ValidateCodeCodeSystemOperation
  (^org.hl7.fhir.r4.model.Parameters validateCode [^ca.uhn.fhir.rest.param.UriParam url
                                                    ^ca.uhn.fhir.rest.param.StringParam code
                                                    ^ca.uhn.fhir.rest.param.StringParam display
                                                    ^org.hl7.fhir.r4.model.Coding coding
                                                    ^ca.uhn.fhir.rest.param.StringParam version
                                                    ^ca.uhn.fhir.rest.param.UriParam system
                                                    ^ca.uhn.fhir.rest.param.StringParam displayLanguage]))

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
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "code"}} code
            ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "system"}} system
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "version"}} version
            ^{:tag ca.uhn.fhir.rest.param.TokenParam OperationParam {:name "coding"}} coding
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "displayLanguage"}} displayLanguage
            ^{:tag ca.uhn.fhir.rest.param.StringAndListParam OperationParam {:name "property"}} property]
    (log/debug "codesystem/$lookup: " {:code code :system system :version version :coding coding :lang displayLanguage :properties property})
    (let [ctx *tx-ctx*
          code' (or (when code (.getValue code)) (when coding (.getValue coding)))
          system' (or (when system (.getValue system)) (when coding (.getSystem coding)))
          result (registry/codesystem-lookup ctx {:system         system'
                                                   :code           code'
                                                   :version        (some-> version .getValue)
                                                   :displayLanguage (some-> displayLanguage .getValue)})]
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
                  ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "url"}} url
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "code"}} code
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "display"}} display
                  ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "coding"}} coding
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "version"}} version
                  ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "system"}} system
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "displayLanguage"}} displayLanguage]
    (log/debug "codesystem/$validate-code:" {:url url :code code :display display :coding coding :system system})
    (let [ctx *tx-ctx*
          coding? (and coding (some-> coding .getCode))
          system' (or (some-> url .getValueAsUriDt .getValueAsString)
                      (some-> system .getValueAsUriDt .getValueAsString)
                      (when coding? (.getSystem coding)))
          code' (or (some-> code .getValue) (when coding? (.getCode coding)))
          result (registry/codesystem-validate-code ctx {:system         system'
                                                          :code           code'
                                                          :display        (or (some-> display .getValue) (when coding? (.getDisplay coding)))
                                                          :version        (some-> version .getValue)
                                                          :displayLanguage (some-> displayLanguage .getValue)
                                                          :input-mode     (if coding? :coding :code)})]
      (fhir/map->parameters result)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;
  SubsumesCodeSystemOperation
  (^{:tag                                  org.hl7.fhir.r4.model.Parameters
     ca.uhn.fhir.rest.annotation.Operation {:name "subsumes" :idempotent true}}
    subsumes [_this
              ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "codeA"}} codeA
              ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "codeB"}} codeB
              ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "system"}} system
              ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "version"}} version
              ^{:tag ca.uhn.fhir.rest.param.TokenParam OperationParam {:name "codingA"}} codingA
              ^{:tag ca.uhn.fhir.rest.param.TokenParam OperationParam {:name "codingB"}} codingB]
    (log/debug "codesystem/$subsumes: " {:codeA codeA :codeB codeB :system system :version version :codingA codingA :codingB codingB})
    (let [ctx *tx-ctx*]
      (fhir/map->parameters
        (cond
          (and codeA codeB system)
          (registry/codesystem-subsumes ctx {:systemA (.getValue system) :codeA (.getValue codeA) :systemB (.getValue system) :codeB (.getValue codeB)})
          (and codingA codingB)
          (registry/codesystem-subsumes ctx {:systemA (.getSystem codingA) :codeA (.getValue codingA) :systemB (.getSystem codingB) :codeB (.getValue codingB)}))))))

(deftype ValueSetResourceProvider [svc]
  IResourceProvider
  (getResourceType [_this] ValueSet)
  ;;;;;;;;;;;;;;;;;;;;;;;
  ValidateCodeValueSetOperation
  (^{:tag                                  org.hl7.fhir.r4.model.Parameters
     ca.uhn.fhir.rest.annotation.Operation {:name "validate-code" :idempotent true}}
    validateCode [_this
                  ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "url"}} url
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "code"}} code
                  ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "system"}} system
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "systemVersion"}} systemVersion
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "display"}} display
                  ^{:tag org.hl7.fhir.r4.model.Coding OperationParam {:name "coding"}} coding
                  ^{:tag org.hl7.fhir.r4.model.CodeableConcept OperationParam {:name "codeableConcept"}} codeableConcept
                  ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "displayLanguage"}} displayLanguage]
    (log/debug "valueset/$validate-code:" {:url url :code code :system system :coding coding :cc codeableConcept})
    (let [ctx *tx-ctx*
          url' (some-> url .getValueAsUriDt .getValueAsString)
          cc? (and codeableConcept (seq (.getCoding codeableConcept)))
          first-coding (when cc? (first (.getCoding codeableConcept)))
          coding? (and coding (some-> coding .getCode))
          system' (or (some-> system .getValueAsUriDt .getValueAsString)
                      (when coding? (.getSystem coding))
                      (when first-coding (.getSystem first-coding)))
          code' (or (some-> code .getValue)
                    (when coding? (.getCode coding))
                    (when first-coding (.getCode first-coding)))
          input-mode (cond cc? :codeableConcept coding? :coding :else :code)
          result (registry/valueset-validate-code ctx {:url             url'
                                                        :system          system'
                                                        :code            code'
                                                        :display         (or (some-> display .getValue)
                                                                             (when coding? (.getDisplay coding))
                                                                             (when first-coding (.getDisplay first-coding)))
                                                        :version         (some-> systemVersion .getValue)
                                                        :valueSetVersion (:valueSetVersion ctx)
                                                        :displayLanguage (some-> displayLanguage .getValue)
                                                        :input-mode      input-mode})
          vs-not-found? (some #(and (= "not-found" (:details-code %))
                                       (nil? (:expression %)))
                                  (get result "issues"))
          result' (if cc?
                    (assoc result "codeableConcept" codeableConcept)
                    result)]
      (if vs-not-found?
        (throw (ResourceNotFoundException.
                 ^String (get result "message")
                 (fhir/build-operation-outcome (get result "issues"))))
        (fhir/map->parameters result'))))
  ;;;;;;;;;;;;;;;;;;;;;;;
  ExpandValueSetOperation
  (^{:tag                                  org.hl7.fhir.r4.model.ValueSet
     ca.uhn.fhir.rest.annotation.Operation {:name "expand" :idempotent true}}
    expand [_this
            ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "url"}} url
            ^{:tag ca.uhn.fhir.rest.param.UriParam OperationParam {:name "context"}} context
            ^{:tag ca.uhn.fhir.rest.param.TokenParam OperationParam {:name "contextDirection"}} contextDirection
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "filter"}} param-filter
            ^{:tag ca.uhn.fhir.rest.param.DateParam OperationParam {:name "date"}} date
            ^{:tag ca.uhn.fhir.rest.param.NumberParam OperationParam {:name "offset"}} offset
            ^{:tag ca.uhn.fhir.rest.param.NumberParam OperationParam {:name "count"}} param-count
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "includeDesignations"}} includeDesignations
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "designation"}} designation
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "includeDefinition"}} includeDefinition
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "activeOnly"}} activeOnly
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "excludeNested"}} excludeNested
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "excludeNotForUI"}} excludeNotForUI
            ^{:tag ca.uhn.fhir.rest.param.StringParam OperationParam {:name "excludePostCoordinated"}} excludePostCoordinated
            ^{:tag ca.uhn.fhir.rest.param.TokenParam OperationParam {:name "displayLanguage"}} displayLanguage]
    (log/debug "valueset/$expand:" {:url url :filter param-filter :activeOnly activeOnly :displayLanguage displayLanguage})
    (let [ctx *tx-ctx*
          include-desig? (some-> includeDesignations .getValue fhir/parse-fhir-boolean)
          url' (some-> url .getValueAsUriDt .getValueAsString)
          active-only? (some-> activeOnly .getValue fhir/parse-fhir-boolean)
          exclude-nested-raw (some-> excludeNested .getValue)
          exclude-nested? (if exclude-nested-raw
                            (fhir/parse-fhir-boolean exclude-nested-raw)
                            true)]
      (if-let [results (registry/valueset-expand ctx {:url             url'
                                                       :activeOnly      active-only?
                                                       :filter          (some-> param-filter .getValue)
                                                       :displayLanguage (some-> displayLanguage .getValue)})]
        (let [vs-impl (registry/valueset ctx url')
              vs-meta (when vs-impl (protos/vs-resource vs-impl {}))
              used-systems (into #{} (keep :system results))
              cs-infos (map (fn [sys]
                              (if-let [cs (registry/codesystem ctx sys)]
                                (let [meta (protos/cs-resource cs {})
                                      ver (get meta "version")
                                      uri (if ver (str sys "|" ver) sys)]
                                  {:uri uri :meta meta})
                                {:uri sys :meta nil}))
                            used-systems)
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
                                     exclude-nested-raw
                                     (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                             (.setName "excludeNested")
                                             (.setValue (BooleanType. (boolean exclude-nested?)))))
                                     (some? active-only?)
                                     (conj (doto (ValueSet$ValueSetExpansionParameterComponent.)
                                             (.setName "activeOnly")
                                             (.setValue (BooleanType. (boolean active-only?))))))
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
  (doto (proxy [TerminologyCapabilities IBaseConformance] [])
    (.setStatus Enumerations$PublicationStatus/ACTIVE)
    (.setVersion hades-version)
    (.setName "Hades")
    (.setTitle "Hades FHIR Terminology Server")
    (.setKind (org.hl7.fhir.r4.model.TerminologyCapabilities$CapabilityStatementKind/INSTANCE))
    (.addCodeSystem (doto (TerminologyCapabilities$TerminologyCapabilitiesCodeSystemComponent.)
                      (.setUri "http://snomed.info/sct")))))

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
      (.setResourceProviders this [(CodeSystemResourceProvider. svc)
                                   (ValueSetResourceProvider. svc)
                                   (ConceptMapResourceProvider. svc)])
      (.setServerConformanceProvider this (HadesConformanceProvider. this))
      (log/debug "Resource providers:" (seq (.getResourceProviders this))))
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
