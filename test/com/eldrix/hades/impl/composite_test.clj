(ns com.eldrix.hades.impl.composite-test
  "Composite-layer unit tests. `register_test` covers
  `load-fhir/build-from-fhir-data` and the construction-time invariants
  (bare-URL binding, supplement wrapping, naming-system resolution).
  This file targets the dispatch and cross-cutting behaviour itself:

    - precomputed `cs-meta` / `vs-meta` cache
    - `with-overlays` precedence and Closeable rejection
    - ConceptMap candidate selection (single, ambiguous)
    - version-availability + check-system-version issue construction"
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as loaders]
            [com.eldrix.hades.impl.protocols :as protos]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private cs-v1
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs" "version" "1.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha"}]})

(def ^:private cs-v2
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs" "version" "2.0" "status" "draft"
   "content" "complete" "caseSensitive" true
   "experimental" true
   "concept" [{"code" "A" "display" "Alpha v2"}]})

(def ^:private cs-other
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs-other" "version" "1.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "X" "display" "ex"}]})

(def ^:private vs-cs
  {"resourceType" "ValueSet"
   "url" "http://example.com/r/vs" "version" "1.0" "status" "retired"
   "compose" {"include" [{"system" "http://example.com/r/cs"}]}})

(def ^:private cm-a-to-b
  {"resourceType" "ConceptMap"
   "url" "http://example.com/r/cm-1" "version" "1.0" "status" "active"
   "sourceUri" "http://example.com/r/cs"
   "targetUri" "http://example.com/r/cs-other"
   "group" [{"source" "http://example.com/r/cs"
             "target" "http://example.com/r/cs-other"
             "element" [{"code" "A"
                         "target" [{"code" "X" "equivalence" "equivalent"}]}]}]})

(def ^:private cm-a-to-b-alt
  (assoc cm-a-to-b "url" "http://example.com/r/cm-2"))

;; ---------------------------------------------------------------------------

