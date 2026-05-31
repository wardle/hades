(ns com.eldrix.hades.protocols.input
  "Specs for operation parameters — what callers send into protocol
  methods and into the public `core` operation functions.

  Every keyword in this namespace describes an *input* field. Sibling
  result-shape specs live in `protocols.result`. Splitting input
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
;; wants `cs-lookup` / `cs-expand*` to surface in the result.
;; Result-side `:properties` is a list of full property maps and lives
;; under `result/properties`; the namespace separation prevents
;; collision.
(s/def ::properties (s/coll-of ::code))

(s/def ::useSupplements (s/coll-of ::canonical))

;; ---------------------------------------------------------------------------
;; Operation parameter shapes
;; ---------------------------------------------------------------------------

(s/def ::codesystem-lookup
  (s/keys :req-un [::code ::system]
          :opt-un [::version ::date ::displayLanguage ::properties ::useSupplements]))

;; ---------------------------------------------------------------------------
;; Query — the unified request passed to cs-expand*. Carries every
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

;; ---------------------------------------------------------------------------
;; Search params — flat map of FHIR search filter values + result-control
;; fields. Consumed by composite `search-code-systems` /
;; `search-value-sets`.
;;
;; Token fields (`:url`, `:version`, `:status`) match exactly. String
;; fields (`:name`, `:title`, `:description`) are `::string-filter` maps
;; — a `:value` with an optional `:modifier` (`:starts-with` (default),
;; `:exact`, `:contains`).
;;
;; `_count` / `_offset` paginate the merged result. Justification: an
;; unfiltered `GET /ValueSet` against the smoke catalogues (1,962 CSs +
;; 10,477 VSs) returns a 121 MB Bundle without a `_count` cap and 3.2
;; MB with `_summary=true`. Honouring `_count` is the difference
;; between a polite KB-sized response and saturating a workstation at
;; modest RPS. Defaults are applied at the HTTP layer, not here.
;; ---------------------------------------------------------------------------

;; String-typed search fields (`:name :title :description`) are FHIR
;; `string` parameters: a `:value` plus an optional `:modifier`. Per the
;; FHIR spec the modifier rides on the parameter name (`title:contains`),
;; defaults to starts-with when omitted, and is drawn from a closed
;; vocabulary. `:status` is a FHIR `token` — exact match, no modifier.
(s/def ::modifier #{:starts-with :exact :contains})
(s/def ::value string?)
(s/def ::string-filter
  (s/keys :req-un [::value] :opt-un [::modifier]))

(s/def ::status string?)
(s/def ::name ::string-filter)
(s/def ::title ::string-filter)
(s/def ::description ::string-filter)
(s/def ::_count nat-int?)
(s/def ::_offset nat-int?)
(s/def ::_summary string?)

(s/def ::search-params
  (s/keys :opt-un [::url ::version ::status
                   ::name ::title ::description
                   ::_count ::_offset ::_summary]))

;; ---------------------------------------------------------------------------
;; Metadata-opts — small DSL passed to `cs-metadata` / `vs-metadata` /
;; `cm-metadata`. Carries the search filters down to providers, which
;; apply them (in SQL, or an in-memory predicate) and emit only the
;; matching registration tuples.
;;
;; Providers MUST honour every opt they receive — callers trust the
;; result and don't re-filter. `{}` (or no keys) means "everything".
;;
;;   :url                — exact-match canonical URL
;;   :version            — exact-match version (alongside or without :url)
;;   :include-implicit?  — when false, drop entries flagged `:implicit?`;
;;                          default true so boot/status/cmd see them
;;   :status             — exact-match status token
;;   :name :title        — `::string-filter` (match per `:modifier`),
;;   :description          same semantics as `::search-params`
;;   :_count             — listing limit: a provider with a large catalogue
;;                          returns at most this many of its sorted-by-
;;                          `(url, version)` tuples (the composite passes
;;                          `offset + count` and applies the global offset
;;                          itself after merging). Absent for routing/index
;;                          callers, who want every match.
;; ---------------------------------------------------------------------------

(s/def ::include-implicit? boolean?)

(s/def ::metadata-opts
  (s/keys :opt-un [::url ::version ::include-implicit?
                   ::status ::name ::title ::description ::_count]))
