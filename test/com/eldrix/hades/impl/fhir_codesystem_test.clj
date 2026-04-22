(ns com.eldrix.hades.impl.fhir-codesystem-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.impl.protocols :as protos]))

(def hierarchical-cs
  {"resourceType"     "CodeSystem"
   "url"              "http://example.com/test-cs"
   "version"          "1.0"
   "name"             "TestCodeSystem"
   "title"            "Test Code System"
   "status"           "active"
   "caseSensitive"    false
   "hierarchyMeaning" "is-a"
   "content"          "complete"
   "concept"          [{"code"    "A"
                        "display" "Concept A"
                        "definition" "The first concept"
                        "designation" [{"language" "de"
                                        "value"    "Konzept A"}]
                        "concept" [{"code"    "A1"
                                    "display" "Concept A1"
                                    "definition" "Child of A"}
                                   {"code"    "A2"
                                    "display" "Concept A2"
                                    "concept" [{"code"    "A2a"
                                                "display" "Concept A2a"}]}]}
                       {"code"    "B"
                        "display" "Concept B"
                        "property" [{"code" "status" "valueCode" "active"}]}
                       {"code"    "C"
                        "display" "Abstract Concept C"
                        "property" [{"code" "notSelectable" "valueBoolean" true}]}]})

(def flat-cs
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/flat-cs"
   "version"      "2.0"
   "name"         "FlatCodeSystem"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "X" "display" "Code X"}
                   {"code" "Y" "display" "Code Y"}
                   {"code" "Z" "display" "Code Z"}]})

(def hier-fcs (fhir-cs/make-fhir-code-system hierarchical-cs))
(def flat-fcs (fhir-cs/make-fhir-code-system flat-cs))

;; --- Construction ---

(deftest construction-test
  (testing "all codes indexed"
    (is (some? hier-fcs))
    (is (some? (protos/cs-lookup hier-fcs {:code "A"})))
    (is (some? (protos/cs-lookup hier-fcs {:code "A1"})))
    (is (some? (protos/cs-lookup hier-fcs {:code "A2"})))
    (is (some? (protos/cs-lookup hier-fcs {:code "A2a"})))
    (is (some? (protos/cs-lookup hier-fcs {:code "B"})))
    (is (nil? (protos/cs-lookup hier-fcs {:code "NOPE"})))))

;; --- cs-lookup ---

(deftest cs-lookup-test
  (testing "returns correct fields"
    (let [result (protos/cs-lookup hier-fcs {:code "A"})]
      (is (= "Concept A" (:display result)))
      (is (= "http://example.com/test-cs" (:system result)))
      (is (= "1.0" (:version result)))
      (is (= "TestCodeSystem" (:name result)))
      (is (= :A (:code result)))
      (is (= "The first concept" (:definition result)))
      (is (false? (:abstract result)) "concept A has children but no notSelectable property")))

  (testing "notSelectable concept has abstract=true"
    (let [result (protos/cs-lookup hier-fcs {:code "C"})]
      (is (true? (:abstract result)))))

  (testing "leaf node has abstract=false"
    (let [result (protos/cs-lookup hier-fcs {:code "B"})]
      (is (false? (:abstract result)))))

  (testing "returns parent/child properties from hierarchy"
    (let [result (protos/cs-lookup hier-fcs {:code "A1"})
          props (:properties result)
          parent-props (filter #(= :parent (:code %)) props)]
      (is (= 1 (count parent-props)))
      (is (= :A (:value (first parent-props))))
      (is (= "Concept A" (:description (first parent-props))))))

  (testing "A has children A1 and A2"
    (let [result (protos/cs-lookup hier-fcs {:code "A"})
          props (:properties result)
          child-props (filter #(= :child (:code %)) props)
          child-codes (set (map :value child-props))]
      (is (= #{:A1 :A2} child-codes))))

  (testing "returns designations"
    (let [result (protos/cs-lookup hier-fcs {:code "A"})
          desigs (:designations result)]
      (is (= 1 (count desigs)))
      (is (= "Konzept A" (:value (first desigs))))
      (is (= :de (:language (first desigs))))))

  (testing "returns concept properties"
    (let [result (protos/cs-lookup hier-fcs {:code "B"})
          props (:properties result)
          status-props (filter #(= :status (:code %)) props)]
      (is (= 1 (count status-props)))
      (is (= :active (:value (first status-props))))))

  (testing "inactive property derived from status"
    (let [result (protos/cs-lookup hier-fcs {:code "A"})
          props (:properties result)
          inactive-props (filter #(= :inactive (:code %)) props)]
      (is (= 1 (count inactive-props)))
      (is (false? (:value (first inactive-props))))))

  (testing "nil for unknown code"
    (is (nil? (protos/cs-lookup hier-fcs {:code "MISSING"})))))

;; --- cs-validate-code ---

(deftest cs-validate-code-test
  (testing "valid code"
    (let [result (protos/cs-validate-code hier-fcs {:code "A"})]
      (is (true? (:result result)))
      (is (= "Concept A" (:display result)))
      (is (nil? (:issues result)))))

  (testing "unknown code"
    (let [result (protos/cs-validate-code hier-fcs {:code "NOPE"})]
      (is (false? (:result result)))
      (is (some? (:message result)))
      (is (= "error" (:severity (first (:issues result)))))
      (is (= "invalid-code" (:details-code (first (:issues result)))))))

  (testing "valid code with correct display"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "Concept A"})]
      (is (true? (:result result)))
      (is (nil? (:issues result)))))

  (testing "valid code with wrong display"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "Wrong"})]
      (is (false? (:result result)))
      (is (str/includes? (:message result) "Wrong"))
      (is (= "invalid-display" (:details-code (first (:issues result)))))))

  (testing "case-insensitive display matching"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "concept a"})]
      (is (true? (:result result)))))

  (testing "display matches designation"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "Konzept A"})]
      (is (true? (:result result))))))

