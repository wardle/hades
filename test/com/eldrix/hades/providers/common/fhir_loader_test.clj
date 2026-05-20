(ns com.eldrix.hades.providers.common.fhir-loader-test
  "Tests for the pure-data FHIR loader. Asserts shape and provenance
  of `fhir-data` produced from JSON files; verifies skip diagnostics
  for unsupported / `.index.json` / nested-Bundle entries; verifies
  hard failures (parse error, oversized file, symlink escape)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.providers.common.fhir-loader :as loaders])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private root "test/resources/fhir-resources/good")

(defn- by-type [fhir-data]
  (group-by :type fhir-data))

(defn- urls [entries] (set (map :url entries)))

;; ---------------------------------------------------------------------------
;; Single-resource → fhir-data
;; ---------------------------------------------------------------------------

(deftest resource->fhir-data-codesystem-test
  (let [data (loaders/resource->fhir-data
               {"resourceType" "CodeSystem"
                "url" "http://example.com/cs" "version" "1.0"
                "status" "active" "content" "complete" "caseSensitive" true
                "concept" [{"code" "A" "display" "Alpha"
                            "concept" [{"code" "A1" "display" "Alpha One"}]}
                           {"code" "B" "display" "Beta"}]})
        {:keys [codesystem-meta concept]} (by-type data)]
    (is (= 1 (count codesystem-meta)))
    (is (= "http://example.com/cs" (:url (first codesystem-meta))))
    (is (= "1.0" (:version (first codesystem-meta))))
    (is (true? (:case-sensitive (first codesystem-meta))))
    (is (= 3 (count concept)) "Alpha, Alpha One (nested), Beta")
    (is (= #{"A" "A1" "B"} (set (map :code concept))))
    (is (= "A" (:parent-code (first (filter #(= "A1" (:code %)) concept)))))))

(deftest resource->fhir-data-valueset-test
  (let [[entry] (loaders/resource->fhir-data
                  {"resourceType" "ValueSet"
                   "url" "http://example.com/vs" "version" "2.0"
                   "status" "active"
                   "compose" {"include" [{"system" "http://example.com/cs"}]}})]
    (is (= :valueset (:type entry)))
    (is (= "http://example.com/vs" (:url entry)))
    (is (= "2.0" (:version entry)))
    (is (some? (:compose entry)))))

(deftest resource->fhir-data-conceptmap-test
  (let [[entry] (loaders/resource->fhir-data
                  {"resourceType" "ConceptMap"
                   "url" "http://example.com/cm"
                   "sourceUri" "http://src" "targetUri" "http://tgt"
                   "group" [{"source" "http://src" "target" "http://tgt"
                             "element" [{"code" "A"
                                         "target" [{"code" "X" "equivalence" "equivalent"}]}]}]})]
    (is (= :conceptmap (:type entry)))
    (is (= "http://src" (:source-uri entry)))
    (is (= "http://tgt" (:target-uri entry)))
    (is (= 1 (count (:groups entry))))))

(deftest resource->fhir-data-bundle-test
  (testing "top-level Bundle recurses into entries"
    (let [data (loaders/resource->fhir-data
                 {"resourceType" "Bundle"
                  "entry" [{"resource" {"resourceType" "CodeSystem"
                                        "url" "http://example.com/b/cs"
                                        "status" "active" "content" "complete"
                                        "caseSensitive" true
                                        "concept" [{"code" "X"}]}}
                           {"resource" {"resourceType" "ValueSet"
                                        "url" "http://example.com/b/vs"
                                        "status" "active"
                                        "compose" {"include" [{"system" "http://example.com/b/cs"}]}}}]})
          {:keys [codesystem-meta valueset]} (by-type data)]
      (is (= 1 (count codesystem-meta)))
      (is (= 1 (count valueset)))))

  (testing "nested Bundle (Bundle inside Bundle entry) is skipped"
    (let [data (loaders/resource->fhir-data
                 {"resourceType" "Bundle"
                  "entry" [{"resource" {"resourceType" "Bundle"
                                        "entry" []}}]})]
      (is (= [{:type :skipped :resource-type "Bundle"
               :reason :nested-bundle :source-path :tx-resource}]
             (vec data))))))

(deftest resource->fhir-data-unsupported-test
  (let [data (loaders/resource->fhir-data
               {"resourceType" "StructureDefinition" "url" "http://x"})]
    (is (= [{:type :skipped :resource-type "StructureDefinition"
             :reason :unsupported-resource-type :source-path :tx-resource}]
           (vec data)))))

;; ---------------------------------------------------------------------------
;; Filesystem walk
;; ---------------------------------------------------------------------------

(deftest load-paths-directory-walk-test
  (let [data (vec (loaders/load-paths root))
        {:keys [codesystem-meta valueset conceptmap skipped]} (by-type data)
        cs-urls (urls codesystem-meta)]

    (testing "discovers CodeSystems at root and nested under subdirectories"
      (is (contains? cs-urls "http://example.com/loader-test/cs"))
      (is (contains? cs-urls "http://example.com/loader-test/nested-cs"))
      (is (contains? cs-urls "http://example.com/loader-test/cs-supplement")))

    (testing "discovers ValueSets and ConceptMaps"
      (is (contains? (urls valueset)   "http://example.com/loader-test/vs"))
      (is (contains? (urls conceptmap) "http://example.com/loader-test/cm")))

    (testing "Bundle contents land alongside individually-filed resources"
      (is (contains? cs-urls "http://example.com/loader-test/bundled-cs"))
      (is (contains? (urls valueset) "http://example.com/loader-test/bundled-vs")))

    (testing "supplement carries content + supplements-target"
      (let [supp (first (filter #(= "supplement" (:content %)) codesystem-meta))]
        (is (some? supp))
        (is (= "http://example.com/loader-test/cs" (:supplements-target supp)))))

    (testing ".index.json is skipped"
      (is (some #(= :index-json (:reason %)) skipped)))

    (testing "StructureDefinition is skipped"
      (is (some #(and (= :unsupported-resource-type (:reason %))
                      (= "StructureDefinition" (:resource-type %)))
                skipped)))

    (testing "every fhir-data entry carries its filesystem source-path"
      (is (every? #(or (= :tx-resource (:source-path %))
                       (string? (:source-path %)))
                  data)))))

(deftest load-paths-single-file-test
  (let [data (vec (loaders/load-paths "test/resources/fhir-resources/good/code-system.json"))
        {:keys [codesystem-meta concept]} (by-type data)]
    (is (= 1 (count codesystem-meta)))
    (is (= 4 (count concept)) "A, B, B1, C")))

(deftest load-paths-parse-error-test
  (testing "a malformed JSON file becomes a :skipped :parse-error entry rather than aborting the walk"
    (let [data    (vec (loaders/load-paths "test/resources/fhir-resources/broken"))
          skipped (filter #(= :skipped (:type %)) data)]
      (is (some #(= :parse-error (:reason %)) skipped)
          "the broken file appears as a parse-error skip")
      (is (some #(re-find #"broken\.json" (or (:source-path %) "")) skipped)
          "the skip carries the offending file path"))))

(deftest load-paths-oversize-test
  (testing "a tiny :max-bytes triggers :max-bytes-exceeded"
    (let [ex (try (doall (loaders/load-paths root {:max-bytes 16}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :max-bytes-exceeded (:reason (ex-data ex)))))))

(deftest load-paths-follows-symlinks-test
  (testing "a symlink to a file outside the root is followed (operator chose root)"
    (let [outside (Files/createTempFile "loader-outside" ".json"
                    (make-array FileAttribute 0))
          tmp-root (Files/createTempDirectory "loader-root"
                    (make-array FileAttribute 0))
          link (.resolve tmp-root "linked.json")]
      (try
        (spit (.toFile outside)
              "{\"resourceType\": \"CodeSystem\", \"url\": \"http://x\", \"version\": \"1\", \"status\": \"active\", \"content\": \"complete\"}")
        (Files/createSymbolicLink link outside (make-array FileAttribute 0))
        (let [data (vec (loaders/load-paths (.toFile tmp-root)))]
          (is (some #(= "http://x" (:url %)) data)
              "the symlinked CodeSystem should be parsed and returned"))
        (finally
          (Files/deleteIfExists link)
          (Files/deleteIfExists tmp-root)
          (Files/deleteIfExists outside))))))

(deftest load-paths-no-leaked-readers-test
  (testing "consuming the seq fully and re-loading works (no reader leak)"
    (let [first-pass  (vec (loaders/load-paths root))
          second-pass (vec (loaders/load-paths root))]
      (is (= (count first-pass) (count second-pass)))
      (is (= (frequencies (map :type first-pass))
             (frequencies (map :type second-pass)))))))
