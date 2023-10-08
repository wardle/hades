(ns com.eldrix.hades.protocols
  "Protocols defining a terminology service.

  These definitions decouple the web server (currently using HAPI) from the
  underlying implementations which can then be dynamically registered at
  runtime.

  The HL7 FHIR TerminologyService is defined in https://hl7.org/fhir/terminology-service.html

  These protocols are *based* on the definitions of TerminologyService, but with
  some changes given that they reflect internal hades abstractions. Therefore:
  - attributes are pluralised when they are one-to-many relationships
  - duplication reduced; e.g. implementations do not need to support both
  system/code and Coding - it is expected that caller will do this."
  (:require [clojure.spec.alpha :as s])
  (:import (java.time LocalDate)))


(s/def ::code string?)
(s/def ::system string?)
(s/def ::version string?)
(s/def ::display string?)
(s/def ::userSelected boolean?)
(s/def ::date #(instance? LocalDate %))
(s/def ::displayLanguage string?)
(s/def ::properties (s/coll-of ::code))
(s/def ::canonical string?)
(s/def ::useSupplements (s/coll-of ::canonical))
(s/def ::codesystem-lookup (s/keys :req-un [::code ::system]
                                   :opt-un [::version ::date ::displayLanguage ::properties ::useSupplements]))



(defprotocol CodeSystem
  "The CodeSystem resource is used to declare the existence of and describe a
  code system or code system supplement and its key properties, and optionally
  define a part or all of its content."
  :extend-via-metadata true
  (cs-resource [this params]
    "Get description of codesystem and key properties as per
    https://hl7.org/fhir/codesystem.html")
  (cs-lookup [this params]
    "Given a code/system, get additional details about the concept,
    including definition, status, designations, and properties. One of the
    products of this operation is a full decomposition of a code from a
    structured terminology.")
  (cs-validate-code [this params]
    "Validate that a coded value is in the code system. The operation returns a
    result (true / false), an error message, and the recommended display for the
    code.")
  (cs-subsumes [this params]
    "Test the subsumption relationship between code/Coding A and code/Coding B
    given the semantics of subsumption in the underlying code system")
  (cs-find-matches [this params]
    "Given a set of properties (and text), return one or more possible matching
    codes"))

(defprotocol ValueSet
  "A value set is selection of codes for use in a particular context."
  :extend-via-metadata true
  (vs-resource [this params])
  (vs-expand [this params]
    "The definition of a value set is used to create a simple collection of
    codes suitable for use for data entry or validation.\n\n")
  (vs-validate-code [this params]
    "Validate that a coded value is in the set of codes allowed by a value set."))

(defprotocol ConceptMap
  :extend-via-metadata true
  (cm-resource [this params])
  (cm-translate [this params]))


