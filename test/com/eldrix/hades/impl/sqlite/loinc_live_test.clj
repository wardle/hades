(ns com.eldrix.hades.impl.sqlite.loinc-live-test
  "Live integration tests against the pinned LOINC SQLite container.
  Tagged `^:live`. Exercises the SQLite provider end-to-end:
  catalogue load, point queries (lookup / validate-code), text search
  (cs-expand*), and registry dispatch."
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
  ;; for the OID resolver test. The application file ships without
  ;; NamingSystem entries by default.
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

(deftest ^:live live-providers-load
  (testing "providers materialise and expose LOINC metadata"
    (is (some? *codesystem*))
    (is (some? *valueset*))
    (is (some? *conceptmap*))
    (is (= loinc-url (-> (protos/cs-metadata *codesystem* {}) first :url)))
    (is (= fixtures/loinc-version (-> (protos/cs-metadata *codesystem* {}) first :version)))))

(deftest ^:live live-cs-lookup-known-code
  (testing "$lookup on a known LOINC code returns full lookup-result"
    (let [r (hades/lookup *svc*
              {:system loinc-url :code "718-7"})]
      (is (= "Hemoglobin [Mass/volume] in Blood" (:display r)))
      (is (= :718-7 (:code r)))
      (is (= loinc-url (:system r))))))

(deftest ^:live live-cs-lookup-unknown-code
  (testing "$lookup returns a not-found result for an unknown code"
    (let [r (hades/lookup *svc*
              {:system loinc-url :code "doesnt-exist-xx"})]
      (is (true? (:not-found r)))
      (is (= :unknown-code (:not-found-reason r))))))

(deftest ^:live live-cs-validate-code-success
  (testing "$validate-code on a valid LOINC code"
    (let [r (hades/validate-code *svc*
              {:system loinc-url :code "2160-0"})]
      (is (true? (:result r)))
      (is (= "Creatinine [Mass/volume] in Serum or Plasma" (:display r))))))

(deftest ^:live live-cs-validate-code-display-mismatch
  (testing "$validate-code with wrong display fails with invalid-display"
    (let [r (hades/validate-code *svc*
              {:system loinc-url :code "2160-0" :display "Not the LOINC display"})]
      (is (false? (:result r)))
      (is (= "invalid-display" (-> r :issues first :details-code))))))

(deftest ^:live live-cs-expand*-fts-text
  (testing "FTS text matching finds LOINC concepts ranked by relevance"
    (let [;; Query is specific enough to put the canonical
          ;; 'Hemoglobin [Mass/volume] in Blood' (718-7) at the top.
          r (protos/cs-expand* *codesystem*
              {:system loinc-url
               :text "Hemoglobin Mass volume Blood"
               :max-hits 5})
          codes (mapv :code (:concepts r))]
      (is (pos? (count codes)))
      (is (= "718-7" (first codes))))))

(deftest ^:live live-cs-expand*-text-and-property-filter
  (testing "FTS text combined with a property filter narrows results"
    (let [r (protos/cs-expand* *codesystem*
              {:system loinc-url
               :text "Creatinine Mass volume Serum Plasma"
               :filters [{:property "STATUS" :op "=" :value "ACTIVE"}]
               :max-hits 5})
          codes (mapv :code (:concepts r))]
      (is (some #{"2160-0"} codes)))))

(deftest ^:live live-cs-expand*-direct-code-filter
  (testing "code = filter pinpoints a single concept"
    (let [r (protos/cs-expand* *codesystem*
              {:system loinc-url
               :filters [{:property "code" :op "=" :value "718-7"}]})]
      (is (= 1 (count (:concepts r))))
      (is (= "718-7" (-> r :concepts first :code))))))

(deftest ^:live live-loinc-oid-resolves-via-naming-system
  (testing "request with system=urn:oid:... resolves to canonical LOINC URL"
    ;; The bare OID would be the value FHIR servers expect after stripping
    ;; the urn:oid: prefix; some clients send the full URN. The resolver
    ;; matches on the stored alias value, which is the bare OID.
    (let [resolved (composite/resolve-canonical
                     (:naming-systems *svc*) "2.16.840.1.113883.6.1")]
      (is (= loinc-url resolved)))
    (is (some? (composite/find-codesystem *svc* "2.16.840.1.113883.6.1")))
    (let [r (hades/lookup *svc*
              {:system "2.16.840.1.113883.6.1" :code "718-7"})]
      (is (= "Hemoglobin [Mass/volume] in Blood" (:display r)))
      (is (= loinc-url (:system r))))))

(deftest ^:live live-vs-expand-loinc-answer-list
  (testing "$expand on any LOINC AnswerList VS returns concepts"
    (let [some-vs (-> (protos/vs-metadata *valueset* {}) first :url)
          r (hades/expand *svc* {:url some-vs})]
      (is (some? r))
      (is (sequential? (:concepts r))))))
