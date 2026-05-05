(ns com.eldrix.hades.impl.protocol-test
  "Internal conformance harness for protocol implementations.

  Exercises protocol impls against the spec'd return contracts in
  protocols.clj. When an external FHIR conformance test fails, add a
  targeted test here first — isolate the layer, then fix.

  Contract validators assert return values conform to specs. Scenario
  tests exercise specific edge cases found via the conformance suite."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.protocols :as protos]))

(stest/instrument)
;; See in_memory_test for rationale: these Hermes fns spec-reject
;; malformed SCT ids at the boundary, but in production they just
;; return nil/empty. Leaving them instrumented turns "unknown code"
;; handling into a 500 in the test suite.
(stest/unstrument
  '[com.eldrix.hermes.core/concept
    com.eldrix.hermes.core/component-refset-items
    com.eldrix.hermes.core/historical-associations
    com.eldrix.hermes.core/subsumed-by?
    com.eldrix.hermes.core/with-historical])

;; ---------------------------------------------------------------------------
;; Generic return-shape conformance — fetches each `core` operation's
;; `:ret` spec via `s/get-spec` and validates the live result against
;; it. Adding `:ret` to a new operation automatically gives it
;; coverage; no per-operation boilerplate here.
;; ---------------------------------------------------------------------------

(defn check-ret
  "Run `f` with `args`, assert the result conforms to f's `:ret` spec
  (looked up via `s/get-spec`). Returns the result for further checks."
  [sym f & args]
  (let [result (apply f args)
        ret-spec (some-> (s/get-spec sym) :ret)]
    (when ret-spec
      (is (s/valid? ret-spec result)
          (str sym " :ret violation:\n" (s/explain-str ret-spec result))))
    result))

;; ---------------------------------------------------------------------------
;; Test fixtures — minimal code systems and value sets for contract testing
;; ---------------------------------------------------------------------------

(def simple-cs-map
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/contract-cs"
   "version"      "1.0"
   "name"         "ContractCS"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "A" "display" "Alpha"}
                    {"code" "B" "display" "Beta"
                     "property" [{"code" "status" "valueCode" "retired"}]}
                    {"code" "C" "display" "Gamma"
                     "property" [{"code" "inactive" "valueBoolean" true}]}]})

(def simple-vs-map
  {"resourceType" "ValueSet"
   "url"          "http://example.com/contract-vs"
   "version"      "1.0"
   "name"         "ContractVS"
   "status"       "active"
   "compose"      {"include" [{"system" "http://example.com/contract-cs"
                                "concept" [{"code" "A"} {"code" "B"}]}]}})

(def cs (load-fhir/from-fhir simple-cs-map))
(def vs (load-fhir/from-fhir simple-vs-map))

(def ^:dynamic *svc* nil)

(defn svc-fixture [f]
  (binding [*svc* (composite/from-providers [cs vs])]
    (f)))

(use-fixtures :each svc-fixture)

;; ---------------------------------------------------------------------------
;; cs-lookup scenario tests — `check-ret` validates the return shape via
;; `core/lookup`'s `:ret` spec; the explicit assertions check semantics.
;; ---------------------------------------------------------------------------

(deftest cs-lookup-contract-test
  (testing "valid code returns spec-conforming result"
    (let [result (check-ret `hades/lookup hades/lookup *svc*
                            {:code "A" :system "http://example.com/contract-cs"})]
      (is (some? result))
      (is (= "Alpha" (:display result)))))

  (testing "unknown code returns a spec-conforming not-found result"
    (let [result (check-ret `hades/lookup hades/lookup *svc*
                            {:code "MISSING" :system "http://example.com/contract-cs"})]
      (is (true? (:not-found result)))
      (is (= :unknown-code (:not-found-reason result)))))

  (testing "unknown system returns a spec-conforming not-found result"
    (let [result (check-ret `hades/lookup hades/lookup *svc*
                            {:code "X" :system "http://example.com/no-such"})]
      (is (true? (:not-found result)))
      (is (= :unknown-system (:not-found-reason result))))))

;; ---------------------------------------------------------------------------
;; cs-validate-code scenario tests
;; ---------------------------------------------------------------------------

(defn- cs-validate [code]
  (check-ret `hades/validate-code hades/validate-code *svc*
             {:code code :system "http://example.com/contract-cs"}))

(deftest cs-validate-code-contract-test
  (testing "valid code returns spec-conforming result"
    (let [result (cs-validate "A")]
      (is (true? (:result result)))
      (is (= "Alpha" (:display result)))))

  (testing "unknown code returns spec-conforming result=false"
    (let [result (cs-validate "MISSING")]
      (is (false? (:result result)))
      (is (some? (:message result)))
      (is (seq (:issues result)))))

  (testing "inactive code has :inactive flag"
    (let [result (cs-validate "B")]
      (is (true? (:result result)))
      (is (true? (:inactive result)))))

  (testing "display mismatch returns issues"
    (let [result (check-ret `hades/validate-code hades/validate-code *svc*
                            {:code "A" :system "http://example.com/contract-cs"
                             :display "Wrong"})]
      (is (false? (:result result)))
      (is (some #(= "invalid-display" (:details-code %)) (:issues result))))))

;; ---------------------------------------------------------------------------
;; vs-validate-code scenario tests
;; ---------------------------------------------------------------------------

(defn- vs-validate [code]
  (check-ret `hades/validate-code hades/validate-code *svc*
             {:url    "http://example.com/contract-vs"
              :code   code
              :system "http://example.com/contract-cs"}))

(deftest vs-validate-code-contract-test
  (testing "code in value set"
    (let [result (vs-validate "A")]
      (is (true? (:result result)))
      (is (= "Alpha" (:display result)))))

  (testing "code not in value set"
    (let [result (vs-validate "C")]
      (is (false? (:result result)))
      (is (some #(= "not-in-vs" (:details-code %)) (:issues result)))))

  (testing "inactive code in value set returns :inactive"
    (let [result (vs-validate "B")]
      (is (true? (:result result)))
      (is (true? (:inactive result))))))

;; ---------------------------------------------------------------------------
;; cs-resource / vs-resource contract tests
;; ---------------------------------------------------------------------------

(deftest resource-contract-test
  (testing "cs-resource returns keyword-keyed metadata"
    (let [result (protos/cs-resource cs {})]
      (is (= "http://example.com/contract-cs" (:url result)))
      (is (= "1.0" (:version result)))
      (is (= "active" (:status result)))))

  (testing "vs-resource returns keyword-keyed metadata"
    (let [result (protos/vs-resource vs {})]
      (is (= "http://example.com/contract-vs" (:url result)))
      (is (= "1.0" (:version result))))))