;; --- cs-subsumes ---

(deftest cs-subsumes-test
  (testing "equivalent"
    (is (= "equivalent" (:outcome (protos/cs-subsumes hier-fcs {:codeA "A" :codeB "A"})))))

  (testing "A subsumes A1"
    (is (= "subsumes" (:outcome (protos/cs-subsumes hier-fcs {:codeA "A" :codeB "A1"})))))

  (testing "A1 subsumed-by A"
    (is (= "subsumed-by" (:outcome (protos/cs-subsumes hier-fcs {:codeA "A1" :codeB "A"})))))

  (testing "transitive: A subsumes A2a"
    (is (= "subsumes" (:outcome (protos/cs-subsumes hier-fcs {:codeA "A" :codeB "A2a"})))))

  (testing "unrelated codes"
    (is (= "not-subsumed" (:outcome (protos/cs-subsumes hier-fcs {:codeA "A1" :codeB "B"})))))

  (testing "flat code system: same code"
    (is (= "equivalent" (:outcome (protos/cs-subsumes flat-fcs {:codeA "X" :codeB "X"})))))

  (testing "flat code system: different codes"
    (is (= "not-subsumed" (:outcome (protos/cs-subsumes flat-fcs {:codeA "X" :codeB "Y"}))))))

;; --- vs-expand ---

