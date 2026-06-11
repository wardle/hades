(ns com.eldrix.hades.providers.common.compose-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.providers.common.vs-validate :as vs-validate]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.providers.ftrm.provider :as ftrm-provider]
            [com.eldrix.hades.providers.ftrm.index :as ftrm-index]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.protocols :as protos])
  (:import (java.io File)))

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

(def test-cs (load-fhir/from-fhir test-cs-map))

(def test-cs-v2-map
  {"resourceType" "CodeSystem"
   "url"          "http://example.com/cs"
   "version"      "2.0"
   "name"         "TestCS-v2"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "A" "display" "Alpha v2"}
                    {"code" "D" "display" "Delta"}]})

(def test-cs-v2 (load-fhir/from-fhir test-cs-v2-map))

(def ^:dynamic *svc* nil)

(defn svc-fixture [f]
  ;; Default fixture: only v1. Tests that need v1+v2 build their own
  ;; service inline (multi-version bare URLs are intentionally unbound,
  ;; which would break the simple include cases).
  (binding [*svc* (composite/from-providers [test-cs])]
    (f)))

(defn- multi-version-svc []
  (composite/from-providers [test-cs test-cs-v2]))

(use-fixtures :each svc-fixture)

(deftest expand-compose-include-all-test
  (testing "include all concepts from a system"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          {:keys [concepts total used-codesystems]} (compose/expand-compose *svc* compose {})]
      (is (= 5 (count concepts)))
      (is (every? #(= "http://example.com/cs" (:system %)) concepts))
      (is (= 5 total))
      (is (seq used-codesystems)))))

