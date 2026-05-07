(ns com.eldrix.hades.impl.metadata
  "Build CapabilityStatement and TerminologyCapabilities resources as
  string-keyed maps ready for FHIR JSON serialisation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private build-info
  (some-> (io/resource "com/eldrix/hades/version.edn") slurp edn/read-string))

(def ^:private hades-version (:version build-info "dev"))

(def ^:private release-date
  (or (:build-date build-info) (str (java.time.Instant/now))))

(def ^:private fhir-version "4.0.1")

(def ^:private cs-operations
  [{"name"       "lookup"
    "definition" "http://hl7.org/fhir/OperationDefinition/CodeSystem-lookup"}
   {"name"       "validate-code"
    "definition" "http://hl7.org/fhir/OperationDefinition/CodeSystem-validate-code"}
   {"name"       "subsumes"
    "definition" "http://hl7.org/fhir/OperationDefinition/CodeSystem-subsumes"}])

(def ^:private vs-operations
  [{"name"       "expand"
    "definition" "http://hl7.org/fhir/OperationDefinition/ValueSet-expand"}
   {"name"       "validate-code"
    "definition" "http://hl7.org/fhir/OperationDefinition/ValueSet-validate-code"}])

(def ^:private cm-operations
  [{"name"       "translate"
    "definition" "http://hl7.org/fhir/OperationDefinition/ConceptMap-translate"}])

(def ^:private search-interaction
  [{"code" "search-type"}])

(def ^:private search-params
  [{"name" "url"         "type" "uri"}
   {"name" "version"     "type" "token"}
   {"name" "status"      "type" "token"}
   {"name" "name"        "type" "string"}
   {"name" "title"       "type" "string"}
   {"name" "description" "type" "string"}])

(defn capability-statement
  "Build a FHIR CapabilityStatement map describing this server."
  [{:keys [url]}]
  (cond-> {"resourceType" "CapabilityStatement"
           "status"       "active"
           "date"         release-date
           "publisher"    "Not provided"
           "kind"         "instance"
           "software"     {"name"        "Hades"
                           "version"     hades-version
                           "releaseDate" release-date}
           "fhirVersion"  fhir-version
           "format"       ["application/fhir+json" "json"]
           "rest"         [{"mode" "server"
                            "resource"
                            [{"type"        "CodeSystem"
                              "profile"     "http://hl7.org/fhir/StructureDefinition/CodeSystem"
                              "interaction" search-interaction
                              "searchParam" search-params
                              "operation"   cs-operations}
                             {"type"        "ValueSet"
                              "profile"     "http://hl7.org/fhir/StructureDefinition/ValueSet"
                              "interaction" search-interaction
                              "searchParam" search-params
                              "operation"   vs-operations}
                             {"type"      "ConceptMap"
                              "profile"   "http://hl7.org/fhir/StructureDefinition/ConceptMap"
                              "operation" cm-operations}]}]}
    url (assoc "implementation" {"description" "Hades FHIR terminology server"
                                 "url"         url})))

(def ^:private expansion-parameter-names
  ["activeOnly" "check-system-version" "count" "displayLanguage"
   "excludeNested" "force-system-version" "includeDefinition"
   "includeDesignations" "offset" "property" "system-version" "tx-resource"])

(defn terminology-capabilities
  "Build a FHIR TerminologyCapabilities map describing terminology
  behaviour. `svc` is the service whose registered CodeSystem URLs
  populate the listing."
  [svc]
  (let [uris (sort (keep (fn [k]
                           (when-not (str/includes? k "|") k))
                         (keys (:codesystems svc))))]
    {"resourceType" "TerminologyCapabilities"
     "status"       "active"
     "date"         release-date
     "version"      hades-version
     "name"         "Hades"
     "title"        "Hades FHIR Terminology Server"
     "kind"         "instance"
     "codeSystem"   (mapv (fn [uri] {"uri" uri}) uris)
     "expansion"    {"parameter"
                     (mapv (fn [p] {"name" p}) expansion-parameter-names)}}))
