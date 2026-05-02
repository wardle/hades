(ns com.eldrix.hades.impl.register-test
  "Tests for `load-fhir/build-from-fhir-data` plus the resulting service
  shape: bare-URL binding rules, supplement wrapping, NamingSystem
  alias resolution, ConceptMap translation."
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.canonical :as canonical]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as loaders]
            [com.eldrix.hades.impl.protocols :as protos]))

(def ^:private cs-v1
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs"  "version" "1.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha"}]})

(def ^:private cs-v2
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs"  "version" "2.0" "status" "active"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha v2"}]})

(def ^:private vs
  {"resourceType" "ValueSet"
   "url" "http://example.com/r/vs" "version" "1.0" "status" "active"
   "compose" {"include" [{"system" "http://example.com/r/cs"}]}})

(def ^:private supp
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs-supp" "version" "1.0" "status" "active"
   "content" "supplement" "supplements" "http://example.com/r/cs|1.0"
   "concept" [{"code" "A"
               "designation" [{"language" "fr" "value" "Alpha (fr)"}]}]})

(defn- fhir-data [resource-maps]
  (mapcat #(loaders/resource->fhir-data % "<test>") resource-maps))

(defn- svc-of
  "Build a service from `resource-maps` via `build-from-fhir-data` and
  `hades/open`."
  [resource-maps]
  (let [{:keys [providers supplements]} (load-fhir/build-from-fhir-data
                                          (fhir-data resource-maps))]
    (hades/open providers {:supplements supplements})))

;; ---------------------------------------------------------------------------

(deftest register-codesystem-and-valueset-test
  (let [{:keys [providers]} (load-fhir/build-from-fhir-data (fhir-data [cs-v1 vs]))
        svc (hades/open providers)]
    (testing "CodeSystem reachable under bare URL and url|version"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs")))
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs|1.0"))))
    (testing "implicit ValueSet of the CodeSystem is also registered"
      (is (some? (composite/find-valueset svc "http://example.com/r/cs")))
      (is (some? (composite/find-valueset svc "http://example.com/r/cs|1.0"))))
    (testing "compose-driven ValueSet reachable under its own URL"
      (is (some? (composite/find-valueset svc "http://example.com/r/vs"))))))

(deftest single-version-bare-url-binding-test
  (let [svc (svc-of [cs-v1])]
    (testing "single version binds the bare URL"
      (let [r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs")
                                {:code "A"})]
        (is (= "Alpha" (:display r)))))))

(deftest multi-version-picks-semver-latest-test
  (let [svc (svc-of [cs-v1 cs-v2])]
    (testing "two semver versions — bare URL binds to the latest"
      (let [r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs")
                                {:code "A"})]
        (is (= "Alpha v2" (:display r)))))
    (testing "versioned keys remain individually reachable"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs|1.0")))
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs|2.0"))))))

(deftest semver-compare-nil-handling
  (testing "nil < any concrete version, two nils are equal"
    (is (zero? (canonical/semver-compare nil nil)))
    (is (neg?  (canonical/semver-compare nil "1.0")))
    (is (pos?  (canonical/semver-compare "1.0" nil)))
    (is (neg?  (canonical/semver-compare nil "http://snomed.info/sct/x/version/20250201")))
    (is (pos?  (canonical/semver-compare "http://snomed.info/sct/x/version/20250201" nil))))
  (testing "non-numeric segments compare as 0 (URI versions tie)"
    (is (zero? (canonical/semver-compare "http://a/x/version/1"
                                         "http://b/y/version/2"))))
  (testing "numeric semvers still compare numerically"
    (is (neg? (canonical/semver-compare "1.9.0" "1.10.0")))
    (is (pos? (canonical/semver-compare "2.0"   "1.99")))))

(def ^:private cs-no-version
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs"  "status" "active"
   "content" "not-present"})

(deftest mixed-nil-and-versioned-bare-url-binds-versioned
  (testing "versionless stub + versioned content for same URL — bare URL binds the versioned entry"
    (let [svc (svc-of [cs-no-version cs-v1])
          r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs")
                              {:code "A"})]
      (is (= "Alpha" (:display r))
          "versioned 1.0 must win over the nil-version stub"))))

(def ^:private cs-non-semver
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs2" "version" "draft" "status" "active"
   "content" "complete" "concept" [{"code" "A" "display" "Alpha"}]})

(def ^:private cs-non-semver-2
  {"resourceType" "CodeSystem"
   "url" "http://example.com/r/cs2" "version" "released" "status" "active"
   "content" "complete" "concept" [{"code" "A" "display" "Alpha released"}]})

(deftest multi-version-ambiguous-throws-test
  (let [{:keys [providers]} (load-fhir/build-from-fhir-data
                              (fhir-data [cs-non-semver cs-non-semver-2]))
        ex (try (hades/open providers) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "expected ambiguous-default to throw")
    (is (= :ambiguous-default (:reason (ex-data ex))))))

(deftest multi-version-explicit-default-test
  (let [{:keys [providers]} (load-fhir/build-from-fhir-data
                              (fhir-data [cs-non-semver cs-non-semver-2]))
        svc (hades/open providers
              {:defaults {"http://example.com/r/cs2" "released"}})]
    (testing ":defaults binding picks the named version for the bare URL"
      (let [r (protos/cs-lookup (composite/find-codesystem svc "http://example.com/r/cs2")
                                {:code "A"})]
        (is (= "Alpha released" (:display r)))))))

(deftest duplicate-resource-fails-fast-test
  (let [ex (try
             (load-fhir/build-from-fhir-data
               (concat (fhir-data [cs-v1])
                       (fhir-data [cs-v1])))
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :duplicate-resource (:reason (ex-data ex))))
    (is (seq (:duplicates (ex-data ex))))))

(deftest supplement-wraps-registered-base-test
  (let [svc (svc-of [cs-v1 supp])]
    (testing "the supplement augments base lookup results"
      (let [r (protos/cs-lookup
                (composite/find-codesystem svc "http://example.com/r/cs|1.0")
                {:code "A"})
            values (set (map :value (:designations r)))]
        (is (contains? values "Alpha (fr)"))))
    (testing "the supplement also reachable under its own URL"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs-supp|1.0"))))))

(deftest end-to-end-fixture-directory-test
  (let [data (loaders/load-paths "test/resources/fhir-resources/good")
        {:keys [providers supplements totals]} (load-fhir/build-from-fhir-data data)
        svc (hades/open providers {:supplements supplements})]
    (testing "fixture directory loads"
      (is (>= (:codesystems totals) 3)
          (str "totals: " totals)))
    (testing "loader-test/cs reachable; A code lookup returns Alpha"
      (let [r (protos/cs-lookup
                (composite/find-codesystem svc "http://example.com/loader-test/cs")
                {:code "A"})]
        (is (= "Alpha" (:display r)))))
    (testing "supplement augments base"
      (let [r (protos/cs-lookup
                (composite/find-codesystem svc "http://example.com/loader-test/cs")
                {:code "A"})
            values (set (map :value (:designations r)))]
        (is (contains? values "Alpha (fr)"))))
    (testing "compose-driven VS expands"
      (let [r (hades/expand svc {:url "http://example.com/loader-test/vs"})]
        (is (= 2 (count (:concepts r))))))
    (testing "ConceptMap translates"
      (let [r (hades/translate svc {:url "http://example.com/loader-test/cm"
                                    :system "http://example.com/loader-test/cs"
                                    :code "A"})]
        (is (true? (:result r)))
        (is (= "X" (:code (first (:matches r)))))))))

(deftest cs-only-provider-not-registered-as-vs-test
  (testing "a CodeSystem provider that doesn't satisfy ValueSet must
            not be indexed under :valuesets — otherwise the implicit-VS
            dispatch routes vs-expand into a non-implementing impl"
    (let [cs-only (reify protos/CodeSystem
                    (cs-metadata [_]
                      [{:url "http://example.com/cs-only" :version "1"}])
                    (cs-resource [_ _] {:url "http://example.com/cs-only" :version "1"})
                    (cs-lookup [_ _] nil)
                    (cs-validate-code [_ _]
                      {:result false :code (keyword "A")
                       :system "http://example.com/cs-only"})
                    (cs-subsumes [_ _] {:outcome "not-subsumed"})
                    (cs-find-matches [_ _] {:concepts []}))
          svc (hades/open [cs-only])]
      (is (some? (composite/find-codesystem svc "http://example.com/cs-only|1")))
      (is (nil? (composite/find-valueset svc "http://example.com/cs-only|1"))
          "CS-only provider must not appear in the ValueSet catalogue")
      (is (nil? (composite/find-valueset svc "http://example.com/cs-only"))))))

(deftest naming-system-resolves-alias-to-canonical
  (let [base (svc-of [cs-v1])
        resolver (fn [id]
                   (case id
                     "urn:oid:1.2.3" "http://example.com/r/cs"
                     nil))
        svc (hades/open [] {:naming-systems [resolver]
                            :codesystems  (:codesystems base)
                            :valuesets    (:valuesets base)})
        ;; reconstruct with naming-systems by re-opening with the providers
        ;; (open's two-arity preserves naming-systems for us)
        svc (composite/->TerminologyService
              (:codesystems base)
              (:valuesets base)
              (:conceptmaps base)
              [resolver]
              (:cs-meta-by-key base)
              (:vs-meta-by-key base)
              {} [])]
    (testing "alias resolves to canonical when no direct match exists"
      (is (some? (composite/find-codesystem svc "urn:oid:1.2.3"))))
    (testing "direct canonical lookup is unaffected"
      (is (some? (composite/find-codesystem svc "http://example.com/r/cs"))))
    (testing "unknown alias does not match"
      (is (nil? (composite/find-codesystem svc "urn:oid:9.9.9"))))
    (testing "resolve-canonical returns input on unmatched non-blank id"
      (is (= "passthrough" (composite/resolve-canonical [resolver] "passthrough"))))
    (testing "resolve-canonical returns nil on blank/nil"
      (is (nil? (composite/resolve-canonical [resolver] nil)))
      (is (nil? (composite/resolve-canonical [resolver] ""))))))