(deftest expand-compose-include-concepts-test
  (testing "include explicit concept list"
    (let [compose {"include" [{"system" "http://example.com/cs"
                               "concept" [{"code" "A" "display" "My Alpha"}
                                          {"code" "B"}]}]}
          concepts (:concepts (compose/expand-compose *svc* compose {}))]
      (is (= 2 (count concepts)))
      (is (= "My Alpha" (:display (first (filter #(= "A" (:code %)) concepts)))))
      (is (= "Beta" (:display (first (filter #(= "B" (:code %)) concepts))))))))

(deftest expand-compose-include-filter-is-a-test
  (testing "include with is-a filter"
    (let [compose {"include" [{"system" "http://example.com/cs"
                               "filter" [{"property" "concept" "op" "is-a" "value" "A"}]}]}
          codes (set (map :code (:concepts (compose/expand-compose *svc* compose {}))))]
      (is (contains? codes "A"))
      (is (contains? codes "A1"))
      (is (contains? codes "A2"))
      (is (not (contains? codes "B"))))))

(deftest expand-compose-exclude-test
  (testing "include all, exclude specific concepts"
    (let [compose {"include" [{"system" "http://example.com/cs"}]
                   "exclude" [{"system" "http://example.com/cs"
                               "concept" [{"code" "B"}]}]}
          concepts (:concepts (compose/expand-compose *svc* compose {}))
          codes (set (map :code concepts))]
      (is (= 4 (count concepts)))
      (is (not (contains? codes "B"))))))

(deftest expand-compose-filter-text-test
  (testing "post-expansion text filter"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose *svc* compose {:filter "alpha"}))]
      (is (= 3 (count concepts)))
      (is (every? #(re-find #"(?i)alpha" (or (:display %) "")) concepts)))))

(deftest expand-compose-pagination-test
  (testing "offset and count"
    (let [compose {"include" [{"system" "http://example.com/cs"}]}
          {all :concepts} (compose/expand-compose *svc* compose {})
          {:keys [concepts]} (compose/expand-compose *svc* compose {:offset 1 :count 2})]
      (is (= 2 (count concepts)))
      (is (= (take 2 (drop 1 all)) concepts)))))

(deftest expand-compose-valueset-ref-test
  (testing "include via valueSet reference"
    (let [compose {"include" [{"valueSet" ["http://example.com/cs"]}]}
          concepts (:concepts (compose/expand-compose *svc* compose {}))]
      (is (= 5 (count concepts))))))

(deftest expand-compose-valueset-ref-preserves-filter-test
  (testing "filter is still passed to imported ValueSet providers as :filter"
    (let [svc (reify protos/ValueSet
                (vs-metadata [_ _] [{:url "http://example.com/vs/imported"}])
                (vs-resource [_ _] {:url "http://example.com/vs/imported"})
                (vs-expand [_ _ {:keys [filter]}]
                  {:url "http://example.com/vs/imported"
                   :concepts (if filter
                               [{:system "http://example.com/cs"
                                 :code "A"
                                 :display "Alpha"}]
                               [{:system "http://example.com/cs"
                                 :code "A"
                                 :display "Alpha"}
                                {:system "http://example.com/cs"
                                 :code "B"
                                 :display "Beta"}])})
                (vs-validate-code [_ _ _] nil))
          compose {"include" [{"valueSet" ["http://example.com/vs/imported"]}]}
          concepts (:concepts (compose/expand-compose svc compose {:filter "alpha"}))]
      (is (= ["A"] (mapv :code concepts))))))

(deftest expand-compose-circular-ref-test
  (testing "circular reference detection"
    (let [compose {"include" [{"valueSet" ["http://example.com/circular"]}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (compose/expand-compose *svc* compose {:expanding #{"http://example.com/circular"}}))))))

(deftest validate-code-pushes-exact-code-filter-to-match-driven-includes
  (testing "large provider-backed includes are validated by indexed code filter, not by scanning a truncated expansion"
    (let [seen-queries (atom [])
          system "http://example.com/truncated-cs"
          target "TARGET"
          cs (reify protos/CodeSystem
               (cs-metadata [_ _]
                 [{:url system :version "1.0" :content "complete" :case-sensitive true}])
               (cs-resource [_ _]
                 {:url system :version "1.0" :content "complete" :case-sensitive true})
               (cs-lookup [_ {:keys [code]}]
                 (when (= target code)
                   {:system system :code target :display "Target" :version "1.0"}))
               (cs-validate-code [_ {:keys [code]}]
                 (if (= target code)
                   {:result true :system system :code target :display "Target" :version "1.0"}
                   {:result false :system system :code code}))
               (cs-subsumes [_ _] nil)
               (cs-expand* [_ query]
                 (swap! seen-queries conj query)
                 (let [code-filter (some #(when (and (= "code" (:property %))
                                                     (= "=" (:op %)))
                                            (:value %))
                                         (:filters query))]
                   {:concepts (if (= target code-filter)
                                [{:system system :code target :display "Target" :version "1.0"}]
                                [{:system system :code "A" :display "Alpha" :version "1.0"}])})))
          svc (composite/from-providers [cs])
          result (vs-validate/validate-code
                  svc
                  {:url "http://example.com/vs"
                   :compose {"include" [{"system" system}]}}
                  {:system system :code target})]
      (is (true? (:result result)))
      (is (= "Target" (:display result)))
      (is (some (fn [query]
                  (some #(= {:property "code" :op "=" :value target} %)
                        (:filters query)))
                @seen-queries)))))

(deftest expand-compose-include-version-test
  (let [svc (multi-version-svc)]
    (testing "include with version selects versioned CodeSystem"
      (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"}]}
            {:keys [concepts compose-pins]} (compose/expand-compose svc compose {})]
        (is (= 2 (count concepts)))
        (is (some #(= "D" (:code %)) concepts))
        (is (not (some #(= "B" (:code %)) concepts)))
        (is (= [{:system "http://example.com/cs" :version "2.0"}] compose-pins))))
    (testing "include with version passes version to concept lookup"
      (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"
                                 "concept" [{"code" "A"}]}]}
            concepts (:concepts (compose/expand-compose svc compose {}))]
        (is (= 1 (count concepts)))
        (is (= "Alpha v2" (:display (first concepts))))))
    (testing "include with version on filters"
      (let [compose {"include" [{"system" "http://example.com/cs" "version" "2.0"
                                 "filter" [{"property" "code" "op" "=" "value" "D"}]}]}
            concepts (:concepts (compose/expand-compose svc compose {}))]
        (is (= 1 (count concepts)))
        (is (= "D" (:code (first concepts))))))))

(deftest expand-compose-force-system-version-test
  (let [svc (multi-version-svc)]
    (testing "force-system-version overrides include version"
      (let [params {:force-system-version {"http://example.com/cs" "2.0"}}
            compose {"include" [{"system" "http://example.com/cs" "version" "1.0"}]}
            concepts (:concepts (compose/expand-compose svc compose params))]
        (is (= 2 (count concepts)))
        (is (some #(= "D" (:code %)) concepts))
        (is (not (some #(= "B" (:code %)) concepts)))))
    (testing "force-system-version applies when no include version"
      (let [params {:force-system-version {"http://example.com/cs" "2.0"}}
            compose {"include" [{"system" "http://example.com/cs"}]}
            concepts (:concepts (compose/expand-compose svc compose params))]
        (is (= 2 (count concepts)))
        (is (some #(= "D" (:code %)) concepts))))))

(deftest expand-compose-system-version-default-test
  (let [svc (multi-version-svc)]
    (testing "system-version provides default when no include version"
      (let [params {:system-version {"http://example.com/cs" "2.0"}}
            compose {"include" [{"system" "http://example.com/cs"}]}
            concepts (:concepts (compose/expand-compose svc compose params))]
        (is (= 2 (count concepts)))
        (is (some #(= "D" (:code %)) concepts))))))

(deftest expand-compose-system-version-no-override-test
  (let [svc (multi-version-svc)]
    (testing "system-version does NOT override include version"
      (let [params {:system-version {"http://example.com/cs" "2.0"}}
            compose {"include" [{"system" "http://example.com/cs" "version" "1.0"}]}
            concepts (:concepts (compose/expand-compose svc compose params))]
        (is (= 5 (count concepts)))
        (is (some #(= "B" (:code %)) concepts))))))

(deftest expand-compose-unknown-system-warning-test
  (testing "multi-system include with unknown CS emits warning (expand purpose)"
    (let [compose {"include" [{"system" "http://example.com/cs"}
                              {"system" "http://unknown.example.com/loinc"}
                              {"system" "http://unknown.example.com/rxnorm"}]}
          {:keys [concepts issues]} (compose/expand-compose *svc* compose {:purpose :expand})]
      (is (= 5 (count concepts)))
      (is (= 2 (count issues)))
      (is (every? #(= "warning" (:severity %)) issues))
      (is (every? #(= "not-found" (:details-code %)) issues))
      (is (some #(re-find #"loinc" (:text %)) issues))
      (is (some #(re-find #"rxnorm" (:text %)) issues))))
  (testing "validate purpose does not emit unknown-system warnings"
    (let [compose {"include" [{"system" "http://unknown.example.com/loinc"}]}
          {:keys [issues]} (compose/expand-compose *svc* compose {})]
      (is (empty? (filter #(and (= "warning" (:severity %))
                                (re-find #"loinc" (:text %)))
                          issues))))))

(deftest expand-compose-check-system-version-test
  (testing "check-system-version passes when version matches"
    (let [params {:check-system-version {"http://example.com/cs" "1.0"}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose *svc* compose params))]
      (is (= 5 (count concepts)))))
  (testing "check-system-version passes with wildcard"
    (let [params {:check-system-version {"http://example.com/cs" "1.x"}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose *svc* compose params))]
      (is (= 5 (count concepts)))))
  (testing "check-system-version with non-existent version returns no results"
    (let [params {:check-system-version {"http://example.com/cs" "3.0"}}
          compose {"include" [{"system" "http://example.com/cs"}]}
          concepts (:concepts (compose/expand-compose *svc* compose params))]
      (is (zero? (count concepts))))))

(defn- temp-sqlite-path []
  (let [^File f (File/createTempFile "hades-compose-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))

(defn- build-draft-cs-db [path]
  (ftrm-index/build! path
    [{:type :codesystem-meta
      :url "http://example.org/cs/draft"
      :version "1.0"
      :status "draft"
      :experimental true
      :content "complete"}
     {:type :concept
      :system "http://example.org/cs/draft" :version "1.0"
      :code "x" :display "X"}]
    {:loader-type "synthetic-draft"})
  (ftrm-index/index! path))

(deftest expand-compose-used-codesystem-from-sqlite-test
  (testing "used-codesystem entry carries status/experimental for SQLite catalogues"
    (let [path (temp-sqlite-path)]
      (try
        (build-draft-cs-db path)
        (let [provider (ftrm-provider/open path)
              svc (composite/from-providers [provider])
              compose {"include" [{"system" "http://example.org/cs/draft"
                                   "version" "1.0"}]}
              {:keys [used-codesystems]} (compose/expand-compose svc compose {})
              u (first used-codesystems)]
          (is (= 1 (count used-codesystems)))
          (is (= "http://example.org/cs/draft|1.0" (:uri u)))
          (is (= "draft" (:status u)))
          (is (true? (:experimental u))))
        (finally (delete-quietly path))))))

(deftest extensional-members-compiler-contract-test
  (let [enumerated {"include" [{"system" "http://example.com/cs"
                                "concept" [{"code" "A" "display" "Alpha"}
                                           {"code" "B" "display" "Beta"}]}]}]
    (testing "purely enumerated compose with displays compiles to members"
      (is (= ["A" "B"] (mapv :code (compose/extensional-members enumerated)))))
    (testing "explicit inactive=true is the default — still compiles"
      (is (some? (compose/extensional-members (assoc enumerated "inactive" true)))))
    (testing "inactive=false needs CodeSystem activity status — not compilable"
      (is (nil? (compose/extensional-members (assoc enumerated "inactive" false)))))
    (testing "compose-level extension alters expansion output — not compilable"
      (is (nil? (compose/extensional-members
                 (assoc enumerated "extension"
                        [{"url" "http://hl7.org/fhir/StructureDefinition/valueset-expansion-parameter"
                          "extension" [{"url" "name" "valueCode" "displayLanguage"}
                                       {"url" "value" "valueCode" "fr"}]}])))))
    (testing "a concept without a display — not compilable"
      (is (nil? (compose/extensional-members
                 (update-in enumerated ["include" 0 "concept"] conj {"code" "C"})))))))