(defn- fhir-data [resource-maps]
  (mapcat #(loaders/resource->fhir-data % "<test>") resource-maps))

(defn- svc-of [resource-maps]
  (let [{:keys [providers supplements]} (load-fhir/build-from-fhir-data
                                          (fhir-data resource-maps))]
    (hades/open providers {:supplements supplements})))

;; ---------------------------------------------------------------------------
;; Precomputed metadata cache
;; ---------------------------------------------------------------------------

(deftest cs-meta-precomputed-cache-test
  (let [svc (svc-of [cs-v1 cs-other])]
    (testing "bare URL hits the cache"
      (is (= "1.0" (:version (composite/cs-meta svc "http://example.com/r/cs"))))
      (is (= "active" (:status (composite/cs-meta svc "http://example.com/r/cs")))))
    (testing "versioned URL hits the cache"
      (is (= "1.0" (:version (composite/cs-meta svc "http://example.com/r/cs|1.0")))))
    (testing "unknown URL returns nil — does not throw"
      (is (nil? (composite/cs-meta svc "http://example.com/r/missing"))))))

(deftest vs-meta-precomputed-cache-test
  (let [svc (svc-of [cs-v1 vs-cs])]
    (testing "VS metadata is cached"
      (is (= "retired" (:status (composite/vs-meta svc "http://example.com/r/vs")))))
    (testing "implicit VS for a CodeSystem is cached too"
      (is (some? (composite/vs-meta svc "http://example.com/r/cs"))))))

(deftest cs-meta-honours-naming-system-aliases-test
  (let [base (svc-of [cs-v1])
        resolver (fn [id]
                   (when (= id "urn:oid:1.2.3") "http://example.com/r/cs"))
        svc (composite/->TerminologyService
              (:codesystems base)
              (:valuesets base)
              (:conceptmaps base)
              [resolver]
              (:cs-meta-by-key base)
              (:vs-meta-by-key base)
              {} [])]
    (testing "alias is resolved against the precomputed map"
      (is (= "1.0" (:version (composite/cs-meta svc "urn:oid:1.2.3")))))
    (testing "unknown alias returns nil"
      (is (nil? (composite/cs-meta svc "urn:oid:9.9.9"))))))

;; ---------------------------------------------------------------------------
;; with-overlays
;; ---------------------------------------------------------------------------

(deftest with-overlays-precedence-test
  (let [base (svc-of [cs-v1])
        ;; overlay introduces a *different* version-2 of the same URL
        {overlay-providers :providers}
        (load-fhir/build-from-fhir-data (fhir-data [cs-v2]))
        layered (hades/with-overlays base overlay-providers)]
    (testing "base codes still reachable at the versioned key"
      (is (some? (composite/find-codesystem layered "http://example.com/r/cs|1.0"))))
    (testing "overlay codes reachable at the new versioned key"
      (is (some? (composite/find-codesystem layered "http://example.com/r/cs|2.0"))))
    (testing "overlay metadata is in the merged cache"
      (is (= "draft" (:status (composite/cs-meta layered "http://example.com/r/cs|2.0")))))
    (testing "base service still works after layering (view, not mutation)"
      (is (some? (composite/find-codesystem base "http://example.com/r/cs|1.0")))
      (is (nil? (composite/find-codesystem base "http://example.com/r/cs|2.0"))))))

(deftest with-overlays-rejects-closeable-test
  (let [base (svc-of [cs-v1])
        closeable-provider (reify
                             java.io.Closeable (close [_] nil)
                             protos/CodeSystem
                             (cs-metadata [_] [{:url "http://example.com/r/closeable"}])
                             (cs-resource [_ _] nil)
                             (cs-lookup [_ _] nil)
                             (cs-validate-code [_ _] nil)
                             (cs-subsumes [_ _] nil)
                             (cs-find-matches [_ _] nil))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Closeable"
          (hades/with-overlays base [closeable-provider])))))

;; ---------------------------------------------------------------------------
;; ConceptMap candidate selection
;; ---------------------------------------------------------------------------

(deftest cm-translate-single-match-test
  (let [svc (svc-of [cs-v1 cs-other cm-a-to-b])
        result (hades/translate svc {:url "http://example.com/r/cm-1" :code "A"})]
    (testing "single ConceptMap match dispatches without ambiguity"
      (is (true? (:result result)))
      (is (some? (seq (:matches result)))))))

(deftest cm-translate-ambiguous-system-target-test
  (let [svc (svc-of [cs-v1 cs-other cm-a-to-b cm-a-to-b-alt])
        result (hades/translate svc {:system "http://example.com/r/cs"
                                     :target "http://example.com/r/cs-other"
                                     :code "A"})]
    (testing "two ConceptMaps for the same (source, target) pair forces :url"
      (is (false? (:result result)))
      (is (some #(= "ambiguous-target" (:details-code %)) (:issues result))))))

(deftest cm-translate-no-match-test
  (let [svc (svc-of [cs-v1 cs-other cm-a-to-b])]
    (testing "no matching ConceptMap returns nil (falls through to caller)"
      (is (nil? (hades/translate svc {:url "http://example.com/r/cm-missing"
                                      :code "A"}))))))

;; ---------------------------------------------------------------------------
;; Version availability + check-system-version
;; ---------------------------------------------------------------------------

(deftest available-versions-test
  (let [svc (svc-of [cs-v1 cs-v2])]
    (testing "lists every registered version for a system"
      (is (= #{"1.0" "2.0"}
             (set (composite/available-versions svc "http://example.com/r/cs")))))
    (testing "returns empty for unknown systems"
      (is (empty? (composite/available-versions svc "http://example.com/r/missing"))))))

(deftest unknown-version-issue-test
  (let [svc (svc-of [cs-v1 cs-v2])]
    (testing "known version yields no issue"
      (is (nil? (composite/unknown-version-issue svc "http://example.com/r/cs" "1.0"))))
    (testing "unknown version yields a not-found issue with valid-versions hint"
      (let [issue (composite/unknown-version-issue svc "http://example.com/r/cs" "9.9.9")]
        (is (= "error" (:severity issue)))
        (is (= "not-found" (:details-code issue)))
        (is (re-find #"Valid versions" (:text issue)))))
    (testing "different :purpose tweaks the trailing clause"
      (let [issue (composite/unknown-version-issue svc "http://example.com/r/cs" "9.9.9" :expand)]
        (is (re-find #"value set cannot be expanded" (:text issue)))
        (is (nil? (:expression issue)))))))

(deftest check-system-version-issue-test
  (let [svc (svc-of [cs-v1])
        sys "http://example.com/r/cs"
        params {:check-system-version {sys "1.0"}}]
    (testing "matching pattern yields no issue"
      (is (nil? (composite/check-system-version-issue svc sys "1.0" params))))
    (testing "mismatch yields a version-error issue"
      (let [issue (composite/check-system-version-issue svc sys "2.0" params)]
        (is (= "error" (:severity issue)))
        (is (= "version-error" (:details-code issue)))
        (is (re-find #"required to be '1.0'" (:text issue)))))))
