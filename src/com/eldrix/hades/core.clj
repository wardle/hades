(ns com.eldrix.hades.core
  "Public Clojure API for Hades — a FHIR terminology service.

  A Hades service is an opaque handle returned by `open`. It carries
  every CodeSystem, ValueSet and ConceptMap provider the service
  knows about, plus the cross-provider logic (NamingSystem alias
  resolution, version dispatch, supplements check, status warnings,
  CodeableConcept aggregation) needed to behave like a single FHIR
  terminology service.

  Convenience functions in this namespace cover the everyday FHIR
  terminology operations:

    - `lookup`                       CodeSystem $lookup
    - `validate-code`                CodeSystem $validate-code (no :url)
                                       or ValueSet $validate-code (:url present)
    - `subsumes`                     CodeSystem $subsumes
    - `find-matches`                 CodeSystem $find-matches
    - `expand`                       ValueSet $expand
    - `validate-codeable-concept`    ValueSet $validate-code with a CC
    - `translate`                    ConceptMap $translate

  All operation params and results are plain Clojure maps with keyword
  keys. Result maps conform to the specs re-exported under this
  namespace (`::lookup-result`, `::expansion-result`, etc.).

  `with-overlays` returns a derived service with extra in-memory
  resources layered on top of the base — used by the HTTP layer to
  scope tx-resource Parameters to a single request, but available to
  any caller who wants per-call provider overrides.

  `open` takes paths to built terminology artefacts. Provider assembly is
  an implementation concern; code that really needs it can use the
  relevant `impl` namespaces directly."
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.hades.impl.paths :as paths]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.protocols.result :as result]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Re-exported result specs — public stable contract for return values.
;; ---------------------------------------------------------------------------

;; clojure.spec lookups go through the keyword's namespace; we re-name
;; the keywords here so callers can `(s/valid? ::core/lookup-result …)`
;; without reaching into impl. The spec definitions live in
;; `impl/protocols/result`; these forms create same-spec aliases.
(s/def ::lookup-result      ::result/lookup)
(s/def ::validate-result    ::result/validate)
(s/def ::expansion-result   ::result/expansion)
(s/def ::translate-result   ::result/translate)
(s/def ::match-result       ::result/match)
(s/def ::issue              ::result/issue)
(s/def ::expansion-concept  ::result/expansion-concept)

;; ---------------------------------------------------------------------------
;; Service lifecycle
;; ---------------------------------------------------------------------------

(defn open
  "Open a Hades terminology service from built terminology artefact paths.

  Each path may point at a Hermes SNOMED DB, a FHIR terminology SQLite
  container, or a file/tree of FHIR JSON resources:

    (hades/open [\".hades/snomed-intl-20250201.db\"
                 \".hades/loinc-2.81.db\"])

  RF2 and LOINC release sources must be imported first. Provider-level
  construction lives in `impl` namespaces.

  Options (second arity):
    :supplements    — vector of `{:meta :lookup}` supplement entries
    :naming-systems — vector of resolver fns for OID/URN aliases
    :defaults       — map of bare canonical URL to version for disambiguation
    :metadata       — load report (returned by `metadata`)
    :closers        — extra close fns

  Returns an opaque, `Closeable` handle. Closing releases every
  provider that implements `java.io.Closeable` in reverse order
  plus any extra `:closers`. Subsequent closes are no-ops."
  ([paths] (paths/open-paths paths))
  ([paths opts] (paths/open-paths paths opts)))

(defn close
  "Release the service and every closeable provider it holds. Safe to
  call more than once; subsequent calls are no-ops."
  [svc]
  (when svc (.close ^java.io.Closeable svc)))

(defn metadata
  "Return the load report describing what `svc` knows about: the
  resources loaded by URL/version, any skipped entries, default
  bindings applied, and totals."
  [svc]
  (:metadata svc))

(defn with-overlays
  "Return a derived service layering the given providers on top of
  `svc`. Each overlay provider's `*-metadata` determines which URLs
  it covers; overlays win on exact-key match. The derived handle is
  a view — closing the base releases resources, closing the view
  does nothing. Used by the HTTP layer to scope FHIR `tx-resource`
  parameters to a single request."
  ([svc providers] (composite/with-overlays svc providers))
  ([svc providers opts] (composite/with-overlays svc providers opts)))

;; ---------------------------------------------------------------------------
;; Operations — convenience wrappers delegating to the protocols.
;;
;; Each function carries an `s/fdef` declaring its input/output
;; contract. The `:ret` specs are the documented return shape and are
;; exercised by `protocol_test.clj`'s generic `check-ret` helper, which
;; fetches them via `s/get-spec`.
;; ---------------------------------------------------------------------------

(s/fdef lookup
  :args (s/cat :svc some? :params map?)
  :ret  ::lookup-result)

(defn lookup
  "CodeSystem $lookup. `params` carries `:system :code` and optionally
  `:version :displayLanguage :properties`. Returns a `::lookup-result`
  — either a found map (`:code :display :system` + properties) or a
  not-found map (`:not-found true :not-found-reason
  :unknown-system|:unknown-code`)."
  [svc params]
  (protos/cs-lookup svc params))

(s/fdef subsumes
  :args (s/cat :svc some? :params map?)
  :ret  (s/nilable map?))

(defn subsumes
  "CodeSystem $subsumes. `params` carries `:systemA :codeA :systemB :codeB`.
  Returns a `::translate-result`-shaped map or nil."
  [svc params]
  (protos/cs-subsumes svc params))

(s/fdef find-matches
  :args (s/cat :svc some? :query map?)
  :ret  (s/nilable ::match-result))

(defn find-matches
  "CodeSystem $find-matches. `query` is an `::input/query` describing
  the filters/text/properties the provider must satisfy."
  [svc query]
  (protos/cs-find-matches svc query))

(s/fdef expand
  :args (s/cat :svc some? :params map?)
  :ret  (s/nilable ::expansion-result))

(defn expand
  "ValueSet $expand. `params` carries `:url` and optional pagination,
  display, filter and version-override fields. Returns an
  `::expansion-result`."
  [svc params]
  (protos/vs-expand svc svc params))

(s/fdef translate
  :args (s/cat :svc some? :params map?)
  :ret  (s/nilable ::translate-result))

(defn translate
  "ConceptMap $translate. `params` may carry `:url` (canonical lookup),
  or `:system :target` (system-pair lookup), plus `:code`. Returns a
  `::translate-result` or nil if no provider matches the request."
  [svc params]
  (protos/cm-translate svc params))

(s/fdef validate-code
  :args (s/cat :svc some? :params map?)
  :ret  ::validate-result)

(defn validate-code
  "Dispatches on whether `params` carries `:url`:
    - with `:url` → ValueSet $validate-code
    - without    → CodeSystem $validate-code
  Returns a `::validate-result`."
  [svc {:keys [url] :as params}]
  (if url
    (protos/vs-validate-code svc svc params)
    (protos/cs-validate-code svc params)))

(s/fdef validate-codeable-concept
  :args (s/cat :svc some? :codings (s/coll-of map?) :base-params map?)
  :ret  ::validate-result)

(defn validate-codeable-concept
  "ValueSet $validate-code against a CodeableConcept. `codings` is a
  seq of `{:system :code :display :version}` maps; `base-params`
  carries `:url` and optional `:valueSetVersion :displayLanguage`."
  [svc codings base-params]
  (composite/validate-codeable-concept svc codings base-params))
