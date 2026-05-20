(ns com.eldrix.hades.providers.loinc.import-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [com.eldrix.hades.providers.loinc.import :as loinc-import]
            [com.eldrix.hades.providers.loinc.store :as store]
            [next.jdbc :as jdbc]
            [next.jdbc.plan :as plan])
  (:import (java.io File)))

(def fixture-root "test/resources/loinc-fixture")

(defn- temp-db-path []
  (let [^File f (File/createTempFile "hades-loinc-import-test" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [^String path]
  (let [^File f (io/file path)]
    (when (.exists f) (.delete f))))

(deftest import-release-upserts-rows-and-stamps-metadata
  (let [path (temp-db-path)]
    (try
      (loinc-import/import-release! path fixture-root {:batch-size 7})
      (loinc-import/import-release! path fixture-root {:batch-size 7})
      (let [ds (store/open path)]
        (try
          (with-open [conn (jdbc/get-connection ds)]
            (is (= "2.74"
                   (plan/select-one! conn :value
                                     ["SELECT value FROM meta WHERE key = 'loinc_version'"])))
            (is (= 1
                   (plan/select-one! conn :count
                                     ["SELECT COUNT(*) AS count FROM loinc"])))
            (is (= 21
                   (plan/select-one! conn :count
                                     ["SELECT COUNT(*) AS count FROM linguistic_variant_row"]))))
          (finally
            (store/close! ds))))
      (finally
        (delete-quietly path)))))
