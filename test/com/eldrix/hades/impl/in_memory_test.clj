(ns com.eldrix.hades.impl.in-memory-test
  "Provider-level tests for in-memory CodeSystem, ValueSet, and ConceptMap
  built via `load-fhir/from-fhir`."
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.supplement :as supplement]))

(stest/instrument)
;; Hermes' specs reject malformed SCT ids (zero, negative, Verhoeff
;; failure) at the function boundary. In production those calls just
;; return nil/empty — the instrumented assertion is a dev-only concern
;; that turns "unknown code" handling into a 500. We unstrument the
;; specific fns that callers feed user-supplied ids into.
(stest/unstrument
  '[com.eldrix.hermes.core/concept
    com.eldrix.hermes.core/component-refset-items
    com.eldrix.hermes.core/historical-associations
    com.eldrix.hermes.core/subsumed-by?
    com.eldrix.hermes.core/with-historical])

;; ---------------------------------------------------------------------------
;; CodeSystem fixtures
;; ---------------------------------------------------------------------------

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

(def hier-cs (load-fhir/from-fhir hierarchical-cs))
(def flat-cs-impl (load-fhir/from-fhir flat-cs))

;; --- Construction ---

(deftest construction-test
  (testing "all codes indexed"
    (is (some? hier-cs))
    (is (some? (protos/cs-lookup hier-cs {:code "A"})))
    (is (some? (protos/cs-lookup hier-cs {:code "A1"})))
    (is (some? (protos/cs-lookup hier-cs {:code "A2"})))
    (is (some? (protos/cs-lookup hier-cs {:code "A2a"})))
    (is (some? (protos/cs-lookup hier-cs {:code "B"})))
    (is (= :unknown-code
           (:not-found-reason (protos/cs-lookup hier-cs {:code "NOPE"}))))))

;; --- cs-lookup ---