(deftest vs-expand-test
  (testing "expand all"
    (let [{:keys [concepts total used-codesystems]} (protos/vs-expand hier-fcs nil {:url "http://example.com/test-cs"})]
      (is (= 6 (count concepts)))
      (is (every? #(= "http://example.com/test-cs" (:system %)) concepts))
      (is (= 6 total))
      (is (seq used-codesystems))))

  (testing "abstract concept in expansion"
    (let [concepts (:concepts (protos/vs-expand hier-fcs nil {:url "http://example.com/test-cs"}))
          c-concept (first (filter #(= "C" (:code %)) concepts))]
      (is (true? (:abstract c-concept)))))

  (testing "expand with filter"
    (let [concepts (:concepts (protos/vs-expand hier-fcs nil {:url "http://example.com/test-cs" :filter "A2"}))]
      (is (= 2 (count concepts)))
      (is (every? #(str/includes? (:display %) "A2") concepts))))

  (testing "expand with filter matches designation"
    (let [concepts (:concepts (protos/vs-expand hier-fcs nil {:url "http://example.com/test-cs" :filter "Konzept"}))]
      (is (= 1 (count concepts)))
      (is (= "A" (:code (first concepts))))))

  (testing "expand with offset and count"
    (let [{all :concepts} (protos/vs-expand hier-fcs nil {:url "http://example.com/test-cs"})
          {:keys [concepts total]} (protos/vs-expand hier-fcs nil {:url "http://example.com/test-cs" :offset 1 :count 2})]
      (is (= 2 (count concepts)))
      (is (= (take 2 (drop 1 all)) concepts))
      (is (= 6 total))))

  (testing "flat expand"
    (let [concepts (:concepts (protos/vs-expand flat-fcs nil {:url "http://example.com/flat-cs"}))]
      (is (= 3 (count concepts))))))

;; --- vs-validate-code ---

(deftest vs-validate-code-test
  (testing "code in system"
    (let [result (protos/vs-validate-code hier-fcs nil {:code "A" :system "http://example.com/test-cs"})]
      (is (true? (:result result)))
      (is (= "Concept A" (:display result)))))

  (testing "code not in system"
    (let [result (protos/vs-validate-code hier-fcs nil {:code "NOPE" :system "http://example.com/test-cs"})]
      (is (false? (:result result)))))

  (testing "wrong system returns nil"
    (is (nil? (protos/vs-validate-code hier-fcs nil {:code "A" :system "http://other.com/cs"}))))

  (testing "nil system checks against this code system"
    (let [result (protos/vs-validate-code hier-fcs nil {:code "B"})]
      (is (true? (:result result))))))

;; --- cs-find-matches ---

(defn- find-matches
  "Test helper — unwrap the ::match-result's :concepts seq."
  [cs query]
  (:concepts (protos/cs-find-matches cs query)))

(deftest cs-find-matches-nil-filters-test
  (testing "nil filters returns all concepts"
    (let [result (find-matches hier-fcs {:system "http://example.com/test-cs"})]
      (is (= 6 (count result)))
      (is (every? #(= "http://example.com/test-cs" (:system %)) result)))))

(deftest cs-find-matches-empty-filters-test
  (testing "empty filters returns all concepts"
    (let [result (find-matches hier-fcs {:system "http://example.com/test-cs" :filters []})]
      (is (= 6 (count result))))))

(deftest cs-find-matches-is-a-test
  (testing "is-a filter"
    (let [result (find-matches hier-fcs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "is-a" :value "A"}]})
          codes (set (map :code result))]
      (is (contains? codes "A"))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (contains? codes "A2a"))
      (is (not (contains? codes "B"))))))

(deftest cs-find-matches-descendent-of-test
  (testing "descendent-of filter excludes the root"
    (let [result (find-matches hier-fcs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "descendent-of" :value "A"}]})
          codes (set (map :code result))]
      (is (not (contains? codes "A")))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (contains? codes "A2a")))))

(deftest cs-find-matches-is-not-a-test
  (testing "is-not-a filter"
    (let [result (find-matches hier-fcs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "is-not-a" :value "A"}]})
          codes (set (map :code result))]
      (is (contains? codes "B"))
      (is (not (contains? codes "A")))
      (is (not (contains? codes "A1"))))))

(deftest cs-find-matches-equals-test
  (testing "= filter on property"
    (let [result (find-matches hier-fcs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "status" :op "=" :value "active"}]})
          codes (set (map :code result))]
      (is (contains? codes "B")))))

(deftest cs-find-matches-equals-code-test
  (testing "= filter on code property"
    (let [result (find-matches hier-fcs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "code" :op "=" :value "B"}]})
          codes (set (map :code result))]
      (is (= #{"B"} codes)))))

(deftest cs-find-matches-multiple-filters-test
  (testing "multiple filters are ANDed"
    (let [result (find-matches hier-fcs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "is-a" :value "A"}
                              {:property "code" :op "=" :value "A1"}]})
          codes (set (map :code result))]
      (is (= #{"A1"} codes)))))

;; --- cs-resource ---

(deftest cs-resource-test
  (let [result (protos/cs-resource hier-fcs {})]
    (is (= "http://example.com/test-cs" (:url result)))
    (is (= "1.0" (:version result)))
    (is (= "TestCodeSystem" (:name result)))))

;; --- vs-resource ---

(deftest vs-resource-test
  (let [result (protos/vs-resource hier-fcs {})]
    (is (= "http://example.com/test-cs" (:url result)))))
