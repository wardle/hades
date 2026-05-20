(ns com.eldrix.hades.impl.expand-test
  "Tests for ValueSet $expand against the pinned SNOMED CT
  International fixture.

  Drives `core/expand` with `clojure.test.check`-generated FHIR
  ValueSet `compose` definitions and `::compose/expand-params`. Each
  generated compose is registered as a `tx-resource`-style overlay on
  the base service so the test exercises the full URL → loaders →
  in-memory provider → composite → compose engine path that the HTTP
  layer hits.

  The property under test:

    For every well-formed compose + params, `core/expand` either
    returns a result conforming to `::core/expansion-result`, or
    signals a typed processing exception — never an undeclared
    crash, spec-failing return, or count/offset invariant violation.

  Tagged `^:live` — needs the pinned SNOMED CT International fixture.
  Runs 100 trials; bump `trials` if soaking. Each trial costs ~0.4s
  on average, so the test contributes ~30s to a `clj -M:test` run."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.impl.load :as load-fhir]))

(set! *warn-on-reflection* true)

(def ^:private snomed-uri "http://snomed.info/sct")

;; Known-good SNOMED anchors — concrete codes the fixture resolves.
;; Generators mix these with random digits so both the happy path and
;; the unknown-code path get exercised on every run.
(def ^:private known-codes
  ["404684003"   ; clinical finding
   "64572001"    ; disease
   "73211009"    ; diabetes mellitus
   "44054006"    ; type 2 DM
   "195967001"   ; asthma
   "24700007"    ; multiple sclerosis
   "71388002"    ; procedure
   "6142004"])   ; influenza

;; FHIR ValueSet.compose.include.filter.op values that SNOMED supports.
;; `regex` is deliberately excluded — pathological patterns can pin a
;; Lucene scan and ReDoS deserves a separate axis of fuzzing.
(def ^:private filter-ops
  ["is-a" "descendent-of" "descendent-or-self-of" "is-not-a"
   "=" "in" "not-in" "exists"])

;; ─── Generators ─────────────────────────────────────────────────────────────

(def ^:private gen-good-code (gen/elements known-codes))

(def ^:private gen-bad-code
  (gen/fmap str (gen/choose 1 99999999)))

(def ^:private gen-code
  (gen/frequency [[8 gen-good-code] [2 gen-bad-code]]))

(def ^:private gen-concept-entry
  (gen/fmap (fn [c] {"code" c}) gen-code))

(def ^:private gen-filter-property
  (gen/frequency
    [[6 (gen/return "concept")]
     [1 (gen/return "parent")]
     [1 (gen/return "child")]
     [1 (gen/return "363698007")]   ; SNOMED attribute: Finding site
     [1 (gen/return "garbage-prop")]]))

(def ^:private gen-filter-value
  (gen/frequency
    [[6 gen-good-code]
     [2 (gen/elements ["pain" "asthma" "fracture"])]
     [1 (gen/return "")]            ; broken-filter path (no value)
     [1 gen-bad-code]]))

(def ^:private gen-filter
  (gen/let [property gen-filter-property
            op       (gen/elements filter-ops)
            value    gen-filter-value]
    (cond-> {"property" property "op" op}
      (not= op "exists") (assoc "value" value))))

(def ^:private gen-include
  (gen/let [concepts (gen/vector gen-concept-entry 0 3)
            filters  (gen/vector gen-filter 0 3)]
    (cond-> {"system" snomed-uri}
      (seq concepts) (assoc "concept" concepts)
      (seq filters)  (assoc "filter" filters))))

(def ^:private gen-compose
  (gen/let [includes (gen/vector gen-include 1 3)
            excludes (gen/vector gen-include 0 2)]
    (cond-> {"include" includes}
      (seq excludes) (assoc "exclude" excludes))))

;; ::compose/expand-params is the contract for what callers may pass to
;; the engine. Derive a generator from the spec so adding a field to
;; the spec automatically extends fuzz coverage. Overrides cap
;; `::offset` / `::count` (unbounded `nat-int?` generators are
;; pathological for paging) and pin `::expanding` to nil — the
;; circular-ref tracker is engine-internal, not a caller input.
(def ^:private gen-params
  (let [override (fn [g] (constantly g))
        spec-gen (s/gen ::compose/expand-params
                        {::compose/offset    (override (gen/one-of [(gen/return nil)
                                                                    (gen/choose 0 50)]))
                         ::compose/count     (override (gen/one-of [(gen/return nil)
                                                                    (gen/choose 0 25)]))
                         ::compose/expanding (override (gen/return nil))})]
    (gen/let [base spec-gen
              lang (gen/one-of [(gen/return nil)
                                (gen/elements ["en" "en-US" "en-GB" "fr"])])]
      (cond-> base
        lang (assoc :displayLanguage lang)))))

;; ─── Property ───────────────────────────────────────────────────────────────

(def ^:dynamic *svc* nil)

(defn- svc-fixture [f]
  (let [svc (hades/open [fixtures/snomed-db-path])]
    (binding [*svc* svc]
      (try (f) (finally (hades/close svc))))))

(use-fixtures :once svc-fixture)

(defn- typed-processing-exception?
  "Whether `t` is an `ex-info` carrying a `:type` the engine is
  documented to raise — `:processing` (compose's circular ValueSet
  guard) or `:validation`. Anything else — NPE, ClassCastException,
  AssertionError, untyped provider errors that bubble up unwrapped —
  is a finding the providers need to fix: every problem must surface
  as a typed FHIR-mappable signal so the HTTP layer can render an
  OperationOutcome."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (#{:processing :validation} (:type (ex-data t)))))

(defn- count-respected? [params {:keys [concepts]}]
  (or (nil? (:count params))
      (<= (count concepts) (:count params))))

(defn- check-result [params result]
  (cond
    (not (s/valid? ::hades/expansion-result result))
    {:reason :spec-fail
     :explain (s/explain-data ::hades/expansion-result result)}

    (not (count-respected? params result))
    {:reason :count-violation
     :requested (:count params)
     :returned  (count (:concepts result))}

    :else nil))

(defn- run-once
  "Register the generated compose as an overlay ValueSet, expand
  through `core/expand`, and check the result. Returns nil on success,
  a `{:reason …}` map otherwise."
  [compose params]
  (let [url  (str "urn:uuid:fuzz-" (java.util.UUID/randomUUID))
        vs   {"resourceType" "ValueSet"
              "url"          url
              "status"       "active"
              "compose"      compose}
        prov (load-fhir/from-fhir vs)
        svc' (hades/with-overlays *svc* [prov])]
    (try
      (check-result params (hades/expand svc' (assoc params :url url)))
      (catch Throwable t
        (when-not (typed-processing-exception? t)
          {:reason :crash
           :class  (.getName (class t))
           :msg    (.getMessage t)})))))

(def ^:private expand-property
  (prop/for-all [compose gen-compose
                 params  gen-params]
    (nil? (run-once compose params))))

(def ^:private trials 100)

(deftest ^:live expand-survives-arbitrary-compose
  (let [result (tc/quick-check trials expand-property {:max-size 30})]
    (if (true? (:result result))
      (is true)
      (let [[compose params] (-> result :shrunk :smallest)
            failure          (run-once compose params)]
        (is false
            (str "Counterexample after " (:num-tests result)
                 " trials (shrunk in " (-> result :shrunk :total-nodes-visited)
                 " steps):\n"
                 "  compose: " (pr-str compose) "\n"
                 "  params:  " (pr-str params)  "\n"
                 "  failure: " (pr-str failure)))))))
