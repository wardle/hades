(ns com.eldrix.hades.metadata
  "Build CapabilityStatement and TerminologyCapabilities resources as
  string-keyed maps ready for FHIR JSON serialisation."
  (:require [com.eldrix.hades.registry :as registry]))

(def ^:private hades-version "1.4.0-SNAPSHOT")

(def ^:private fhir-version "4.0.1")

(defn- now-iso []
  (str (java.time.Instant/now)))

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

(defn capability-statement
  "Build a FHIR CapabilityStatement map describing this server."
  [{:keys [url]}]
  (let [ts (now-iso)]
    (cond-> {"resourceType" "CapabilityStatement"
             "status"       "active"
             "date"         ts
             "publisher"    "Not provided"
             "kind"         "instance"
             "software"     {"name"        "Hades"
                             "version"     hades-version
                             "releaseDate" ts}   ;; TODO: use a proper release date from build
             "fhirVersion"  fhir-version
             "format"       ["application/fhir+json" "json"]
             "rest"         [{"mode" "server"
                              "resource"
                              [{"type"      "CodeSystem"
                                "profile"   "http://hl7.org/fhir/StructureDefinition/CodeSystem"
                                "operation" cs-operations}
                               {"type"      "ValueSet"
                                "profile"   "http://hl7.org/fhir/StructureDefinition/ValueSet"
                                "operation" vs-operations}
                               {"type"      "ConceptMap"
                                "profile"   "http://hl7.org/fhir/StructureDefinition/ConceptMap"
                                "operation" cm-operations}]}]}
      url (assoc "implementation" {"description" "Hades FHIR terminology server"
                                   "url"         url}))))

(def ^:private expansion-parameter-names
  ["activeOnly" "check-system-version" "count" "displayLanguage"
   "excludeNested" "force-system-version" "includeDefinition"
   "includeDesignations" "offset" "property" "system-version" "tx-resource"])

(defn terminology-capabilities
  "Build a FHIR TerminologyCapabilities map describing terminology behaviour."
  []
  {"resourceType" "TerminologyCapabilities"
   "status"       "active"
   "date"         (now-iso)  ;; TODO: use proper release date from build metadata
   "version"      hades-version
   "name"         "Hades"
   "title"        "Hades FHIR Terminology Server"
   "kind"         "instance"
   "codeSystem"   (mapv (fn [uri] {"uri" uri}) (sort (keys @registry/codesystems)))
   "expansion"    {"parameter"
                   (mapv (fn [p] {"name" p}) expansion-parameter-names)}})
