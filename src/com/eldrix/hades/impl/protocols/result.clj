(ns com.eldrix.hades.impl.protocols.result
  "Specs for protocol return values — the data contracts between
  layers. Every keyword in this namespace describes an output field
  produced by a protocol method or a `core` operation. Sibling input
  specs live in `impl.protocols.input`. Splitting input vs result
  spec namespaces eliminates the field-name collisions that come from
  using the same word (`:code`, `:properties`, `:designations`) for
  different shapes."
  (:require [clojure.spec.alpha :as s]))

;; ---------------------------------------------------------------------------
;; Scalar fields shared across result shapes
;; ---------------------------------------------------------------------------

(s/def ::url string?)
(s/def ::system string?)
(s/def ::version (s/nilable string?))
(s/def ::display (s/nilable string?))
(s/def ::name (s/nilable string?))
(s/def ::title (s/nilable string?))
(s/def ::definition (s/nilable string?))
(s/def ::description (s/nilable string?))
(s/def ::status #{"active" "draft" "retired"})
(s/def ::experimental boolean?)
(s/def ::standards-status (s/nilable string?))
(s/def ::abstract boolean?)
(s/def ::message string?)

;; FHIR `code` values are strings, but Hades currently surfaces them as
;; keywords in result maps (e.g. `:73211009`). The string-only form is
;; a future migration; for now the result-side spec accepts either.
(s/def ::code (s/or :string string? :keyword keyword?))
(s/def ::normalized-code ::code)

(s/def ::inactive boolean?)
(s/def ::inactive-status (s/nilable #{"inactive" "retired" "deprecated"}))
(s/def ::not-found boolean?)
(s/def ::x-unknown-system (s/nilable string?))
(s/def ::x-caused-by-unknown-system (s/nilable string?))

;; ---------------------------------------------------------------------------
;; Issue — structured problem report used in validate-code and
;; expansion results. Mirrors FHIR OperationOutcome.issue.
;; ---------------------------------------------------------------------------

(s/def ::severity #{"fatal" "error" "warning" "information"})
(s/def ::type #{"code-invalid" "invalid" "not-found" "not-supported"
                "business-rule" "exception" "processing" "informational"
                "too-costly"})
(s/def ::details-code string?)
(s/def ::text string?)
(s/def ::expression (s/coll-of string?))
(s/def ::message-id string?)

(s/def ::issue
  (s/keys :req-un [::severity ::type ::text]
          :opt-un [::details-code ::expression ::message-id]))
(s/def ::issues (s/coll-of ::issue))

;; ---------------------------------------------------------------------------
;; Designation + property — concept attachments returned by cs-lookup
;; ---------------------------------------------------------------------------

(s/def ::language keyword?)
(s/def ::value string?)
(s/def ::use (s/keys :req-un [::system ::code] :opt-un [::display]))
(s/def ::designation
  (s/keys :req-un [::value]
          :opt-un [::language ::use]))
(s/def ::designations (s/nilable (s/coll-of ::designation)))

(s/def ::property-code (s/or :keyword keyword? :string string?))
(s/def ::property-value (s/or :boolean boolean? :string string?
                              :keyword keyword? :number number?))
(s/def ::property
  (s/and #(contains? % :code)
         #(s/valid? ::property-code (:code %))
         #(contains? % :value)
         #(s/valid? ::property-value (:value %))))
(s/def ::properties (s/nilable (s/coll-of ::property)))

;; ---------------------------------------------------------------------------
;; Validate-code result — returned by cs-validate-code and vs-validate-code
;; ---------------------------------------------------------------------------

;; The `:result` field on a validate result is a boolean (valid? yes/no).
;; The `:result` field on a translate result is a boolean too. Same
;; field, same shape — just kept under one shared name here.
(s/def ::result boolean?)

(s/def ::validate
  (s/keys :req-un [::result]
          :opt-un [::code ::system ::version ::display
                   ::inactive ::inactive-status ::normalized-code
                   ::message ::issues ::not-found
                   ::x-unknown-system ::x-caused-by-unknown-system]))

;; ---------------------------------------------------------------------------
;; Lookup result — returned by core/lookup (composite cs-lookup).
;;
;; Provider-level `protos/cs-lookup` still returns nil on miss; the
;; composite synthesises a self-describing not-found map so the HTTP
;; handler can render a 404 without re-probing the registry.
;; ---------------------------------------------------------------------------

(s/def ::not-found-reason #{:unknown-system :unknown-code})

(s/def ::lookup
  (s/or :found     (s/keys :req-un [::code ::display ::system]
                           :opt-un [::name ::version ::definition ::abstract
                                    ::properties ::designations])
        :not-found (s/keys :req-un [::not-found ::not-found-reason]
                           :opt-un [::system ::code ::message ::issues])))

;; ---------------------------------------------------------------------------
;; Expansion result — returned by vs-expand
;;
;; A map, not a bare seq. Carries concepts plus metadata so HTTP /
;; wire layers don't need to re-derive used-codesystems or
;; compose-pinned versions.
;;
;; `:multi-version-systems` is the set of system canonicals that
;; appear at more than one version in the merged expansion (pre-paging).
;; The vs-validate-code path uses it to switch on overload semantics —
;; when the same system contributes multiple versions, version-specific
;; matching rules apply.
;; ---------------------------------------------------------------------------

(s/def ::expansion-concept
  (s/keys :req-un [::code ::system]
          :opt-un [::display ::version ::inactive ::inactive-status
                   ::abstract ::designations ::properties]))

(s/def ::concepts (s/coll-of ::expansion-concept))
(s/def ::total nat-int?)
(s/def ::display-language (s/nilable string?))

(s/def ::uri string?)
(s/def ::used-codesystem
  (s/keys :req-un [::uri]
          :opt-un [::status ::experimental ::standards-status]))
(s/def ::used-codesystems (s/coll-of ::used-codesystem))
(s/def ::used-valuesets (s/coll-of string?))

(s/def ::compose-pin
  (s/keys :req-un [::system] :opt-un [::version]))
(s/def ::compose-pins (s/coll-of ::compose-pin))

(s/def ::multi-version-systems (s/coll-of string? :kind set?))

(s/def ::expansion
  (s/keys :req-un [::concepts]
          :opt-un [::total ::used-codesystems ::used-valuesets
                   ::compose-pins ::issues ::display-language
                   ::multi-version-systems]))

;; ---------------------------------------------------------------------------
;; Match result — returned by cs-find-matches. `:total` is optional and
;; populated only when cheaply knowable by the provider.
;; ---------------------------------------------------------------------------

(s/def ::match
  (s/keys :req-un [::concepts]
          :opt-un [::total ::issues]))

;; ---------------------------------------------------------------------------
;; ConceptMap $translate — a single match entry plus the overall result.
;; `:equivalence` uses the FHIR R4 ConceptMapEquivalence value set; R5
;; servers may downstream-translate to the newer `:relationship` codes.
;; ---------------------------------------------------------------------------

(s/def ::equivalence
  #{"relatedto" "equivalent" "equal" "wider" "subsumes"
    "narrower" "specializes" "inexact" "unmatched" "disjoint"})

(s/def ::translate-match
  (s/keys :req-un [::equivalence ::system ::code]
          :opt-un [::display ::version]))

(s/def ::matches (s/coll-of ::translate-match))

(s/def ::translate
  (s/keys :req-un [::result]
          :opt-un [::message ::matches ::issues]))

;; ---------------------------------------------------------------------------
;; Resource metadata — returned by cs-resource and vs-resource
;; ---------------------------------------------------------------------------

(s/def ::compose (s/nilable map?))

(s/def ::resource-meta
  (s/keys :opt-un [::url ::version ::name ::title ::status
                   ::description ::experimental
                   ::compose ::standards-status]))

;; ---------------------------------------------------------------------------
;; Search result — returned by core/search-code-systems / search-value-sets
;; ---------------------------------------------------------------------------

(s/def ::resources (s/coll-of ::resource-meta))
(s/def ::search-result
  (s/keys :req-un [::total ::resources]))

;; ---------------------------------------------------------------------------
;; Provider self-description — registry-key tuples each provider
;; exposes. Three sibling shapes, one per resource type. Returned from
;; the matching `cs-metadata` / `vs-metadata` / `cm-metadata` protocol
;; method at registration time; consumers (boot driver, request-scoped
;; overlay interceptor) reduce them into the registry's keyspace.
;; ---------------------------------------------------------------------------

(s/def ::content (s/nilable string?))

;; `:implicit?` flags a routing-only entry that the provider advertises
;; so dispatch finds it (e.g. Hermes' "all of SNOMED" implicit
;; ValueSet) but where `*-resource` does not produce a published
;; resource. Search and other catalogue listings filter these out at
;; the tuple level.
(s/def ::implicit? boolean?)

;; CodeSystem metadata — `:content` distinguishes regular vs supplement
;; CodeSystems; `:supplements` is the canonical of the base when
;; `:content` is "supplement".
(s/def ::cs-metadata
  (s/keys :req-un [::url]
          :opt-un [::version ::content ::supplements ::implicit?]))

;; ValueSet metadata — only `:url`/`:version` for now; future fields
;; (binding strength, jurisdiction) can land here without changing the
;; registration contract.
(s/def ::vs-metadata
  (s/keys :req-un [::url]
          :opt-un [::version ::implicit?]))

;; ConceptMap metadata — providers may expose multiple ConceptMaps
;; (e.g. forward + reverse of a SNOMED map refset); each tuple gets
;; indexed by both url and (source, target) system pair so callers can
;; $translate with either shape of params.
(s/def ::target string?)
(s/def ::cm-metadata
  (s/keys :req-un [::url ::system ::target]
          :opt-un [::title ::description ::version]))

;; Used by the supplement provider metadata: `:supplements` is the
;; canonical of the base CodeSystem when `:content` is "supplement".
(s/def ::supplements (s/coll-of string?))
