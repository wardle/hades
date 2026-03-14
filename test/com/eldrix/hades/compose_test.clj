(ns com.eldrix.hades.compose-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.compose :as compose]
            [com.eldrix.hades.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.registry :as registry]))

(def test-cs-map
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/cs"
   "version"      "1.0"
   "name"         "TestCS"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "A" "display" "Alpha"
                     "concept" [{"code" "A1" "display" "Alpha One"}
                                {"code" "A2" "display" "Alpha Two"}]}
                    {"code" "B" "display" "Beta"
                     "property" [{"code" "status" "valueCode" "retired"}]}
                    {"code" "C" "display" "Charlie"}]})

(def test-cs (fhir-cs/make-fhir-code-system test-cs-map))

(defn register-fixture [f]
  (registry/register-codesystem "http://example.com/cs" test-cs)
  (registry/register-valueset "http://example.com/cs" test-cs)
  (f))

(use-fixtures :each register-fixture)

(deftest expand-compose-include-all-test
  (testing "include all concepts from a system"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          result (compose/expand-compose nil compose {})]
      (is (= 5 (count result)))
      (is (every? #(= "http://example.com/cs" (:system %)) result)))))

(deftest expand-compose-include-concepts-test
  (testing "include explicit concept list"
    (let [compose {"include" [{"system" "http://example.com/cs"
                               "concept" [{"code" "A" "display" "My Alpha"}
                                          {"code" "B"}]}]}
          result (compose/expand-compose nil compose {})]
      (is (= 2 (count result)))
      (is (= "My Alpha" (:display (first (filter #(= "A" (:code %)) result)))))
      (is (= "Beta" (:display (first (filter #(= "B" (:code %)) result))))))))

(deftest expand-compose-include-filter-is-a-test
  (testing "include with is-a filter"
    (let [compose {"include" [{"system" "http://example.com/cs"
                               "filter" [{"property" "concept" "op" "is-a" "value" "A"}]}]}
          result (compose/expand-compose nil compose {})
          codes (set (map :code result))]
      (is (contains? codes "A"))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (not (contains? codes "B"))))))

(deftest expand-compose-exclude-test
  (testing "include all, exclude specific concepts"
    (let [compose {"include" [{"system" "http://example.com/cs"}]
                   "exclude" [{"system" "http://example.com/cs"
                               "concept" [{"code" "B"}]}]}
          result (compose/expand-compose nil compose {})
          codes (set (map :code result))]
      (is (= 4 (count result)))
      (is (not (contains? codes "B"))))))

(deftest expand-compose-filter-text-test
  (testing "post-expansion text filter"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          result (compose/expand-compose nil compose {:filter "alpha"})]
      (is (= 3 (count result)))
      (is (every? #(re-find #"(?i)alpha" (or (:display %) "")) result)))))

(deftest expand-compose-pagination-test
  (testing "offset and count"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          all (compose/expand-compose nil compose {})
          paged (compose/expand-compose nil compose {:offset 1 :count 2})]
      (is (= 2 (count paged)))
      (is (= (take 2 (drop 1 all)) paged)))))

(deftest expand-compose-valueset-ref-test
  (testing "include via valueSet reference"
    (let [compose {"include" [{"valueSet" ["http://example.com/cs"]}]}
          result (compose/expand-compose nil compose {})]
      (is (= 5 (count result))))))

(deftest expand-compose-circular-ref-test
  (testing "circular reference detection"
    (let [compose {"include" [{"valueSet" ["http://example.com/circular"]}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (compose/expand-compose nil compose {:expanding #{"http://example.com/circular"}}))))))
