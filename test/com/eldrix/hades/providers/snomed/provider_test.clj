(ns com.eldrix.hades.providers.snomed.provider-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.providers.snomed.provider :as snomed]
            [com.eldrix.hades.providers.snomed.expansion :as expansion]
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
    {:query :isa :ecl "<<[sctid]"}]
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
                          [{:property "concept" :op "=" :value "73211009"}])))))
    (is (= (.toString (hermes.search/q-concept-id 73211009))
           (.toString (only-query
                        (snomed/compose-filters->query nil
                          [{:property "code" :op "=" :value "73211009"}])))))
    "code = SCTID is accepted for shared compose validation narrowing")

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

(def ^:dynamic *svc* nil)

(defn- live-fixture [f]
  (let [svc (hermes/open (:path (fixtures/fixtures-by-id :sct/conformance)))]
    (binding [*svc* svc]
      (try (f) (finally (hermes/close svc))))))

(use-fixtures :once live-fixture)

(deftest ^:live cs-expand*-supports-code-eq-filter
  (let [provider (snomed/->HermesService *svc*)
        r (protos/cs-expand* provider {:system "http://snomed.info/sct"
                                       :filters [{:property "code"
                                                  :op "="
                                                  :value "73211009"}]})]
    (is (= ["73211009"] (mapv :code (:concepts r))))))

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

(deftest ^:live ecl-filter-property-aliases
  (testing "constraint and expression both parse the same ECL"
    (doseq [property ["constraint" "expression"]]
      (let [{:keys [query issues]}
            (snomed/compose-filters->query *svc*
              [{:property property :op "=" :value "<< 73211009"}])]   ; diabetes mellitus
        (is (nil? issues) (str "property=" property " produced issues"))
        (is (instance? Query query) (str "property=" property " did not produce a query")))))
  (testing "unsupported op surfaces the property name in the issue"
    (let [{:keys [issues]}
          (snomed/compose-filters->query *svc*
            [{:property "constraint" :op "regex" :value ".*"}])]
      (is (= 1 (count issues)))
      (is (str/includes? (:text (first issues)) "property=constraint")))))

;; Post-coordinated expression validation. Both paths decide membership by
;; structural subsumption of the whole expression, not the focus concept:
;; the focus alone misses refinement-introduced supertypes and excludes a
;; refined expression from a strict `descendant-of` constraint.
(def ^:private repair-tendon-right
  "Repair of tendon of hand (367430006) refined with laterality = right."
  "367430006:{272741003=24028007}")

(deftest ^:live expression-membership-in-filter-include
  ;; Explicit ValueSet filter include: validation narrows it with a synthetic
  ;; `code=<expression>` filter, and `cs-expand*` resolves membership directly.
  (let [provider (snomed/->HermesService *svc*)
        codes (fn [filters]
                (mapv :code (:concepts (protos/cs-expand* provider
                                         {:system "http://snomed.info/sct"
                                          :filters filters}))))]
    (testing "expressions=true + is-a ancestor → member"
      (is (= [repair-tendon-right]
             (codes [{:property "concept" :op "is-a" :value "71388002"}   ; Procedure
                     {:property "expressions" :op "=" :value "true"}
                     {:property "code" :op "=" :value repair-tendon-right}]))))
    (testing "expressions=false → expressions are never members"
      (is (empty? (codes [{:property "concept" :op "is-a" :value "71388002"}
                          {:property "expressions" :op "=" :value "false"}
                          {:property "code" :op "=" :value repair-tendon-right}]))))
    (testing "is-a an unrelated concept → not a member"
      (is (empty? (codes [{:property "concept" :op "is-a" :value "73211009"} ; Diabetes
                          {:property "expressions" :op "=" :value "true"}
                          {:property "code" :op "=" :value repair-tendon-right}]))))))

(deftest ^:live implicit-isa-is-reflexive
  ;; FHIR `?fhir_vs=isa/X` is reflexive: includes X itself, not just strict
  ;; descendants. Affects both plain-concept validation and expansion.
  (let [provider (snomed/->HermesService *svc*)
        url      "http://snomed.info/sct?fhir_vs=isa/73211009"     ; Diabetes mellitus
        validate (fn [c] (:result (protos/vs-validate-code provider *svc*
                                    {:url url :system "http://snomed.info/sct" :code c})))]
    (testing "the root concept itself is a member of its own isa value set"
      (is (true? (validate "73211009"))))
    (testing "a descendant remains a member"
      (let [a-descendant (first (filter #(not= 73211009 %)
                                        (com.eldrix.hermes.core/intersect-ecl
                                          *svc*
                                          (mapv :conceptId
                                                (take 50 (com.eldrix.hermes.core/expand-ecl *svc* "<73211009")))
                                          "<73211009")))]
        (is (true? (validate (str a-descendant))))))))

(deftest ^:live implicit-valueset-expression-validation
  (let [provider (snomed/->HermesService *svc*)
        result   (fn [vs]
                   (protos/vs-validate-code provider *svc*
                     {:url (str "http://snomed.info/sct?fhir_vs" vs)
                      :system "http://snomed.info/sct"
                      :code repair-tendon-right}))]
    (testing "member of isa/<focus> — subsumption is reflexive"
      (is (true? (:result (result "=isa/367430006")))))
    (testing "member of isa/<ancestor>"
      (is (true? (:result (result "=isa/71388002")))))
    (testing "not a member of isa/<unrelated>"
      (is (false? (:result (result "=isa/73211009")))))
    (testing "member of ?fhir_vs (all)"
      (is (true? (:result (result "")))))
    (testing "arbitrary ECL value set is declined as not-supported"
      (let [r (result "=ecl/<<71388002")]
        (is (false? (:result r)))
        (is (= "not-supported" (:type (first (:issues r)))))))))

(deftest ^:live validate-code-display-validation-contract
  ;; Display validation must observe the same contract as every other provider
  ;; (cf. provider-parity-test, display-test): a wrong display is strict by
  ;; default (result=false, error) and lenient on request (result=true,
  ;; warning), with the issue reported on `Coding.display`. SNOMED matches
  ;; displays via Hermes language reference sets rather than FHIR designations,
  ;; but the observable result shape must not diverge.
  (let [provider (snomed/->HermesService *svc*)
        wrong    "definitely not the right display"
        cs (fn [extra] (protos/cs-validate-code provider
                         (merge {:system "http://snomed.info/sct" :code "73211009" :display wrong} extra)))
        vs (fn [extra] (protos/vs-validate-code provider *svc*
                         (merge {:url "http://snomed.info/sct?fhir_vs=isa/73211009"
                                 :system "http://snomed.info/sct" :code "73211009" :display wrong} extra)))]
    (doseq [[label validate] [["cs-validate-code" cs] ["vs-validate-code" vs]]]
      (testing (str label " — wrong display is strict by default")
        (let [r (validate nil) issue (first (:issues r))]
          (is (false? (:result r)) label)
          (is (= "error" (:severity issue)) label)
          (is (= "invalid-display" (:details-code issue)) label)
          (is (= ["Coding.display"] (:expression issue)) label)))
      (testing (str label " — wrong display is lenient when requested")
        (let [r (validate {:lenient-display-validation true}) issue (first (:issues r))]
          (is (true? (:result r)) label)
          (is (= "warning" (:severity issue)) label)
          (is (= "invalid-display" (:details-code issue)) label)
          (is (= ["Coding.display"] (:expression issue)) label))))))

(comment
  (#'snomed/parse-snomed-uri "http://snomed.info/sct"))
