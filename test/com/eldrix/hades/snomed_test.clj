(ns com.eldrix.hades.snomed-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [com.eldrix.hades.snomed :as snomed]))


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



(comment
  (#'snomed/parse-snomed-uri "http://snomed.info/sct"))