(deftest cs-lookup-test
  (testing "returns correct fields"
    (let [result (protos/cs-lookup hier-cs {:code "A"})]
      (is (= "Concept A" (:display result)))
      (is (= "http://example.com/test-cs" (:system result)))
      (is (= "1.0" (:version result)))
      (is (= "TestCodeSystem" (:name result)))
      (is (= :A (:code result)))
      (is (= "The first concept" (:definition result)))
      (is (false? (:abstract result)) "concept A has children but no notSelectable property")))

  (testing "notSelectable concept has abstract=true"
    (let [result (protos/cs-lookup hier-cs {:code "C"})]
      (is (true? (:abstract result)))))

  (testing "leaf node has abstract=false"
    (let [result (protos/cs-lookup hier-cs {:code "B"})]
      (is (false? (:abstract result)))))

  (testing "returns parent/child properties from hierarchy"
    (let [result (protos/cs-lookup hier-cs {:code "A1"})
          props (:properties result)
          parent-props (filter #(= :parent (:code %)) props)]
      (is (= 1 (count parent-props)))
      (is (= :A (:value (first parent-props))))
      (is (= "Concept A" (:description (first parent-props))))))

  (testing "A has children A1 and A2"
    (let [result (protos/cs-lookup hier-cs {:code "A"})
          props (:properties result)
          child-props (filter #(= :child (:code %)) props)
          child-codes (set (map :value child-props))]
      (is (= #{:A1 :A2} child-codes))))

  (testing "returns designations"
    (let [result (protos/cs-lookup hier-cs {:code "A"})
          desigs (:designations result)]
      (is (= 1 (count desigs)))
      (is (= "Konzept A" (:value (first desigs))))
      (is (= :de (:language (first desigs))))))

  (testing "returns concept properties"
    (let [result (protos/cs-lookup hier-cs {:code "B"})
          props (:properties result)
          status-props (filter #(= :status (:code %)) props)]
      (is (= 1 (count status-props)))
      (is (= :active (:value (first status-props))))))

  (testing "inactive property derived from status"
    (let [result (protos/cs-lookup hier-cs {:code "A"})
          props (:properties result)
          inactive-props (filter #(= :inactive (:code %)) props)]
      (is (= 1 (count inactive-props)))
      (is (false? (:value (first inactive-props))))))

  (testing "not-found map for unknown code"
    (let [r (protos/cs-lookup hier-cs {:code "MISSING"})]
      (is (true? (:not-found r)))
      (is (= :unknown-code (:not-found-reason r))))))

;; --- cs-validate-code ---

(deftest cs-validate-code-test
  (testing "valid code"
    (let [result (protos/cs-validate-code hier-cs {:code "A"})]
      (is (true? (:result result)))
      (is (= "Concept A" (:display result)))
      (is (nil? (:issues result)))))

  (testing "unknown code"
    (let [result (protos/cs-validate-code hier-cs {:code "NOPE"})]
      (is (false? (:result result)))
      (is (some? (:message result)))
      (is (= "error" (:severity (first (:issues result)))))
      (is (= "invalid-code" (:details-code (first (:issues result)))))))

  (testing "valid code with correct display"
    (let [result (protos/cs-validate-code hier-cs {:code "A" :display "Concept A"})]
      (is (true? (:result result)))
      (is (nil? (:issues result)))))

  (testing "valid code with wrong display"
    (let [result (protos/cs-validate-code hier-cs {:code "A" :display "Wrong"})]
      (is (false? (:result result)))
      (is (str/includes? (:message result) "Wrong"))
      (is (= "invalid-display" (:details-code (first (:issues result)))))))

  (testing "case-insensitive display matching"
    (let [result (protos/cs-validate-code hier-cs {:code "A" :display "concept a"})]
      (is (true? (:result result)))))

  (testing "display matches designation"
    (let [result (protos/cs-validate-code hier-cs {:code "A" :display "Konzept A"})]
      (is (true? (:result result))))))

;; --- cs-subsumes ---

(deftest cs-subsumes-test
  (testing "equivalent"
    (is (= "equivalent" (:outcome (protos/cs-subsumes hier-cs {:codeA "A" :codeB "A"})))))

  (testing "A subsumes A1"
    (is (= "subsumes" (:outcome (protos/cs-subsumes hier-cs {:codeA "A" :codeB "A1"})))))

  (testing "A1 subsumed-by A"
    (is (= "subsumed-by" (:outcome (protos/cs-subsumes hier-cs {:codeA "A1" :codeB "A"})))))

  (testing "transitive: A subsumes A2a"
    (is (= "subsumes" (:outcome (protos/cs-subsumes hier-cs {:codeA "A" :codeB "A2a"})))))

  (testing "unrelated codes"
    (is (= "not-subsumed" (:outcome (protos/cs-subsumes hier-cs {:codeA "A1" :codeB "B"})))))

  (testing "flat code system: same code"
    (is (= "equivalent" (:outcome (protos/cs-subsumes flat-cs-impl {:codeA "X" :codeB "X"})))))

  (testing "flat code system: different codes"
    (is (= "not-subsumed" (:outcome (protos/cs-subsumes flat-cs-impl {:codeA "X" :codeB "Y"}))))))

;; --- vs-expand (implicit ValueSet of a CodeSystem) ---

(deftest vs-expand-test
  (testing "expand all"
    (let [{:keys [concepts total used-codesystems]}
          (protos/vs-expand hier-cs nil {:url "http://example.com/test-cs"})]
      (is (= 6 (count concepts)))
      (is (every? #(= "http://example.com/test-cs" (:system %)) concepts))
      (is (= 6 total))
      (is (seq used-codesystems))))

  (testing "abstract concept in expansion"
    (let [concepts (:concepts (protos/vs-expand hier-cs nil {:url "http://example.com/test-cs"}))
          c-concept (first (filter #(= "C" (:code %)) concepts))]
      (is (true? (:abstract c-concept)))))

  (testing "expand with filter"
    (let [concepts (:concepts (protos/vs-expand hier-cs nil {:url "http://example.com/test-cs" :filter "A2"}))]
      (is (= 2 (count concepts)))
      (is (every? #(str/includes? (:display %) "A2") concepts))))

  (testing "expand with filter matches designation"
    (let [concepts (:concepts (protos/vs-expand hier-cs nil {:url "http://example.com/test-cs" :filter "Konzept"}))]
      (is (= 1 (count concepts)))
      (is (= "A" (:code (first concepts))))))

  (testing "expand with offset and count"
    (let [{all :concepts} (protos/vs-expand hier-cs nil {:url "http://example.com/test-cs"})
          {:keys [concepts total]} (protos/vs-expand hier-cs nil {:url "http://example.com/test-cs" :offset 1 :count 2})]
      (is (= 2 (count concepts)))
      (is (= (take 2 (drop 1 all)) concepts))
      (is (= 6 total))))

  (testing "flat expand"
    (let [concepts (:concepts (protos/vs-expand flat-cs-impl nil {:url "http://example.com/flat-cs"}))]
      (is (= 3 (count concepts))))))

;; --- vs-validate-code via implicit ValueSet ---

(deftest vs-validate-code-via-cs-test
  (testing "code in system"
    (let [result (protos/vs-validate-code hier-cs nil {:code "A" :system "http://example.com/test-cs"})]
      (is (true? (:result result)))
      (is (= "Concept A" (:display result)))))

  (testing "code not in system"
    (let [result (protos/vs-validate-code hier-cs nil {:code "NOPE" :system "http://example.com/test-cs"})]
      (is (false? (:result result)))))

  (testing "wrong system returns nil"
    (is (nil? (protos/vs-validate-code hier-cs nil {:code "A" :system "http://other.com/cs"}))))

  (testing "nil system checks against this code system"
    (let [result (protos/vs-validate-code hier-cs nil {:code "B"})]
      (is (true? (:result result))))))

;; --- cs-find-matches ---

(defn- find-matches [impl query]
  (:concepts (protos/cs-find-matches impl query)))

(deftest cs-find-matches-test
  (testing "nil filters returns all concepts"
    (let [result (find-matches hier-cs {:system "http://example.com/test-cs"})]
      (is (= 6 (count result)))
      (is (every? #(= "http://example.com/test-cs" (:system %)) result))))

  (testing "empty filters returns all concepts"
    (let [result (find-matches hier-cs {:system "http://example.com/test-cs" :filters []})]
      (is (= 6 (count result)))))

  (testing "is-a filter"
    (let [result (find-matches hier-cs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "is-a" :value "A"}]})
          codes (set (map :code result))]
      (is (contains? codes "A"))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (contains? codes "A2a"))
      (is (not (contains? codes "B")))))

  (testing "descendent-of filter excludes the root"
    (let [result (find-matches hier-cs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "descendent-of" :value "A"}]})
          codes (set (map :code result))]
      (is (not (contains? codes "A")))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (contains? codes "A2a"))))

  (testing "is-not-a filter"
    (let [result (find-matches hier-cs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "is-not-a" :value "A"}]})
          codes (set (map :code result))]
      (is (contains? codes "B"))
      (is (not (contains? codes "A")))
      (is (not (contains? codes "A1")))))

  (testing "= filter on property"
    (let [result (find-matches hier-cs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "status" :op "=" :value "active"}]})
          codes (set (map :code result))]
      (is (contains? codes "B"))))

  (testing "= filter on code property"
    (let [result (find-matches hier-cs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "code" :op "=" :value "B"}]})
          codes (set (map :code result))]
      (is (= #{"B"} codes))))

  (testing "multiple filters are ANDed"
    (let [result (find-matches hier-cs
                   {:system "http://example.com/test-cs"
                    :filters [{:property "concept" :op "is-a" :value "A"}
                              {:property "code" :op "=" :value "A1"}]})
          codes (set (map :code result))]
      (is (= #{"A1"} codes)))))

;; --- resource methods ---

(deftest cs-resource-test
  (let [result (protos/cs-resource hier-cs {})]
    (is (= "http://example.com/test-cs" (:url result)))
    (is (= "1.0" (:version result)))
    (is (= "TestCodeSystem" (:name result)))))

(deftest vs-resource-via-cs-test
  (let [result (protos/vs-resource hier-cs {})]
    (is (= "http://example.com/test-cs" (:url result)))))

;; ---------------------------------------------------------------------------
;; Compose-driven ValueSet
;; ---------------------------------------------------------------------------

(def vs-test-cs-map
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/cs"
   "version"      "1.0"
   "name"         "TestCS"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "X" "display" "X-ray"}
                   {"code" "Y" "display" "Yankee"}
                   {"code" "Z" "display" "Zulu"}]})

(def vs-test-cs (load-fhir/from-fhir vs-test-cs-map))

(def vs-test-vs-map
  {"resourceType" "ValueSet"
   "url"          "http://example.com/vs"
   "version"      "1.0"
   "name"         "TestVS"
   "status"       "active"
   "compose"      {"include" [{"system" "http://example.com/cs"
                                "concept" [{"code" "X"}
                                           {"code" "Y"}]}]}})

(def ^:dynamic *svc* nil)

(defn vs-svc-fixture [f]
  (binding [*svc* (composite/from-providers [vs-test-cs])]
    (f)))

(use-fixtures :each vs-svc-fixture)

(deftest compose-vs-construction-test
  (let [vs (load-fhir/from-fhir vs-test-vs-map)]
    (is (some? vs))
    (testing "vs-resource returns metadata"
      (let [res (protos/vs-resource vs {})]
        (is (= "http://example.com/vs" (:url res)))
        (is (= "1.0" (:version res)))))))

(deftest compose-vs-expand-test
  (let [vs (load-fhir/from-fhir vs-test-vs-map)]
    (testing "expand returns included concepts"
      (let [{:keys [concepts total used-codesystems]} (protos/vs-expand vs *svc* {})]
        (is (= 2 (count concepts)))
        (let [codes (set (map :code concepts))]
          (is (contains? codes "X"))
          (is (contains? codes "Y"))
          (is (not (contains? codes "Z"))))
        (is (= 2 total))
        (is (seq used-codesystems))))))

(deftest compose-vs-validate-code-test
  (let [vs (load-fhir/from-fhir vs-test-vs-map)]
    (testing "code in value set"
      (let [result (protos/vs-validate-code vs *svc* {:code "X" :system "http://example.com/cs"})]
        (is (true? (:result result)))
        (is (= "X-ray" (:display result)))))

    (testing "code not in value set"
      (let [result (protos/vs-validate-code vs *svc* {:code "Z" :system "http://example.com/cs"})]
        (is (false? (:result result)))))

    (testing "display mismatch"
      (let [result (protos/vs-validate-code vs *svc* {:code "X" :system "http://example.com/cs" :display "Wrong"})]
        (is (true? (:result result)))
        (is (some? (:message result)))))))

;; ---------------------------------------------------------------------------
;; Supplement wrapper — observable behaviour
;;
;; A `supplemented-codesystem` wrapper must extend cs-lookup output
;; (designation list + property list) with the supplement's contributions
;; without losing or reordering the base concept's data.
;; ---------------------------------------------------------------------------

(def supplement-base-cs
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/supp-base"
   "version"      "1.0"
   "status"       "active"
   "content"      "complete"
   "caseSensitive" true
   "concept"      [{"code" "A" "display" "Alpha"
                    "designation" [{"language" "en" "value" "Alpha (en)"}]
                    "property" [{"code" "kind" "valueCode" "primary"}]}
                   {"code" "B" "display" "Beta"}]})

(def supplement-cs
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/supp-extra"
   "version"      "1.0"
   "status"       "active"
   "content"      "supplement"
   "supplements"  "http://example.com/supp-base"
   "concept"      [{"code" "A"
                    "designation" [{"language" "fr" "value" "Alpha (fr)"}]
                    "property" [{"code" "extra" "valueString" "from-supplement"}]}]})

(deftest supplement-wrapper-equivalence-test
  (let [base (load-fhir/from-fhir supplement-base-cs)
        ;; Build supplement lookup directly, mirroring the indexer.
        supp-resources (load-fhir/from-fhir-resources [supplement-cs])
        supp-lookup (-> supp-resources :supplements first :lookup)
        wrapped (supplement/supplemented-codesystem base supp-lookup)]

    (testing "cs-lookup augments designations on covered concept"
      (let [r (protos/cs-lookup wrapped {:code "A"})
            desig-values (set (map :value (:designations r)))]
        (is (contains? desig-values "Alpha (en)"))
        (is (contains? desig-values "Alpha (fr)"))))

    (testing "cs-lookup augments properties on covered concept"
      (let [r (protos/cs-lookup wrapped {:code "A"})
            props (:properties r)]
        (is (some #(and (= :extra (:code %)) (= "from-supplement" (:value %))) props))))

    (testing "cs-lookup leaves uncovered concepts unchanged"
      (let [r-base (protos/cs-lookup base {:code "B"})
            r-wrapped (protos/cs-lookup wrapped {:code "B"})]
        (is (= (set (:designations r-base)) (set (:designations r-wrapped))))
        (is (= (set (map :code (:properties r-base)))
               (set (map :code (:properties r-wrapped)))))))

    (testing "cs-validate-code: display matching a supplement designation succeeds"
      (let [r (protos/cs-validate-code wrapped {:code "A" :display "Alpha (fr)"})]
        (is (true? (:result r)))
        (is (nil? (:issues r)))))

    (testing "cs-subsumes: pass-through to base"
      (is (= "equivalent" (:outcome (protos/cs-subsumes wrapped {:codeA "A" :codeB "A"})))))))

;; ---------------------------------------------------------------------------
;; Expansion-only ValueSet — `compose` absent, `expansion.contains` baked.
;;
;; Many published FHIR packages (IPS, US Core, IG-shipped terminology
;; bundles) ship ValueSets whose only definition is a pre-computed
;; expansion — typically because the source terminology (SNOMED, LOINC)
;; is too large or unstable to express via compose rules. The loader
;; preserves `:expansion` on the fhir-data entry; both in-memory and
;; SQLite providers must serve those baked entries from `vs-expand`,
;; otherwise such ValueSets are silently empty after import.
;;
;; The CodeSystem referenced by the baked entries is intentionally NOT
;; in the service — these tests assert that the expansion is served
;; *without* needing the upstream CodeSystem registered.
;; ---------------------------------------------------------------------------

(def baked-vs-map
  {"resourceType" "ValueSet"
   "url"          "http://example.com/baked-vs"
   "version"      "1.0"
   "name"         "BakedVS"
   "status"       "active"
   "expansion"    {"identifier" "urn:uuid:test-baked"
                   "timestamp"  "2026-01-01T00:00:00Z"
                   "total"      5
                   "contains"   [{"system"  "http://example.com/external-cs"
                                  "code"    "alpha"
                                  "display" "Alpha"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "bravo"
                                  "display" "Bravo"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "charlie"
                                  "display" "Charlie"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "delta"
                                  "display" "Delta"}
                                 {"system"  "http://example.com/external-cs"
                                  "code"    "echo"
                                  "display" "Echo"}]}})

(deftest expansion-only-vs-resource-test
  (testing "vs-resource exposes a synthesised compose for an expansion-only ValueSet"
    (let [vs (load-fhir/from-fhir baked-vs-map)
          r  (protos/vs-resource vs {})]
      (is (= "http://example.com/baked-vs" (:url r)))
      (is (= "1.0" (:version r)))
      (let [compose (:compose r)
            include (first (get compose "include"))]
        (is (some? compose)
            "loader synthesises compose so vs-resource has a definition to return")
        (is (= 1 (count (get compose "include")))
            "all baked entries share one (system, version) so one include suffices")
        (is (= "http://example.com/external-cs" (get include "system")))
        (is (= 5 (count (get include "concept"))))))))

(deftest expansion-only-vs-expand-test
  (testing "vs-expand serves baked entries from an expansion-only ValueSet, with no CodeSystem in the service"
    (let [vs  (load-fhir/from-fhir baked-vs-map)
          svc (composite/from-providers [vs])
          {:keys [concepts total]} (protos/vs-expand vs svc {:url "http://example.com/baked-vs"})]
      (is (= 5 (count concepts)))
      (is (= 5 total))
      (is (= #{"alpha" "bravo" "charlie" "delta" "echo"}
             (set (map :code concepts))))
      (is (every? #(= "http://example.com/external-cs" (:system %)) concepts))
      (testing "displays carried through"
        (let [by-code (into {} (map (juxt :code :display)) concepts)]
          (is (= "Alpha" (get by-code "alpha")))
          (is (= "Echo"  (get by-code "echo"))))))))

(deftest expansion-only-vs-offset-count-test
  (testing "offset/count slice baked expansion deterministically"
    (let [vs  (load-fhir/from-fhir baked-vs-map)
          svc (composite/from-providers [vs])
          {:keys [concepts total]}
          (protos/vs-expand vs svc {:url "http://example.com/baked-vs"
                                    :offset 1 :count 2})]
      (is (= 2 (count concepts)))
      (is (= 5 total) "total reflects pre-paging size of baked expansion")
      (is (= ["bravo" "charlie"] (mapv :code concepts))))))

(deftest expansion-only-vs-filter-test
  (testing "filter narrows baked expansion to displays matching the term"
    (let [vs  (load-fhir/from-fhir baked-vs-map)
          svc (composite/from-providers [vs])
          {:keys [concepts]}
          (protos/vs-expand vs svc {:url "http://example.com/baked-vs"
                                    :filter "alp"})]
      (is (= ["alpha"] (mapv :code concepts))
          "filter must restrict baked entries to those whose display contains the term"))))
