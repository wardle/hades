(ns com.eldrix.hades.core
  "Implementation of a FHIR terminology server.
  See https://hl7.org/fhir/terminology-service.html"
  (:gen-class)
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.convert :as convert]
            [com.eldrix.hermes.terminology :as terminology]
            [com.eldrix.hermes.service :as svc]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.service SnomedService)
           (ca.uhn.fhir.context FhirContext)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (ca.uhn.fhir.rest.server RestfulServer IResourceProvider)
           (javax.servlet Servlet)
           (ca.uhn.fhir.rest.server.interceptor ResponseHighlighterInterceptor)
           (org.eclipse.jetty.server Server ServerConnector)
           (ca.uhn.fhir.rest.annotation OperationParam)
           (org.hl7.fhir.r4.model CodeSystem ValueSet ValueSet$ValueSetExpansionComponent)
           (java.util Locale)))

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
                                           ^ca.uhn.fhir.rest.param.TokenParam displayLanguage
                                           ]))

(deftype CodeSystemResourceProvider [^SnomedService svc]
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
      (convert/lookup :svc svc :code (Long/parseLong code') :system system' :displayLanguage displayLanguage)))

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
    (cond
      (and codeA codeB system)
      (convert/subsumes? :svc svc :systemA (.getValue system) :codeA (.getValue codeA) :systemB (.getValue system) :codeB (.getValue codeB))
      (and codingA codingB)
      (convert/subsumes? :svc svc :systemA (.getSystem codingA) :codeA (.getValue codingA) :systemB (.getSystem codingB) :codeB (.getValue codingB)))))

(deftype ValueSetResourceProvider [^SnomedService svc]
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
    (print "valueset/$expand:" {:url url :filter param-filter :activeOnly activeOnly :displayLanguage displayLanguage})
    (when-let [constraint (convert/parse-implicit-value-set (.getValue url))]
      (let [constraint' (if-not param-filter
                          constraint
                          (str constraint " {{ term = \"" (.getValue param-filter) "\", type = syn, dialect = (" (or displayLanguage (.toLanguageTag (Locale/getDefault))) ")  }}"))
            _ (println "constraint = " constraint')
            results (svc/search svc {:constraint constraint'})
            components (map convert/result->vs-component results)]
        (println "results: " results)
        (println "components: " components)
        (doto (ValueSet.)
          (.setExpansion (doto (ValueSet$ValueSetExpansionComponent.)
                           (.setTotal (count results))
                           (.setContains components)))))))) ;; components = ValueSetExpansionContainsComponent

(defn ^Servlet make-r4-servlet [^SnomedService svc]
  (proxy [RestfulServer] [(FhirContext/forR4)]
    (initialize []
      (log/info "Initialising HL7 FHIR R4 server; providers: CodeSystem ValueSet")
      (.setResourceProviders this [(CodeSystemResourceProvider. svc) (ValueSetResourceProvider. svc)])
      (log/debug "Resource providers:" (seq (.getResourceProviders this)))
      (.registerInterceptor this (ResponseHighlighterInterceptor.)))))

(defn ^Server make-server [^SnomedService svc {:keys [port]}]
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
        (System/exit 1))
    (let [[index-path port-str] args
          port (Integer/parseInt port-str)
          svc (terminology/open index-path)
          server (make-server svc {:port port})]
      (.start server))))

(comment

  (def svc (com.eldrix.hermes.terminology/open "/Users/mark/Dev/hermes/snomed.db"))

  (def server (make-server svc {:port 8080}))
  (.start server)
  (.stop server)

  (do
    (.stop server)
    (def server (make-server svc {:port 8080}))
    (.start server))

  (svc/search svc {:s "mnd"})
  (svc/getConcept svc 24700007)

  (svc/getPreferredSynonym svc 233753001 "en")
  (svc/getReleaseInformation svc)
  (keys (svc/getExtendedConcept svc 138875005))
  (get-in (svc/getExtendedConcept svc 24700007) [:parent-relationships com.eldrix.hermes.snomed/IsA])
  )
