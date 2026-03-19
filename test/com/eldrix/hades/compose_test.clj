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

(def test-cs-v2-map
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/cs"
   "version"      "2.0"
   "name"         "TestCS-v2"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "A" "display" "Alpha v2"}
                    {"code" "D" "display" "Delta"}]})

(def test-cs-v2 (fhir-cs/make-fhir-code-system test-cs-v2-map))

(defn register-fixture [f]
  (registry/register-codesystem "http://example.com/cs" test-cs)
  (registry/register-valueset "http://example.com/cs" test-cs)
  (registry/register-codesystem "http://example.com/cs|1.0" test-cs)
  (registry/register-valueset "http://example.com/cs|1.0" test-cs)
  (registry/register-codesystem "http://example.com/cs|2.0" test-cs-v2)
  (registry/register-valueset "http://example.com/cs|2.0" test-cs-v2)
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

(deftest expand-compose-include-version-test
  (testing "include with version selects versioned CodeSystem"
    (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"}]}
          result (compose/expand-compose nil compose {})]
      (is (= 2 (count result)))
      (is (some #(= "D" (:code %)) result))
      (is (not (some #(= "B" (:code %)) result)))))
  (testing "include with version passes version to concept lookup"
    (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"
                               "concept" [{"code" "A"}]}]}
          result (compose/expand-compose nil compose {})]
      (is (= 1 (count result)))
      (is (= "Alpha v2" (:display (first result))))))
  (testing "include with version on filters"
    (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"
                               "filter" [{"property" "code" "op" "=" "value" "D"}]}]}
          result (compose/expand-compose nil compose {})]
      (is (= 1 (count result)))
      (is (= "D" (:code (first result)))))))

(deftest expand-compose-force-system-version-test
  (testing "force-system-version overrides include version"
    (let [ctx {:request {:force-system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs" "version" "1.0"}]}
          result (compose/expand-compose ctx compose {})]
      (is (= 2 (count result)))
      (is (some #(= "D" (:code %)) result))
      (is (not (some #(= "B" (:code %)) result)))))
  (testing "force-system-version applies when no include version"
    (let [ctx {:request {:force-system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          result (compose/expand-compose ctx compose {})]
      (is (= 2 (count result)))
      (is (some #(= "D" (:code %)) result)))))

(deftest expand-compose-system-version-default-test
  (testing "system-version provides default when no include version"
    (let [ctx {:request {:system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          result (compose/expand-compose ctx compose {})]
      (is (= 2 (count result)))
      (is (some #(= "D" (:code %)) result)))))

(deftest expand-compose-system-version-no-override-test
  (testing "system-version does NOT override include version"
    (let [ctx {:request {:system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs" "version" "1.0"}]}
          result (compose/expand-compose ctx compose {})]
      (is (= 5 (count result)))
      (is (some #(= "B" (:code %)) result)))))

(deftest expand-compose-check-system-version-test
  (testing "check-system-version passes when version matches"
    (let [ctx {:request {:check-system-version {"http://example.com/cs" "1.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          result (compose/expand-compose ctx compose {})]
      (is (= 5 (count result)))))
  (testing "check-system-version passes with wildcard"
    (let [ctx {:request {:check-system-version {"http://example.com/cs" "1.x"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          result (compose/expand-compose ctx compose {})]
      (is (= 5 (count result)))))
  (testing "check-system-version with non-existent version returns no results"
    (let [ctx {:request {:check-system-version {"http://example.com/cs" "3.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          result (compose/expand-compose ctx compose {})]
      (is (zero? (count result))))))
