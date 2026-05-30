(ns com.eldrix.hades.composite
  "Composite TerminologyService.

  Holds the catalogues built by the boot driver (or supplied directly to
  `open-with`):

    :codesystems    {url|version → provider} ; bare URL also keyed when
                                               single-version; OID/URN
                                               identifiers from each
                                               CodeSystem's `:identifiers`
                                               metadata field land here
                                               too — one URL, one lookup
    :valuesets      {url|version → provider}
    :conceptmaps    [{:impl :description}]   ; multi-axis lookup
    :cs-providers / :vs-providers            ; unique provider vectors

  The record satisfies `protos/CodeSystem`, `protos/ValueSet`,
  `protos/ConceptMap`, and `java.io.Closeable`. Each protocol method
  dispatches to a child provider by URL/version and invokes the
  matching protocol method on the child — providers accept any URL
  they registered (canonical or identifier alias) and substitute
  internally. `.close` walks
  the distinct providers held by this composite and closes any that are
  themselves `Closeable` — opening owns closing. Cross-provider concerns
  — version-override application, supplements check, inactive warnings,
  status warnings, CodeableConcept aggregation — live on the composite,
  not on leaves.

  `with-overlays` returns a derived composite layered on top of a base.
  Overlay catalogues take precedence; the derived composite's `.close`
  closes only the overlay-introduced providers, never the base — the
  base owns its own lifecycle."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.providers.common.canonical :as canonical]
            [com.eldrix.hades.providers.common.issues :as issues]
            [com.eldrix.hades.providers.common.search-filter :as search-filter]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.protocols.result :as result]
            [com.eldrix.hades.providers.common.supplement :as supplement]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Lookup helpers — pure, operate on catalogue maps
;; ---------------------------------------------------------------------------

(defn- lookup-impl
  "Look up an implementation in a catalogue map. Tries the key as-is,
  then the wildcard url|*, then strips query params. Overlays are
  pre-merged into the catalogue by `with-overlays`, so there is no
  separate overlay map at lookup time."
  [base key]
  (or (get base key)
      (let [[base-url version] (canonical/parse-versioned-uri key)]
        (when version
          (get base (canonical/versioned-uri base-url canonical/wildcard-version))))
      (when-let [u (canonical/uri-without-query key)]
        (when (not= key u)
          (get base u)))))

(defn find-codesystem
  "Find a CodeSystem provider by URL. The URL index holds both
  canonical and identifier URLs for every CodeSystem, so a lookup
  against any of them succeeds in one step. `svc` is the
  TerminologyService record."
  [{:keys [codesystems]} key]
  (lookup-impl codesystems key))

(defn- dynamic-valueset
  [valueset-providers key]
  (let [[url version] (canonical/parse-versioned-uri key)]
    (some (fn [vs]
            (when (seq (protos/vs-metadata vs {:url url :version version}))
              vs))
          valueset-providers)))

(defn find-valueset
  [{:keys [valuesets vs-providers]} key]
  ;; `valuesets` can contain one entry per canonical ValueSet URL, while a
  ;; catalogue provider such as FTRM can serve hundreds of thousands of URLs.
  ;; Dynamic fallback must therefore scan providers, not valueset-map values.
  (let [dynamic-providers (or vs-providers (distinct (vals valuesets)))]
    (or (lookup-impl valuesets key)
        (dynamic-valueset dynamic-providers key))))

