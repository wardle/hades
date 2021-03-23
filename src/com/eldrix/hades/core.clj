(ns com.eldrix.hades.core
  "Implementation of a FHIR terminology server.
  See https://hl7.org/fhir/terminology-service.html"
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
           (org.hl7.fhir.r4.model CodeSystem Parameters)))

(definterface LookupCodeSystemOperation
  (^org.hl7.fhir.r4.model.Parameters lookup [^ca.uhn.fhir.rest.param.StringParam code
                                             ^ca.uhn.fhir.rest.param.UriParam system
                                             ^ca.uhn.fhir.rest.param.StringParam version
                                             ^ca.uhn.fhir.rest.param.TokenParam coding
                                             ^ca.uhn.fhir.rest.param.StringParam displayLanguage
                                             ^ca.uhn.fhir.rest.param.StringAndListParam property]))

(deftype CodeSystemResourceProvider [^SnomedService svc]
  IResourceProvider
  (getResourceType [_this] CodeSystem)
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
    (println "code system $lookup operation: " {:code code :system system :version version :coding coding :lang displayLanguage :properties property})
    (convert/lookup :svc svc :code (Long/parseLong (.getValue code)) :system (.getValue system))))

(defn ^Servlet make-r4-servlet [^SnomedService svc]
  (proxy [RestfulServer] [(FhirContext/forR4)]
    (initialize []
      (log/info "Initialising HL7 FHIR R4 server; providers: CodeSystem")
      (.setResourceProviders this [(CodeSystemResourceProvider. svc)])
      (println (seq (.getResourceProviders this)))
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
  (if-not (= 3 (count args))
    (do (println "Usage: clj -M:fhir-r4 <snomed-index-path> <port>")
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

  (svc/search svc {:s "mnd"})
  (svc/getConcept svc 24700007)

  (svc/getConcept svc 24700007)
  (svc/getReleaseInformation svc)
  (keys (svc/getExtendedConcept svc 138875005))
  (get-in (svc/getExtendedConcept svc 24700007) [:parent-relationships com.eldrix.hermes.snomed/IsA])
  )
