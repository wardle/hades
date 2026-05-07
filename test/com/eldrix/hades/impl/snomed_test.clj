(ns com.eldrix.hades.impl.snomed-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.snomed :as snomed]
            [com.eldrix.hades.impl.snomed.expansion :as expansion]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.search :as hermes.search])
  (:import (org.apache.lucene.search Query)))


(def implicit-value-sets
  "Each triple a URI, corresponding expected parse result and expected implicit valueset"
  [["http://snomed.info/sct"
    {:host "snomed.info", :edition nil, :version nil, :path "/sct" :query nil}]
   ["http://snomed.info/sct/900000000000207008"
    {:host "snomed.info", :edition "900000000000207008", :version nil, :path "/sct/900000000000207008", :query nil}]
   ["http://snomed.info/sct/900000000000207008/version/20130731"
    {:host "snomed.info", :edition "900000000000207008", :version "20130731", :path "/sct/900000000000207008/version/20130731" :query nil}]
   ["http://snomed.info/sct?fhir_vs"
    {:host "snomed.info", :edition nil, :version nil, :path "/sct" :query {:fhir_vs ""}}
    {:query :all, :ecl "*"}]
   ["http://snomed.info/sct?fhir_vs=isa/[sctid]"
    {:host "snomed.info", :edition nil, :version nil, :path "/sct" :query {:fhir_vs "isa/[sctid]"}}
    {:query :isa :ecl "<[sctid]"}]
   ["http://snomed.info/sct?fhir_vs=refset"
    {:host "snomed.info", :edition nil, :version nil, :path "/sct" :query {:fhir_vs "refset"}}
    {:query :refsets :ecl "<900000000000455006"}]
   ["http://snomed.info/sct?fhir_vs=refset/[sctid]"
    {:host "snomed.info", :edition nil, :version nil, :path "/sct" :query {:fhir_vs "refset/[sctid]"}}
    {:query :in-refset, :ecl "^[sctid]"}]
   ["http://snomed.info/sct?fhir_vs=ecl/[ecl]"
    {:host "snomed.info", :edition nil, :version nil, :path "/sct" :query {:fhir_vs "ecl/[ecl]"}}
    {:query :ecl, :ecl "[ecl]"}]
   ["http://snomed.info/sct?fhirvs=refset/123" ;; typo in URL
    {:host "snomed.info", :edition nil, :version nil, :path "/sct" :query {:fhirvs "refset/123"}}
    nil]])

(deftest parse-implicit-valueset
  (doseq [[uri expected-parsed expected-vs] implicit-value-sets]
    (is (set/subset? (set expected-parsed) (set (#'snomed/parse-snomed-uri uri))))
    (when (:query expected-parsed)
      (is (= expected-vs (snomed/parse-implicit-value-set uri))))))


;; -- compose-filters->query ---------------------------------------------------
;;
;; The unit tests below cover the filter shapes that build their Lucene
;; Query *without* consulting the SNOMED store — `concept`, `child`,
;; `<sctid>` attribute refinement, `expressions`, and the issue/unsupported
;; paths. `parent` and `generalizes` need a real Hermes store at query-
;; build time and are exercised by the live tests + the generative
;; expand-fuzz test against the pinned fixture.

(defn- only-query
  "Pull the :query value out of a successful compose-filters->query result.
   Asserts no :issues, returns the Lucene Query."
  [r]
  (is (nil? (:issues r)))
  (let [q (:query r)]
    (is (instance? Query q))
    q))

(deftest compose-filters->query
  (testing "no filters → match-any"
    (is (= (.toString (hermes.search/q-any))
           (.toString (only-query (snomed/compose-filters->query nil nil)))))
    (is (= (.toString (hermes.search/q-any))
           (.toString (only-query (snomed/compose-filters->query nil []))))))

  (testing "concept hierarchy ops produce the expected Lucene Query"
    (is (= (.toString (hermes.search/q-descendantOrSelfOf 73211009))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "is-a" :value "73211009"}])))))
    (is (= (.toString (hermes.search/q-descendantOf 73211009))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "descendent-of" :value "73211009"}])))))
    (is (= (.toString (hermes.search/q-descendantOf 73211009))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "descendant-of" :value "73211009"}]))))
        "British and US spellings both accepted")
    (is (= (.toString (hermes.search/q-not (hermes.search/q-any)
                                           (hermes.search/q-descendantOrSelfOf 73211009)))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "is-not-a" :value "73211009"}])))))
    (is (= (.toString (hermes.search/q-memberOf 900000000000497000))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "in" :value "900000000000497000"}])))))
    (is (= (.toString (hermes.search/q-concept-id 73211009))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "=" :value "73211009"}]))))))

  (testing "child = SCTID maps to q-childOf (no store needed)"
    (is (= (.toString (hermes.search/q-childOf 73211009))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "child" :op "=" :value "73211009"}]))))))

  (testing "expressions = true|false is a no-op (drops the filter, leaves match-any)"
    (is (= (.toString (hermes.search/q-any))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "expressions" :op "=" :value "true"}])))))
    (is (= (.toString (hermes.search/q-any))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "expressions" :op "=" :value "false"}]))))))

  (testing "attribute-id refinement (regression: EX05/EX08)"
    (is (= (.toString
             (hermes.search/q-and
               [(hermes.search/q-descendantOrSelfOf 195967001)
                (hermes.search/q-attribute-descendantOrSelfOf 363698007 89187006)]))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "is-a" :value "195967001"}
                           {:property "363698007" :op "=" :value "89187006"}])))))
    (is (= (.toString (hermes.search/q-attribute-descendantOrSelfOf 116676008 72704001))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "116676008" :op "=" :value "72704001"}]))))
        "single-clause output is the bare query — no redundant q-any wrapper"))

  (testing "multiple hierarchies AND-combined"
    (is (= (.toString
             (hermes.search/q-and
               [(hermes.search/q-descendantOrSelfOf 64572001)
                (hermes.search/q-descendantOrSelfOf 404684003)]))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "concept" :op "is-a" :value "64572001"}
                           {:property "concept" :op "is-a" :value "404684003"}]))))))

  (testing "missing value → invalid issue, not a poisoned query"
    (let [{:keys [query issues]}
          (snomed/compose-filters->query nil
            [{:property "concept" :op "is-a" :value nil}])]
      (is (nil? query))
      (is (= 1 (count issues)))
      (is (str/includes? (:text (first issues)) "missing value"))))

  (testing "non-numeric value where SCTID required → invalid issue (no parser crash)"
    (doseq [op ["is-a" "is-not-a" "descendent-of" "descendant-of"
                "generalizes" "in" "="]]
      (let [{:keys [query issues]}
            (snomed/compose-filters->query nil
              [{:property "concept" :op op :value "pain"}])]
        (is (nil? query) (str "op=" op " should not produce a query"))
        (is (= 1 (count issues)) (str "op=" op " should produce one issue"))
        (is (= "invalid" (:type (first issues))))
        (is (str/includes? (:text (first issues))
                           "is not a valid SNOMED concept id"))))
    (testing "child = non-numeric"
      (let [{:keys [query issues]}
            (snomed/compose-filters->query nil
              [{:property "child" :op "=" :value "pain"}])]
        (is (nil? query))
        (is (= "invalid" (:type (first issues))))))
    (testing "<sctid> = non-numeric"
      (let [{:keys [query issues]}
            (snomed/compose-filters->query nil
              [{:property "363698007" :op "=" :value "pain"}])]
        (is (nil? query))
        (is (= "invalid" (:type (first issues)))))))

  (testing "unsupported filters emit not-supported issues"
    (let [{:keys [query issues]}
          (snomed/compose-filters->query nil
            [{:property "concept" :op "is-a" :value "404684003"}
             {:property "display" :op "regex" :value "^fracture"}])]
      (is (nil? query))
      (is (= 1 (count issues)))
      (is (= "not-supported" (:type (first issues))))
      (is (str/includes? (:text (first issues)) "display")))))


