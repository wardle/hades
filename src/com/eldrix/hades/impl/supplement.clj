(ns com.eldrix.hades.impl.supplement
  "Supplement composition: a thin wrapper deftype that augments any
  `CodeSystem` provider with extra designations and properties from a
  supplement lookup, plus a pure resolver that walks a supplement seq
  and replaces matching base providers with the wrapper.

  Talks to the base only via protocol methods, so it works against
  in-memory, Hermes, or any future provider."
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.hades.impl.display :as display]
            [com.eldrix.hades.impl.fhir-extract :as fhir-extract]
            [com.eldrix.hades.impl.protocols :as protos]))

(set! *warn-on-reflection* true)

(defn- supplement-properties->result-properties
  "Convert raw FHIR property maps from a supplement into the
  keyword-keyed `{:code :value}` shape used by `cs-lookup` results.
  Excludes `parent`/`child` properties — those are derived from
  hierarchy, not the supplement."
  [props]
  (keep (fn [prop]
          (let [pc (get prop "code")
                v  (fhir-extract/typed-property-value prop)]
            (when (and pc (some? v) (not (#{"parent" "child"} pc)))
              {:code (keyword pc) :value v})))
        props))

(defn- result-code->str
  "Result `:code` is a keyword; supplement lookup keys are strings."
  [code]
  (cond
    (keyword? code) (name code)
    (string? code)  code))

(defn- augment-lookup-result
  [result extras]
  (cond-> result
    (seq (:designations extras))
    (update :designations (fnil into []) (:designations extras))
    (seq (:properties extras))
    (update :properties (fnil into []) (supplement-properties->result-properties (:properties extras)))))

(defn- display-matches-supplement?
  "True when `display` matches one of the supplement designations
  (case-insensitive, language-aware via display-langs)."
  [extras display display-langs]
  (display/display-matches?
    {:designations (:designations extras)}
    display
    display-langs))

(deftype SupplementedCodeSystem [base supplement-lookup]
  protos/CodeSystem
  (cs-metadata [_]
    (protos/cs-metadata base))

  (cs-resource [_ params]
    (protos/cs-resource base params))

  (cs-lookup [_ params]
    (let [r (protos/cs-lookup base params)]
      (if (:not-found r)
        r
        (if-let [extras (get supplement-lookup (result-code->str (:code r)))]
          (augment-lookup-result r extras)
          r))))

  (cs-validate-code [_ {:keys [code display displayLanguage] :as params}]
    (let [base-result (protos/cs-validate-code base params)
          extras (get supplement-lookup code)]
      (cond
        ;; Base failed solely on display mismatch and the supplement has
        ;; a designation that matches — flip to success.
        (and display extras
             (false? (:result base-result))
             (let [issues (:issues base-result)]
               (and (seq issues)
                    (every? #(= "invalid-display" (:details-code %)) issues)))
             (display-matches-supplement?
               extras display (display/parse-display-language displayLanguage)))
        (-> base-result
            (assoc :result true :display display)
            (dissoc :issues :message))

        :else base-result)))

  (cs-subsumes [_ params]
    (protos/cs-subsumes base params))

  (cs-find-matches [_ query]
    ;; Augmented search across supplement designations is a deferred
    ;; refinement; pass-through for now.
    (protos/cs-find-matches base query))

  protos/ValueSet
  (vs-metadata [_]
    (when (satisfies? protos/ValueSet base)
      (protos/vs-metadata base)))

  (vs-resource [_ params]
    (protos/vs-resource base params))

  (vs-expand [_ svc params]
    (protos/vs-expand base svc params))

  (vs-validate-code [_ svc {:keys [code display displayLanguage] :as params}]
    (let [base-result (protos/vs-validate-code base svc params)
          extras (get supplement-lookup code)]
      (cond
        (and display extras
             (false? (:result base-result))
             (let [issues (:issues base-result)]
               (and (seq issues)
                    (every? #(= "invalid-display" (:details-code %)) issues)))
             (display-matches-supplement?
               extras display (display/parse-display-language displayLanguage)))
        (-> base-result
            (assoc :result true :display display)
            (dissoc :issues :message))

        :else base-result))))

(s/fdef supplemented-codesystem
  :args (s/cat :base #(satisfies? protos/CodeSystem %)
               :supplement-lookup (s/map-of string? map?)))

(defn supplemented-codesystem
  "Wrap any CodeSystem (and optionally ValueSet) provider with a
  supplement lookup. `base` must satisfy `protos/CodeSystem`.

  `supplement-lookup` is the `{code → {:designations [...] :properties [...]}}`
  map produced by the indexer for a `content=\"supplement\"` CodeSystem."
  [base supplement-lookup]
  (->SupplementedCodeSystem base supplement-lookup))

;; ---------------------------------------------------------------------------
;; Supplement resolution
;;
;; Pure helper that wraps each supplement's base provider with a
;; `SupplementedCodeSystem`. Bases are looked up in `providers` first
;; (the in-progress overlay or freshly-built batch), then via the
;; optional `lookup-fallback` (used when a supplement loaded after the
;; base reaches an already-registered provider like Hermes).
;; ---------------------------------------------------------------------------

(defn- parse-supplements-target
  "Split a `supplements` canonical (`url` or `url|version`) into [url version]."
  [target]
  (when target
    (let [idx (.lastIndexOf ^String target "|")]
      (if (pos? idx)
        [(subs target 0 idx) (subs target (inc idx))]
        [target nil]))))

(defn- find-base
  [providers lookup-fallback base-url base-version]
  (let [versioned-key (if base-version (str base-url "|" base-version) base-url)]
    (or (get-in providers [:codesystems versioned-key])
        (when base-version (get-in providers [:codesystems base-url]))
        (when lookup-fallback (lookup-fallback versioned-key))
        (when (and base-version lookup-fallback) (lookup-fallback base-url)))))

(defn- write-wrapper
  [providers base-url wrapped base]
  (let [version (:version (protos/cs-resource base {}))
        register-keys (cond-> [base-url]
                        version (conj (str base-url "|" version)))
        register-into (fn [m] (reduce #(assoc %1 %2 wrapped) m register-keys))]
    (cond-> providers
      (satisfies? protos/CodeSystem base) (update :codesystems register-into)
      (satisfies? protos/ValueSet  base)  (update :valuesets   register-into))))

(s/fdef resolve-supplements
  :args (s/cat :providers       map?
               :supplements     (s/coll-of map?)
               :lookup-fallback (s/? (s/nilable ifn?))))

(defn resolve-supplements
  "Apply each supplement's lookup to the matching base in `providers`
  by wrapping it with a `SupplementedCodeSystem`. Returns the updated
  providers map.

  `providers` is `{:codesystems {url|ver → impl} :valuesets {...}}`.
  `supplements` is the `[{:meta :lookup} ...]` seq emitted by the
  indexer.

  `lookup-fallback`, if supplied, is called with a registry key when no
  overlay match is found — used so a supplement can wrap a base that
  was registered before this batch."
  ([providers supplements] (resolve-supplements providers supplements nil))
  ([providers supplements lookup-fallback]
   (reduce
     (fn [providers {:keys [meta lookup]}]
       (let [[base-url base-version] (parse-supplements-target (:supplements-target meta))]
         (if-let [base (and base-url (find-base providers lookup-fallback base-url base-version))]
           (write-wrapper providers base-url
                          (supplemented-codesystem base lookup) base)
           providers)))
     providers supplements)))
