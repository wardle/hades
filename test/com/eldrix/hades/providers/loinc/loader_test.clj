(ns com.eldrix.hades.providers.loinc.loader-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [com.eldrix.hades.providers.loinc.loader :as loinc]))

(def fixture-root "test/resources/loinc-fixture")

(defn- drain [dir opts]
  (let [ch (async/chan 8)]
    (async/thread (loinc/stream-release ch dir opts))
    (async/<!! (async/into [] ch))))

(deftest version-derives-from-loinc-table
  (is (= "2.74" (:version (loinc/metadata fixture-root)))))

(deftest regex-path-distinguishes-catalog-from-per-language
  (let [by-path (group-by :path (drain fixture-root nil))]
    (is (= #{::loinc/linguistic-variant-catalog}
           (set (map :type (by-path "AccessoryFiles/LinguisticVariants/LinguisticVariants.csv")))))
    (is (= #{::loinc/linguistic-variant}
           (set (map :type (by-path "AccessoryFiles/LinguisticVariants/deAT24LinguisticVariant.csv")))))
    (is (= #{"24"}
           (set (map :variant-id
                     (by-path "AccessoryFiles/LinguisticVariants/deAT24LinguisticVariant.csv")))))))

(deftest invalid-directory-emits-error-block
  (let [last-block (last (drain "/tmp/no-such-loinc-release" nil))]
    (is (= ::loinc/error (:type last-block)))))

(deftest minimal-release-requires-only-essential-files
  (let [root (doto (java.io.File/createTempFile "loinc-minimal" "")
               (.delete)
               (.mkdirs))
        loinc-dir (doto (io/file root "LoincTable")
                    (.mkdirs))]
    (io/copy (io/file fixture-root "LoincTable" "Loinc.csv")
             (io/file loinc-dir "Loinc.csv"))
    (is (= "2.74" (:version (loinc/metadata root))))
    (is (= ["LoincTable/Loinc.csv"]
           (mapv :path (:files (loinc/metadata root)))))
    (is (= {::loinc/loinc 1}
           (:by-type (loinc/row-counts root))))))

(deftest row-counts-tallies-every-known-type
  (let [{:keys [per-file by-type]} (loinc/row-counts fixture-root)]
    (is (= {::loinc/loinc                       1
            ::loinc/map-to                      1
            ::loinc/source-organization         1
            ::loinc/consumer-name               1
            ::loinc/group                       1
            ::loinc/group-attribute             1
            ::loinc/group-loinc-term            1
            ::loinc/parent-group                1
            ::loinc/parent-group-attribute      1
            ::loinc/part                        1
            ::loinc/part-link-primary           1
            ::loinc/part-link-supplementary     1
            ::loinc/part-related-code-mapping   1
            ::loinc/hierarchy                   1
            ::loinc/answer-list                 1
            ::loinc/answer-list-link            1
            ::loinc/panel                       1
            ::loinc/panels-loinc                1
            ::loinc/panels-answer-list          1
            ::loinc/panels-answer-list-link     1
            ::loinc/document-ontology           1
            ::loinc/imaging-document            1
            ::loinc/rsna-playbook               1
            ::loinc/ieee-medical-device-mapping 1
            ::loinc/universal-lab-orders        1
            ::loinc/linguistic-variant-catalog  1
            ::loinc/linguistic-variant          21
            ::loinc/loinc-change-snapshot       1
            ::loinc/part-change-snapshot        1
            ::loinc/updates                     1
            ::loinc/updates-additional-fields   1}
           by-type))
    (is (every? #{1} (vals per-file))
        "every fixture file carries exactly one data row")
    (is (= 51 (count per-file))
        "streams every known fixture CSV except duplicate LoincTableCore files")
    (is (contains? per-file "AccessoryFiles/PartFile/LoincPartLink_Primary.csv"))
    (is (contains? per-file "AccessoryFiles/PartFile/LoincPartLink_Supplementary.csv"))
    (is (contains? per-file "AccessoryFiles/GroupFile/GroupAttributes.csv"))
    (is (contains? per-file "AccessoryFiles/GroupFile/ParentGroupAttributes.csv"))
    (is (contains? per-file "AccessoryFiles/PanelsAndForms/Loinc.csv"))
    (is (contains? per-file "AccessoryFiles/ChangeSnapshot/LoincChangeSnapshot.csv"))
    (is (contains? per-file "AccessoryFiles/Updates/UpdatesAdditionalFields.csv"))
    (is (not (contains? per-file "LoincTableCore/LoincTableCore.csv")))
    (is (not (contains? per-file "LoincTableCore/MapTo.csv")))))

(deftest row-counts-propagates-loader-errors
  (is (thrown? Exception (loinc/row-counts "/tmp/no-such-loinc-release"))))
