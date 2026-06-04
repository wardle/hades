(ns com.eldrix.hades.core
  "Public Clojure API for Hades — a FHIR terminology service.

  A Hades service is an opaque handle returned by `open`. It carries
  every CodeSystem, ValueSet and ConceptMap provider the service
  knows about, plus the cross-provider logic (identifier-based URL
  dispatch, version resolution, supplements check, status warnings,
  CodeableConcept aggregation) needed to behave like a single FHIR
  terminology service.

  Convenience functions in this namespace cover the everyday FHIR
  terminology operations:

    - `lookup`                       CodeSystem $lookup
    - `validate-code`                CodeSystem $validate-code (no :url)
                                       or ValueSet $validate-code (:url present)
    - `subsumes`                     CodeSystem $subsumes
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
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.protocols.result :as result]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Re-exported result specs — public stable contract for return values.
;; ---------------------------------------------------------------------------

;; clojure.spec lookups go through the keyword's namespace; we re-name
;; the keywords here so callers can `(s/valid? ::core/lookup-result …)`
;; without reaching into the protocol-specs namespace. The spec
;; definitions live in `protocols.result`; these forms create
;; same-spec aliases.
(s/def ::lookup-result      ::result/lookup)
(s/def ::validate-result    ::result/validate)
(s/def ::expansion-result   ::result/expansion)
(s/def ::translate-result   ::result/translate)
(s/def ::search-result      ::result/search-result)
(s/def ::issue              ::result/issue)
(s/def ::expansion-concept  ::result/expansion-concept)

;; ---------------------------------------------------------------------------
;; Service lifecycle
;; ---------------------------------------------------------------------------

(defn open
  "Open a Hades terminology service from built terminology artefact paths.

  Each path may point at a Hermes SNOMED DB, a FHIR terminology SQLite
  container, or a file/tree of FHIR JSON resources:

    (hades/open [\"snomed.db\" \"loinc.db\"])

  RF2 and LOINC release sources must be imported first. Provider-level
  construction lives in `impl` namespaces.

  Options (second arity):
    :defaults       — map of bare canonical URL to version for disambiguation
    :default-locale — fallback BCP 47 locale forwarded to `hermes/open`

  OID/URN aliases ride on each CodeSystem provider's `:identifiers`
  metadata — they're indexed alongside canonical URLs at composite
  construction, so a lookup against any alias routes to the provider
  natively. No separate alias registration.

  Returns an opaque, `Closeable` handle. Closing releases every
  provider that implements `java.io.Closeable` in reverse order.
  Subsequent closes are no-ops."
  ([paths] (paths/open-paths paths))
  ([paths opts] (paths/open-paths paths opts)))

(defn close
  "Release the service and every closeable provider it holds. Safe to
  call more than once; subsequent calls are no-ops."
  [svc]
  (when svc (.close ^java.io.Closeable svc)))

(defn metadata
  "Describe what `svc` knows about: every CodeSystem, ValueSet, and
  ConceptMap registered, composed live from each provider's
  `*-metadata`, plus totals. Entries are deduplicated and ordered by
  `url|version`: a resource served by more than one provider appears
  once. `:totals` therefore counts the distinct served catalogue."
  [svc]
  (let [distinct-by (fn [ms] (->> ms (sort-by (juxt :url :version))
                                  (partition-by (juxt :url :version))
                                  (mapv first)))
        css (distinct-by (protos/cs-metadata svc {}))
        vss (distinct-by (protos/vs-metadata svc {}))
        cms (distinct-by (protos/cm-metadata svc {}))]
    {:codesystems css
     :valuesets   vss
     :conceptmaps cms
     :totals      {:codesystems (count css)
                   :valuesets   (count vss)
                   :conceptmaps (count cms)}}))

(defn with-overlays
  "Return a derived service layering the given providers on top of
  `svc`. Each overlay provider's `*-metadata` determines which URLs
  it covers; overlays win on exact-key match. The derived handle is
  a view — closing the base releases resources, closing the view
  does nothing. A `content=\"supplement\"` overlay provider is detected
  and wired onto its base automatically."
  [svc providers] (composite/with-overlays svc providers))

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
  :ret  ::translate-result)

(defn translate
  "ConceptMap $translate. `params` may carry `:url` (canonical lookup),
  or `:system :target` (system-pair lookup), plus `:code`. Returns a
  `::translate-result`."
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

(s/fdef search-code-systems
  :args (s/cat :svc some? :params map?)
  :ret  ::search-result)

(defn search-code-systems
  "FHIR REST search across registered CodeSystems. `params` is a flat
  map of token filters (`:url :version :status`), string filters
  (`:name :title :description`, each a `{:value :modifier}` map where
  `:modifier` is `:starts-with` (default), `:exact`, or `:contains`),
  and result-control fields (`:_count :_offset :_summary`). Returns
  `{:total :resources}`."
  [svc params]
  (composite/search-code-systems svc params))

(s/fdef search-value-sets
  :args (s/cat :svc some? :params map?)
  :ret  ::search-result)

(defn search-value-sets
  "FHIR REST search across registered ValueSets. Same params and
  return shape as `search-code-systems`. Implicit ValueSets (e.g. the
  Hermes 'all of SNOMED' VS) are excluded automatically — their
  `vs-resource` returns nil."
  [svc params]
  (composite/search-value-sets svc params))

(s/fdef search-concept-maps
  :args (s/cat :svc some? :params map?)
  :ret  ::search-result)

(defn search-concept-maps
  "FHIR REST search across registered ConceptMaps. Same params and
  return shape as `search-code-systems`."
  [svc params]
  (composite/search-concept-maps svc params))
