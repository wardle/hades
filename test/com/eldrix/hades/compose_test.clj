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
          {:keys [concepts total used-codesystems]} (compose/expand-compose nil compose {})]
      (is (= 5 (count concepts)))
      (is (every? #(= "http://example.com/cs" (:system %)) concepts))
      (is (= 5 total))
      (is (seq used-codesystems)))))

(deftest expand-compose-include-concepts-test
  (testing "include explicit concept list"
    (let [compose {"include" [{"system" "http://example.com/cs"
                               "concept" [{"code" "A" "display" "My Alpha"}
                                          {"code" "B"}]}]}
          concepts (:concepts (compose/expand-compose nil compose {}))]
      (is (= 2 (count concepts)))
      (is (= "My Alpha" (:display (first (filter #(= "A" (:code %)) concepts)))))
      (is (= "Beta" (:display (first (filter #(= "B" (:code %)) concepts))))))))

(deftest expand-compose-include-filter-is-a-test
  (testing "include with is-a filter"
    (let [compose {"include" [{"system" "http://example.com/cs"
                               "filter" [{"property" "concept" "op" "is-a" "value" "A"}]}]}
          codes (set (map :code (:concepts (compose/expand-compose nil compose {}))))]
      (is (contains? codes "A"))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (not (contains? codes "B"))))))

(deftest expand-compose-exclude-test
  (testing "include all, exclude specific concepts"
    (let [compose {"include" [{"system" "http://example.com/cs"}]
                   "exclude" [{"system" "http://example.com/cs"
                               "concept" [{"code" "B"}]}]}
          concepts (:concepts (compose/expand-compose nil compose {}))
          codes (set (map :code concepts))]
      (is (= 4 (count concepts)))
      (is (not (contains? codes "B"))))))

(deftest expand-compose-filter-text-test
  (testing "post-expansion text filter"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose nil compose {:filter "alpha"}))]
      (is (= 3 (count concepts)))
      (is (every? #(re-find #"(?i)alpha" (or (:display %) "")) concepts)))))

(deftest expand-compose-pagination-test
  (testing "offset and count"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          {all :concepts} (compose/expand-compose nil compose {})
          {:keys [concepts total]} (compose/expand-compose nil compose {:offset 1 :count 2})]
      (is (= 2 (count concepts)))
      (is (= (take 2 (drop 1 all)) concepts))
      (is (= 5 total)))))

(deftest expand-compose-valueset-ref-test
  (testing "include via valueSet reference"
    (let [compose {"include" [{"valueSet" ["http://example.com/cs"]}]}
          concepts (:concepts (compose/expand-compose nil compose {}))]
      (is (= 5 (count concepts))))))

(deftest expand-compose-circular-ref-test
  (testing "circular reference detection"
    (let [compose {"include" [{"valueSet" ["http://example.com/circular"]}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (compose/expand-compose nil compose {:expanding #{"http://example.com/circular"}}))))))

(deftest expand-compose-include-version-test
  (testing "include with version selects versioned CodeSystem"
    (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"}]}
          {:keys [concepts compose-pins]} (compose/expand-compose nil compose {})]
      (is (= 2 (count concepts)))
      (is (some #(= "D" (:code %)) concepts))
      (is (not (some #(= "B" (:code %)) concepts)))
      (is (= [{:system "http://example.com/cs" :version "2.0"}] compose-pins))))
  (testing "include with version passes version to concept lookup"
    (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"
                               "concept" [{"code" "A"}]}]}
          concepts (:concepts (compose/expand-compose nil compose {}))]
      (is (= 1 (count concepts)))
      (is (= "Alpha v2" (:display (first concepts))))))
  (testing "include with version on filters"
    (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"
                               "filter" [{"property" "code" "op" "=" "value" "D"}]}]}
          concepts (:concepts (compose/expand-compose nil compose {}))]
      (is (= 1 (count concepts)))
      (is (= "D" (:code (first concepts)))))))

(deftest expand-compose-force-system-version-test
  (testing "force-system-version overrides include version"
    (let [ctx {:request {:force-system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs" "version" "1.0"}]}
          concepts (:concepts (compose/expand-compose ctx compose {}))]
      (is (= 2 (count concepts)))
      (is (some #(= "D" (:code %)) concepts))
      (is (not (some #(= "B" (:code %)) concepts)))))
  (testing "force-system-version applies when no include version"
    (let [ctx {:request {:force-system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose ctx compose {}))]
      (is (= 2 (count concepts)))
      (is (some #(= "D" (:code %)) concepts)))))

(deftest expand-compose-system-version-default-test
  (testing "system-version provides default when no include version"
    (let [ctx {:request {:system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose ctx compose {}))]
      (is (= 2 (count concepts)))
      (is (some #(= "D" (:code %)) concepts)))))

(deftest expand-compose-system-version-no-override-test
  (testing "system-version does NOT override include version"
    (let [ctx {:request {:system-version {"http://example.com/cs" "2.0"}}}
          compose {"include" [{"system" "http://example.com/cs" "version" "1.0"}]}
          concepts (:concepts (compose/expand-compose ctx compose {}))]
      (is (= 5 (count concepts)))
      (is (some #(= "B" (:code %)) concepts)))))

(deftest expand-compose-unknown-system-warning-test
  (testing "multi-system include with unknown CS emits warning (expand purpose)"
    (let [compose {"include" [{"system" "http://example.com/cs"}
                              {"system" "http://unknown.example.com/loinc"}
                              {"system" "http://unknown.example.com/rxnorm"}]}
          {:keys [concepts issues]} (compose/expand-compose nil compose {:purpose :expand})]
      (is (= 5 (count concepts)))
      (is (= 2 (count issues)))
      (is (every? #(= "warning" (:severity %)) issues))
      (is (every? #(= "not-found" (:details-code %)) issues))
      (is (some #(re-find #"loinc" (:text %)) issues))
      (is (some #(re-find #"rxnorm" (:text %)) issues))))
  (testing "validate purpose does not emit unknown-system warnings"
    (let [compose {"include" [{"system" "http://unknown.example.com/loinc"}]}
          {:keys [issues]} (compose/expand-compose nil compose {})]
      (is (empty? (filter #(and (= "warning" (:severity %))
                                (re-find #"loinc" (:text %)))
                          issues))))))

(deftest expand-compose-check-system-version-test
  (testing "check-system-version passes when version matches"
    (let [ctx {:request {:check-system-version {"http://example.com/cs" "1.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose ctx compose {}))]
      (is (= 5 (count concepts)))))
  (testing "check-system-version passes with wildcard"
    (let [ctx {:request {:check-system-version {"http://example.com/cs" "1.x"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose ctx compose {}))]
      (is (= 5 (count concepts)))))
  (testing "check-system-version with non-existent version returns no results"
    (let [ctx {:request {:check-system-version {"http://example.com/cs" "3.0"}}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose ctx compose {}))]
      (is (zero? (count concepts))))))
