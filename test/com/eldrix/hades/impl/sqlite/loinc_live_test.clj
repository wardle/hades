(ns com.eldrix.hades.impl.sqlite.loinc-live-test
  "Live integration tests against `.hades/loinc-2.81.db`. Tagged
  `^:live` so they're skipped on machines without the pinned LOINC
  build. Provision with:

    clj -M:run import --db .hades/loinc-2.81.db /path/to/Loinc_2.81

  Exercises the SQLite provider end-to-end: catalogue load, point
  queries (lookup / validate-code), text search (find-matches), and
  registry dispatch."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider]))

(def ^:private loinc-db-path ".hades/loinc-2.81.db")
(def ^:private loinc-url "http://loinc.org")
(def ^:private loinc-version "2.81")

(def ^:private state (atom nil))

(defn- have-db? [] (.exists (io/file loinc-db-path)))

(defn provider-fixture [f]
  (if-not (have-db?)
    ;; clojure.test still runs the body when a fixture body is empty.
    ;; Skip silently — `^:live` is the contract for excluding these.
    (f)
    (let [{:keys [codesystem valueset conceptmap naming-system datasource]}
          (sqlite-provider/open-providers loinc-db-path)]
      ;; Idempotently install the LOINC OID so the resolver test has
      ;; data to work with. The application file ships without
      ;; NamingSystem entries by default.
      (db/add-naming-system! datasource
        {:url loinc-url :name "LOINC" :status "active" :kind "codesystem"
         :id-type "oid" :value "2.16.840.1.113883.6.1" :preferred true})
      (let [providers (filterv some? [codesystem valueset conceptmap])
            svc (hades/open providers
                            (cond-> {} naming-system
                                    (assoc :naming-systems [naming-system])))]
        (reset! state {:codesystem codesystem :valueset valueset
                       :conceptmap conceptmap :svc svc :datasource datasource})
        (try (f)
             (finally
               (hades/close svc)
               (db/close! datasource)
               (reset! state nil)))))))

(use-fixtures :once provider-fixture)

(defmacro live-test [name & body]
  `(deftest ~(with-meta name {:live true})
     (when (have-db?)
       ~@body)))

(live-test live-providers-load
  (testing "providers materialise and expose LOINC metadata"
    (let [{:keys [codesystem valueset conceptmap]} @state]
      (is (some? codesystem))
      (is (some? valueset))
      (is (some? conceptmap))
      (is (= loinc-url (-> codesystem protos/cs-metadata first :url)))
      (is (= loinc-version (-> codesystem protos/cs-metadata first :version))))))

(live-test live-cs-lookup-known-code
  (testing "$lookup on a known LOINC code returns full lookup-result"
    (let [r (hades/lookup (:svc @state)
              {:system loinc-url :code "718-7"})]
      (is (= "Hemoglobin [Mass/volume] in Blood" (:display r)))
      (is (= :718-7 (:code r)))
      (is (= loinc-url (:system r))))))

(live-test live-cs-lookup-unknown-code
  (testing "$lookup returns nil for an unknown code (registry contract)"
    (is (nil? (hades/lookup (:svc @state)
                {:system loinc-url :code "doesnt-exist-xx"})))))

(live-test live-cs-validate-code-success
  (testing "$validate-code on a valid LOINC code"
    (let [r (hades/validate-code (:svc @state)
              {:system loinc-url :code "2160-0"})]
      (is (true? (:result r)))
      (is (= "Creatinine [Mass/volume] in Serum or Plasma" (:display r))))))

(live-test live-cs-validate-code-display-mismatch
  (testing "$validate-code with wrong display fails with invalid-display"
    (let [r (hades/validate-code (:svc @state)
              {:system loinc-url :code "2160-0" :display "Not the LOINC display"})]
      (is (false? (:result r)))
      (is (= "invalid-display" (-> r :issues first :details-code))))))

(live-test live-cs-find-matches-fts-text
  (testing "FTS text matching finds LOINC concepts ranked by relevance"
    (let [{:keys [codesystem]} @state
          ;; Query is specific enough to put the canonical
          ;; 'Hemoglobin [Mass/volume] in Blood' (718-7) at the top.
          r (protos/cs-find-matches codesystem
              {:system loinc-url
               :text "Hemoglobin Mass volume Blood"
               :max-hits 5})
          codes (mapv :code (:concepts r))]
      (is (pos? (count codes)))
      (is (= "718-7" (first codes))))))

(live-test live-cs-find-matches-text-and-property-filter
  (testing "FTS text combined with a property filter narrows results"
    (let [{:keys [codesystem]} @state
          r (protos/cs-find-matches codesystem
              {:system loinc-url
               :text "Creatinine Mass volume Serum Plasma"
               :filters [{:property "STATUS" :op "=" :value "ACTIVE"}]
               :max-hits 5})
          codes (mapv :code (:concepts r))]
      (is (some #{"2160-0"} codes)))))

(live-test live-cs-find-matches-direct-code-filter
  (testing "code = filter pinpoints a single concept"
    (let [{:keys [codesystem]} @state
          r (protos/cs-find-matches codesystem
              {:system loinc-url
               :filters [{:property "code" :op "=" :value "718-7"}]})]
      (is (= 1 (count (:concepts r))))
      (is (= "718-7" (-> r :concepts first :code))))))

(live-test live-loinc-oid-resolves-via-naming-system
  (testing "request with system=urn:oid:... resolves to canonical LOINC URL"
    ;; The bare OID would be the value FHIR servers expect after stripping
    ;; the urn:oid: prefix; some clients send the full URN. The resolver
    ;; matches on the stored alias value, which is the bare OID.
    (let [resolved (composite/resolve-canonical
                     (:naming-systems (:svc @state)) "2.16.840.1.113883.6.1")]
      (is (= loinc-url resolved)))
    (is (some? (composite/find-codesystem (:svc @state) "2.16.840.1.113883.6.1")))
    (let [r (hades/lookup (:svc @state)
              {:system "2.16.840.1.113883.6.1" :code "718-7"})]
      (is (= "Hemoglobin [Mass/volume] in Blood" (:display r)))
      (is (= loinc-url (:system r))))))

(live-test live-vs-expand-loinc-answer-list
  (testing "$expand on any LOINC AnswerList VS returns concepts"
    (let [{:keys [valueset]} @state
          some-vs (-> valueset protos/vs-metadata first :url)
          r (hades/expand (:svc @state)
              {:url some-vs})]
      (is (some? r))
      (is (sequential? (:concepts r))))))
