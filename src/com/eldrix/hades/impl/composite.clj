(ns com.eldrix.hades.impl.composite
  "Composite TerminologyService.

  Holds the catalogues built by the boot driver (or supplied directly to
  `open-with`):

    :codesystems    {url|version → impl}    ; bare URL also keyed when
                                              single-version
    :valuesets      {url|version → impl}
    :conceptmaps    [{:impl :description}]   ; multi-axis lookup
    :naming-systems [resolver-fn ...]        ; OID/URN → canonical
    :metadata       {…}                      ; load report

  The record satisfies `protos/CodeSystem`, `protos/ValueSet`,
  `protos/ConceptMap`. Each method dispatches to a child provider by URL
  / version / NamingSystem alias, then invokes the matching protocol
  method on the child. Cross-provider concerns — version-override
  application, supplements check, inactive warnings, status warnings,
  CodeableConcept aggregation — live on the composite, not on leaves.

  `with-overlays` returns a derived composite layered on top of a base.
  Overlay catalogues take precedence; closers and the load report are
  inherited from the base only (overlays don't own them)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.impl.canonical :as canonical]
            [com.eldrix.hades.impl.issues :as issues]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.protocols.result :as result]
            [com.eldrix.hades.impl.supplement :as supplement]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Lookup helpers — pure, operate on catalogue maps
;; ---------------------------------------------------------------------------

(defn- lookup-impl
  "Look up an implementation from an overlay map and a base map.
  Tries the key as-is, then url|version, then the wildcard url|*, then
  strips query params."
  [overlay base key]
  (or (get overlay key)
      (get base key)
      (let [[base-url version] (canonical/parse-versioned-uri key)]
        (when version
          (or (get overlay (canonical/versioned-uri base-url version))
              (get base (canonical/versioned-uri base-url version))
              (get overlay (canonical/versioned-uri base-url canonical/wildcard-version))
              (get base (canonical/versioned-uri base-url canonical/wildcard-version)))))
      (when-let [u (canonical/uri-without-query key)]
        (when (not= key u)
          (or (get overlay u) (get base u))))))

(defn resolve-canonical
  "Resolve an identifier (OID, URN, URI alias, canonical URL) to a
  canonical URL via the registered NamingSystem resolvers. Returns the
  canonical URL when one resolves, or `id` unchanged when no resolver
  matches. Blank/nil input returns nil."
  [resolvers id]
  (when-not (or (nil? id) (and (string? id) (str/blank? id)))
    (or (some (fn [resolver] (resolver id)) resolvers)
        id)))

(defn find-codesystem
  "Find a CodeSystem provider by URL, with NamingSystem alias fallback.
  `svc` is the TerminologyService record."
  [{:keys [codesystems naming-systems]} key]
  (or (lookup-impl nil codesystems key)
      (let [resolved (resolve-canonical naming-systems key)]
        (when (and resolved (not= resolved key))
          (lookup-impl nil codesystems resolved)))))

(defn find-valueset
  [{:keys [valuesets naming-systems]} key]
  (or (lookup-impl nil valuesets key)
      (let [resolved (resolve-canonical naming-systems key)]
        (when (and resolved (not= resolved key))
          (lookup-impl nil valuesets resolved)))))

(defn available-versions
  "List all registered versions for a system URL."
  [{:keys [codesystems]} system]
  (let [prefix (str system "|")]
    (distinct
      (keep (fn [k]
              (when (and (string? k) (.startsWith ^String k prefix))
                (.substring ^String k (count prefix))))
            (keys codesystems)))))

(defn find-matching-version
  "Resolve a version pattern against available versions. If the pattern
  contains 'x' wildcard segments, finds the latest matching registered
  version. Otherwise returns the pattern as-is."
  [svc system pattern]
  (when pattern
    (if (some #(= "x" %) (str/split pattern #"\."))
      (reduce (fn [best v]
                (if (and (canonical/version-matches? pattern v)
                         (or (nil? best) (pos? (canonical/semver-compare v best))))
                  v best))
              nil (available-versions svc system))
      pattern)))

;; ---------------------------------------------------------------------------
;; ConceptMap candidate selection
;; ---------------------------------------------------------------------------

(defn- matches-cm-request?
  [{desc-url :url desc-sys :system desc-tgt :target}
   {req-url :url req-sys :system req-tgt :target}]
  (cond
    req-url (or (= req-url desc-url)
                (and desc-url (= (canonical/uri-without-query req-url) desc-url)))
    (and req-sys req-tgt) (and (= req-sys desc-sys) (= req-tgt desc-tgt))
    :else false))

(defn- candidate-cm-impls
  [conceptmaps request]
  (vec (distinct
         (for [{:keys [impl description]} conceptmaps
               :when (matches-cm-request? description request)]
           impl))))

;; ---------------------------------------------------------------------------
;; Cross-provider concerns
;; ---------------------------------------------------------------------------

;; inactive-concept warnings live in `impl/issues` so the same logic is
;; shared between this composite layer (CodeSystem $validate-code) and
;; `vs-validate/validate-code` (ValueSet $validate-code).

(defn cs-meta
  "Return the cached `cs-resource` map for `system` (a URL, optionally
  with `|version`). Constant time. Returns nil if no provider serves
  this key. Use this in cross-cutting helpers instead of calling
  `find-codesystem` + `protos/cs-resource` separately."
  [{:keys [cs-meta-by-key naming-systems] :as svc} system]
  (or (get cs-meta-by-key system)
      (let [resolved (resolve-canonical naming-systems system)]
        (when (and resolved (not= resolved system))
          (get cs-meta-by-key resolved)))
      ;; Fall back to a live call when the impl was found via
      ;; canonical/version stripping that bypasses the precomputed
      ;; map (rare: bare-URL request with only a versioned binding
      ;; precomputed). Pass `:url`/`:system` so multi-resource impls
      ;; can dispatch.
      (when-let [cs (find-codesystem svc system)]
        (let [[url version] (canonical/parse-versioned-uri system)]
          (protos/cs-resource cs (cond-> {:url url :system url}
                                   version (assoc :version version)))))))

(defn vs-meta
  [{:keys [vs-meta-by-key naming-systems] :as svc} url]
  (or (get vs-meta-by-key url)
      (let [resolved (resolve-canonical naming-systems url)]
        (when (and resolved (not= resolved url))
          (get vs-meta-by-key resolved)))
      (when-let [vs (find-valueset svc url)]
        (let [[u version] (canonical/parse-versioned-uri url)]
          (protos/vs-resource vs (cond-> {:url u}
                                   version (assoc :version version)))))))

(defn add-cs-status-warnings
  "Add informational issues for CodeSystem publication status (draft/retired/experimental)."
  [result svc system]
  (if-let [meta (cs-meta svc system)]
    (let [status (:status meta)
          experimental? (:experimental meta)
          version (:version meta)
          cs-ref (if version (str system "|" version) system)
          issues (cond-> []
                   (= "draft" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to draft CodeSystem " cs-ref)})
                   (= "retired" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to deprecated CodeSystem " cs-ref)})
                   experimental?
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to experimental CodeSystem " cs-ref)}))]
      (if (seq issues)
        (update result :issues (fn [existing] (vec (concat issues (or existing [])))))
        result))
    result))

(defn add-vs-status-warnings
  [result svc url]
  (if-let [meta (when url (vs-meta svc url))]
    (let [status (:status meta)
          version (:version meta)
          vs-ref (if version (str url "|" version) url)
          issues (cond-> []
                   (= "retired" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to withdrawn ValueSet " vs-ref)})
                   (= "draft" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to draft ValueSet " vs-ref)}))]
      (if (seq issues)
        (update result :issues (fn [existing] (vec (concat (or existing []) issues))))
        result))
    result))

(defn unknown-version-issue
  "Return an UNKNOWN_CODESYSTEM_VERSION issue if the caller's version doesn't
   correspond to a registered CS, or nil if the version exists. The optional
   `purpose` chooses the trailing message clause."
  ([svc system version] (unknown-version-issue svc system version :validate))
  ([svc system version purpose]
   (when (and version system)
     (let [versioned-key (canonical/versioned-uri system version)]
       (when-not (find-codesystem svc versioned-key)
         (let [valid (sort (available-versions svc system))
               valid-str (cond
                           (seq valid)
                           (str ". Valid versions: " (str/join " or " valid))
                           (find-codesystem svc system) ""
                           :else ". No versions of this code system are known")
               clause (case purpose
                        :expand "the value set cannot be expanded"
                        "the code cannot be validated")
               base {:severity     "error"
                     :type         "not-found"
                     :details-code "not-found"
                     :text         (str "A definition for CodeSystem '" system "' version '" version
                                        "' could not be found, so " clause valid-str)}]
           (cond-> base
             (not= purpose :expand) (assoc :expression ["Coding.system"]))))))))

(defn- codesystem-version-of [svc system]
  (:version (cs-meta svc system)))

(defn check-system-version-issue
  "Return a check-system-version error issue if the resolved version doesn't
   match the check pattern, or nil if the check passes."
  [svc system resolved-version params]
  (when-let [check-versions (:check-system-version params)]
    (when-let [check-pattern (get check-versions system)]
      (let [actual (or resolved-version (codesystem-version-of svc system))]
        (when (and actual (not (canonical/version-matches? check-pattern actual)))
          {:severity     "error"
           :type         "exception"
           :details-code "version-error"
           :text         (str "The version '" actual "' is not allowed for system '"
                              system "': required to be '" check-pattern
                              "' by a version-check parameter")
           :expression   ["Coding.version"]})))))

(defn check-supplement-refs
  "Validate that every supplement canonical referenced by the operation is
  registered. Returns nil when all resolve; otherwise a `{:message :issues}`
  ready for a 4xx response.

  `vs-url` is optional: when supplied we look up the ValueSet's declared
  supplements via the precomputed metadata map; otherwise only the
  caller's `:use-supplements` are checked."
  [svc vs-url params]
  (let [op-refs (:use-supplements params)
        vs-refs (when vs-url (:supplements (vs-meta svc vs-url)))
        all-refs (distinct (concat op-refs vs-refs))
        missing (first (remove #(find-codesystem svc %) all-refs))]
    (when missing
      (let [msg (str "Required supplement not found: " missing)]
        {:message msg
         :issues  [{:severity "error" :type "not-found"
                    :details-code "not-found" :text msg}]}))))

;; ---------------------------------------------------------------------------
;; The TerminologyService record
;;
;; Type is intentionally not part of the public namespace. External
;; callers treat the handle as opaque and use functions in
;; `com.eldrix.hades.core` (or, where they truly want to extend the
;; service, the protocols in `impl/protocols`). The record satisfies
;; the three protocols itself, so the composite is substitutable for
;; any single provider.
;; ---------------------------------------------------------------------------

(defn- run-closers [closers]
  (run! (fn [f]
          (try (f) (catch Exception _ nil)))
        closers))

(defrecord TerminologyService
  [codesystems valuesets conceptmaps naming-systems
   cs-meta-by-key vs-meta-by-key
   metadata closers]

  java.io.Closeable
  (close [_] (run-closers closers))

  protos/CodeSystem
  (cs-metadata [_ opts]
    (eduction (mapcat #(protos/cs-metadata % opts)) (distinct (vals codesystems))))

  (cs-resource [this {:keys [system version] :as params}]
    (let [canonical (resolve-canonical naming-systems system)
          key (canonical/versioned-uri canonical version)]
      (when-let [cs (find-codesystem this key)]
        (protos/cs-resource cs (assoc params :system canonical)))))

  (cs-lookup [this {:keys [system code version] :as params}]
    (let [canonical (resolve-canonical naming-systems system)
          lookup-key (canonical/versioned-uri canonical version)]
      (if-let [cs (find-codesystem this lookup-key)]
        (protos/cs-lookup cs (assoc params :system canonical))
        (issues/unknown-system-lookup system code))))

  (cs-validate-code [this {:keys [system code version] :as params}]
    (let [canonical (resolve-canonical naming-systems system)
          lookup-key (canonical/versioned-uri canonical version)]
      (if-let [cs (find-codesystem this lookup-key)]
        (-> (protos/cs-validate-code cs (assoc params :system canonical))
            (issues/add-inactive-warning))
        (let [msg (str "A definition for CodeSystem '" system
                       "' could not be found, so the code cannot be validated")]
          {:result false
           :code (when code (keyword code))
           :system system
           :message msg
           :x-unknown-system system
           :issues [{:severity "error" :type "not-found"
                     :details-code "not-found" :text msg
                     :expression ["system"]}]}))))

  (cs-subsumes [this {:keys [systemA systemB] :as params}]
    (when-not (= systemA systemB)
      (throw (ex-info "Currently, can only check subsumption within same codesystem" params)))
    (when-let [cs (find-codesystem this systemA)]
      (protos/cs-subsumes cs params)))

  (cs-find-matches [this {:keys [system version] :as query}]
    (let [lookup-key (canonical/versioned-uri system version)]
      (when-let [cs (find-codesystem this lookup-key)]
        (protos/cs-find-matches cs query))))

  protos/ValueSet
  (vs-metadata [_ opts]
    (eduction (mapcat #(protos/vs-metadata % opts)) (distinct (vals valuesets))))

  (vs-resource [this {:keys [url] :as params}]
    (when-let [vs (find-valueset this url)]
      (protos/vs-resource vs params)))

  (vs-expand [this svc {:keys [url valueSetVersion] :as params}]
    (let [lookup-key (canonical/versioned-uri url valueSetVersion)]
      (when-let [vs (find-valueset this lookup-key)]
        (protos/vs-expand vs (or svc this) params))))

  (vs-validate-code [this svc {:keys [url system code valueSetVersion] :as params}]
    (when (str/blank? url)
      (throw (ex-info "ValueSet $validate-code requires a url or valueSet parameter"
                      {:type :invalid :details-code "invalid"})))
    (let [vs-lookup (canonical/versioned-uri url valueSetVersion)]
      (if-let [vs (find-valueset this vs-lookup)]
        (protos/vs-validate-code vs (or svc this) params)
        (let [msg (str "A definition for the value Set '" vs-lookup "' could not be found")]
          {:result    false
           :not-found true
           :code      (when code (keyword code))
           :system    system
           :message   msg
           :issues    [{:severity "error" :type "not-found"
                        :details-code "not-found" :text msg}]}))))

  protos/ConceptMap
  (cm-metadata [_ {url-q :url ver-q :version}]
    (eduction
     (map :description)
     (filter (fn [m]
               (and (or (nil? url-q) (= url-q (:url m)))
                    (or (nil? ver-q) (= ver-q (:version m))))))
     conceptmaps))

  (cm-resource [_ params]
    (when-let [cm (first (candidate-cm-impls conceptmaps params))]
      (protos/cm-resource cm params)))

  (cm-translate [_ {:keys [url system target] :as params}]
    (let [hits (candidate-cm-impls conceptmaps {:url url :system system :target target})]
      (cond
        (empty? hits) nil

        (= 1 (count hits))
        (protos/cm-translate (first hits) params)

        ;; Multiple ConceptMaps match a system+target pair (different
        ;; canonicals, different versions). Force the caller to disambiguate
        ;; with `:url` rather than guessing which map they want.
        :else
        (let [msg (str "Multiple ConceptMaps match the request — "
                       "supply `:url` to disambiguate. "
                       "system='" system "' target='" target "'")]
          {:result false
           :message msg
           :issues [{:severity     "error"
                     :type         "invalid"
                     :details-code "ambiguous-target"
                     :text         msg}]})))))

;; ---------------------------------------------------------------------------
;; Search — FHIR REST search for CodeSystem / ValueSet
;;
;; Composes existing per-provider primitives (`*-metadata` +
;; `*-resource`). The metadata walk gives a cheap `(url, version)`
;; projection; we pre-filter on those keys so the URL hot path
;; (1400/1800 FS01 requests) collapses each provider to ≤1 surviving
;; tuple before any `*-resource` materialisation.
;;
;; Routing-only entries — Hermes' "all of SNOMED" implicit ValueSet
;; and the wildcard CodeSystem version — are flagged `:implicit?` on
;; their metadata tuples and filtered out at tuple level so catalogue
;; listings never need a `*-resource` round-trip to know they don't
;; produce a published resource.
;; ---------------------------------------------------------------------------

(defn- string-match
  "FHIR string search semantics. `mode` is `:starts-with`,
  `:exact`, or `:contains`. nil candidate never matches."
  [candidate query mode]
  (when candidate
    (case mode
      :exact       (= candidate query)
      :starts-with (str/starts-with? (str/lower-case candidate) (str/lower-case query))
      :contains    (str/includes? (str/lower-case candidate) (str/lower-case query)))))

(defn- matches-resource-filters?
  "Apply the materialised-resource filter set. Tokens (`:status`) must
  match exactly; strings (`:name :title :description`) honour their
  companion `*-mode`. URL/version are not checked here — the metadata
  DSL already pushed them down to the provider."
  [m {:keys [status name title description
             name-mode title-mode description-mode]}]
  (and (or (nil? status)      (= status (:status m)))
       (or (nil? name)        (string-match (:name m) name (or name-mode :starts-with)))
       (or (nil? title)       (string-match (:title m) title (or title-mode :starts-with)))
       (or (nil? description) (string-match (:description m) description
                                            (or description-mode :starts-with)))))

(defn- key->params
  "Map a metadata tuple to the `*-resource` params map. Multi-resource
  providers dispatch on `:url`/`:system`; single-resource impls ignore
  the keys and return their bound resource."
  [{:keys [url version]}]
  (cond-> {:url url :system url}
    version (assoc :version version)))

(defn- compare-by-url-version
  "Sort comparator: alphabetic on URL, then `semver-compare` on version."
  [a b]
  (let [c (compare (:url a) (:url b))]
    (if (zero? c)
      (canonical/semver-compare (:version a) (:version b))
      c)))

(defn- compare-pair-by-url-version
  "Sort comparator over `[provider tuple]` pairs."
  [[_ a] [_ b]]
  (compare-by-url-version a b))

(defn- summarise-resource
  "Apply `_summary` projection. `_summary=true` drops `:compose` from
  ValueSet resources (the only large field `vs-resource` impls
  return). Other values pass through."
  [{:keys [_summary]} m]
  (if (= "true" _summary)
    (dissoc m :compose)
    m))

(def ^:private resource-meta-filter-keys [:status :name :title :description])

(defn- needs-materialisation?
  "Whether the supplied filters reference fields only available on the
  materialised resource map (not on the cheap metadata tuple)."
  [filters]
  (boolean (some #(contains? filters %) resource-meta-filter-keys)))

(defn- pair-seq
  "Lazy seq of `[provider tuple]` pairs. Token filters (`:url :version`)
  and implicit-suppression have already been pushed into `metadata-fn`
  via the metadata-opts DSL — providers honour them, so this layer
  trusts every emitted tuple."
  [providers metadata-fn opts]
  (eduction
   (mapcat (fn [p] (eduction (map #(vector p %)) (metadata-fn p opts))))
   (distinct providers)))

(defn- page-of
  "Lazy page slice: drop `offset`, then take `_count` if specified."
  [_count offset xs]
  (cond->> (drop offset xs)
    _count (take _count)))

(defn- search*
  "Two paths, picked from `filters`:

    * Cheap path — only `:url`/`:version` filters or none. The
      provider applies them via the metadata-opts DSL, so we sort the
      surviving tuples (no `*-resource` calls), page, and materialise
      ONLY the page. For a 10k-VS browse with `_count=10` this is ~10
      `*-resource` calls instead of 10k; for a URL-by-url request
      against a 2.5k-VS catalogue, the SQLite catalogue's key filter
      avoids 2,499 tuple allocations.

    * Filtered path — at least one of `:status :name :title
      :description` is supplied. We can't filter without the
      materialised resource map; realise everything that survived the
      tuple stage, then sort/page over the result.

  `:_summary=count` short-circuits the page projection in both paths
  (return `total` only).

  Defaults and caps for `:_count` belong at the HTTP boundary; this
  fn takes whatever is supplied."
  [providers metadata-fn resource-fn {:keys [_count _offset _summary] :as params}]
  (let [filters (dissoc params :_count :_offset :_summary)
        opts    (cond-> {:include-implicit? false}
                  (:url filters)     (assoc :url (:url filters))
                  (:version filters) (assoc :version (:version filters)))
        offset  (or _offset 0)
        count?  (= "count" _summary)
        pairs   (pair-seq providers metadata-fn opts)]
    (if (needs-materialisation? filters)
      (let [matched   (into []
                            (keep (fn [[p t]]
                                    (let [m (resource-fn p (key->params t))]
                                      (when (and m (matches-resource-filters? m filters))
                                        m))))
                            pairs)
            sorted    (sort compare-by-url-version matched)
            resources (if count?
                        []
                        (into [] (map #(summarise-resource params %))
                              (page-of _count offset sorted)))]
        (s/assert ::result/search-result {:total (count matched) :resources resources}))
      (let [sorted-pairs (sort compare-pair-by-url-version pairs)
            resources    (if count?
                           []
                           (into [] (comp (keep (fn [[p t]] (resource-fn p (key->params t))))
                                          (map #(summarise-resource params %)))
                                 (page-of _count offset sorted-pairs)))]
        (s/assert ::result/search-result
                  {:total (count sorted-pairs) :resources resources})))))

(defn search-code-systems
  "Search registered CodeSystem resources. `params` is a
  `::input/search-params`; returns a `::result/search-result`."
  [{:keys [codesystems]} params]
  (search* (vals codesystems) protos/cs-metadata protos/cs-resource params))

(defn search-value-sets
  "Search registered ValueSet resources. `params` is a
  `::input/search-params`; returns a `::result/search-result`.

  Implicit ValueSets — those advertised in `vs-metadata` for routing
  only — are dropped at the provider via `:include-implicit? false` in
  the metadata-opts the composite hands down."
  [{:keys [valuesets]} params]
  (search* (vals valuesets) protos/vs-metadata protos/vs-resource params))

;; ---------------------------------------------------------------------------
;; CodeableConcept aggregation — uses vs-validate-code per coding then
;; aggregates per the FHIR spec. Lives outside the protocol because it's
;; multi-call orchestration, not a leaf operation.
;; ---------------------------------------------------------------------------

(defn validate-codeable-concept
  "Validate a CodeableConcept against a ValueSet. Iterates each coding,
  validates independently, and aggregates per the FHIR spec."
  [svc codings base-params]
  (let [per-coding
        (map-indexed
          (fn [idx coding-map]
            (let [result (protos/vs-validate-code svc svc
                           (merge base-params
                                  (select-keys coding-map [:system :code :display :version])))]
              (cond-> (assoc result :coding-index idx)
                (:issues result)
                (update :issues
                        (fn [issues]
                          (mapv (fn [i]
                                  (cond-> (assoc i :coding-index idx)
                                    (= "not-in-vs" (:details-code i))
                                    (assoc :details-code "this-code-not-in-vs"
                                           :severity "information")))
                                issues))))))
          codings)
        first-not-found (first (filter :not-found per-coding))]
    (if first-not-found
      first-not-found
      (let [valid         (last (filter :result per-coding))
            invalid       (remove :result per-coding)
            all-issues    (into [] (mapcat :issues) invalid)
            cs-error-msgs (into [] (comp (keep #(when (= "invalid-code" (:details-code %)) (:text %)))
                                         (distinct))
                                all-issues)
            error-msg     (first cs-error-msgs)]
        (if valid
          (cond-> valid
            (seq invalid)    (assoc :result false)
            (seq all-issues) (update :issues (fnil into []) all-issues)
            error-msg        (assoc :message error-msg))
          (let [version-issue-codes #{"vs-invalid" "version-error" "version-mismatch"}
                best-invalid (last (filter (fn [r]
                                             (and (:display r) (:system r)
                                                  (some #(contains? version-issue-codes (:details-code %))
                                                        (:issues r))
                                                  (not (some #(= "not-in-vs" (:details-code %))
                                                             (:issues r)))))
                                           per-coding))]
            (if best-invalid
              (assoc best-invalid :result false)
              (let [url (:url base-params)
                    vs-ver (:version (vs-meta svc url))
                    vs-url-ver (if vs-ver (str url "|" vs-ver) url)
                    no-valid-msg (str "No valid coding was found for the value set '" vs-url-ver "'")
                    combined-msg (if (seq cs-error-msgs)
                                   (str no-valid-msg "; " (str/join "; " cs-error-msgs))
                                   no-valid-msg)
                    no-valid-issue {:severity "error" :type "code-invalid"
                                    :details-code "not-in-vs" :text no-valid-msg}]
                {:result  false
                 :message combined-msg
                 :issues  (into [no-valid-issue] all-issues)}))))))))

;; ---------------------------------------------------------------------------
;; Construction from provider impls
;;
;; `from-providers` builds a TerminologyService by walking each
;; provider's *-metadata and indexing its impls under the URLs (and
;; `url|version` keys) it advertises. CodeSystem providers also
;; register against `valuesets` so the implicit ValueSet of a
;; CodeSystem answers `vs-validate-code` and `vs-expand`. Bare URL
;; bindings happen only when a single version of that URL is present;
;; multi-version groups leave the bare URL unbound.
;;
;; Throws `ex-info` on duplicate `(resource-type, url, version)`
;; tuples across providers.
;; ---------------------------------------------------------------------------

(defn- versioned-key
  "Registry-key form of `(url, version)`: always `\"url|version\"`,
  with empty-string version for unversioned entries. Distinct from
  `canonical/versioned-uri`, which returns the bare URL when version
  is nil — fine for FHIR canonicals, but it collides with
  `bare-bindings` in the registry and would let a bare-URL default
  overwrite an unversioned provider on `merge`."
  [url version]
  (str url "|" (or version "")))

(defn- collect-cs-entries [providers]
  (for [p providers
        :when (satisfies? protos/CodeSystem p)
        m (protos/cs-metadata p {})]
    {:provider p :url (:url m) :version (:version m)
     :content (:content m) :supplements (:supplements m)}))

(defn- collect-vs-entries [providers]
  (for [p providers
        :when (satisfies? protos/ValueSet p)
        m (protos/vs-metadata p {})]
    {:provider p :url (:url m) :version (:version m)}))

(defn- collect-cm-entries [providers]
  (for [p providers
        :when (satisfies? protos/ConceptMap p)
        m (protos/cm-metadata p {})]
    {:provider p :description m}))

(defn- detect-duplicates
  "Diagnostic: report `(url, version)` groups that come from more than one
  provider. Returned by registration so the caller can warn-log; never
  fatal — duplicate resolution is the collapse functions' job."
  [entries resource-type]
  (->> entries
       (filter #(not (:supplement? %)))
       ;; Wildcard entries are per-provider routing hints saying "I'll
       ;; answer any version under this URL", not catalogue rows that
       ;; compete with another provider. Two providers each claiming a
       ;; wildcard for the same URL is genuine ambiguity, but it surfaces
       ;; through the bare-URL contest below, not as a duplicate.
       (remove #(= canonical/wildcard-version (:version %)))
       (group-by (juxt :url :version))
       (keep (fn [[[url ver] es]]
               (let [providers (into #{} (map :provider) es)]
                 (when (> (count providers) 1)
                   {:resource-type resource-type
                    :url url :version ver
                    :count (count providers)}))))))

(defn- prefer-non-stub-cs
  "Pick the keeper between two CodeSystem entries for the same
  (url, version). A `content: \"not-present\"` row is a stub: a package
  may ship one purely so the URL is registerable for downstream VS
  expansion / validation, intending the actual concepts to come from a
  real terminology service. A stub must never override a non-stub,
  regardless of registration order. Among same-rank entries, the
  later-registered wins."
  [a b]
  (let [a-stub? (= "not-present" (:content a))
        b-stub? (= "not-present" (:content b))]
    (cond
      (and a-stub? (not b-stub?)) b
      (and b-stub? (not a-stub?)) a
      :else b)))

(defn- collapse-duplicates
  "Collapse duplicate `(url, version)` entries into a single entry per
  group via `pick-fn`. Wildcard entries pass through unchanged — they're
  per-provider routing hints, not catalogue rows. Preserves the order
  of first-seen `(url, version)` keys; later entries either overwrite
  or are discarded by `pick-fn`."
  [entries pick-fn]
  (let [wildcard? #(= canonical/wildcard-version (:version %))
        wild     (filterv wildcard? entries)
        non-wild (remove wildcard? entries)
        picks (reduce
                (fn [acc e]
                  (let [k [(:url e) (:version e)]]
                    (if-let [prev (get acc k)]
                      (assoc acc k (pick-fn prev e))
                      (assoc acc k e))))
                {}
                non-wild)]
    (into wild (vals picks))))

(defn- pick-latest-semver
  "Of a vector of `entries` (each `{:version ...}`), return the entry
  with the strictly-greatest semver version, or nil if no clear
  winner."
  [entries]
  (when (seq entries)
    (let [sorted (sort-by :version canonical/semver-compare entries)
          latest (last sorted)
          runner-up (last (butlast sorted))]
      (when (or (nil? runner-up)
                (pos? (canonical/semver-compare (:version latest) (:version runner-up))))
        latest))))

(defn- bare-url-binding
  "Pick the impl for the bare URL. The contest is over distinct provider
  identities, not raw entries — a single provider that advertises N
  versioned URIs for the same URL (e.g. one Hermes service composing
  multiple SNOMED modules) is one candidate, not N. One distinct
  provider → use it. Multiple → consult `defaults`, else pick
  semver-latest across the entries, else throw `:ambiguous-default`.
  The semver-latest convention matches the FHIR tx-ecosystem
  expectation: when a request bundles multiple versions of a CodeSystem
  and the operation doesn't pin one, the latest wins."
  [resource-type url url-entries defaults]
  (let [providers (distinct (map :provider url-entries))]
    (cond
      (= 1 (count providers))
      (first providers)

      (contains? defaults url)
      (let [ver (get defaults url)
            e (some #(when (= ver (:version %)) %) url-entries)]
        (or (:provider e)
            (throw (ex-info (str ":defaults references unknown version for "
                                 resource-type " '" url "': " ver)
                            {:reason :unknown-default :url url :version ver}))))

      :else
      (if-let [latest (pick-latest-semver url-entries)]
        (:provider latest)
        (throw (ex-info (str "Ambiguous default version for " resource-type
                             " '" url "': set `:defaults {\"" url
                             "\" \"<version>\"}` to bind the bare URL.")
                        {:reason :ambiguous-default :url url
                         :versions (mapv :version url-entries)}))))))

(defn- index-by-key
  "Build the `{url|version → provider}` catalogue plus bare-URL bindings.
  Wildcard (`version=\"*\"`) entries register under `url|*` only; they
  don't compete for bare-URL binding."
  [entries defaults resource-type]
  (let [concrete-entries (remove #(= canonical/wildcard-version (:version %))
                                 entries)
        by-url-ver (reduce (fn [acc {:keys [url version provider]}]
                             (assoc acc (versioned-key url version) provider))
                           {} concrete-entries)
        by-url (group-by :url
                         concrete-entries)
        bare-bindings (reduce-kv
                        (fn [m url url-entries]
                          (if (seq url-entries)
                            (assoc m url (bare-url-binding
                                           resource-type url url-entries defaults))
                            m))
                        {} by-url)
        wildcard-bindings
        (reduce-kv
          (fn [m url wildcard-entries]
            (let [providers (distinct (map :provider wildcard-entries))
                  selected (get bare-bindings url)
                  bind (fn [provider]
                         (assoc m (versioned-key url canonical/wildcard-version)
                                  provider))]
              (cond
                (and selected (some #{selected} providers))
                (bind selected)

                (= 1 (count providers))
                (bind (first providers))

                :else
                (throw (ex-info (str "Ambiguous wildcard provider for "
                                     resource-type " '" url
                                     "': set `:defaults {\"" url
                                     "\" \"<version>\"}` to bind the wildcard.")
                                {:reason :ambiguous-default
                                 :url url
                                 :versions [canonical/wildcard-version]})))))
          {}
          (group-by :url
                    (filter #(= canonical/wildcard-version (:version %))
                            entries)))]
    (merge by-url-ver wildcard-bindings bare-bindings)))

(defn from-providers
  "Build a TerminologyService from a sequence of provider impls.
  Each provider must satisfy at least one of CodeSystem, ValueSet,
  ConceptMap. Catalogues are populated from `*-metadata` calls.

  Options:
    :naming-systems — vector of resolver fns `(fn [id] → canonical-or-nil)`
    :supplements    — vector of `{:meta :lookup}` maps from the indexer.
                      Each wraps the matching base provider with a
                      SupplementedCodeSystem. Bases are looked up in the
                      providers list first, then via `:lookup-fallback`.
    :lookup-fallback — fn `(fn [key] → impl-or-nil)` consulted when a
                       supplement's base isn't in `providers`. Used during
                       layered boot (e.g. supplement loaded after a Hermes
                       base already in another service).
    :defaults       — `{url version}` map binding the bare URL to a
                      specific version. Required when two or more
                      providers serve the same canonical and the
                      semver-latest is ambiguous.
    :metadata       — load report attached to the service.
    :closers        — extra close fns. Providers that satisfy Closeable
                      are auto-closed; explicit closers extend that."
  ([providers] (from-providers providers {}))
  ([providers {:keys [naming-systems supplements lookup-fallback defaults
                      metadata closers]}]
   (let [defaults   (or defaults {})
         cs-entries (collect-cs-entries providers)
         vs-entries (collect-vs-entries providers)
         cm-entries (collect-cm-entries providers)
         cs-dups    (seq (detect-duplicates cs-entries :CodeSystem))
         vs-dups    (seq (detect-duplicates vs-entries :ValueSet))
         ;; Duplicate `(url, version)` from distinct providers is the
         ;; reality of layered package loading: e.g. `fhir.tx.support.r4`
         ;; and `us.nlm.vsac` both ship `HSLOC|2022`. Resolve to a single
         ;; entry per group rather than refusing to boot — non-stub beats
         ;; stub regardless of order, then last-wins by registration.
         cs-entries (collapse-duplicates cs-entries prefer-non-stub-cs)
         vs-entries (collapse-duplicates vs-entries (fn [_ b] b))]
     (doseq [d cs-dups]
       (log/warn "duplicate CodeSystem registration resolved" d))
     (doseq [d vs-dups]
       (log/warn "duplicate ValueSet registration resolved" d))
     (let [;; A CodeSystem provider that *also* satisfies the ValueSet
           ;; protocol serves its implicit ValueSet (the FHIR convention
           ;; that every CodeSystem URL is also a ValueSet selecting all
           ;; its codes). Providers that only do CodeSystem stay out.
           cs-as-vs-entries (filter #(satisfies? protos/ValueSet (:provider %))
                                    cs-entries)
           base {:codesystems (index-by-key cs-entries defaults :CodeSystem)
                 :valuesets   (into (index-by-key vs-entries defaults :ValueSet)
                                    (index-by-key cs-as-vs-entries defaults :CodeSystem))
                 :conceptmaps (mapv (fn [e] {:impl (:provider e)
                                             :description (:description e)})
                                    cm-entries)
                 :naming-systems (or naming-systems [])}
           ;; Resolve supplements: replace each base impl with a
           ;; SupplementedCodeSystem wrapper.
           with-supps
           (if (seq supplements)
             (let [resolved (supplement/resolve-supplements
                              (select-keys base [:codesystems :valuesets])
                              supplements
                              lookup-fallback)]
               (assoc base
                      :codesystems (:codesystems resolved)
                      :valuesets (:valuesets resolved)))
             base)
           auto-closers (sequence
                          (comp (filter #(instance? java.io.Closeable %))
                                (map (fn [p] #(.close ^java.io.Closeable p))))
                          (reverse providers))
           all-closers (concat auto-closers closers)
           ;; Precompute resource metadata once. Every cross-cutting
           ;; helper consults these maps with O(1) lookup; no per-request
           ;; DB hit. Recompute on `with-overlays` so layered services
           ;; get matching meta for their layered impls.
           ;;
           ;; Pass `:url`/`:system`/`:version` derived from each key so
           ;; multi-resource impls (catalogues serving thousands of CSs
           ;; or VSs through one provider, e.g. SQLite) can return the
           ;; right entry. Single-resource impls ignore the params.
           ;;
           ;; Pass `:url`/`:system`/`:version` derived from each key so
           ;; multi-resource impls (catalogues serving thousands of CSs
           ;; or VSs through one provider, e.g. SQLite) can return the
           ;; right entry. Single-resource impls ignore the params.
           key->params (fn [k]
                         (let [[url version] (canonical/parse-versioned-uri k)]
                           (cond-> {:url url :system url}
                             version (assoc :version version))))
           cs-meta-by-key (reduce-kv (fn [m k cs] (assoc m k (protos/cs-resource cs (key->params k))))
                                     {} (:codesystems with-supps))
           vs-meta-by-key (reduce-kv (fn [m k vs] (assoc m k (protos/vs-resource vs (key->params k))))
                                     {} (:valuesets with-supps))]
       (->TerminologyService
         (:codesystems with-supps)
         (:valuesets with-supps)
         (:conceptmaps with-supps)
         (:naming-systems with-supps)
         cs-meta-by-key
         vs-meta-by-key
         (or metadata {})
         all-closers)))))

(defn with-overlays
  "Return a derived TerminologyService layering the given providers on
  top of `base`. Overlay entries take precedence on exact-key match.
  The derived handle is a view: closing the base service releases
  resources, closing the view does nothing.

  Because the view never runs closers, overlay providers must not own
  resources — `Closeable` providers are rejected at construction time.
  If you need a resource-owning overlay, build a fresh service via
  `from-providers`/`core/open` and close it explicitly when done."
  ([base providers] (with-overlays base providers nil))
  ([base providers {:keys [supplements naming-systems]}]
   (when-let [bad (first (filter #(instance? java.io.Closeable %) providers))]
     (throw (ex-info "Overlay provider is Closeable; with-overlays cannot release it"
                     {:reason :closeable-overlay
                      :provider-class (.getName (class bad))})))
   (let [overlay (from-providers providers
                                  (cond-> {:lookup-fallback #(find-codesystem base %)}
                                    (seq supplements) (assoc :supplements supplements)
                                    (seq naming-systems) (assoc :naming-systems naming-systems)))]
     ;; Overlay-first ordering for `:conceptmaps` and `:naming-systems`:
     ;; `candidate-cm-impls` and `resolve-canonical` both walk in order
     ;; and pick the first match, so overlay entries shadow base entries.
     (->TerminologyService
       (merge (:codesystems base) (:codesystems overlay))
       (merge (:valuesets base)   (:valuesets   overlay))
       (into (vec (:conceptmaps overlay))    (:conceptmaps base))
       (into (vec (:naming-systems overlay)) (:naming-systems base))
       (merge (:cs-meta-by-key base) (:cs-meta-by-key overlay))
       (merge (:vs-meta-by-key base) (:vs-meta-by-key overlay))
       (:metadata base)
       nil))))
