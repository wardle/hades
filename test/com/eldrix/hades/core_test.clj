(ns com.eldrix.hades.core-test
  "Smoke tests for the public Clojure API. Verifies that `open` builds
  a service from artifact paths and that the operation convenience fns
  route correctly."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.load :as load-fhir])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private cs-fhir
  {"resourceType" "CodeSystem"
   "url"          "http://example.org/cs/colours"
   "version"      "1.0"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "red"   "display" "Red"}
                   {"code" "green" "display" "Green"}
                   {"code" "blue"  "display" "Blue"}]})

(def ^:private vs-fhir
  {"resourceType" "ValueSet"
   "url"          "http://example.org/vs/colours"
   "version"      "1.0"
   "status"       "active"
   "compose"      {"include" [{"system" "http://example.org/cs/colours"}]}})

(def ^:dynamic *svc* nil)

(defn- mk-tmp-dir ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- delete-tree! [^File f]
  (when (.isDirectory f)
    (run! delete-tree! (or (.listFiles f) [])))
  (.delete f))

(defn svc-fixture [f]
  (let [root (mk-tmp-dir "hades-core-fixture")]
    (try
      (spit (io/file root "colours-cs.json") (json/write-str cs-fhir))
      (spit (io/file root "colours-vs.json") (json/write-str vs-fhir))
      (let [svc (hades/open [(.getPath root)])]
        (binding [*svc* svc]
          (try (f) (finally (hades/close svc)))))
      (finally
        (delete-tree! root)))))

(use-fixtures :once svc-fixture)

(deftest open-and-lookup
  (testing "lookup returns details for a known code"
    (let [r (hades/lookup *svc* {:system "http://example.org/cs/colours" :code "red"})]
      (is (= "Red" (:display r)))
      (is (= "http://example.org/cs/colours" (:system r)))))
  (testing "lookup reports unknown-code for a missing code in a known system"
    (let [r (hades/lookup *svc* {:system "http://example.org/cs/colours" :code "nope"})]
      (is (true? (:not-found r)))
      (is (= :unknown-code (:not-found-reason r)))))
  (testing "lookup reports unknown-system for an unregistered system"
    (let [r (hades/lookup *svc* {:system "http://no/such" :code "x"})]
      (is (true? (:not-found r)))
      (is (= :unknown-system (:not-found-reason r))))))

(deftest open-from-fhir-json-path
  (testing "public open accepts terminology artifact paths"
    (let [root (mk-tmp-dir "hades-core-open-path")]
      (try
        (spit (io/file root "colours.json")
              (json/write-str {"resourceType" "CodeSystem"
                               "url" "http://example.org/cs/path-colours"
                               "status" "active"
                               "content" "complete"
                               "concept" [{"code" "red" "display" "Red"}]}))
        (with-open [svc (hades/open [(.getPath root)])]
          (is (= "Red" (:display (hades/lookup svc {:system "http://example.org/cs/path-colours"
                                                    :code "red"})))))
        (finally
          (delete-tree! root))))))

(deftest validate-code-cs
  (testing "CS validate-code (no :url)"
    (let [r (hades/validate-code *svc* {:system "http://example.org/cs/colours"
                                        :code   "red"})]
      (is (true? (:result r)))
      (is (= "Red" (:display r)))))
  (testing "CS validate-code reports unknown system"
    (let [r (hades/validate-code *svc* {:system "http://no/such" :code "x"})]
      (is (false? (:result r)))
      (is (= "http://no/such" (:x-unknown-system r))))))

(deftest expand-and-with-overlays
  (testing "expand returns concepts from the implicit ValueSet"
    (let [r (hades/expand *svc* {:url "http://example.org/cs/colours"})]
      (is (= 3 (count (:concepts r))))
      (is (every? #{"red" "green" "blue"} (map :code (:concepts r))))))
  (testing "with-overlays layers an extra provider for one call"
    (let [extra-fhir (assoc cs-fhir
                            "url"     "http://example.org/cs/extras"
                            "concept" [{"code" "x" "display" "Extra"}])
          extra (load-fhir/from-fhir extra-fhir)
          svc'  (hades/with-overlays *svc* [extra])]
      (is (some? (hades/lookup svc' {:system "http://example.org/cs/extras" :code "x"})))
      (testing "base service unaffected"
        (is (= :unknown-system
               (:not-found-reason
                 (hades/lookup *svc* {:system "http://example.org/cs/extras" :code "x"}))))))))

(deftest metadata-empty-on-open
  (is (= {} (hades/metadata *svc*))))
