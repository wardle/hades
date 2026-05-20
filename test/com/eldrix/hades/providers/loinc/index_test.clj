(ns com.eldrix.hades.providers.loinc.index-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [com.eldrix.hades.providers.loinc.import :as loinc-import]
            [com.eldrix.hades.providers.loinc.index :as loinc-index]
            [com.eldrix.hades.providers.loinc.store :as store]
            [next.jdbc :as jdbc]
            [next.jdbc.plan :as plan])
  (:import (java.io File)))

(def fixture-root "test/resources/loinc-fixture")

(defn- temp-db-path []
  (let [^File f (File/createTempFile "hades-loinc-index-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [^String path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))

(deftest rebuilds-loinc-fts-index
  (let [path (temp-db-path)]
    (try
      (loinc-import/import-release! path fixture-root {:batch-size 7})
      (loinc-index/index! path)
      (let [ds (store/open path)]
        (try
          (with-open [conn (jdbc/get-connection ds)]
            (is (= "100000-9"
                   (plan/select-one!
                    conn
                    :loinc_num
                    ["SELECT l.loinc_num
                      FROM loinc_fts f
                      JOIN loinc l ON l.rowid = f.rowid
                      WHERE loinc_fts MATCH 'informatics'
                      LIMIT 1"])))
            (is (= "14394-1"
                   (plan/select-one!
                    conn
                    :loinc_num
                    ["SELECT loinc_num
                      FROM loinc_variant_fts
                      WHERE loinc_variant_fts MATCH 'Harnstoff'
                      LIMIT 1"])))
            (is (= {:loinc_num "33512-5"
                    :variant_id "28"
                    :iso_language "es"
                    :iso_country "MX"}
                   (plan/select-one!
                    conn
                    [:loinc_num :variant_id :iso_language :iso_country]
                    ["SELECT loinc_num, variant_id, iso_language, iso_country
                      FROM loinc_variant_fts
                      WHERE loinc_variant_fts MATCH 'Punto'
                        AND iso_language = 'es'
                      ORDER BY CASE
                                 WHEN iso_language = 'es' AND iso_country = 'MX' THEN 0
                                 WHEN iso_language = 'es' THEN 1
                                 ELSE 2
                               END
                      LIMIT 1"]))))
          (finally
            (store/close! ds))))
      (finally
        (delete-quietly path)))))
