(ns com.eldrix.hades.impl.index.sqlite-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.index.sqlite :as sqlite-index]
            [com.eldrix.hades.impl.loaders.loinc :as loinc]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider]
            [next.jdbc :as jdbc])
  (:import (java.io File)))

(def fixture-root "test/resources/loinc-fixture")

(defn- temp-db-path []
  (let [^File f (File/createTempFile "hades-build-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [^String path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))

(defn- count-rows [ds sql]
  (-> (jdbc/execute-one! ds [sql]) vals first))

(defn- import-loinc-fixture! [path version]
  (sqlite-index/import-fhir-data
    path
    (loinc/stream-release fixture-root {:version version})
    {:loader-type "loinc-csv"}))

(deftest build-loinc-fixture-into-sqlite
  (let [path (temp-db-path)]
    (try
      (let [result (import-loinc-fixture! path "2.82")]
        (sqlite-index/index! path)
        (let [ds (db/open path)]
        (testing "result reports targets and resources"
          (is (= path (:db-path result)))
          (is (>= (count (:resources result)) 4))
          (is (some #(and (= "CodeSystem" (:resource-type %))
                          (= "http://loinc.org" (:url %))
                          (= "2.82" (:version %)))
                    (:resources result))))
        (testing "concept rows landed"
          ;; 9 LOINC concepts + 6 LA-concepts + 6 LP-parts = 21
          (is (= 21 (count-rows ds "SELECT COUNT(*) FROM concept")))
          (is (= "Hemoglobin [Mass/volume] in Blood"
                 (-> (jdbc/execute-one! ds
                       ["SELECT display FROM concept
                         WHERE cs_url='http://loinc.org' AND code='718-7'"])
                     vals first))))
        (testing "typed properties — STATUS as code, COMPONENT as string"
          (let [props (jdbc/execute! ds
                        ["SELECT prop_code, value_type, value_str
                          FROM concept_property
                          WHERE cs_url='http://loinc.org' AND code='718-7'
                          ORDER BY prop_code"])
                by-code (into {}
                              (map (fn [{:concept_property/keys [prop_code value_type value_str]}]
                                     [prop_code [value_type value_str]]))
                              props)]
            (is (= ["code" "ACTIVE"] (get by-code "STATUS")))
            (is (= ["string" "Hemoglobin"] (get by-code "COMPONENT")))
            (is (= ["string" "Bld"] (get by-code "SYSTEM")))))
        (testing "designations carried"
          (is (pos? (count-rows ds "SELECT COUNT(*) FROM concept_designation
                                    WHERE cs_url='http://loinc.org' AND code='718-7'"))))
        (testing "hierarchy: ComponentHierarchyBySystem becomes concept_parent rows"
          (is (= 1 (count-rows ds "SELECT COUNT(*) FROM concept_parent
                                   WHERE cs_url='http://loinc.org' AND code='2951-2'")))
          (let [parent (-> (jdbc/execute-one! ds
                             ["SELECT parent_code FROM concept_parent
                               WHERE cs_url='http://loinc.org' AND code='2951-2'"])
                           vals first)]
            (is (= "LP386648-2" parent))))
        (testing "ancestor closure populated for the hierarchy"
          (is (pos? (count-rows ds "SELECT COUNT(*) FROM concept_ancestor
                                    WHERE cs_url='http://loinc.org' AND descendent_code='2951-2'"))))
        (testing "LP-* parts ingested as concepts"
          (is (some? (jdbc/execute-one! ds
                       ["SELECT code FROM concept
                         WHERE cs_url='http://loinc.org' AND code='LP15099-2'"]))))
        (testing "LoincPartLink_Primary surfaces typed Coding properties"
          (let [rows (jdbc/execute! ds
                       ["SELECT prop_code, value_type, value_coding_code, value_coding_display
                         FROM concept_property
                         WHERE cs_url='http://loinc.org' AND code='2951-2'
                           AND value_type='Coding'
                         ORDER BY prop_code"])
                by-axis (into {} (map (fn [{:concept_property/keys [prop_code value_type value_coding_code value_coding_display]}]
                                        [prop_code [value_type value_coding_code value_coding_display]]))
                              rows)]
            (is (= ["Coding" "LP15099-2" "Sodium"]
                   (get by-axis "COMPONENT")))))
        (testing "linguistic variants land as language-tagged designations"
          (let [de-rows (jdbc/execute! ds
                          ["SELECT use_code, value FROM concept_designation
                            WHERE cs_url='http://loinc.org' AND code='2951-2'
                              AND language='de-AT'"])]
            (is (some #(= "Natrium" (:concept_designation/value %)) de-rows))))
        (testing "valuesets and conceptmaps landed"
          (is (= 2 (count-rows ds "SELECT COUNT(*) FROM valueset")))
          (is (= 1 (count-rows ds "SELECT COUNT(*) FROM conceptmap")))
          (is (= 2 (count-rows ds "SELECT COUNT(*) FROM conceptmap_element"))))
        (testing "tx_resource catalogue"
          (let [rows (db/list-resources ds)
                cs   (filter #(= "CodeSystem" (:resource-type %)) rows)]
            (is (= 1 (count cs)))
            ;; 9 LOINC-table rows + 6 unique LA-codes + 6 LP-parts = 21
            (is (= 21 (:concept-count (first cs))))))))
      (finally (delete-quietly path)))))

(deftest subsumes-uses-closure-table
  (let [path (temp-db-path)]
    (try
      (import-loinc-fixture! path "2.82")
      (sqlite-index/index! path)
      (let [{:keys [datasource codesystem]} (sqlite-provider/open-providers path)
            call (fn [params] (protos/cs-subsumes codesystem params))]
        (try
          (testing "ancestor → descendant returns 'subsumes'"
            (is (= "subsumes" (:outcome (call {:systemA "http://loinc.org"
                                               :codeA "LP386648-2"
                                               :codeB "2951-2"})))))
          (testing "descendant → ancestor returns 'subsumed-by'"
            (is (= "subsumed-by" (:outcome (call {:systemA "http://loinc.org"
                                                  :codeA "2951-2"
                                                  :codeB "LP386648-2"})))))
          (testing "same code returns 'equivalent'"
            (is (= "equivalent" (:outcome (call {:systemA "http://loinc.org"
                                                 :codeA "2951-2"
                                                 :codeB "2951-2"})))))
          (testing "unrelated codes return 'not-subsumed'"
            (is (= "not-subsumed" (:outcome (call {:systemA "http://loinc.org"
                                                   :codeA "2951-2"
                                                   :codeB "718-7"})))))
          (finally
            (when (instance? java.io.Closeable datasource)
              (.close ^java.io.Closeable datasource)))))
      (finally (delete-quietly path)))))

(deftest update-in-place-replaces-same-version
  (let [path (temp-db-path)]
    (try
      (import-loinc-fixture! path "2.82")
      (let [ds (db/open path)
            cnt-before (count-rows ds "SELECT COUNT(*) FROM concept")]
        (testing "second build with same version is idempotent in row count"
          (import-loinc-fixture! path "2.82")
          (let [ds (db/open path)]
            (is (= cnt-before (count-rows ds "SELECT COUNT(*) FROM concept"))))))
      (finally (delete-quietly path)))))

(deftest multi-version-coexist
  (let [path (temp-db-path)]
    (try
      (import-loinc-fixture! path "2.82")
      (import-loinc-fixture! path "2.83")
      (let [ds (db/open path)
            versions (->> (jdbc/execute! ds
                            ["SELECT DISTINCT cs_version FROM concept WHERE cs_url='http://loinc.org'"])
                          (map :concept/cs_version)
                          set)]
        (is (= #{"2.82" "2.83"} versions)))
      (finally (delete-quietly path)))))
