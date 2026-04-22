(ns com.eldrix.hades.fhir-valueset-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.impl.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.impl.fhir-valueset :as fhir-vs]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.registry :as registry]))

(stest/instrument (filter #(clojure.string/starts-with? (namespace %) "com.eldrix.hades")
                          (stest/instrumentable-syms)))

(def test-cs-map
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/cs"
   "version"      "1.0"
   "name"         "TestCS"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "X" "display" "X-ray"}
                    {"code" "Y" "display" "Yankee"}
                    {"code" "Z" "display" "Zulu"}]})

(def test-cs (fhir-cs/make-fhir-code-system test-cs-map))

(def test-vs-map
  {"resourceType" "ValueSet"
   "url"          "http://example.com/vs"
   "version"      "1.0"
   "name"         "TestVS"
   "status"       "active"
   "compose"      {"include" [{"system" "http://example.com/cs"
                                "concept" [{"code" "X"}
                                           {"code" "Y"}]}]}})

(defn register-fixture [f]
  (registry/register-codesystem "http://example.com/cs" test-cs)
  (registry/register-valueset "http://example.com/cs" test-cs)
  (f))

(use-fixtures :each register-fixture)

(deftest make-fhir-value-set-test
  (let [fvs (fhir-vs/make-fhir-value-set test-vs-map)]
    (is (some? fvs))
    (testing "vs-resource returns metadata"
      (let [res (protos/vs-resource fvs {})]
        (is (= "http://example.com/vs" (:url res)))
        (is (= "1.0" (:version res)))))))

(deftest vs-expand-test
  (let [fvs (fhir-vs/make-fhir-value-set test-vs-map)]
    (testing "expand returns included concepts"
      (let [{:keys [concepts total used-codesystems]} (protos/vs-expand fvs nil {})]
        (is (= 2 (count concepts)))
        (let [codes (set (map :code concepts))]
          (is (contains? codes "X"))
          (is (contains? codes "Y"))
          (is (not (contains? codes "Z"))))
        (is (= 2 total))
        (is (seq used-codesystems))))))

(deftest vs-validate-code-test
  (let [fvs (fhir-vs/make-fhir-value-set test-vs-map)]
    (testing "code in value set"
      (let [result (protos/vs-validate-code fvs nil {:code "X" :system "http://example.com/cs"})]
        (is (true? (:result result)))
        (is (= "X-ray" (:display result)))))

    (testing "code not in value set"
      (let [result (protos/vs-validate-code fvs nil {:code "Z" :system "http://example.com/cs"})]
        (is (false? (:result result)))))

    (testing "display mismatch"
      (let [result (protos/vs-validate-code fvs nil {:code "X" :system "http://example.com/cs" :display "Wrong"})]
        (is (true? (:result result)))
        (is (some? (:message result)))))))
