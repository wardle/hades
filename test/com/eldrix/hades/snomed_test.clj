(ns com.eldrix.hades.snomed-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.snomed :as snomed]))


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



(deftest compose-filters->ecl
  (testing "no filters → wildcard"
    (is (= {:ecl "*"} (snomed/compose-filters->ecl nil)))
    (is (= {:ecl "*"} (snomed/compose-filters->ecl []))))
  (testing "concept hierarchy ops"
    (is (= {:ecl "<< 73211009"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "is-a" :value "73211009"}])))
    (is (= {:ecl "< 73211009"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "descendent-of" :value "73211009"}])))
    (is (= {:ecl "< 73211009"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "descendant-of" :value "73211009"}])))
    (is (= {:ecl ">> 73211009"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "generalizes" :value "73211009"}])))
    (is (= {:ecl "* MINUS << 73211009"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "is-not-a" :value "73211009"}])))
    (is (= {:ecl "^ 900000000000497000"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "in" :value "900000000000497000"}])))
    (is (= {:ecl "73211009"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "=" :value "73211009"}]))))
  (testing "parent/child"
    (is (= {:ecl ">! 73211009"}
           (snomed/compose-filters->ecl
             [{:property "parent" :op "=" :value "73211009"}])))
    (is (= {:ecl "<! 73211009"}
           (snomed/compose-filters->ecl
             [{:property "child" :op "=" :value "73211009"}]))))
  (testing "raw ECL via expression"
    (is (= {:ecl "<< 73211009 OR << 22298006"}
           (snomed/compose-filters->ecl
             [{:property "expression" :op "="
               :value "<< 73211009 OR << 22298006"}]))))
  (testing "attribute-id refinement (regression: EX05/EX08)"
    (is (= {:ecl "(<< 195967001) : 363698007 = 89187006"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "is-a" :value "195967001"}
              {:property "363698007" :op "=" :value "89187006"}])))
    (is (= {:ecl "(<< 404684003) : 116676008 = 72704001"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "is-a" :value "404684003"}
              {:property "116676008" :op "=" :value "72704001"}])))
    (is (= {:ecl "(*) : 116676008 = 72704001"}
           (snomed/compose-filters->ecl
             [{:property "116676008" :op "=" :value "72704001"}]))
        "refinement with no hierarchy gets a wildcard focus")
    (is (= {:ecl "(<< 404684003) : 116676008 = 72704001, 363698007 = 302509004"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "is-a" :value "404684003"}
              {:property "116676008" :op "=" :value "72704001"}
              {:property "363698007" :op "=" :value "302509004"}]))
        "multiple refinements comma-joined"))
  (testing "multiple hierarchies AND-combined"
    (is (= {:ecl "<< 64572001 AND << 404684003"}
           (snomed/compose-filters->ecl
             [{:property "concept" :op "is-a" :value "64572001"}
              {:property "concept" :op "is-a" :value "404684003"}]))))
  (testing "unsupported filters emit issues, not silently drop"
    (let [{:keys [ecl issues]}
          (snomed/compose-filters->ecl
            [{:property "concept" :op "is-a" :value "404684003"}
             {:property "display" :op "regex" :value "^fracture"}])]
      (is (nil? ecl))
      (is (= 1 (count issues)))
      (is (= "error" (:severity (first issues))))
      (is (= "not-supported" (:type (first issues))))
      (is (str/includes? (:text (first issues)) "display")))
    (let [{:keys [issues]}
          (snomed/compose-filters->ecl
            [{:property "concept" :op "is-a" :value nil}])]
      (is (= 1 (count issues)))
      (is (str/includes? (:text (first issues)) "missing value")))))

(comment
  (#'snomed/parse-snomed-uri "http://snomed.info/sct"))
