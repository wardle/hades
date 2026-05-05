(ns com.eldrix.hades.impl.protocols.input
  "Specs for operation parameters — what callers send into protocol
  methods and into the public `core` operation functions.

  Every keyword in this namespace describes an *input* field. Sibling
  result-shape specs live in `impl.protocols.result`. Splitting input
  and result spec namespaces avoids the keyword collisions that arise
  when the same field name (`:code`, `:properties`, `:designations`)
  has different shapes on the way in vs the way out."
  (:require [clojure.spec.alpha :as s])
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Scalar fields
;; ---------------------------------------------------------------------------

(s/def ::url string?)
(s/def ::code string?)
(s/def ::system string?)
(s/def ::version (s/nilable string?))
(s/def ::display (s/nilable string?))
(s/def ::userSelected boolean?)
(s/def ::date #(instance? LocalDate %))
(s/def ::displayLanguage string?)
(s/def ::canonical string?)

;; `:properties` on input is the list of property *names* the caller
;; wants `cs-lookup` / `cs-find-matches` to surface in the result.
;; Result-side `:properties` is a list of full property maps and lives
;; under `result/properties`; the namespace separation prevents
;; collision.
(s/def ::properties (s/coll-of ::code))

(s/def ::useSupplements (s/coll-of ::canonical))
;; Supplement canonicals declared on a ValueSet via the
;; http://hl7.org/fhir/StructureDefinition/valueset-supplement extension.
(s/def ::supplements (s/coll-of ::canonical))

;; ---------------------------------------------------------------------------
;; Operation parameter shapes
;; ---------------------------------------------------------------------------

(s/def ::codesystem-lookup
  (s/keys :req-un [::code ::system]
          :opt-un [::version ::date ::displayLanguage ::properties ::useSupplements]))

;; ---------------------------------------------------------------------------
;; Query — the unified request passed to cs-find-matches. Carries every
;; constraint a provider must honour: concept-level filters, request
;; text, display language, requested properties, pagination, active
;; scope. Providers MUST return concepts that satisfy the ENTIRE query.
;;
;; offset/skip is deliberately NOT part of the Query. Lucene (via
;; Hermes) currently lacks efficient search-after pagination, so
;; emulating skip in a provider would mean materialising the discarded
;; prefix. Compose applies offset itself after dedup/exclude — also the
;; only correct place for multi-include expansions.
;; ---------------------------------------------------------------------------

(s/def ::filter-property string?)
(s/def ::filter-op string?)
(s/def ::filter-value string?)
(s/def ::filter-entry
  (s/keys :req-un [::filter-property ::filter-op ::filter-value]))
(s/def ::filters (s/nilable (s/coll-of ::filter-entry)))
(s/def ::text (s/nilable string?))
(s/def ::max-hits (s/nilable nat-int?))
(s/def ::active-only (s/nilable boolean?))

(s/def ::query
  (s/keys :req-un [::system]
          :opt-un [::version ::displayLanguage ::properties
                   ::filters ::text ::max-hits ::active-only]))
