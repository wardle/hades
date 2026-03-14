(ns com.eldrix.hades.fhir-codesystem-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.protocols :as protos]))

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
                        "property" [{"code" "status" "valueCode" "active"}]}]})

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
      (is (= "Concept A" (get result "display")))
      (is (= "http://example.com/test-cs" (get result "system")))
      (is (= "1.0" (get result "version")))
      (is (= "TestCodeSystem" (get result "name")))
      (is (= :A (get result "code")))
      (is (= "The first concept" (get result "definition")))
      (is (true? (get result "abstract")))))

  (testing "leaf node has abstract=false"
    (let [result (protos/cs-lookup hier-fcs {:code "B"})]
      (is (false? (get result "abstract")))))

  (testing "returns parent/child properties from hierarchy"
    (let [result (protos/cs-lookup hier-fcs {:code "A1"})
          props (get result "property")
          parent-props (filter #(= :parent (:code %)) props)]
      (is (= 1 (count parent-props)))
      (is (= :A (:value (first parent-props))))
      (is (= "Concept A" (get (first parent-props) "description")))))

  (testing "A has children A1 and A2"
    (let [result (protos/cs-lookup hier-fcs {:code "A"})
          props (get result "property")
          child-props (filter #(= :child (:code %)) props)
          child-codes (set (map :value child-props))]
      (is (= #{:A1 :A2} child-codes))))

  (testing "returns designations"
    (let [result (protos/cs-lookup hier-fcs {:code "A"})
          desigs (get result "designation")]
      (is (= 1 (count desigs)))
      (is (= "Konzept A" (get (first desigs) "value")))
      (is (= :de (get (first desigs) "language")))))

  (testing "returns concept properties"
    (let [result (protos/cs-lookup hier-fcs {:code "B"})
          props (get result "property")
          status-props (filter #(= :status (:code %)) props)]
      (is (= 1 (count status-props)))
      (is (= "active" (:value (first status-props))))))

  (testing "inactive property derived from status"
    (let [result (protos/cs-lookup hier-fcs {:code "A"})
          props (get result "property")
          inactive-props (filter #(= :inactive (:code %)) props)]
      (is (= 1 (count inactive-props)))
      (is (false? (:value (first inactive-props))))))

  (testing "nil for unknown code"
    (is (nil? (protos/cs-lookup hier-fcs {:code "MISSING"})))))

;; --- cs-validate-code ---

(deftest cs-validate-code-test
  (testing "valid code"
    (let [result (protos/cs-validate-code hier-fcs {:code "A"})]
      (is (true? (get result "result")))
      (is (= "Concept A" (get result "display")))
      (is (nil? (get result "issues")))))

  (testing "unknown code"
    (let [result (protos/cs-validate-code hier-fcs {:code "NOPE"})]
      (is (false? (get result "result")))
      (is (some? (get result "message")))
      (is (= "error" (:severity (first (get result "issues")))))
      (is (= "invalid-code" (:details-code (first (get result "issues")))))))

  (testing "valid code with correct display"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "Concept A"})]
      (is (true? (get result "result")))
      (is (nil? (get result "issues")))))

  (testing "valid code with wrong display"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "Wrong"})]
      (is (false? (get result "result")))
      (is (str/includes? (get result "message") "Wrong"))
      (is (= "invalid-display" (:details-code (first (get result "issues")))))))

  (testing "case-insensitive display matching"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "concept a"})]
      (is (true? (get result "result")))))

  (testing "display matches designation"
    (let [result (protos/cs-validate-code hier-fcs {:code "A" :display "Konzept A"})]
      (is (true? (get result "result"))))))

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
    (let [result (protos/vs-expand hier-fcs {:url "http://example.com/test-cs"})]
      (is (= 5 (count result)))
      (is (every? #(= "http://example.com/test-cs" (:system %)) result))))

  (testing "expand with filter"
    (let [result (protos/vs-expand hier-fcs {:url "http://example.com/test-cs" :filter "A2"})]
      (is (= 2 (count result)))
      (is (every? #(str/includes? (:display %) "A2") result))))

  (testing "expand with filter matches designation"
    (let [result (protos/vs-expand hier-fcs {:url "http://example.com/test-cs" :filter "Konzept"})]
      (is (= 1 (count result)))
      (is (= "A" (:code (first result))))))

  (testing "expand with offset and count"
    (let [all (protos/vs-expand hier-fcs {:url "http://example.com/test-cs"})
          paged (protos/vs-expand hier-fcs {:url "http://example.com/test-cs" :offset 1 :count 2})]
      (is (= 2 (count paged)))
      (is (= (take 2 (drop 1 all)) paged))))

  (testing "flat expand"
    (let [result (protos/vs-expand flat-fcs {:url "http://example.com/flat-cs"})]
      (is (= 3 (count result))))))

;; --- vs-validate-code ---

(deftest vs-validate-code-test
  (testing "code in system"
    (let [result (protos/vs-validate-code hier-fcs {:code "A" :system "http://example.com/test-cs"})]
      (is (true? (get result "result")))
      (is (= "Concept A" (get result "display")))))

  (testing "code not in system"
    (let [result (protos/vs-validate-code hier-fcs {:code "NOPE" :system "http://example.com/test-cs"})]
      (is (false? (get result "result")))))

  (testing "wrong system returns nil"
    (is (nil? (protos/vs-validate-code hier-fcs {:code "A" :system "http://other.com/cs"}))))

  (testing "nil system checks against this code system"
    (let [result (protos/vs-validate-code hier-fcs {:code "B"})]
      (is (true? (get result "result"))))))

;; --- cs-find-matches ---

(deftest cs-find-matches-nil-filters-test
  (testing "nil filters returns all concepts"
    (let [result (protos/cs-find-matches hier-fcs {:filters nil})]
      (is (= 5 (count result)))
      (is (every? #(= "http://example.com/test-cs" (:system %)) result)))))

(deftest cs-find-matches-empty-filters-test
  (testing "empty filters returns all concepts"
    (let [result (protos/cs-find-matches hier-fcs {:filters []})]
      (is (= 5 (count result))))))

(deftest cs-find-matches-is-a-test
  (testing "is-a filter"
    (let [result (protos/cs-find-matches hier-fcs
                   {:filters [{:property "concept" :op "is-a" :value "A"}]})
          codes (set (map :code result))]
      (is (contains? codes "A"))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (contains? codes "A2a"))
      (is (not (contains? codes "B"))))))

(deftest cs-find-matches-descendant-of-test
  (testing "descendant-of filter excludes the root"
    (let [result (protos/cs-find-matches hier-fcs
                   {:filters [{:property "concept" :op "descendant-of" :value "A"}]})
          codes (set (map :code result))]
      (is (not (contains? codes "A")))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (contains? codes "A2a")))))

(deftest cs-find-matches-is-not-a-test
  (testing "is-not-a filter"
    (let [result (protos/cs-find-matches hier-fcs
                   {:filters [{:property "concept" :op "is-not-a" :value "A"}]})
          codes (set (map :code result))]
      (is (contains? codes "B"))
      (is (not (contains? codes "A")))
      (is (not (contains? codes "A1"))))))

(deftest cs-find-matches-equals-test
  (testing "= filter on property"
    (let [result (protos/cs-find-matches hier-fcs
                   {:filters [{:property "status" :op "=" :value "active"}]})
          codes (set (map :code result))]
      (is (contains? codes "B")))))

(deftest cs-find-matches-equals-code-test
  (testing "= filter on code property"
    (let [result (protos/cs-find-matches hier-fcs
                   {:filters [{:property "code" :op "=" :value "B"}]})
          codes (set (map :code result))]
      (is (= #{"B"} codes)))))

(deftest cs-find-matches-multiple-filters-test
  (testing "multiple filters are ANDed"
    (let [result (protos/cs-find-matches hier-fcs
                   {:filters [{:property "concept" :op "is-a" :value "A"}
                              {:property "code" :op "=" :value "A1"}]})
          codes (set (map :code result))]
      (is (= #{"A1"} codes)))))

;; --- cs-resource ---

(deftest cs-resource-test
  (let [result (protos/cs-resource hier-fcs {})]
    (is (= "http://example.com/test-cs" (get result "url")))
    (is (= "1.0" (get result "version")))
    (is (= "TestCodeSystem" (get result "name")))))

;; --- vs-resource ---

(deftest vs-resource-test
  (let [result (protos/vs-resource hier-fcs {})]
    (is (= "ValueSet" (get result "resourceType")))
    (is (= "http://example.com/test-cs" (get result "url")))))
