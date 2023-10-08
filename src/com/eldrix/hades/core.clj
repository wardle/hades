(ns com.eldrix.hades.core
  "Implementation of a FHIR terminology server.
  See https://hl7.org/fhir/terminology-service.html"
  (:gen-class)
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.fhir :as fhir]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hades.registry :as registry]
            [com.eldrix.hades.snomed :as snomed])
  (:import (ca.uhn.fhir.context FhirContext)
           (ca.uhn.fhir.rest.annotation OperationParam)
           (ca.uhn.fhir.rest.server RestfulServer IResourceProvider)
           (ca.uhn.fhir.rest.server.interceptor ResponseHighlighterInterceptor)
           (javax.servlet Servlet)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.hl7.fhir.r4.model CodeSystem OperationOutcome ValueSet ValueSet$ValueSetExpansionComponent ConceptMap)))



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
    (let [code' (or (when code (.getValue code)) (when coding (.getValue coding)))
          system' (or (when system (.getValue system)) (when coding (.getSystem coding)))]
      (fhir/map->parameters (registry/codesystem-lookup {:system system' :code code' :displayLanguage displayLanguage}))))

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
    (fhir/map->parameters
      (cond
        (and codeA codeB system)
        (registry/codesystem-subsumes {:systemA (.getValue system) :codeA (.getValue codeA) :systemB (.getValue system) :codeB (.getValue codeB)})
        (and codingA codingB)
        (registry/codesystem-subsumes {:systemA (.getSystem codingA) :codeA (.getValue codingA) :systemB (.getSystem codingB) :codeB (.getValue codingB)})))))

(deftype ValueSetResourceProvider [svc]
  IResourceProvider
  (getResourceType [_this] ValueSet)
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
    (if-let [results (registry/valueset-expand {:url             (some-> url .getValueAsUriDt .getValueAsString)
                                                :activeOnly      (some-> activeOnly .getValue fhir/parse-fhir-boolean)
                                                :filter          (some-> param-filter .getValue)
                                                :displayLanguage (some-> displayLanguage .getValue)})]
      (doto (ValueSet.)
        (.setExpansion (doto (ValueSet$ValueSetExpansionComponent.)
                         (.setTotal (count results))
                         (.setContains (map fhir/map->vs-expansion results))))))))

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

(defn make-r4-servlet ^Servlet [svc]
  (proxy [RestfulServer] [(FhirContext/forR4)]
    (initialize []
      (log/info "Initialising HL7 FHIR R4 server; providers: CodeSystem ValueSet")
      (.setResourceProviders this [(CodeSystemResourceProvider. svc)
                                   (ValueSetResourceProvider. svc)
                                   (ConceptMapResourceProvider. svc)])
      (log/debug "Resource providers:" (seq (.getResourceProviders this)))
      (.registerInterceptor this (ResponseHighlighterInterceptor.)))))

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

(defn -main [& args]
  (if-not (= 2 (count args))
    (do (println "Usage: clj -M:run <snomed-index-path> <port>")
        (println "   or: java -jar hades-server.jar <snomed-index-path> <port>")
        (System/exit 1))
    (let [[index-path port-str] args
          port (Integer/parseInt port-str)
          svc (hermes/open index-path)
          snomed (snomed/->HermesService svc)
          server (make-server svc {:port port})]
      (registry/register-codesystem "http://snomed.info/sct" snomed)
      (registry/register-codesystem "sct" snomed)
      (registry/register-valueset "http://snomed.info/sct" snomed)
      (registry/register-valueset "sct" snomed)
      (.start server))))

(comment

  (def svc (hermes/open "/Users/mark/Dev/hermes/snomed.db"))

  (def server (make-server svc {:port 8080}))
  (.start server)
  (.stop server)

  (do
    (.stop server)
    (def server (make-server svc {:port 8080}))
    (.start server))

  (hermes/search svc {:s "mnd"})
  (hermes/concept svc 24700007)

  (hermes/preferred-synonym svc 233753001 "en")
  (hermes/release-information svc)
  (keys (hermes/extended-concept svc 138875005))
  (get-in (hermes/extended-concept svc 24700007) [:parent-relationships com.eldrix.hermes.snomed/IsA]))

