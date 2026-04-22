(ns com.eldrix.hades.protocol-test
  "Internal conformance harness for protocol implementations.

  Exercises protocol impls against the spec'd return contracts in
  protocols.clj. When an external FHIR conformance test fails, add a
  targeted test here first — isolate the layer, then fix.

  Contract validators assert return values conform to specs. Scenario
  tests exercise specific edge cases found via the conformance suite."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.impl.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.impl.fhir-valueset :as fhir-vs]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Spec instrumentation — validate inputs at boundaries during test runs
;; ---------------------------------------------------------------------------

(stest/instrument (filter #(clojure.string/starts-with? (namespace %) "com.eldrix.hades")
                          (stest/instrumentable-syms)))

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

(def cs (fhir-cs/make-fhir-code-system simple-cs-map))
(def vs (fhir-vs/make-fhir-value-set simple-vs-map))

(defn register-fixture [f]
  (registry/register-codesystem "http://example.com/contract-cs" cs)
  (registry/register-valueset "http://example.com/contract-cs" cs)
  (f))

(use-fixtures :each register-fixture)

;; ---------------------------------------------------------------------------
;; Contract validators — generic functions that assert spec conformance
;; ---------------------------------------------------------------------------

(defn assert-lookup-contract
  "Assert that cs-lookup returns a valid ::protos/lookup-result."
  [impl code]
  (let [result (protos/cs-lookup impl {:code code :system "http://example.com/contract-cs"})]
    (when result
      (is (s/valid? ::protos/lookup-result result)
          (str "cs-lookup contract violation:\n" (s/explain-str ::protos/lookup-result result))))
    result))

(defn assert-validate-code-contract
  "Assert that cs-validate-code returns a valid ::protos/validate-result."
  [impl code]
  (let [result (protos/cs-validate-code impl {:code code})]
    (when result
      (is (s/valid? ::protos/validate-result result)
          (str "cs-validate-code contract violation:\n" (s/explain-str ::protos/validate-result result))))
    result))

(defn assert-vs-validate-code-contract
  "Assert that vs-validate-code returns a valid ::protos/validate-result."
  [impl ctx params]
  (let [result (protos/vs-validate-code impl ctx params)]
    (when result
      (is (s/valid? ::protos/validate-result result)
          (str "vs-validate-code contract violation:\n" (s/explain-str ::protos/validate-result result))))
    result))

;; ---------------------------------------------------------------------------
;; cs-lookup contract tests
;; ---------------------------------------------------------------------------

(deftest cs-lookup-contract-test
  (testing "valid code returns spec-conforming result"
    (let [result (assert-lookup-contract cs "A")]
      (is (some? result))
      (is (= "Alpha" (:display result)))))

  (testing "unknown code returns nil"
    (is (nil? (protos/cs-lookup cs {:code "MISSING"})))))

;; ---------------------------------------------------------------------------
;; cs-validate-code contract tests
;; ---------------------------------------------------------------------------

(deftest cs-validate-code-contract-test
  (testing "valid code returns spec-conforming result"
    (let [result (assert-validate-code-contract cs "A")]
      (is (true? (:result result)))
      (is (= "Alpha" (:display result)))))

  (testing "unknown code returns spec-conforming result=false"
    (let [result (assert-validate-code-contract cs "MISSING")]
      (is (false? (:result result)))
      (is (some? (:message result)))
      (is (seq (:issues result)))))

  (testing "inactive code has :inactive flag"
    (let [result (assert-validate-code-contract cs "B")]
      (is (true? (:result result)))
      (is (true? (:inactive result)))))

  (testing "display mismatch returns issues"
    (let [result (protos/cs-validate-code cs {:code "A" :display "Wrong"})]
      (is (s/valid? ::protos/validate-result result))
      (is (false? (:result result)))
      (is (some #(= "invalid-display" (:details-code %)) (:issues result))))))

;; ---------------------------------------------------------------------------
;; vs-validate-code contract tests
;; ---------------------------------------------------------------------------

(deftest vs-validate-code-contract-test
  (testing "code in value set"
    (let [result (assert-vs-validate-code-contract vs nil
                   {:code "A" :system "http://example.com/contract-cs"})]
      (is (true? (:result result)))
      (is (= "Alpha" (:display result)))))

  (testing "code not in value set"
    (let [result (assert-vs-validate-code-contract vs nil
                   {:code "C" :system "http://example.com/contract-cs"})]
      (is (false? (:result result)))
      (is (some #(= "not-in-vs" (:details-code %)) (:issues result)))))

  (testing "inactive code in value set returns :inactive"
    (let [result (assert-vs-validate-code-contract vs nil
                   {:code "B" :system "http://example.com/contract-cs"})]
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