(defn available-versions
  "List all registered versions for a system URL."
  [{:keys [codesystems]} system]
  (let [prefix (str system "|")]
    (distinct
      (keep (fn [k]
              (when (and (string? k) (str/starts-with? k prefix))
                (subs k (count prefix))))
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
  [{desc-url :url desc-sys :system desc-tgt :target pairs :pairs}
   {req-url :url req-sys :system req-tgt :target}]
  (cond
    req-url (or (= req-url desc-url)
                (and desc-url (= (canonical/uri-without-query req-url) desc-url)))
    (and req-sys req-tgt) (and (or (= req-sys desc-sys)
                                   (some #(= req-sys (:system %)) pairs))
                               (or (= req-tgt desc-tgt)
                                   (some #(= req-tgt (:target %)) pairs)))
    :else false))

(defn- candidate-cm-entries
  [conceptmaps request]
  (distinct
    (for [{:keys [impl description]} conceptmaps
          :when (matches-cm-request? description request)]
           {:impl impl :description description})))

(defn- candidate-cm-impls
  [conceptmaps request]
  (mapv :impl (candidate-cm-entries conceptmaps request)))

(defn- translate-not-found
  [{:keys [url system target]}]
  (let [msg (if url
              (str "A definition for ConceptMap '" url "' could not be found")
              (str "No ConceptMap matched system='" system "' target='" target "'"))]
    {:result false
     :message msg
     :not-found true
     :issues [{:severity "error"
               :type "not-found"
               :details-code "not-found"
               :text msg}]}))

(defn- match-signature
  [result]
  (->> (:matches result)
       (map #(select-keys % [:system :code :version]))
       set))

(defn- ambiguous-cm-result
  [system target]
  (let [msg (str "Multiple ConceptMaps match the request — "
                 "supply `:url` to disambiguate. "
                 "system='" system "' target='" target "'")]
    {:result false
     :message msg
     :issues [{:severity     "error"
               :type         "invalid"
               :details-code "ambiguous-target"
               :text         msg}]}))

(defn- params-for-cm-entry
  [params {:keys [description]}]
  (cond-> params
    (nil? (:url params)) (assoc :url (:url description))
    (and (nil? (:version params)) (:version description))
    (assoc :version (:version description))))

;; ---------------------------------------------------------------------------
;; Cross-provider concerns
;; ---------------------------------------------------------------------------

;; inactive-concept warnings live in `impl/issues` so the same logic is
;; shared between this composite layer (CodeSystem $validate-code) and
;; `vs-validate/validate-code` (ValueSet $validate-code).

(defn cs-meta
  "Return the `cs-resource` map for `system` (a URL, optionally with
  `|version`), or nil if no provider serves this key. Resolves the
  serving provider and asks it directly. Use this in cross-cutting
  helpers instead of calling `find-codesystem` + `protos/cs-resource`
  separately."
  [svc system]
  (when-let [cs (find-codesystem svc system)]
    (let [[url version] (canonical/parse-versioned-uri system)]
      (protos/cs-resource cs (cond-> {:url url :system url}
                               version (assoc :version version))))))

(defn vs-meta
  "Return the `vs-resource` map for `url` (optionally with `|version`),
  or nil if no provider serves it. Asks the serving provider directly."
  [svc url]
  (when-let [vs (find-valueset svc url)]
    (let [[u version] (canonical/parse-versioned-uri url)]
      (protos/vs-resource vs (cond-> {:url u}
                               version (assoc :version version))))))

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
        (update result :issues (fn [existing] (into issues existing)))
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
        (update result :issues (fn [existing] (into (vec existing) issues)))
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
;; service, the protocols in `com.eldrix.hades.protocols`). The record satisfies
;; the three protocols itself, so the composite is substitutable for
;; any single provider.
;; ---------------------------------------------------------------------------

(defn- close-provider!
  "Close `p` if it is `Closeable`; swallow close-time exceptions."
  [p]
  (when (instance? java.io.Closeable p)
    (try (.close ^java.io.Closeable p) (catch Exception _ nil))))

(defrecord TerminologyService
  [codesystems valuesets conceptmaps
   cs-providers vs-providers
   owned-providers]

  java.io.Closeable
  (close [_] (run! close-provider! (reverse owned-providers)))

  protos/CodeSystem
  (cs-metadata [_ opts]
    (eduction (mapcat #(protos/cs-metadata % opts)) cs-providers))

  (cs-resource [this {:keys [system version] :as params}]
    (let [lookup-key (canonical/versioned-uri system version)]
      (when-let [cs (find-codesystem this lookup-key)]
        (protos/cs-resource cs params))))

  (cs-lookup [this {:keys [system code version] :as params}]
    (let [lookup-key (canonical/versioned-uri system version)]
      (if-let [cs (find-codesystem this lookup-key)]
        (protos/cs-lookup cs params)
        (issues/unknown-system-lookup system code))))

  (cs-validate-code [this {:keys [system code version] :as params}]
    (let [lookup-key (canonical/versioned-uri system version)]
      (if-let [cs (find-codesystem this lookup-key)]
        (-> (protos/cs-validate-code cs params)
            (issues/add-inactive-warning))
        (issues/unknown-system-validate system code))))

  (cs-subsumes [this {:keys [systemA systemB] :as params}]
    (cond
      (not= systemA systemB)
      {:issues [{:severity "error" :type "invalid"
                 :details-code "MSG_PARAM_INVALID"
                 :text "$subsumes can only check subsumption within a single code system"
                 :expression ["systemA" "systemB"]}]}

      :else
      (if-let [cs (find-codesystem this systemA)]
        (protos/cs-subsumes cs params)
        (issues/unknown-system-subsumes systemA))))

  (cs-expand* [this {:keys [system version] :as query}]
    (let [lookup-key (canonical/versioned-uri system version)]
      (when-let [cs (find-codesystem this lookup-key)]
        (protos/cs-expand* cs query))))

  protos/ValueSet
  (vs-metadata [_ opts]
    (eduction (mapcat #(protos/vs-metadata % opts)) vs-providers))

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
    (let [hits (candidate-cm-entries conceptmaps {:url url :system system :target target})]
      (cond
        (empty? hits) (translate-not-found params)

        (= 1 (count hits))
        (let [{:keys [impl] :as hit} (first hits)]
          (protos/cm-translate impl (params-for-cm-entry params hit)))

        ;; Multiple ConceptMaps can share a broad system/target pair (for
        ;; example, LOINC answer maps and LOINC part maps both target SNOMED).
        ;; Use the requested source code as an additional discriminator. If
        ;; only one map knows the code, dispatch to it; if several return the
        ;; same target set, return that set; if several return different target
        ;; sets, preserve the ambiguity instead of guessing clinically.
        :else
        (let [results (keep (fn [hit]
                              (let [r (protos/cm-translate (:impl hit)
                                                           (params-for-cm-entry params hit))]
                                (when (seq (:matches r)) r)))
                            hits)
              signatures (set (map match-signature results))]
          (cond
            (empty? results) {:result false :message "No matches found"}
            (= 1 (count signatures)) (first results)
            :else (ambiguous-cm-result system target)))))))

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

(defn- key->params
  "Map a metadata tuple to the `*-resource` params map. Multi-resource
  providers dispatch on `:url`/`:system`; single-resource impls ignore
  the keys and return their bound resource."
  [{:keys [url version]}]
  (cond-> {:url url :system url}
    version (assoc :version version)))

(defn- compare-by-url-version
  "Sort comparator: alphabetic on URL, then `semver-compare` on version.
  The URL compare short-circuits, so versions are only parsed for the
  rare same-URL ties."
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

(defn- pair-seq
  "Lazy seq of `[provider tuple]` pairs. Filters (`:url :version :status
  :name :title :description`) and implicit-suppression are pushed into
  `metadata-fn` via the metadata-opts DSL; providers honour them, so each
  emitted tuple is a match."
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
  "Search `providers` for the resources matching `params`. The full
  filter set (`:url :version :status :name :title :description`) is
  pushed to each provider via the metadata-opts DSL, and implicit
  routing-only entries are suppressed (`:include-implicit? false`), so
  each emitted tuple is a real, listable match. Tuples are deduplicated
  by `[url version]` (a resource served by two providers counts once,
  first registration wins), sorted, and only the requested page is
  materialised via `resource-fn` — the total is counted from the cheap
  metadata tuples, never by materialising the whole catalogue.

  `:_summary=count` returns `total` only; `:_count`/`:_offset`
  paginate. Defaults and caps for `:_count` are applied at the HTTP
  boundary."
  [providers metadata-fn resource-fn {:keys [_count _offset _summary] :as params}]
  (let [filters (dissoc params :_count :_offset :_summary)
        opts    (into {:include-implicit? false} filters)
        deduped (->> (pair-seq providers metadata-fn opts)
                     (reduce (fn [m [_ t :as pair]]
                               (let [k [(:url t) (:version t)]]
                                 (cond-> m (not (contains? m k)) (assoc k pair))))
                             {})
                     vals)
        ;; `_summary=count` needs only the total, so skip the sort entirely.
        resources (when-not (= "count" _summary)
                    (into [] (comp (keep (fn [[p t]] (resource-fn p (key->params t))))
                                   (map #(summarise-resource params %)))
                          (page-of _count (or _offset 0)
                                   (sort compare-pair-by-url-version deduped))))]
    (s/assert ::result/search-result
              {:total (count deduped) :resources (or resources [])})))

(defn search-code-systems
  "Search registered CodeSystem resources. `params` is a
  `::input/search-params`; returns a `::result/search-result`."
  [{:keys [cs-providers]} params]
  (search* cs-providers protos/cs-metadata protos/cs-resource params))

(defn search-value-sets
  "Search registered ValueSet resources. `params` is a
  `::input/search-params`; returns a `::result/search-result`.

  Implicit ValueSets — those advertised in `vs-metadata` for routing
  only — are dropped at the provider via `:include-implicit? false` in
  the metadata-opts the composite hands down."
  [{:keys [vs-providers]} params]
  (search* vs-providers protos/vs-metadata protos/vs-resource params))

(defn search-concept-maps
  "Search registered ConceptMap resources. `params` is a
  `::input/search-params`; returns a `::result/search-result`."
  [svc {:keys [_count _offset _summary] :as params}]
  (let [{url-q :url ver-q :version :as filters} (dissoc params :_count :_offset :_summary)
        matched (->> (protos/cm-metadata svc {})
                     (keep (fn [d]
                             (when (and (or (nil? url-q) (= url-q (:url d)))
                                        (or (nil? ver-q) (= ver-q (:version d))))
                               (protos/cm-resource svc (key->params d)))))
                     (filter #(search-filter/matches-resource-filters? % filters))
                     (reduce (fn [m {:keys [url version] :as r}] (assoc m [url version] r)) {})
                     vals
                     (sort compare-by-url-version))
        page (if (= "count" _summary)
               []
               (into [] (map #(summarise-resource params %))
                     (page-of _count (or _offset 0) matched)))]
    (s/assert ::result/search-result {:total (count matched) :resources page})))

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
    (or first-not-found
        (let [valid         (last (filter :result per-coding))
              invalid       (remove :result per-coding)
              all-issues    (into [] (mapcat :issues) invalid)
              cs-error-msgs (into [] (comp (keep #(when (= "invalid-code" (:details-code %)) (:text %)))
                                           (distinct))
                                  all-issues)]
          (if valid
            (cond-> valid
              (seq invalid)    (assoc :result false)
              (seq all-issues) (update :issues (fnil into []) all-issues)
              (seq cs-error-msgs) (assoc :message (first cs-error-msgs)))
            (let [version-issue-codes #{"vs-invalid" "version-error" "version-mismatch"}
                  best-invalid (last (filter (fn [r]
                                               (and (:display r) (:system r)
                                                    (some #(contains? version-issue-codes (:details-code %))
                                                          (:issues r))
                                                    (not-any? #(= "not-in-vs" (:details-code %))
                                                              (:issues r))))
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

(defn- collect-cs-entries
  "Walk every CodeSystem provider's cs-metadata and produce one entry
  per URL it serves. A CodeSystem with `:identifiers` (OIDs, URNs,
  legacy URIs) emits one entry per identifier in addition to its
  canonical URL — all pointing to the same provider — so the URL
  index resolves any of them in one lookup. Providers accept any URL
  they registered and substitute internally."
  [providers]
  (for [p providers
        :when (satisfies? protos/CodeSystem p)
        m (protos/cs-metadata p {})
        url (cons (:url m) (:identifiers m))]
    {:provider p :url url :version (:version m)
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

(defn- supplement-specs
  "Derive `supplement/resolve-supplements` input from CodeSystem entries
  whose provider is a `content=\"supplement\"` CodeSystem able to yield
  its augmentation table. One spec per supplement provider."
  [cs-entries]
  (->> cs-entries
       (filter (fn [{:keys [content supplements provider]}]
                 (and (= "supplement" content)
                      supplements
                      (satisfies? supplement/SupplementSource provider))))
       (group-by :provider)
       vals
       (map (fn [entries]
              (let [{:keys [provider supplements]} (first entries)]
                {:meta   {:supplements-target supplements}
                 :lookup (supplement/supplement-lookup-table provider)})))))

(defn from-providers
  "Build a TerminologyService from a sequence of provider impls.
  Each provider must satisfy at least one of CodeSystem, ValueSet,
  ConceptMap. Catalogues are populated from `*-metadata` calls.

  A `content=\"supplement\"` CodeSystem among `providers` is detected
  here (via `supplement/SupplementSource`) and its augmentation table is
  wired onto the base it supplements, replacing that base with a
  `SupplementedCodeSystem` wrapper. Bases are looked up in `providers`
  first, then via `:lookup-fallback`.

  Options:
    :lookup-fallback — fn `(fn [key] → impl-or-nil)` consulted when a
                       supplement's base isn't in `providers`. Used during
                       layered boot (e.g. supplement loaded after a Hermes
                       base already in another service).
    :defaults       — `{url version}` map binding the bare URL to a
                      specific version. Required when two or more
                      providers serve the same canonical and the
                      semver-latest is ambiguous.

  The service owns the providers passed in: `.close` closes every one
  that is `Closeable`, in reverse registration order.

  Aliases (OIDs/URNs/legacy URIs) ride on each CodeSystem's
  `:identifiers` metadata field — the composite indexes them here so a
  lookup against any identifier substitutes the canonical URL before
  dispatching to the provider."
  ([providers] (from-providers providers {}))
  ([providers {:keys [lookup-fallback defaults]}]
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
                                    cm-entries)}
           ;; Detect content="supplement" providers and wrap the bases
           ;; they supplement with a SupplementedCodeSystem.
           supps (supplement-specs cs-entries)
           with-supps
           (if (seq supps)
             (let [resolved (supplement/resolve-supplements
                              (select-keys base [:codesystems :valuesets])
                              supps
                              lookup-fallback)]
               (assoc base
                      :codesystems (:codesystems resolved)
                      :valuesets (:valuesets resolved)))
             base)
           cs-providers (distinct (vals (:codesystems with-supps)))
           vs-providers (distinct (vals (:valuesets with-supps)))]
       (->TerminologyService
         (:codesystems with-supps)
         (:valuesets with-supps)
         (:conceptmaps with-supps)
         cs-providers
         vs-providers
         (distinct providers))))))

(defn with-overlays
  "Return a derived TerminologyService layering `providers` on top of
  `base`. Overlay entries take precedence on exact-key match. Used
  per-request for `tx-resource` parameters. A `content=\"supplement\"`
  overlay provider is detected by `from-providers` and wired onto its
  base (in the overlay or, via `lookup-fallback`, in `base`)."
  [base providers]
  (let [overlay (from-providers providers {:lookup-fallback #(find-codesystem base %)})
        codesystems (merge (:codesystems base) (:codesystems overlay))
        valuesets (merge (:valuesets base) (:valuesets overlay))]
    ;; Overlay-first ordering for `:conceptmaps`: `candidate-cm-impls`
    ;; walks in order and picks the first match, so overlay entries
    ;; shadow base entries.
    (->TerminologyService
      codesystems
      valuesets
      (into (:conceptmaps overlay) (:conceptmaps base))
      (distinct (concat (:cs-providers overlay) (:cs-providers base)))
      (distinct (concat (:vs-providers overlay) (:vs-providers base)))
      ;; A derived view owns no providers: the base owns its own and the
      ;; request-scoped overlay providers are released by GC. Closing the
      ;; view must not close the base.
      nil)))