;; -- Live tests ---------------------------------------------------------------
;;
;; Exercises filter shapes that depend on the Hermes store at query-build
;; time — `parent =` (proximal parents) and `concept generalizes`
;; (ancestor closure). End-to-end: build the Query via
;; `compose-filters->query`, expand it through `expansion/expand`, and
;; assert real concepts come back. Catches regressions where the store
;; field accessor breaks (e.g. nil-store ⇒ NPE only at query time).

(def ^:dynamic *svc* nil)

(defn- live-fixture [f]
  (let [svc (hermes/open (fixtures/assert-snomed-db!))]
    (binding [*svc* svc]
      (try (f) (finally (hermes/close svc))))))

(use-fixtures :once live-fixture)

(deftest ^:live parent-eq-resolves-against-store
  (let [{:keys [query issues]}
        (snomed/compose-filters->query *svc*
          [{:property "parent" :op "=" :value "73211009"}])]   ; diabetes mellitus
    (is (nil? issues))
    (is (instance? Query query))
    (let [{:keys [concepts]} (expansion/expand
                               {:svc *svc* :query query :limit 50})]
      (is (seq concepts) "parent= must return at least one proximal parent")
      (is (every? (fn [c] (pos? (:conceptId c))) concepts)))))

(deftest ^:live generalizes-resolves-against-store
  (let [{:keys [query issues]}
        (snomed/compose-filters->query *svc*
          [{:property "concept" :op "generalizes" :value "73211009"}])]
    (is (nil? issues))
    (is (instance? Query query))
    (let [{:keys [concepts]} (expansion/expand
                               {:svc *svc* :query query :limit 50})]
      (is (seq concepts)
          "generalizes must return at least one ancestor (including self)")
      ;; The diabetes mellitus concept itself must appear in the ancestor set
      (is (some #(= 73211009 (:conceptId %)) concepts)))))

(deftest ^:live attribute-refinement-resolves-end-to-end
  (let [{:keys [query issues]}
        (snomed/compose-filters->query *svc*
          [{:property "concept" :op "is-a" :value "195967001"}        ; asthma
           {:property "363698007" :op "=" :value "89187006"}])]       ; finding site = lung structure (or descendant)
    (is (nil? issues))
    (is (instance? Query query))
    (let [{:keys [concepts]} (expansion/expand
                               {:svc *svc* :query query :limit 50})]
      (is (seq concepts) "asthma + lung-finding-site refinement should return concepts"))))

(comment
  (#'snomed/parse-snomed-uri "http://snomed.info/sct"))
