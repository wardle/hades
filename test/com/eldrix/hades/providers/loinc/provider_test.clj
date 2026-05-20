(ns com.eldrix.hades.providers.loinc.provider-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.providers.loinc.model :as loinc-model]
            [com.eldrix.hades.providers.loinc.provider :as loinc-provider]
            [com.eldrix.hades.providers.loinc.store :as loinc-store]
            [com.eldrix.hades.protocols :as protos]))

(def ^:dynamic *cs* nil)
(def ^:dynamic *vs* nil)
(def ^:dynamic *cm* nil)
(def ^:dynamic *svc* nil)

(defn provider-fixture [f]
  (let [{:keys [datasource codesystem valueset conceptmap]} (loinc-provider/open-providers fixtures/loinc-db-path)]
    (try
      (binding [*cs* codesystem
                *vs* valueset
                *cm* conceptmap
                *svc* (composite/from-providers [codesystem valueset conceptmap])]
        (f))
      (finally
        (loinc-store/close! datasource)))))

(use-fixtures :once provider-fixture)

(defn- codes [{:keys [concepts]}]
  (mapv :code concepts))

(declare subvalue?)

(defn- submap?
  [expected actual]
  (and (map? actual)
       (every? (fn [[k v]]
                 (subvalue? v (get actual k)))
               expected)))

