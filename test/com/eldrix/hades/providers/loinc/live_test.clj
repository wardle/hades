(ns com.eldrix.hades.providers.loinc.live-test
  "Live integration tests against the pinned native LOINC database."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.protocols :as protos]))

(def ^:private loinc-url "http://loinc.org")

(def ^:dynamic *svc* nil)

(defn provider-fixture [f]
  (with-open [svc (hades/open [fixtures/loinc-db-path])]
    (binding [*svc* svc]
      (f))))

(use-fixtures :once provider-fixture)

(defn- includes-code? [code]
  (fn [{:keys [concepts]}] (some #{code} (map :code concepts))))

(defn- top-code? [code]
  (fn [{:keys [concepts]}] (= code (some-> concepts first :code))))

(defn- count>= [n] (fn [{:keys [concepts]}] (>= (count concepts) n)))
(defn- count<= [n] (fn [{:keys [concepts]}] (<= (count concepts) n)))
(defn- count= [n] (fn [{:keys [concepts]}] (= n (count concepts))))

(defn- total= [n] (fn [r] (= n (:total r))))

(defn- display? [s] (fn [r] (= s (:display r))))
(defn- result? [v] (fn [r] (= v (:result r))))
(defn- not-found? [r] (true? (:not-found r)))
(defn- not-found-reason? [reason] (fn [r] (= reason (:not-found-reason r))))
(defn- issue-details? [code] (fn [r] (= code (-> r :issues first :details-code))))
(defn- match-code? [code] (fn [r] (some #(= code (:code %)) (:matches r))))

(defn- vs-listed? [url]
  (fn [m] (boolean (some #(= url (:url %)) (:valuesets m)))))

(defn- vs-url-matching? [re]
  (fn [m] (boolean (some #(re-find re (str (:url %))) (:valuesets m)))))

(defn- equals? [v] (fn [r] (= v r)))

(defn- run-op [op input]
  (let [input (assoc input :system loinc-url)]
    (case op
      :lookup            (hades/lookup *svc* input)
      :validate-code     (hades/validate-code *svc* input)
      :search-concepts   (protos/cs-expand* *svc* input)
      :expand            (hades/expand *svc* input)
      :translate         (hades/translate *svc* input)
      :metadata          (hades/metadata *svc*)
      :resolve-canonical (composite/resolve-canonical (:naming-systems *svc*) (:id input)))))

(defn- check [{:keys [name op input expect]}]
  (testing name
    (let [r (run-op op input)]
      (doseq [[label pred] expect]
        (is (pred r) (str name " - " label))))))

(def ^:private cases
  [{:name "lookup: known code -> display + code + system"
    :op :lookup :input {:code "718-7"}
    :expect [["display" (display? "Hemoglobin [Mass/volume] in Blood")]
             ["code" #(= "718-7" (:code %))]
             ["system" #(= loinc-url (:system %))]]}

   {:name "lookup: unknown code -> not-found / unknown-code"
    :op :lookup :input {:code "doesnt-exist-xx"}
    :expect [["not-found" not-found?]
             ["reason" (not-found-reason? :unknown-code)]]}

   {:name "validate-code: valid code -> result=true + display"
    :op :validate-code :input {:code "2160-0"}
    :expect [["result" (result? true)]
             ["display" (display? "Creatinine [Mass/volume] in Serum or Plasma")]]}

   {:name "validate-code: wrong display -> result=false + invalid-display"
    :op :validate-code :input {:code "2160-0" :display "Not the LOINC display"}
    :expect [["result" (result? false)]
             ["issue" (issue-details? "invalid-display")]]}

   {:name "search-concepts: multi-token display query ranks 718-7 first"
    :op :search-concepts :input {:text "Hemoglobin Mass volume Blood" :max-hits 5}
    :expect [["count>=1" (count>= 1)]
             ["top=718-7" (top-code? "718-7")]]}

   {:name "search-concepts: text + STATUS filter includes 2160-0"
    :op :search-concepts
    :input {:text "Creatinine Mass volume Serum Plasma"
            :filters [{:property "STATUS" :op "=" :value "ACTIVE"}]
            :max-hits 5}
    :expect [["includes 2160-0" (includes-code? "2160-0")]]}

   {:name "search-concepts: SHORTNAME 'Hgb' -> includes 718-7"
    :op :search-concepts :input {:text "Hgb" :max-hits 500}
    :expect [["includes 718-7" (includes-code? "718-7")]]}

   {:name "search-concepts: SHORTNAME 'HbA1c' -> includes 4548-4"
    :op :search-concepts :input {:text "HbA1c" :max-hits 500}
    :expect [["includes 4548-4" (includes-code? "4548-4")]]}

   {:name "search-concepts: LinguisticVariantDisplayName 'Natrium' -> includes 2951-2"
    :op :search-concepts :input {:text "Natrium" :max-hits 500}
    :expect [["includes 2951-2" (includes-code? "2951-2")]]}

   {:name "search-concepts: British 'Haemoglobin' surfaces hemoglobin codes"
    :op :search-concepts :input {:text "Haemoglobin" :max-hits 50}
    :expect [["count>=1" (count>= 1)]
             ["includes 4548-4/718-7"
              #(or ((includes-code? "4548-4") %)
                   ((includes-code? "718-7") %))]]}

   {:name "search-concepts: axis-only 'Massenkonzentration' <= 10"
    :op :search-concepts :input {:text "Massenkonzentration" :max-hits 1000}
    :expect [["count<=10" (count<= 10)]]}

   {:name "search-concepts: axis-only 'Zeitpunkt' <= 10"
    :op :search-concepts :input {:text "Zeitpunkt" :max-hits 1000}
    :expect [["count<=10" (count<= 10)]]}

   {:name "search-concepts: code = filter -> exactly one concept"
    :op :search-concepts :input {:filters [{:property "code" :op "=" :value "718-7"}]}
    :expect [["count=1" (count= 1)]
             ["is 718-7" #(= "718-7" (some-> % :concepts first :code))]]}

   {:name "translate: MapTo maps deprecated LOINC to replacement"
    :op :translate
    :input {:url "http://loinc.org/cm/map-to"
            :code "1009-0"
            :target loinc-url}
    :expect [["result=true" (result? true)]
             ["maps to 1007-4" (match-code? "1007-4")]]}

   ;; -------------------------------------------------------------------------
   ;; LoincValueSet — desired behaviour from the LOINC FHIR IG. The IG
   ;; defines four URL patterns under `http://loinc.org/vs[/...]`:
   ;;   • bare /vs           -> all of LOINC
   ;;   • /vs/{LL-id}        -> answer-list members
   ;;   • /vs/{LG-id}        -> group members
   ;;   • /vs/{LP-code}      -> multi-axial hierarchy descendants
   ;; Cases that fail today are missing-functionality flags; the LL ones
   ;; pass against the current implementation.
   ;; -------------------------------------------------------------------------

   {:name "metadata: vs catalogue lists the all-of-LOINC implicit VS"
    :op :metadata :input {}
    :expect [["http://loinc.org/vs is listed" (vs-listed? "http://loinc.org/vs")]]}

   {:name "metadata: vs catalogue lists at least one LL answer-list VS"
    :op :metadata :input {}
    :expect [["any LL URL listed" (vs-url-matching? #"^http://loinc\.org/vs/LL\d")]]}

   {:name "metadata: vs catalogue lists at least one LG group VS"
    :op :metadata :input {}
    :expect [["any LG URL listed" (vs-url-matching? #"^http://loinc\.org/vs/LG\d")]]}

   {:name "vs-expand: all-of-LOINC with filter narrows to hemoglobin"
    :op :expand
    :input {:url "http://loinc.org/vs" :filter "hemoglobin" :count 50}
    :expect [["includes 718-7" (includes-code? "718-7")]]}

   {:name "vs-expand: all-of-LOINC includes answer codes"
    :op :expand
    :input {:url "http://loinc.org/vs" :filter "6 or more times" :count 10}
    :expect [["includes LA14674-8" (includes-code? "LA14674-8")]]}

   {:name "vs-expand: all-of-LOINC includes part codes"
    :op :expand
    :input {:url "http://loinc.org/vs" :filter "Hemoglobin" :count 100}
    :expect [["includes LP14449-0" (includes-code? "LP14449-0")]]}

   {:name "vs-expand: all-of-LOINC includes group codes"
    :op :expand
    :input {:url "http://loinc.org/vs" :filter "Cytomegalovirus DNA" :count 100}
    :expect [["includes LG51973-2" (includes-code? "LG51973-2")]]}

   {:name "vs-expand: LP hierarchy ValueSet returns LNC descendants"
    :op :expand :input {:url "http://loinc.org/vs/LP392452-1"}
    :expect [["includes 718-7" (includes-code? "718-7")]]}

   {:name "vs-expand: LG group ValueSet returns group members"
    :op :expand :input {:url "http://loinc.org/vs/LG51973-2"}
    :expect [["count>=1" (count>= 1)]]}

   {:name "vs-expand: LL answer-list ValueSet returns answer items"
    :op :expand :input {:url "http://loinc.org/vs/LL1234-5"}
    :expect [["includes LA14674-8" (includes-code? "LA14674-8")]
             ["total=5" (total= 5)]]}

   {:name "vs-validate-code: all-of-LOINC accepts any LOINC code"
    :op :validate-code
    :input {:url "http://loinc.org/vs" :code "718-7"}
    :expect [["result=true" (result? true)]]}

   {:name "vs-validate-code: all-of-LOINC accepts answer list answer codes"
    :op :validate-code
    :input {:url "http://loinc.org/vs" :code "LA14674-8"}
    :expect [["result=true" (result? true)]]}

   {:name "vs-validate-code: all-of-LOINC accepts part codes"
    :op :validate-code
    :input {:url "http://loinc.org/vs" :code "LP14449-0"}
    :expect [["result=true" (result? true)]]}

   {:name "vs-validate-code: LP hierarchy accepts descendant"
    :op :validate-code
    :input {:url "http://loinc.org/vs/LP392452-1" :code "718-7"}
    :expect [["result=true" (result? true)]]}

   {:name "vs-validate-code: LP hierarchy rejects non-descendant"
    :op :validate-code
    :input {:url "http://loinc.org/vs/LP392452-1" :code "2160-0"}
    :expect [["result=false" (result? false)]]}

   {:name "vs-validate-code: LL answer-list accepts member"
    :op :validate-code
    :input {:url "http://loinc.org/vs/LL1234-5" :code "LA14674-8"}
    :expect [["result=true" (result? true)]]}

   {:name "vs-validate-code: LL answer-list rejects non-member"
    :op :validate-code
    :input {:url "http://loinc.org/vs/LL1234-5" :code "LA9999-9"}
    :expect [["result=false" (result? false)]]}

   {:name "vs-validate-code: wrong display surfaces invalid-display issue"
    :op :validate-code
    :input {:url "http://loinc.org/vs/LL1234-5" :code "LA14674-8" :display "Bogus"}
    :expect [["result=false" (result? false)]
             ["invalid-display" (issue-details? "invalid-display")]]}

   ;; -------------------------------------------------------------------------
   ;; NamingSystem — the LOINC OID resolves via both `urn:oid:` and bare
   ;; forms; unknown OIDs must NOT resolve to LOINC.
   ;; -------------------------------------------------------------------------

   {:name "naming-system: urn:oid: prefix resolves to LOINC"
    :op :resolve-canonical :input {:id "urn:oid:2.16.840.1.113883.6.1"}
    :expect [["= loinc-url" (equals? loinc-url)]]}

   {:name "naming-system: bare OID resolves to LOINC"
    :op :resolve-canonical :input {:id "2.16.840.1.113883.6.1"}
    :expect [["= loinc-url" (equals? loinc-url)]]}

   {:name "naming-system: unknown OID is not claimed as LOINC"
    :op :resolve-canonical :input {:id "1.2.3.4.5.6.7.8.9"}
    :expect [["not loinc-url" #(not= loinc-url %)]]}])

(deftest ^:live operation-cases
  (doseq [c cases] (check c)))

(deftest ^:live live-loinc-oid-resolves-via-naming-system
  (testing "request with system=urn:oid:... resolves to canonical LOINC URL"
    (is (= loinc-url
           (composite/resolve-canonical (:naming-systems *svc*) "2.16.840.1.113883.6.1")))
    (let [r (hades/lookup *svc*
              {:system "2.16.840.1.113883.6.1" :code "718-7"})]
      (is (= "Hemoglobin [Mass/volume] in Blood" (:display r)))
      (is (= loinc-url (:system r))))))

(deftest ^:live live-vs-expand-loinc-answer-list
  (testing "$expand on any LOINC AnswerList VS returns concepts once native ValueSet support exists"
    (let [r (hades/expand *svc* {:url "http://loinc.org/vs/LL1234-5"})]
      (is (sequential? (:concepts r)))
      (is (seq (:concepts r))))))
