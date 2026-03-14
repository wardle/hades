(ns com.eldrix.hades.fhir-valueset-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.fhir-valueset :as fhir-vs]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry]))

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
        (is (= "http://example.com/vs" (get res "url")))
        (is (= "1.0" (get res "version")))))))

(deftest vs-expand-test
  (let [fvs (fhir-vs/make-fhir-value-set test-vs-map)]
    (testing "expand returns included concepts"
      (let [result (protos/vs-expand fvs {})]
        (is (= 2 (count result)))
        (let [codes (set (map :code result))]
          (is (contains? codes "X"))
          (is (contains? codes "Y"))
          (is (not (contains? codes "Z"))))))))

(deftest vs-validate-code-test
  (let [fvs (fhir-vs/make-fhir-value-set test-vs-map)]
    (testing "code in value set"
      (let [result (protos/vs-validate-code fvs {:code "X" :system "http://example.com/cs"})]
        (is (true? (get result "result")))
        (is (= "X-ray" (get result "display")))))

    (testing "code not in value set"
      (let [result (protos/vs-validate-code fvs {:code "Z" :system "http://example.com/cs"})]
        (is (false? (get result "result")))))

    (testing "display mismatch"
      (let [result (protos/vs-validate-code fvs {:code "X" :system "http://example.com/cs" :display "Wrong"})]
        (is (true? (get result "result")))
        (is (some? (get result "message")))))))
