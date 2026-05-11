(ns com.eldrix.hades.impl.sqlite.loinc-live-test
  "Live integration tests against the pinned LOINC SQLite container.
  Tagged `^:live`. Most tests are data-driven via `cases` — one row per
  case, dispatched on `:op`. Multi-step / catalogue / setup-coupled
  tests keep their own deftest below."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider]))

(def ^:private loinc-url "http://loinc.org")

(def ^:dynamic *svc* nil)
(def ^:dynamic *codesystem* nil)
(def ^:dynamic *valueset* nil)
(def ^:dynamic *conceptmap* nil)

(defn provider-fixture [f]
  ;; Open the SQLite providers separately so per-impl tests can call
  ;; `protos/cs-expand*` directly. The Hades service is built from
  ;; the same providers + a naming-system entry installed idempotently
  ;; for the OID resolver test.
  (let [{:keys [datasource codesystem valueset conceptmap]}
        (sqlite-provider/open-providers fixtures/loinc-db-path)]
    (db/add-naming-system! datasource
      {:url loinc-url :name "LOINC" :status "active" :kind "codesystem"
       :id-type "oid" :value "2.16.840.1.113883.6.1" :preferred true})
    (let [{:keys [naming-system]} (sqlite-provider/open-providers fixtures/loinc-db-path)
          svc (composite/from-providers (filterv some? [codesystem valueset conceptmap])
                          (cond-> {:closers [#(db/close! datasource)]}
                            naming-system (assoc :naming-systems [naming-system])))]
      (binding [*svc* svc *codesystem* codesystem
                *valueset* valueset *conceptmap* conceptmap]
        (try (f) (finally (hades/close svc)))))))

(use-fixtures :once provider-fixture)

;; ---------------------------------------------------------------------------
;; Predicate helpers — keep the cases table flat
;; ---------------------------------------------------------------------------

(defn- includes-code? [code]
  (fn [{:keys [concepts]}] (some #{code} (map :code concepts))))

(defn- top-code? [code]
  (fn [{:keys [concepts]}] (= code (-> concepts first :code))))

(defn- count>= [n] (fn [{:keys [concepts]}] (>= (count concepts) n)))
(defn- count<= [n] (fn [{:keys [concepts]}] (<= (count concepts) n)))
(defn- count=  [n] (fn [{:keys [concepts]}] (= n (count concepts))))

(defn- display? [s]      (fn [r] (= s (:display r))))
(defn- result?  [v]      (fn [r] (= v (:result r))))
(defn- not-found?       [r] (true? (:not-found r)))
(defn- not-found-reason? [reason] (fn [r] (= reason (:not-found-reason r))))
(defn- issue-details? [code] (fn [r] (= code (-> r :issues first :details-code))))

;; ---------------------------------------------------------------------------
;; Dispatch + check
;; ---------------------------------------------------------------------------

(defn- run-op [op input]
  (let [input (assoc input :system loinc-url)]
    (case op
      :lookup        (hades/lookup *svc* input)
      :validate-code (hades/validate-code *svc* input)
      :search-concepts  (protos/cs-expand* *codesystem* input))))

(defn- check [{:keys [name op input expect]}]
  (testing name
    (let [r (run-op op input)]
      (doseq [[label pred] expect]
        (is (pred r) (str name " — " label))))))

;; ---------------------------------------------------------------------------
;; Cases — one row per behaviour. Add new rows here, not new deftests.
;; These cases exercise the internal `cs-expand*` text/filter contract.
;; ---------------------------------------------------------------------------

(def ^:private cases
  [;; --- lookup ---
   {:name "lookup: known code → display + code + system"
    :op :lookup :input {:code "718-7"}
    :expect [["display"    (display? "Hemoglobin [Mass/volume] in Blood")]
             ["code"       #(= :718-7 (:code %))]
             ["system"     #(= loinc-url (:system %))]]}

   {:name "lookup: unknown code → not-found / unknown-code"
    :op :lookup :input {:code "doesnt-exist-xx"}
    :expect [["not-found"  not-found?]
             ["reason"     (not-found-reason? :unknown-code)]]}

   ;; --- validate-code ---
   {:name "validate-code: valid code → result=true + display"
    :op :validate-code :input {:code "2160-0"}
    :expect [["result"     (result? true)]
             ["display"    (display? "Creatinine [Mass/volume] in Serum or Plasma")]]}

   {:name "validate-code: wrong display → result=false + invalid-display"
    :op :validate-code :input {:code "2160-0" :display "Not the LOINC display"}
    :expect [["result"     (result? false)]
             ["issue"      (issue-details? "invalid-display")]]}

   ;; --- search-concepts: display path (spec §1 R1.1(a)) ---
   {:name "search-concepts: multi-token display query ranks 718-7 first"
    :op :search-concepts :input {:text "Hemoglobin Mass volume Blood" :max-hits 5}
    :expect [["count>=1"   (count>= 1)]
             ["top=718-7"  (top-code? "718-7")]]}

   {:name "search-concepts: text + STATUS filter includes 2160-0"
    :op :search-concepts
    :input {:text "Creatinine Mass volume Serum Plasma"
            :filters [{:property "STATUS" :op "=" :value "ACTIVE"}]
            :max-hits 5}
    :expect [["includes 2160-0" (includes-code? "2160-0")]]}

   ;; --- search-concepts: designation path (spec §3.2 / §4.1 at live scale) ---
   {:name "search-concepts: SHORTNAME 'Hgb' → includes 718-7"
    :op :search-concepts :input {:text "Hgb" :max-hits 500}
    :expect [["includes 718-7" (includes-code? "718-7")]]}

   {:name "search-concepts: SHORTNAME 'HbA1c' → includes 4548-4"
    :op :search-concepts :input {:text "HbA1c" :max-hits 500}
    :expect [["includes 4548-4" (includes-code? "4548-4")]]}

   {:name "search-concepts: LinguisticVariantDisplayName 'Natrium' → includes 2951-2"
    :op :search-concepts :input {:text "Natrium" :max-hits 500}
    :expect [["includes 2951-2" (includes-code? "2951-2")]]}

   ;; --- search-concepts: cross-locale RELATEDNAMES2 (spec §4.2) ---
   {:name "search-concepts: British 'Haemoglobin' surfaces hemoglobin codes"
    :op :search-concepts :input {:text "Haemoglobin" :max-hits 50}
    :expect [["count>=1"            (count>= 1)]
             ["includes 4548-4/718-7"
              #(or ((includes-code? "4548-4") %)
                   ((includes-code? "718-7")  %))]]}

   ;; --- search-concepts: axis-only negative guard (spec §4.3) ---
   {:name "search-concepts: axis-only 'Massenkonzentration' ≤ 10"
    :op :search-concepts :input {:text "Massenkonzentration" :max-hits 1000}
    :expect [["count<=10" (count<= 10)]]}

   {:name "search-concepts: axis-only 'Zeitpunkt' ≤ 10"
    :op :search-concepts :input {:text "Zeitpunkt" :max-hits 1000}
    :expect [["count<=10" (count<= 10)]]}

   ;; --- search-concepts: filter-only path ---
   {:name "search-concepts: code = filter → exactly one concept"
    :op :search-concepts :input {:filters [{:property "code" :op "=" :value "718-7"}]}
    :expect [["count=1"   (count= 1)]
             ["is 718-7"  #(= "718-7" (-> % :concepts first :code))]]}])

(deftest ^:live operation-cases
  (doseq [c cases] (check c)))

;; ---------------------------------------------------------------------------
;; Complex / multi-step tests — outside the cases table
;; ---------------------------------------------------------------------------

(deftest ^:live live-loinc-oid-resolves-via-naming-system
  (testing "request with system=urn:oid:... resolves to canonical LOINC URL"
    (let [resolved (composite/resolve-canonical
                     (:naming-systems *svc*) "2.16.840.1.113883.6.1")]
      (is (= loinc-url resolved)))
    (let [r (hades/lookup *svc*
              {:system "2.16.840.1.113883.6.1" :code "718-7"})]
      (is (= "Hemoglobin [Mass/volume] in Blood" (:display r)))
      (is (= loinc-url (:system r))))))

(deftest ^:live live-vs-expand-loinc-answer-list
  (testing "$expand on any LOINC AnswerList VS returns concepts"
    (let [some-vs (-> (protos/vs-metadata *valueset* {}) first :url)
          r (hades/expand *svc* {:url some-vs})]
      (is (sequential? (:concepts r))))))