(defn- subseq?
  [expected actual]
  (and (sequential? actual)
       (every? (fn [expected-item]
                 (some #(subvalue? expected-item %) actual))
               expected)))

(defn- subvalue?
  [expected actual]
  (cond
    (map? expected) (submap? expected actual)
    (sequential? expected) (subseq? expected actual)
    :else (= expected actual)))

(defn- loinc-filter
  [property op value]
  {"property" property "op" op "value" value})

(defn- expand-compose
  [filters params]
  (compose/expand-compose
   *svc*
   {"include" [{"system" "http://loinc.org"
                "filter" filters}]}
   params))

(defn- has-code?
  [expected-code concepts]
  (some (fn [{:keys [code]}]
          (= expected-code code))
        concepts))

(def cs-lookup-cases
  [{:label "German lookup prefers LONG_COMMON_NAME, not CLASS/axis text"
    :input {:system "http://loinc.org"
            :code "718-7"
            :displayLanguage "de-DE"}
    :expected {:code "718-7"
               :display "Hämoglobin [Masse/Volumen] in Blut"}}
   {:label "French lookup prefers LONG_COMMON_NAME"
    :input {:system "http://loinc.org"
            :code "718-7"
            :displayLanguage "fr-FR"}
    :expected {:code "718-7"
               :display "Hémoglobine [Masse/Volume] Sang ; Numérique"}}
   ;; Calibrated against fhir.loinc.org and tx.fhir.org on 2026-05-17.
   ;; fhir.loinc.org keeps the English display but returns de-DE
   ;; LONG_COMMON_NAME as a designation; tx.fhir.org promotes that
   ;; designation to the top-level display. Hades chooses the latter for
   ;; rendering while preserving the designation.
   {:label "German lookup returns preferred designation text"
    :input {:system "http://loinc.org"
            :code "718-7"
            :displayLanguage "de-DE"
            :properties ["designation"]}
    :expected {:display "Hämoglobin [Masse/Volumen] in Blut"
               :designations [{:language "de-DE"
                               :use {:system "http://loinc.org" :code "LONG_COMMON_NAME"}
                               :value "Hämoglobin [Masse/Volumen] in Blut"}
                              {:language "de-AT"
                               :use {:system "http://loinc.org" :code "LinguisticVariantDisplayName"}
                               :value "Hämoglobin"}]}}
   {:label "answer list answer code lookup"
    :input {:system "http://loinc.org" :code "LA14674-8"}
    :expected {:code "LA14674-8"
               :display "6 or more times"
               :abstract false}}
   {:label "part code lookup"
    :input {:system "http://loinc.org" :code "LP14449-0"}
    :expected {:code "LP14449-0"
               :display "Hemoglobin"
               :abstract false}}
   {:label "group code lookup"
    :input {:system "http://loinc.org" :code "LG51973-2"}
    :expected {:code "LG51973-2"
               :display "Cytomegalovirus DNA|IU/mL|ANYBldSerPl"
               :abstract false}}
   {:label "property=designation returns designations without typed properties"
    :input {:system "http://loinc.org"
            :code "718-7"
            :displayLanguage "de-DE"
            :properties ["designation"]}
    :expected (fn [r]
                (is (seq (:designations r)))
                (is (empty? (:properties r))))}
   {:label "typed property filter excludes unrelated properties"
    :input {:system "http://loinc.org"
            :code "718-7"
            :properties ["COMPONENT"]}
    :expected {:properties [{:code :COMPONENT}]}}
   {:label "lookup emits LOINC axis properties plus hierarchy"
    :input {:system "http://loinc.org" :code "718-7"}
    :expected {:properties [{:code :COMPONENT
                             :value {:system "http://loinc.org"
                                     :code "LP14449-0"
                                     :display "Hemoglobin"}}
                            {:code :PROPERTY
                             :value {:system "http://loinc.org"
                                     :code "LP6827-2"
                                     :display "MCnc"}}
                            {:code :SYSTEM
                             :value {:system "http://loinc.org"
                                     :code "LP7057-5"
                                     :display "Bld"}}
                            {:code :CLASS
                             :value {:system "http://loinc.org"
                                     :code "LP7803-2"
                                     :display "HEM/BC"}}
                            {:code :STATUS
                             :value "ACTIVE"}
                            {:code :parent
                             :value {:system "http://loinc.org"
                                     :code "LP392452-1"
                                     :display "Hemoglobin | Blood | Hematology and Cell counts"}}]}}])

(deftest ^:live cs-lookup
  (doseq [{:keys [label input expected]} cs-lookup-cases]
    (testing label
      (let [result (protos/cs-lookup *cs* input)]
        (if (fn? expected)
          (expected result)
          (is (submap? expected result)))))))

(deftest ^:live cs-lookup-property-wildcard
  (testing "property=* behaves like no property filter"
    (let [unfiltered (protos/cs-lookup *cs* {:system "http://loinc.org" :code "718-7"})
          wildcard (protos/cs-lookup *cs* {:system "http://loinc.org"
                                           :code "718-7"
                                           :properties ["*"]})]
      (is (= (set (map :code (:properties unfiltered)))
             (set (map :code (:properties wildcard))))))))

(deftest ^:live loinc-codesystem-metadata-advertises-case-insensitive
  (let [meta (first (protos/cs-metadata *cs* {:url "http://loinc.org"}))]
    (is (= false (:case-sensitive meta)))))

(def validate-code-cases
  [{:label "displayLanguage accepts matching localized display"
    :input {:system "http://loinc.org"
            :code "718-7"
            :display "Hämoglobin [Masse/Volumen] in Blut"
            :displayLanguage "de-DE"}
    :expected {:result true
               :code "718-7"
               :display "Hämoglobin [Masse/Volumen] in Blut"}}
   {:label "displayLanguage rejects unrelated display with invalid-display issue"
    :input {:system "http://loinc.org"
            :code "718-7"
            :display "not the right display"
            :displayLanguage "de-DE"}
    :expected {:result false
               :issues [{:details-code "invalid-display"
                         :expression ["Coding.display"]}]}}])

(deftest ^:live validate-code-display-language-checks-designations
  (doseq [{:keys [label input expected]} validate-code-cases]
    (testing label
      (is (submap? expected (protos/cs-validate-code *cs* input))))))

(deftest ^:live validate-code-surfaces-inactive-and-unknown-system
  (testing "deprecated LOINC codes carry inactive metadata through composite warnings"
    (let [r (protos/cs-validate-code *svc* {:system "http://loinc.org"
                                            :code "100653-5"})]
      (is (true? (:result r)))
      (is (true? (:inactive r)))
      (is (= "inactive" (:inactive-status r)))
      (is (some #(and (= "warning" (:severity %))
                      (= "code-comment" (:details-code %)))
                (:issues r)))))
  (testing "unknown systems use the shared validate-code shape"
    (let [r (protos/cs-validate-code *cs* {:system "http://example.org"
                                           :code "x"})]
      (is (false? (:result r)))
      (is (= "http://example.org" (:x-unknown-system r)))
      (is (= "not-found" (-> r :issues first :details-code))))))

(def implicit-valueset-validate-code-cases
  [{:label "implicit ValueSet validate-code accepts matching localized display"
    :input {:url "http://loinc.org/vs"
            :system "http://loinc.org"
            :code "718-7"
            :display "Hämoglobin [Masse/Volumen] in Blut"
            :displayLanguage "de-DE"}
    :expected {:result true
               :code "718-7"
               :display "Hämoglobin [Masse/Volumen] in Blut"}}
   {:label "implicit ValueSet validate-code rejects unrelated localized display"
    :input {:url "http://loinc.org/vs"
            :system "http://loinc.org"
            :code "718-7"
            :display "not the right display"
            :displayLanguage "de-DE"}
    :expected {:result false
               :issues [{:details-code "invalid-display"
                         :expression ["Coding.display"]}]}}])

(deftest ^:live implicit-valueset-validate-code-checks-localized-display
  (doseq [{:keys [label input expected]} implicit-valueset-validate-code-cases]
    (testing label
      (is (submap? expected (protos/vs-validate-code *vs* nil input))))))

(def cs-expand-cases
  [{:label "canonical English multi-token search is AND-of-tokens"
    :input {:system "http://loinc.org"
            :text "hemoglobin blood"
            :max-hits 10}
    :expect-concepts? true}
   {:label "canonical English multi-token search requires every token"
    :input {:system "http://loinc.org"
            :text "hemoglobin unlikelytoken"
            :max-hits 10}
    :expected-codes []}
   {:label "German searches hit linguistic variant FTS"
    :input {:system "http://loinc.org"
            :text "Hämoglobin"
            :displayLanguage "de-DE"
            :max-hits 10}
    :expect-concepts? true
    :display-pattern #"Hämoglobin"}
   {:label "French searches hit linguistic variant FTS"
    :input {:system "http://loinc.org"
            :text "hémoglobine"
            :displayLanguage "fr-FR"
            :max-hits 10}
    :expect-concepts? true
    :display-pattern #"(?i)hémoglobine"}
   {:label "Chinese searches hit linguistic variant FTS"
    :input {:system "http://loinc.org"
            :text "血红蛋白"
            :displayLanguage "zh-CN"
            :max-hits 10}
    :expect-concepts? true}])

(deftest ^:live expand-searches-canonical-and-linguistic-text
  (doseq [{:keys [label input expected-codes expect-concepts? display-pattern]} cs-expand-cases]
    (testing label
      (let [concepts (:concepts (protos/cs-expand* *cs* input))]
        (when (some? expected-codes)
          (is (= expected-codes (mapv :code concepts))))
        (when expect-concepts?
          (is (seq concepts)))
        (when display-pattern
          (is (every? #(re-find display-pattern (:display %)) concepts)))))))

(deftest ^:live cs-expand*-supports-code-eq-filter
  (let [r (protos/cs-expand* *cs* {:system "http://loinc.org"
                                   :filters [{:property "code"
                                              :op "="
                                              :value "718-7"}]})]
    (is (= ["718-7"] (codes r)))))

(deftest ^:live codesystem-expand-respects-requested-version
  (let [r (protos/cs-expand* *cs* {:system "http://loinc.org"
                                   :version "not-the-loaded-version"
                                   :text "hemoglobin"
                                   :max-hits 10})]
    (is (empty? (:concepts r)))))

(deftest ^:live expand-empty-token-filter-does-not-broaden-results
  (testing "CodeSystem search"
    (let [r (protos/cs-expand* *cs* {:system "http://loinc.org"
                                     :text "&&&"
                                     :max-hits 10})]
      (is (empty? (:concepts r)))))
  (testing "implicit ValueSet search"
    (let [r (protos/vs-expand *vs* nil {:url "http://loinc.org/vs"
                                        :filter "&&&"
                                        :count 10})]
      (is (empty? (:concepts r)))
      (is (= 0 (:total r))))))

(def answer-list-expand-cases
  [{:label "answer list ValueSets are expandable"
    :input {:url "http://loinc.org/vs/LL1234-5"}
    :expected {:total 5
               :concepts [{:system "http://loinc.org"
                           :code "LA14674-8"
                           :display "6 or more times"}
                          {:code "LA14675-5"}
                          {:code "LA14676-3"}
                          {:code "LA14677-1"}
                          {:code "LA14678-9"}]}}
   {:label "answer list expansion supports filter"
    :input {:url "http://loinc.org/vs/LL1234-5"
            :filter "4 drinks"}
    :expected {:total 1
               :concepts [{:code "LA14678-9"}]}}
   {:label "answer list expansion supports paging"
    :input {:url "http://loinc.org/vs/LL1234-5"
            :offset 1
            :count 2}
    :expected {:total 5
               :concepts [{:code "LA14675-5"}
                          {:code "LA14676-3"}]}}])

(def answer-list-validate-code-cases
  [{:label "answer list validate-code accepts member code"
    :input {:url "http://loinc.org/vs/LL1234-5"
            :system "http://loinc.org"
            :code "LA14674-8"}
    :expected {:result true
               :display "6 or more times"}}
   {:label "answer list validate-code rejects non-member code"
    :input {:url "http://loinc.org/vs/LL1234-5"
            :system "http://loinc.org"
            :code "LA2-8"}
    :expected {:result false}}
   {:label "answer list validate-code rejects wrong display with shared display issue"
    :input {:url "http://loinc.org/vs/LL1234-5"
            :system "http://loinc.org"
            :code "LA14674-8"
            :display "not the right display"}
    :expected {:result false
               :message "Wrong Display Name 'not the right display' for http://loinc.org#LA14674-8. Valid display is '6 or more times' (for the language(s) '--')"
               :issues [{:details-code "invalid-display"
                         :expression ["Coding.display"]}]}}])

(def specific-valueset-validate-code-cases
  [{:label "part hierarchy validate-code rejects non-LOINC system"
    :input {:url "http://loinc.org/vs/LP248770-2"
            :system "http://example.org"
            :code "1009-0"}
    :expected {:result false
               :system "http://example.org"
               :code "1009-0"}}
   {:label "group validate-code rejects non-LOINC system"
    :input {:url "http://loinc.org/vs/LG51017-8"
            :system "http://example.org"
            :code "101721-9"}
    :expected {:result false
               :system "http://example.org"
               :code "101721-9"}}
   {:label "group validate-code returns canonical code casing"
    :input {:url "http://loinc.org/vs/LG51017-8"
            :system "http://loinc.org"
            :code "101721-9"}
    :expected {:result true
               :system "http://loinc.org"
               :code "101721-9"}}
   {:label "group validate-code rejects wrong display with shared display issue"
    :input {:url "http://loinc.org/vs/LG51017-8"
            :system "http://loinc.org"
            :code "101721-9"
            :display "not the right display"}
    :expected {:result false
               :message "Wrong Display Name 'not the right display' for http://loinc.org#101721-9. Valid display is 'Deprecated SARS-CoV-2 (COVID-19) in Respiratory specimen by NAA with probe detection' (for the language(s) '--')"
               :issues [{:details-code "invalid-display"
                         :expression ["Coding.display"]}]}}])

(deftest ^:live answer-list-valuesets-expand-and-validate
  (testing "answer list ValueSets are advertised"
    (let [url "http://loinc.org/vs/LL1234-5"
          meta (first (filter #(= url (:url %)) (protos/vs-metadata *vs* {})))]
      (is (= url (:url meta)))))
  (testing "part hierarchy ValueSets are advertised"
    (let [url "http://loinc.org/vs/LP248770-2"
          meta (first (filter #(= url (:url %)) (protos/vs-metadata *vs* {})))]
      (is (= url (:url meta)))))
  (doseq [{:keys [label input expected]} answer-list-expand-cases]
    (testing label
      (is (submap? expected (protos/vs-expand *vs* nil input)))))
  (doseq [{:keys [label input expected]} answer-list-validate-code-cases]
    (testing label
      (is (submap? expected (protos/vs-validate-code *vs* nil input)))))
  (doseq [{:keys [label input expected]} specific-valueset-validate-code-cases]
    (testing label
      (is (submap? expected (protos/vs-validate-code *vs* nil input)))))
  (testing "non-answer-list LOINC ValueSet URLs are not claimed as answer lists"
    (is (nil? (protos/vs-expand *vs* nil {:url "http://loinc.org/vs/LP12345-6"})))
    (is (nil? (protos/vs-expand *vs* nil {:url "http://loinc.org/vs/LG123-4"})))))

(deftest ^:live group-valuesets-expand-like-loinc
  ;; fhir.loinc.org expands LG51973-2 to these five LOINC terms for the
  ;; pinned 2.82 release. Exact membership is more stable than display
  ;; text and catches broken group-file import or ordering.
  (let [url "http://loinc.org/vs/LG51973-2"
        r (protos/vs-expand *vs* nil {:url url :count 10})]
    (is (= 5 (:total r)))
    (is (= ["100675-8" "105880-9" "29604-6" "30247-1" "72493-0"]
           (codes r)))))

(deftest ^:live expansion-pagination-is-deterministic
  (testing "answer-list paging is stable"
    (let [url "http://loinc.org/vs/LL1234-5"
          page #(codes (protos/vs-expand *vs* nil {:url url :offset 1 :count 2}))]
      (is (= ["LA14675-5" "LA14676-3"] (page)))
      (is (= (page) (page)))))
  (testing "implicit all-LOINC paging is stable"
    (let [page #(codes (protos/vs-expand *vs* nil {:url "http://loinc.org/vs"
                                                   :filter "hemoglobin"
                                                   :offset 1
                                                   :count 5}))]
      (is (= (page) (page)))
      (is (= 5 (count (page)))))))

(deftest ^:live filtered-implicit-valueset-reports-total
  (let [r (protos/vs-expand *vs* nil {:url "http://loinc.org/vs"
                                      :filter "hemoglobin"
                                      :count 5})]
    (is (= 5 (count (:concepts r))))
    (is (integer? (:total r)))
    (is (< 5 (:total r)))))

(def expansion-filter-cases
  [{:label "column filters narrow expansion"
    :input {:system "http://loinc.org"
            :filters [{:property "PROPERTY" :op "=" :value "MCnc"}
                      {:property "SYSTEM" :op "=" :value "Bld"}]
            :max-hits 1000}
    :expected-codes ["718-7"]}
   {:label "hierarchy filters narrow expansion"
    :input {:system "http://loinc.org"
            :filters [{:property "parent" :op "=" :value "LP392452-1"}]
            :max-hits 100}
    :expected-codes ["718-7"]}
   {:label "active-only narrows code-filtered expansion"
    :input {:system "http://loinc.org"
            :filters [{:property "code" :op "=" :value "718-7"}]
            :active-only true}
    :expected-codes ["718-7"]
    :exact-codes? true}
   {:label "in filters are case-insensitive for LOINC property values"
    :input {:system "http://loinc.org"
            :filters [{:property "PROPERTY" :op "in" :value "mcnc"}
                      {:property "SYSTEM" :op "in" :value "bld"}]
            :max-hits 1000}
    :expected-codes ["718-7"]}
   {:label "not-in filters are case-insensitive for LOINC property values"
    :input {:system "http://loinc.org"
            :filters [{:property "code" :op "=" :value "718-7"}
                      {:property "PROPERTY" :op "not-in" :value "mcnc"}]
            :max-hits 1000}
    :expected-codes []
    :exact-codes? true}])

(def subsumes-cases
  [{:label "part parent subsumes term"
    :input {:codeA "LP392452-1"
            :codeB "718-7"}
    :expected {:outcome "subsumes"}}
   {:label "term is subsumed by part parent"
    :input {:codeA "718-7"
            :codeB "LP392452-1"}
    :expected {:outcome "subsumed-by"}}])

(deftest ^:live expansion-filters-and-hierarchy
  (doseq [{:keys [label input expected-codes exact-codes?]} expansion-filter-cases]
    (testing label
      (let [actual-codes (codes (protos/cs-expand* *cs* input))]
        (if exact-codes?
          (is (= expected-codes actual-codes))
          (doseq [code expected-codes]
            (is (some #{code} actual-codes)))))))
  (doseq [{:keys [label input expected]} subsumes-cases]
    (testing label
      (is (submap? expected (protos/cs-subsumes *cs* input))))))

(def expand-compose-cases
  [{:label "STATUS and CLASS filters narrow the composed expansion"
    :filters [(loinc-filter "STATUS" "=" "ACTIVE")
              (loinc-filter "CLASS" "=" "HEM/BC")
              (loinc-filter "code" "=" "718-7")]
    :params {:count 10}
    :codes ["718-7"]}
   {:label "CLASS filter excludes a code from another class"
    :filters [(loinc-filter "CLASS" "=" "HEM/BC")
              (loinc-filter "code" "=" "8480-6")]
    :params {:count 10}
    :codes []}
   {:label "activeOnly is pushed through compose filters"
    :filters [(loinc-filter "code" "=" "1009-0")]
    :params {:activeOnly true :count 10}
    :codes []}])

(deftest ^:live compose-expansion-applies-loinc-property-filters
  (doseq [{:keys [label filters params codes]} expand-compose-cases]
    (testing label
      (let [r (expand-compose filters params)]
        (is (= codes (mapv :code (:concepts r))))
        (is (empty? (:issues r))))))
  (testing "unsupported LOINC filters surface as errors and do not broaden"
    (let [r (expand-compose [(loinc-filter "not-a-loinc-filter" "=" "x")] {:count 10})]
      (is (empty? (:concepts r)))
      (is (= [{:severity "error"
               :type "invalid"
               :details-code "vs-invalid"
               :text "Unsupported LOINC filter property=\"not-a-loinc-filter\", op=\"=\", value=\"x\""}]
             (:issues r))))))

(deftest ^:live unsupported-filters-do-not-broaden-loinc-expansion
  (let [r (protos/cs-expand* *cs* {:system "http://loinc.org"
                                   :filters [{:property "not-a-loinc-filter"
                                              :op "="
                                              :value "x"}]
                                   :max-hits 5})]
    (is (empty? (:concepts r))
        "unsupported LOINC filters must not be silently ignored")
    (is (= [{:severity "error"
             :type "invalid"
             :details-code "vs-invalid"
             :text "Unsupported LOINC filter property=\"not-a-loinc-filter\", op=\"=\", value=\"x\""}]
           (:issues r)))))

(deftest ^:live active-only-applies-to-unfiltered-implicit-loinc-expansion
  ;; 100653-5 is the first deprecated code in the pinned 2.82 DB when
  ;; the LNC slice is ordered by code. Expanding the implicit all-LOINC
  ;; ValueSet at that page with activeOnly=true must skip it rather than
  ;; returning an inactive concept.
  (let [r (protos/vs-expand *vs* nil {:url "http://loinc.org/vs"
                                      :activeOnly true
                                      :offset 729
                                      :count 1})
        c (first (:concepts r))]
    (is (not= "100653-5" (:code c)))
    (is (not (:inactive c)))))

(def filtered-active-only-cases
  [{:label "deprecated parts are excluded"
    :base {:url "http://loinc.org/vs"
           :filter "Levomepromazine"
           :count 20}
    :inactive-code "LP101194-1"}
   {:label "deprecated groups are excluded"
    :base {:url "http://loinc.org/vs"
           :filter "Views Broden"
           :count 20}
    :inactive-code "LG38463-2"}])

(deftest ^:live active-only-applies-to-filtered-implicit-loinc-expansion
  (doseq [{:keys [label base inactive-code]} filtered-active-only-cases]
    (testing label
      (let [inactive (protos/vs-expand *vs* nil base)
            active (protos/vs-expand *vs* nil (assoc base :activeOnly true))]
        (is (has-code? inactive-code (:concepts inactive)))
        (is (not (has-code? inactive-code (:concepts active))))))))

(def specific-active-only-cases
  [{:label "part hierarchy ValueSets exclude deprecated member concepts"
    :base {:url "http://loinc.org/vs/LP248770-2"
           :offset 219
           :count 1}
    :inactive-concept {:system "http://loinc.org"
                       :code "1009-0"
                       :display "Deprecated Direct antiglobulin test.poly specific reagent [Presence] on Red Blood Cells"
                       :inactive true}}
   {:label "group ValueSets exclude deprecated member concepts"
    :base {:url "http://loinc.org/vs/LG51017-8"
           :count 200}
    :inactive-concept {:code "101721-9"
                       :inactive true}}])

(deftest ^:live active-only-applies-to-specific-loinc-valuesets
  (doseq [{:keys [label base inactive-concept]} specific-active-only-cases]
    (testing label
      (let [inactive (protos/vs-expand *vs* nil base)
            active (protos/vs-expand *vs* nil (assoc base :activeOnly true))
            inactive-code (:code inactive-concept)]
        (is (some #(submap? inactive-concept %) (:concepts inactive)))
        (is (not (has-code? inactive-code (:concepts active))))
        (is (< (:total active) (:total inactive)))))))

(def map-to-translate-cases
  [{:label "MapTo uses imported release data"
    :input {:url (:url (loinc-model/conceptmap :map-to))
            :system "http://loinc.org"
            :code "1009-0"
            :target "http://loinc.org"}
    :expected {:result true
               :matches [{:equivalence "relatedto"
                          :system "http://loinc.org"
                          :code "1007-4"
                          :display "Direct antiglobulin test.polyspecific reagent [Presence] on Red Blood Cells"}]}}
   {:label "MapTo is directional for public FHIR translate"
    :input {:url (:url (loinc-model/conceptmap :map-to))
            :system "http://loinc.org"
            :code "1007-4"
            :target "http://loinc.org"}
    :expected {:result false
               :message "No matches found"}}])

(deftest ^:live map-to-conceptmap-uses-imported-release-data
  ;; fhir.loinc.org currently returns no match for this informal URL and
  ;; tx.fhir.org does not publish it as a ConceptMap. The native provider
  ;; deliberately exposes the imported MapTo.csv rows under this URL.
  (doseq [{:keys [label input expected]} map-to-translate-cases]
    (testing label
      (is (submap? expected (protos/cm-translate *cm* input))))))

(deftest ^:live imported-conceptmaps-are-advertised
  (let [metas (protos/cm-metadata *cm* {})
        urls (set (map :url metas))
        part-url (loinc-model/part-related-conceptmap-url "http://snomed.info/sct")]
    (is (contains? urls (:url (loinc-model/conceptmap :map-to))))
    (is (contains? urls part-url))
    (is (contains? urls (:url (loinc-model/conceptmap :ieee-medical-device))))
    (is (contains? urls (:url (loinc-model/conceptmap :rsna-rid))))
    (is (contains? urls (:url (loinc-model/conceptmap :rsna-rpid))))
    (is (= {:url part-url
            :system "http://loinc.org"
            :target "http://snomed.info/sct"
            :title "LOINC part related code mappings to http://snomed.info/sct"
            :version fixtures/loinc-version}
           (protos/cm-resource *cm* {:url part-url})))))

(def external-conceptmap-translate-cases
  [{:label "part related code mappings translate LOINC to SNOMED CT"
    :input {:url (loinc-model/part-related-conceptmap-url "http://snomed.info/sct")
            :system "http://loinc.org"
            :code "LP14449-0"
            :target "http://snomed.info/sct"}
    :expected {:matches [{:equivalence "equivalent"
                          :system "http://snomed.info/sct"
                          :code "38082009"
                          :display "Hemoglobin (substance)"}]}}
   {:label "part related code mappings translate SNOMED CT to LOINC"
    :input {:url (loinc-model/part-related-conceptmap-url "http://snomed.info/sct")
            :system "http://snomed.info/sct"
            :code "38082009"
            :target "http://loinc.org"}
    :expected {:matches [{:equivalence "equivalent"
                          :system "http://loinc.org"
                          :code "LP14449-0"
                          :display "Hemoglobin"}]}}
   {:label "IEEE medical device mappings translate LOINC to IEEE"
    :input {:url (:url (loinc-model/conceptmap :ieee-medical-device))
            :system "http://loinc.org"
            :code "11556-8"
            :target (:target (loinc-model/conceptmap :ieee-medical-device))}
    :expected {:matches [{:equivalence "equivalent"
                          :system (:target (loinc-model/conceptmap :ieee-medical-device))
                          :code "160116"
                          :display "MDC_CONC_PO2_GEN"}]}}
   {:label "IEEE medical device mappings translate IEEE to LOINC"
    :input {:url (:url (loinc-model/conceptmap :ieee-medical-device))
            :system (:target (loinc-model/conceptmap :ieee-medical-device))
            :code "160116"
            :target "http://loinc.org"}
    :expected {:matches [{:code "11556-8"}]}}
   {:label "non-equivalent mappings preserve wider equivalence"
    :input {:url (loinc-model/part-related-conceptmap-url "http://snomed.info/sct")
            :system "http://loinc.org"
            :code "LP14448-2"
            :target "http://snomed.info/sct"}
    :expected {:matches [{:equivalence "wider"
                          :system "http://snomed.info/sct"
                          :code "38082009"}]}}
   {:label "non-equivalent mappings invert to narrower equivalence"
    :input {:url (loinc-model/part-related-conceptmap-url "http://snomed.info/sct")
            :system "http://snomed.info/sct"
            :code "38082009"
            :target "http://loinc.org"}
    :expected {:matches [{:equivalence "narrower"
                          :system "http://loinc.org"
                          :code "LP14448-2"}]}}
   {:label "RSNA RID mappings translate LOINC to RadLex"
    :input {:url (:url (loinc-model/conceptmap :rsna-rid))
            :system "http://loinc.org"
            :code "24531-6"
            :target (:target (loinc-model/conceptmap :rsna-rid))}
    :expected {:matches [{:code "RID431"}]}}
   {:label "RSNA RID mappings translate RadLex to LOINC"
    :input {:url (:url (loinc-model/conceptmap :rsna-rid))
            :system (:target (loinc-model/conceptmap :rsna-rid))
            :code "RID431"
            :target "http://loinc.org"}
    :expected {:matches [{:code "24531-6"}]}}
   {:label "RSNA RPID mappings translate LOINC to Playbook procedure IDs"
    :input {:url (:url (loinc-model/conceptmap :rsna-rpid))
            :system "http://loinc.org"
            :code "24531-6"
            :target (:target (loinc-model/conceptmap :rsna-rpid))}
    :expected {:matches [{:equivalence "relatedto"
                          :system (:target (loinc-model/conceptmap :rsna-rpid))
                          :code "RPID2142"
                          :display "US Retroperitoneum"}]}}
   {:label "RSNA RPID mappings translate Playbook procedure IDs to LOINC"
    :input {:url (:url (loinc-model/conceptmap :rsna-rpid))
            :system (:target (loinc-model/conceptmap :rsna-rpid))
            :code "RPID2142"
            :target "http://loinc.org"}
    :expected {:matches [{:code "24531-6"}]}}])

(deftest ^:live imported-external-conceptmaps-translate-both-directions
  (doseq [{:keys [label input expected]} external-conceptmap-translate-cases]
    (testing label
      (is (submap? expected (protos/cm-translate *cm* input))))))
