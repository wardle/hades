(ns com.eldrix.hades.impl.protocols
  "Protocols defining a terminology service.

  Implementations participate in a Hades service by satisfying one or
  more of `CodeSystem`, `ValueSet`, and `ConceptMap`. The composite
  layer dispatches on URL/version, calls the matching protocol method,
  and aggregates cross-provider concerns.

  Spec definitions are split across three sibling namespaces so input
  and output field names don't collide:

    `impl.protocols`         — protocols + the loader/indexer
                                interchange (`::fhir-data`).
    `impl.protocols.input`   — operation parameter specs.
    `impl.protocols.result`  — protocol return-value specs.

  The HL7 FHIR TerminologyService is defined in
  https://hl7.org/fhir/terminology-service.html. These protocols are
  *based* on that definition but reflect Hades' internal abstractions:
  attributes are pluralised when one-to-many; callers split system/code
  and Coding shapes themselves rather than each impl handling both."
  (:require [clojure.spec.alpha :as s]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Loader-to-indexer interchange (fhir-data)
;;
;; `fhir-data` is the tagged map emitted by loaders and consumed by
;; the indexer. Variants describe a CodeSystem header, a single
;; concept (after recursive flattening), a ValueSet, or a ConceptMap.
;; This pipeline sits next to the protocol abstractions but isn't
;; itself an input or a return value, so the specs live here rather
;; than in `input` or `result`.
;; ---------------------------------------------------------------------------

(s/def ::url string?)
(s/def ::system string?)
(s/def ::code string?)
(s/def ::version (s/nilable string?))
(s/def ::display (s/nilable string?))
(s/def ::name (s/nilable string?))
(s/def ::title (s/nilable string?))
(s/def ::definition (s/nilable string?))
(s/def ::description (s/nilable string?))
(s/def ::status #{"active" "draft" "retired"})
(s/def ::experimental boolean?)
(s/def ::standards-status (s/nilable string?))
(s/def ::case-sensitive boolean?)
(s/def ::hierarchy-meaning (s/nilable string?))
(s/def ::content (s/nilable string?))
(s/def ::property-defs (s/coll-of map?))
(s/def ::filter-defs (s/coll-of map?))
(s/def ::metadata (s/nilable map?))
(s/def ::parent-code (s/nilable string?))

;; Canonical reference, optionally pinned with `|version`.
(s/def ::canonical-with-version
  (s/and string? #(re-matches #"[^|]+(\|.+)?" %)))

(s/def ::supplements-target (s/nilable ::canonical-with-version))

;; A `:source-path` is normally a filesystem path string, but the loader
;; uses sentinel keywords for synthetic origins (tx-resource POST bodies
;; and inline ValueSet parameters).
(s/def ::source-path
  (s/or :path string?
        :sentinel #{:tx-resource :inline-valueset}))

(s/def ::codesystem-meta-data
  (s/keys :req-un [::url]
          :opt-un [::version ::case-sensitive ::hierarchy-meaning ::content
                   ::name ::title ::description ::status ::experimental
                   ::standards-status ::property-defs ::filter-defs
                   ::metadata ::source-path]))

;; :designations / :properties on a concept are not in the s/keys form
;; — their names would collide with result-level `::result/designations`
;; / `::result/properties`. Indexer-level invariants:
;;  - :designations is a coll of result/designation maps
;;  - :properties is a coll of raw FHIR string-keyed property maps
(s/def ::concept-data
  (s/keys :req-un [::system ::code]
          :opt-un [::version ::display ::definition
                   ::parent-code ::source-path]))

(s/def ::concept-designation-data
  (s/keys :req-un [::system ::code]
          :opt-un [::version ::language ::use ::value ::source-path]))

(s/def ::ex any?)
(s/def ::stream-error-data
  (s/keys :req-un [::ex]))

(s/def ::compose map?)
(s/def ::expansion map?)

(s/def ::valueset-data
  (s/keys :req-un [::url]
          :opt-un [::version ::metadata ::compose ::expansion ::source-path]))

(s/def ::source-uri (s/nilable string?))
(s/def ::source-version (s/nilable string?))
(s/def ::target-uri (s/nilable string?))
(s/def ::target-version (s/nilable string?))
(s/def ::groups (s/coll-of map?))

(s/def ::conceptmap-data
  (s/keys :req-un [::url]
          :opt-un [::version ::source-uri ::source-version ::target-uri
                   ::target-version ::metadata ::groups ::source-path]))

(defmulti fhir-data-type :type)
(defmethod fhir-data-type :codesystem-meta [_] ::codesystem-meta-data)
(defmethod fhir-data-type :concept        [_] ::concept-data)
(defmethod fhir-data-type :concept-designation [_] ::concept-designation-data)
(defmethod fhir-data-type :valueset       [_] ::valueset-data)
(defmethod fhir-data-type :conceptmap     [_] ::conceptmap-data)
(defmethod fhir-data-type :stream-error   [_] ::stream-error-data)

(s/def ::fhir-data (s/multi-spec fhir-data-type :type))
(s/def ::fhir-data-batch (s/coll-of ::fhir-data :kind sequential?))

;; Canonical concept payload used by non-FHIR loaders and persistent
;; indexed providers. Currently identical to `::concept-data`; kept
;; under a distinct name so storage backends that need to diverge from
;; the FHIR-loader shape can without churning every concept consumer.
(s/def ::canonical-concept ::concept-data)

;; ---------------------------------------------------------------------------
;; Protocols
;; ---------------------------------------------------------------------------

(defprotocol CodeSystem
  "The CodeSystem resource is used to declare the existence of and
  describe a code system or code system supplement and its key
  properties, and optionally define a part or all of its content."
  :extend-via-metadata true
  (cs-metadata [this opts]
    "Return a reducible/seq of `::result/cs-metadata` maps — one per
    CodeSystem this provider exposes that survives `opts`.

    `opts` is a `::input/metadata-opts`: a small DSL the caller uses to
    push filters down so non-survivors are never realised. Providers
    MUST honour every opt they receive — callers trust the result is
    already filtered. `{}` (no opts) means \"everything\".

    Called by the boot driver at registration time (with `{}`) and by
    `search*` on the request hot path (with the request's URL/version
    + `:include-implicit? false`).")
  (cs-resource [this params]
    "Get description of codesystem and key properties as per
    https://hl7.org/fhir/codesystem.html")
  (cs-lookup [this params]
    "Given a code/system, get additional details about the concept,
    including definition, status, designations, and properties. One of
    the products of this operation is a full decomposition of a code
    from a structured terminology.")
  (cs-validate-code [this params]
    "Validate that a coded value is in the code system. The operation
    returns a result (true / false), an error message, and the
    recommended display for the code.")
  (cs-subsumes [this params]
    "Test the subsumption relationship between code/Coding A and
    code/Coding B given the semantics of subsumption in the underlying
    code system")
  (cs-find-matches [this query]
    "Return the concepts that satisfy the ENTIRE query
    (`::input/query`). Providers must honour every supplied constraint
    — filters, text, bounds, properties, activeOnly, displayLanguage —
    either natively or by delegating to shared helpers. Returns a
    `::result/match` map. Returning a lazy seq for `:concepts` is fine;
    only `:total` needs to be computed eagerly when known."))

(defprotocol ValueSet
  "A value set is selection of codes for use in a particular context."
  :extend-via-metadata true
  (vs-metadata [this opts]
    "Return a reducible/seq of `::result/vs-metadata` maps — one per
    ValueSet this provider exposes that survives `opts`. Same DSL +
    contract as `cs-metadata`.")
  (vs-resource [this params])
  (vs-expand [this svc params]
    "Expand a ValueSet to its constituent codes. `svc` is the
    TerminologyService (possibly with overlays applied via
    `core/with-overlays`); compose calls protocol methods on `svc`
    for cross-CodeSystem lookups.")
  (vs-validate-code [this svc params]
    "Validate that a coded value is in the set of codes allowed by a
    value set. `svc` is the TerminologyService (possibly with overlays
    applied)."))

(defprotocol ConceptMap
  "A ConceptMap resource describes mappings between concepts in
  different CodeSystems or ValueSets."
  :extend-via-metadata true
  (cm-metadata [this opts]
    "Return a reducible/seq of `::result/cm-metadata` maps — one per
    ConceptMap this provider exposes that survives `opts`. Same DSL +
    contract as `cs-metadata`. The composite uses these to build its
    url-based and (source, target) system-pair indices; a provider
    handling both directions of a map must emit two entries.")
  (cm-resource [this params])
  (cm-translate [this params]
    "Given a source Coding, return a `::result/translate`. Params carry
    `:url` (the ConceptMap canonical, possibly with implicit-form query
    parameters), `:system`, `:code`, and optionally `:target` and
    `:version`. Impls must return a complete `::result/translate` —
    no downstream patching."))
