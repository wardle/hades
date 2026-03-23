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


;; ---------------------------------------------------------------------------
;; Input specs (operation parameters)
;; ---------------------------------------------------------------------------

(s/def ::url string?)
(s/def ::code string?)
(s/def ::system string?)
(s/def ::version (s/nilable string?))
(s/def ::display (s/nilable string?))
(s/def ::userSelected boolean?)
(s/def ::date #(instance? LocalDate %))
(s/def ::displayLanguage string?)
(s/def ::properties (s/coll-of ::code))
(s/def ::canonical string?)
(s/def ::useSupplements (s/coll-of ::canonical))
(s/def ::codesystem-lookup (s/keys :req-un [::code ::system]
                                   :opt-un [::version ::date ::displayLanguage ::properties ::useSupplements]))

;; ---------------------------------------------------------------------------
;; Return value specs (data contracts between layers)
;; ---------------------------------------------------------------------------

;; Issue — structured problem report used in validate-code and expansion results.
;; Matches the FHIR OperationOutcome issue structure.
;; Note: :type uses a namespaced spec to avoid clashing with clojure.core/type.
(s/def ::severity #{"fatal" "error" "warning" "information"})
(s/def ::type #{"code-invalid" "invalid" "not-found" "not-supported"
                 "business-rule" "exception" "processing"})
(s/def ::details-code string?)
(s/def ::text string?)
(s/def ::expression (s/coll-of string?))
(s/def ::issue
  (s/keys :req-un [::severity ::type ::details-code ::text]
          :opt-un [::expression]))
(s/def ::issues (s/coll-of ::issue))

;; Return value code — FHIR codes are strings but hades currently uses keywords
;; in results (e.g. :73211009). This will be migrated to strings in future; for
;; now the return specs accept keywords. The input ::code spec stays string?.
(s/def ::code-or-kw (s/or :string string? :keyword keyword?))

;; Validate-code result — returned by cs-validate-code and vs-validate-code.
;; Protocol impls must return a complete result; no downstream patching.
(s/def ::result boolean?)
(s/def ::inactive boolean?)
(s/def ::inactive-status (s/nilable #{"inactive" "retired" "deprecated"}))
(s/def ::normalized-code (s/or :string string? :keyword keyword?))
(s/def ::message string?)
(s/def ::validate-result
  (s/and (s/keys :req-un [::result]
                 :opt-un [::system ::version ::display
                          ::inactive ::inactive-status ::normalized-code
                          ::message ::issues])
         ;; :code in results is keyword (migration: will become string)
         #(or (nil? (:code %)) (s/valid? ::code-or-kw (:code %)))))

;; Designation — an alternative name for a concept, typically in a
;; different language or for a different use (synonym, FSN, etc.).
;; Mirrors FHIR CodeSystem.concept.designation.
(s/def ::language keyword?)
(s/def ::value string?)
(s/def ::use (s/keys :req-un [::system ::code] :opt-un [::display]))
(s/def ::designation
  (s/keys :req-un [::value]
          :opt-un [::language ::use]))

;; Property — a concept property returned by $lookup.
;; :code is the property name (keyword), :value is the property value
;; (boolean, string, keyword for coded values, or number).
;; Optional :description provides a human-readable label for coded values.
;; Optional :code-display provides the display for the property type itself.
(s/def ::property-code (s/or :keyword keyword? :string string?))
(s/def ::property-value (s/or :boolean boolean? :string string?
                              :keyword keyword? :number number?))
(s/def ::property
  (s/and #(contains? % :code)
         #(s/valid? ::property-code (:code %))
         #(contains? % :value)
         #(s/valid? ::property-value (:value %))))

;; Lookup result — returned by cs-lookup.
(s/def ::name (s/nilable string?))
(s/def ::definition (s/nilable string?))
(s/def ::abstract boolean?)
(s/def ::properties (s/nilable (s/coll-of ::property)))
(s/def ::designations (s/nilable (s/coll-of ::designation)))
(s/def ::lookup-result
  (s/and (s/keys :req-un [::display ::system]
                 :opt-un [::name ::version ::definition ::abstract
                          ::properties ::designations])
         ;; :code in results is keyword (migration: will become string)
         #(s/valid? ::code-or-kw (:code %))))

;; Expansion concept — a single entry in an expansion result.
;; :code here is always a string (compose engine returns strings).
(s/def ::expansion-concept
  (s/keys :req-un [::code ::system]
          :opt-un [::display ::version ::inactive ::inactive-status
                   ::abstract ::designations ::properties]))

;; Used codesystem — metadata about a CodeSystem consulted during expansion.
(s/def ::uri string?)
(s/def ::status #{"active" "draft" "retired"})
(s/def ::experimental boolean?)
(s/def ::used-codesystem
  (s/keys :req-un [::uri]
          :opt-un [::status ::experimental]))

;; Compose pin — a system+version pair locked by the compose definition.
(s/def ::compose-pin (s/keys :req-un [::system] :opt-un [::version]))

;; Expansion result — returned by vs-expand. A map, not a bare seq.
;; Carries concepts plus metadata so the server layer doesn't need to
;; re-derive used-codesystems or compose-pinned versions.
(s/def ::concepts (s/coll-of ::expansion-concept))
(s/def ::total nat-int?)
(s/def ::used-codesystems (s/coll-of ::used-codesystem))
(s/def ::used-valuesets (s/coll-of string?))
(s/def ::compose-pins (s/coll-of ::compose-pin))
(s/def ::expansion-result
  (s/keys :req-un [::concepts]
          :opt-un [::total ::used-codesystems ::used-valuesets
                   ::compose-pins ::issues]))

;; Resource metadata — returned by cs-resource and vs-resource.
(s/def ::title (s/nilable string?))
(s/def ::description (s/nilable string?))
(s/def ::resource-meta
  (s/keys :opt-un [::url ::version ::name ::title ::status
                   ::description ::experimental]))



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
  (vs-expand [this ctx params]
    "The definition of a value set is used to create a simple collection of
    codes suitable for use for data entry or validation.
    ctx is the overlay/request context (::registry/ctx), or nil.")
  (vs-validate-code [this ctx params]
    "Validate that a coded value is in the set of codes allowed by a value set.
    ctx is the overlay/request context (::registry/ctx), or nil."))

(defprotocol ConceptMap
  :extend-via-metadata true
  (cm-resource [this params])
  (cm-translate [this params]))


