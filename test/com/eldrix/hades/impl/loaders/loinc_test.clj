(ns com.eldrix.hades.impl.loaders.loinc-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.loaders.loinc :as loinc]))

(def fixture-root "test/resources/loinc-fixture")

(defn- by-type [data t]
  (filterv #(= t (:type %)) data))

(defn- by-code [concepts code]
  (some #(when (= code (:code %)) %) concepts))

(deftest infer-version-from-directory
  (testing "version inferred from a `Loinc_X.YZ` directory name"
    (is (= "2.82" (loinc/infer-version (io/file "/tmp/Loinc_2.82"))))
    (is (= "2.82" (loinc/infer-version (io/file "/tmp/Loinc_2.82-rc1"))))
    (is (= "1.0"  (loinc/infer-version (io/file "/tmp/loinc-1.0"))))
    (is (nil?     (loinc/infer-version (io/file "/tmp/no-version-here")))))
  (testing "explicit :version overrides directory inference"
    (let [data (loinc/load-paths fixture-root {:version "9.99"})
          meta (first (by-type data :codesystem-meta))]
      (is (= "9.99" (:version meta)))))
  (testing "missing version + un-parseable directory name throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (loinc/load-paths "/tmp/no-version-here")))))

(deftest codesystem-meta-shape
  (let [data (loinc/load-paths fixture-root {:version "2.82"})
        meta (first (by-type data :codesystem-meta))]
    (testing ":codesystem-meta header"
      (is (= "http://loinc.org" (:url meta)))
      (is (= "2.82" (:version meta)))
      (is (true? (:case-sensitive meta)))
      (is (= "complete" (:content meta)))
      (is (= "active" (:status meta)))
      (is (= "LOINC" (:name meta))))
    (testing "property-defs declare the Core columns we surface"
      (let [codes (set (map #(get % "code") (:property-defs meta)))]
        (is (every? codes ["STATUS" "CLASS" "CLASSTYPE"
                           "COMPONENT" "PROPERTY" "TIME_ASPCT"
                           "SYSTEM" "SCALE_TYP" "METHOD_TYP"
                           "SHORTNAME"
                           "VersionFirstReleased" "VersionLastChanged"]))))))

(deftest concept-emission
  (let [data (loinc/load-paths fixture-root {:version "2.82"})
        concepts (by-type data :concept)]
    (testing "every Core row → one :concept (plus LA-codes + LP-parts)"
      ;; 9 LOINC rows + 6 unique LA-codes + 5 LP-parts
      (is (= 20 (count concepts))))
    (testing "Hemoglobin row maps to typed properties"
      (let [c (by-code concepts "718-7")]
        (is (some? c))
        (is (= "Hemoglobin [Mass/volume] in Blood" (:display c)))
        (is (= "http://loinc.org" (:system c)))
        (is (= "2.82" (:version c)))
        (let [props (->> (:properties c)
                         (map (fn [p] [(get p "code")
                                       (or (get p "valueCode")
                                           (get p "valueString"))]))
                         (into {}))]
          (is (= "ACTIVE" (props "STATUS")))
          (is (= "Hemoglobin" (props "COMPONENT")))
          (is (= "Bld" (props "SYSTEM")))
          (is (= "Qn" (props "SCALE_TYP")))
          (is (= "HEM/BC" (props "CLASS"))))
        (testing "STATUS is valueCode; axis columns are valueString"
          (let [status (some #(when (= "STATUS" (get % "code")) %) (:properties c))
                system (some #(when (= "SYSTEM" (get % "code")) %) (:properties c))]
            (is (contains? status "valueCode"))
            (is (contains? system "valueString"))))
        (testing "SHORTNAME emitted as designation"
          (let [d (first (:designations c))]
            (is (= "Hgb Bld" (:value d)))
            (is (= "SHORTNAME" (get-in d [:use :code])))))))
    (testing "LA-codes emitted as concepts in same code system"
      (let [c (by-code concepts "LA28912-9")]
        (is (some? c))
        (is (= "http://loinc.org" (:system c)))
        (is (= "Met" (:display c)))))
    (testing "blank cells dropped from properties"
      (let [c (by-code concepts "1009-0")
            method (some #(when (= "METHOD_TYP" (get % "code")) %) (:properties c))]
        ;; row has Agglutination as METHOD_TYP — should appear
        (is (some? method))
        (is (= "Agglutination" (get method "valueString")))))))

(deftest answer-list-valuesets
  (let [data (loinc/load-paths fixture-root {:version "2.82"})
        vses (by-type data :valueset)
        by-url (group-by :url vses)]
    (testing "one ValueSet per LL-id"
      (is (= 2 (count vses))))
    (testing "Met/Not met list contents (compose include block)"
      (let [vs (first (by-url "http://loinc.org/vs/LL6136-7"))
            include (get-in vs [:compose "include" 0])
            concepts (get include "concept")]
        (is (= "http://loinc.org" (get include "system")))
        (is (= 4 (count concepts)))
        (is (= #{"LA28912-9" "LA28913-7" "LA28914-5" "LA28915-2"}
               (set (map #(get % "code") concepts))))))))

(deftest map-to-conceptmap
  (let [data (loinc/load-paths fixture-root {:version "2.82"})
        cms  (by-type data :conceptmap)]
    (testing "single ConceptMap aggregating MapTo.csv"
      (is (= 1 (count cms)))
      (let [cm (first cms)
            elements (get-in cm [:groups 0 :elements])]
        (is (= "http://loinc.org" (:source-uri cm)))
        (is (= "http://loinc.org" (:target-uri cm)))
        (is (= 2 (count elements)))
        (let [el (first elements)]
          (is (= "1009-0" (:code el)))
          (is (= "1007-4" (-> el :target first :code)))
          (is (= "equivalent" (-> el :target first :equivalence))))
        (testing "comment carried through when present"
          (let [el (second elements)]
            (is (= "Cross-domain note" (-> el :target first :comment)))))))))

(deftest part-concepts-and-hierarchy
  (let [data (loinc/load-paths fixture-root {:version "2.82"})
        concepts (by-type data :concept)
        sodium (by-code concepts "2951-2")
        comp-part (by-code concepts "LP15099-2")]
    (testing "Part.csv emits LP-* concepts with PartName + STATUS + PartTypeName"
      (is (some? comp-part))
      (is (= "Sodium" (:display comp-part)))
      (let [props (->> (:properties comp-part)
                       (map (fn [p] [(get p "code")
                                     (or (get p "valueCode")
                                         (get p "valueString"))]))
                       (into {}))]
        (is (= "ACTIVE" (props "STATUS")))
        (is (= "COMPONENT" (props "PartTypeName")))))
    (testing "ComponentHierarchyBySystem populates :parents on the LOINC concept"
      (is (= ["LP386648-2"] (:parents sodium))))
    (testing "LoincPartLink_Primary surfaces axis Codings on the LOINC concept"
      (let [coding-props (filter #(get % "valueCoding") (:properties sodium))
            by-axis (group-by #(get % "code") coding-props)]
        (is (some? (get by-axis "COMPONENT")))
        (is (= "LP15099-2" (get-in (first (get by-axis "COMPONENT"))
                                   ["valueCoding" "code"])))
        (is (= "Sodium" (get-in (first (get by-axis "COMPONENT"))
                                ["valueCoding" "display"])))))))

(deftest linguistic-variants-as-designations
  (let [data (loinc/load-paths fixture-root {:version "2.82"})
        concepts (by-type data :concept)
        sodium (by-code concepts "2951-2")
        de-designations (filter #(= "de-AT" (:language %)) (:designations sodium))
        by-use (group-by #(get-in % [:use :code]) de-designations)]
    (testing "German variant rows surface as language-tagged designations"
      (is (seq de-designations))
      (is (= "Natrium"
             (:value (first (get by-use "LinguisticVariantDisplayName"))))))))

(deftest missing-files-tolerated
  (testing "absent MapTo.csv / AnswerList.csv just omit those events"
    (let [tmp (doto (java.io.File/createTempFile "loinc-empty" "")
                (.delete))
          root (java.io.File. (.getParentFile tmp) "Loinc_0.1")]
      (.mkdirs (java.io.File. root "LoincTableCore"))
      (let [csv (java.io.File. root "LoincTableCore/LoincTableCore.csv")]
        (spit csv (str "\"LOINC_NUM\",\"COMPONENT\",\"PROPERTY\",\"TIME_ASPCT\","
                       "\"SYSTEM\",\"SCALE_TYP\",\"METHOD_TYP\",\"CLASS\","
                       "\"CLASSTYPE\",\"LONG_COMMON_NAME\",\"SHORTNAME\","
                       "\"EXTERNAL_COPYRIGHT_NOTICE\",\"STATUS\","
                       "\"VersionFirstReleased\",\"VersionLastChanged\"\n"
                       "\"X\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\","
                       "\"X display\",\"\",\"\",\"ACTIVE\",\"0.1\",\"0.1\"\n")))
      (try
        (let [data (loinc/load-paths (.getPath root))]
          (is (= 1 (count (by-type data :codesystem-meta))))
          (is (= 1 (count (by-type data :concept))))
          (is (zero? (count (by-type data :valueset))))
          (is (zero? (count (by-type data :conceptmap)))))
        (finally
          (doseq [^java.io.File f (reverse (file-seq root))]
            (.delete f)))))))
