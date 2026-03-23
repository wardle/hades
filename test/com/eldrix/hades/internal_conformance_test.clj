(ns com.eldrix.hades.internal-conformance-test
  "Internal conformance tests — exercise the specific FHIR spec requirements
  that the HL7 conformance suite checks, at the component level (no HTTP).

  Each test section maps to a conformance suite or regression category.
  When a conformance test fails, add the internal test here first, fix at
  the component level, then confirm the conformance test passes."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.compose :as compose]
            [com.eldrix.hades.fhir :as fhir]
            [com.eldrix.hades.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.fhir-valueset :as fhir-vs]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Test data — mirrors the HL7 tx-ecosystem "simple" and "version" test suites
;; ---------------------------------------------------------------------------

(def simple-cs
  {"resourceType" "CodeSystem"
   "url"          "http://hl7.org/fhir/test/CodeSystem/simple"
   "version"      "0.1.0"
   "name"         "SimpleCS"
   "status"       "active"
   "content"      "complete"
   "caseSensitive" false
   "concept"      [{"code" "code1" "display" "Display 1"}
                    {"code" "code2" "display" "Display 2"
                     "property" [{"code" "status" "valueCode" "retired"}]}
                    {"code" "code3" "display" "Display 3"
                     "property" [{"code" "inactive" "valueBoolean" true}]}
                    {"code" "code4" "display" "Display 4"}]})

(def simple-vs
  {"resourceType" "ValueSet"
   "url"          "http://hl7.org/fhir/test/ValueSet/simple-all"
   "version"      "0.1.0"
   "name"         "SimpleVS"
   "status"       "active"
   "compose"      {"include" [{"system" "http://hl7.org/fhir/test/CodeSystem/simple"}]}})

(def version-cs-1
  {"resourceType" "CodeSystem"
   "url"          "http://hl7.org/fhir/test/CodeSystem/version"
   "version"      "1.0.0"
   "name"         "VersionCS1"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "code1" "display" "Display 1 (1.0)"}
                    {"code" "code2" "display" "Display 2 (1.0)"}]})

(def version-cs-2
  {"resourceType" "CodeSystem"
   "url"          "http://hl7.org/fhir/test/CodeSystem/version"
   "version"      "1.2.0"
   "name"         "VersionCS2"
   "status"       "active"
   "content"      "complete"
   "concept"      [{"code" "code1" "display" "Display 1 (1.2)"}
                    {"code" "code2" "display" "Display 2 (1.2)"}
                    {"code" "code3" "display" "Display 3 (1.2)"}]})

(def version-vs-no-pin
  {"resourceType" "ValueSet"
   "url"          "http://hl7.org/fhir/test/ValueSet/version-all"
   "version"      "0.1.0"
   "name"         "VersionVSAll"
   "status"       "active"
   "compose"      {"include" [{"system" "http://hl7.org/fhir/test/CodeSystem/version"}]}})

(def version-vs-pin-1
  {"resourceType" "ValueSet"
   "url"          "http://hl7.org/fhir/test/ValueSet/version"
   "version"      "1.0.0"
   "name"         "VersionVS1"
   "status"       "active"
   "compose"      {"include" [{"system" "http://hl7.org/fhir/test/CodeSystem/version"
                                "version" "1.0.0"}]}})

(def draft-cs
  {"resourceType" "CodeSystem"
   "url"          "http://hl7.org/fhir/test/CodeSystem/draft"
   "version"      "0.1.0"
   "name"         "DraftCS"
   "status"       "draft"
   "content"      "complete"
   "concept"      [{"code" "A" "display" "Alpha"}]})

(def draft-vs
  {"resourceType" "ValueSet"
   "url"          "http://hl7.org/fhir/test/ValueSet/draft-vs"
   "version"      "0.1.0"
   "name"         "DraftVS"
   "status"       "active"
   "compose"      {"include" [{"system" "http://hl7.org/fhir/test/CodeSystem/draft"}]}})

(defn- make-impls []
  (let [cs (fhir-cs/make-fhir-code-system simple-cs)
        vs (fhir-vs/make-fhir-value-set simple-vs)
        vcs1 (fhir-cs/make-fhir-code-system version-cs-1)
        vcs2 (fhir-cs/make-fhir-code-system version-cs-2)
        vvs-nopin (fhir-vs/make-fhir-value-set version-vs-no-pin)
        vvs-pin1 (fhir-vs/make-fhir-value-set version-vs-pin-1)
        dcs (fhir-cs/make-fhir-code-system draft-cs)
        dvs (fhir-vs/make-fhir-value-set draft-vs)]
    {:cs cs :vs vs :vcs1 vcs1 :vcs2 vcs2
     :vvs-nopin vvs-nopin :vvs-pin1 vvs-pin1 :dcs dcs :dvs dvs}))

(defn register-fixture [f]
  (let [{:keys [cs vs vcs1 vcs2 vvs-nopin vvs-pin1 dcs dvs]} (make-impls)]
    (registry/register-codesystem "http://hl7.org/fhir/test/CodeSystem/simple" cs)
    (registry/register-valueset "http://hl7.org/fhir/test/ValueSet/simple-all" vs)
    (registry/register-codesystem "http://hl7.org/fhir/test/CodeSystem/version" vcs2)
    (registry/register-codesystem "http://hl7.org/fhir/test/CodeSystem/version|1.0.0" vcs1)
    (registry/register-codesystem "http://hl7.org/fhir/test/CodeSystem/version|1.2.0" vcs2)
    (registry/register-valueset "http://hl7.org/fhir/test/ValueSet/version-all" vvs-nopin)
    (registry/register-valueset "http://hl7.org/fhir/test/ValueSet/version" vvs-pin1)
    (registry/register-valueset "http://hl7.org/fhir/test/ValueSet/version|1.0.0" vvs-pin1)
    (registry/register-codesystem "http://hl7.org/fhir/test/CodeSystem/draft" dcs)
    (registry/register-valueset "http://hl7.org/fhir/test/CodeSystem/draft" dcs)
    (registry/register-valueset "http://hl7.org/fhir/test/ValueSet/draft-vs" dvs))
  (f))

(use-fixtures :each register-fixture)

;; ---------------------------------------------------------------------------
;; Helper: assert spec conformance
;; ---------------------------------------------------------------------------

(defn- assert-validate-result [result]
  (is (s/valid? ::protos/validate-result result)
      (str "validate-result spec violation:\n" (s/explain-str ::protos/validate-result result)))
  result)

(defn- assert-expansion-result [result]
  (is (s/valid? ::protos/expansion-result result)
      (str "expansion-result spec violation:\n" (s/explain-str ::protos/expansion-result result)))
  result)

(defn- issue-codes [result]
  (set (map :details-code (:issues result))))

(defn- issue-severities [result]
  (set (map :severity (:issues result))))

;; ---------------------------------------------------------------------------
;; 1. Expression path contract
;;    Impls use canonical Coding.* paths. adjust-issue-expressions transforms.
;; ---------------------------------------------------------------------------

(deftest expression-path-contract-test
  (testing "impls use canonical Coding.* paths"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"
                    :system "http://hl7.org/fhir/test/CodeSystem/simple"
                    :code "code1" :display "Wrong Display"
                    :input-mode :coding})]
      (assert-validate-result result)
      (is (some #(= ["Coding.display"] (:expression %)) (:issues result))
          "display issue should use Coding.display")))

  (testing "impls use Coding.code for not-in-vs"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"
                    :system "http://hl7.org/fhir/test/CodeSystem/simple"
                    :code "MISSING"
                    :input-mode :coding})]
      (assert-validate-result result)
      (is (some #(= ["Coding.code"] (:expression %)) (:issues result))
          "not-in-vs issue should use Coding.code"))))

(deftest adjust-expressions-code-mode-test
  (testing "code mode strips Coding. prefix"
    (let [issues [{:severity "error" :type "invalid" :details-code "invalid-display"
                   :text "wrong" :expression ["Coding.display"]}]
          adjusted (fhir/adjust-issue-expressions issues :code nil)]
      (is (= ["display"] (:expression (first adjusted))))))

  (testing "code mode converts bare Coding to code"
    (let [issues [{:severity "warning" :type "business-rule" :details-code "code-comment"
                   :text "inactive" :expression ["Coding"]}]
          adjusted (fhir/adjust-issue-expressions issues :code nil)]
      (is (= ["code"] (:expression (first adjusted))))))

  (testing "code mode leaves already-bare paths unchanged"
    (let [issues [{:severity "error" :type "not-found" :details-code "not-found"
                   :text "missing" :expression ["system"]}]
          adjusted (fhir/adjust-issue-expressions issues :code nil)]
      (is (= ["system"] (:expression (first adjusted)))))))

(deftest adjust-expressions-coding-mode-test
  (testing "coding mode keeps Coding.* paths as-is"
    (let [issues [{:severity "error" :type "invalid" :details-code "invalid-display"
                   :text "wrong" :expression ["Coding.display"]}]
          adjusted (fhir/adjust-issue-expressions issues :coding nil)]
      (is (= ["Coding.display"] (:expression (first adjusted)))))))

(deftest adjust-expressions-cc-mode-test
  (testing "CC mode replaces Coding.* with CodeableConcept.coding[N].*"
    (let [issues [{:severity "error" :type "invalid" :details-code "invalid-display"
                   :text "wrong" :expression ["Coding.display"] :coding-index 0}]
          adjusted (fhir/adjust-issue-expressions issues :codeableConcept nil)]
      (is (= ["CodeableConcept.coding[0].display"] (:expression (first adjusted))))
      (is (nil? (:coding-index (first adjusted))) "coding-index stripped after adjustment")))

  (testing "CC mode uses per-issue coding-index"
    (let [issues [{:severity "error" :type "code-invalid" :details-code "not-in-vs"
                   :text "a" :expression ["Coding.code"] :coding-index 0}
                  {:severity "error" :type "code-invalid" :details-code "not-in-vs"
                   :text "b" :expression ["Coding.code"] :coding-index 1}]
          adjusted (fhir/adjust-issue-expressions issues :codeableConcept nil)]
      (is (= ["CodeableConcept.coding[0].code"] (:expression (first adjusted))))
      (is (= ["CodeableConcept.coding[1].code"] (:expression (second adjusted))))))

  (testing "CC mode leaves already-adjusted paths unchanged"
    (let [issues [{:severity "error" :type "invalid" :details-code "vs-invalid"
                   :text "ver" :expression ["CodeableConcept.coding[0].version"]}]
          adjusted (fhir/adjust-issue-expressions issues :codeableConcept nil)]
      (is (= ["CodeableConcept.coding[0].version"] (:expression (first adjusted)))))))

;; ---------------------------------------------------------------------------
;; 2. VS not-found — registry returns :not-found flag
;; ---------------------------------------------------------------------------

(deftest vs-not-found-test
  (testing "unknown VS URL returns :not-found"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://example.com/no-such-vs"
                    :system "http://hl7.org/fhir/test/CodeSystem/simple"
                    :code "code1"})]
      (assert-validate-result result)
      (is (false? (:result result)))
      (is (true? (:not-found result)))
      (is (contains? (issue-codes result) "not-found"))))

  (testing "unknown VS version returns :not-found"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/version"
                    :valueSetVersion "2.4.0"
                    :system "http://hl7.org/fhir/test/CodeSystem/version"
                    :code "code1"})]
      (assert-validate-result result)
      (is (true? (:not-found result)))
      (is (str/includes? (:message result) "2.4.0"))))

  (testing "valid VS does NOT return :not-found"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"
                    :system "http://hl7.org/fhir/test/CodeSystem/simple"
                    :code "code1"})]
      (assert-validate-result result)
      (is (nil? (:not-found result))))))

;; ---------------------------------------------------------------------------
;; 3. CC aggregation — valueset-validate-codeableconcept
;; ---------------------------------------------------------------------------

(deftest cc-valid-coding-test
  (testing "single valid coding"
    (let [result (registry/valueset-validate-codeableconcept nil
                   [{:system "http://hl7.org/fhir/test/CodeSystem/simple" :code "code1"}]
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"})]
      (is (true? (:result result)))
      (is (= "Display 1" (:display result))))))

(deftest cc-invalid-coding-test
  (testing "single invalid coding returns this-code-not-in-vs + invalid-code"
    (let [result (registry/valueset-validate-codeableconcept nil
                   [{:system "http://hl7.org/fhir/test/CodeSystem/simple" :code "MISSING"}]
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"})]
      (assert-validate-result result)
      (is (false? (:result result)))
      ;; Per FHIR: in CC mode, per-coding not-in-vs becomes this-code-not-in-vs (information)
      (is (contains? (issue-codes result) "this-code-not-in-vs"))
      (is (contains? (issue-codes result) "invalid-code")))))

(deftest cc-multi-coding-one-valid-test
  (testing "two codings, one valid — result false with all issues"
    (let [result (registry/valueset-validate-codeableconcept nil
                   [{:system "http://hl7.org/fhir/test/CodeSystem/simple" :code "MISSING"}
                    {:system "http://hl7.org/fhir/test/CodeSystem/simple" :code "code1"}]
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"})]
      ;; The valid coding is found but invalid codings make result false
      (is (some? (:display result)) "display from valid coding")
      (is (some #(= "invalid-code" (:details-code %)) (:issues result))
          "invalid-code issue from the bad coding"))))

(deftest cc-vs-not-found-test
  (testing "CC with unknown VS propagates :not-found"
    (let [result (registry/valueset-validate-codeableconcept nil
                   [{:system "http://hl7.org/fhir/test/CodeSystem/simple" :code "code1"}]
                   {:url "http://example.com/no-such-vs"})]
      (is (true? (:not-found result)))
      (is (false? (:result result))))))

(deftest cc-issues-have-coding-index-test
  (testing "per-coding issues tagged with coding-index"
    (let [result (registry/valueset-validate-codeableconcept nil
                   [{:system "http://hl7.org/fhir/test/CodeSystem/simple" :code "MISSING"}
                    {:system "http://hl7.org/fhir/test/CodeSystem/simple" :code "ALSO-MISSING"}]
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"})
          indices (set (keep :coding-index (:issues result)))]
      (is (contains? indices 0) "issues from first coding tagged 0")
      (is (contains? indices 1) "issues from second coding tagged 1"))))

;; ---------------------------------------------------------------------------
;; 4. Expand result shape
;; ---------------------------------------------------------------------------

(deftest expand-result-shape-test
  (testing "expand returns ::expansion-result with required keys"
    (let [result (registry/valueset-expand nil
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"})]
      (assert-expansion-result result)
      (is (pos? (:total result)))
      (is (seq (:concepts result)))
      (is (seq (:used-codesystems result)))))

  (testing "expand concepts have required keys"
    (let [{:keys [concepts]} (registry/valueset-expand nil
                               {:url "http://hl7.org/fhir/test/ValueSet/simple-all"})]
      (is (every? :code concepts))
      (is (every? :system concepts))
      (is (every? :display concepts)))))

(deftest expand-version-pinned-test
  (testing "versioned include expands from pinned CodeSystem"
    (let [result (registry/valueset-expand nil
                   {:url "http://hl7.org/fhir/test/ValueSet/version"})
          codes (set (map :code (:concepts result)))]
      (assert-expansion-result result)
      ;; VS pins version 1.0.0 which has code1 and code2
      (is (contains? codes "code1"))
      (is (contains? codes "code2"))
      ;; code3 only in version 1.2.0
      (is (not (contains? codes "code3")))))

  (testing "compose-pins reflects pinned version"
    (let [{:keys [compose-pins]} (registry/valueset-expand nil
                                   {:url "http://hl7.org/fhir/test/ValueSet/version"})]
      (is (= [{:system "http://hl7.org/fhir/test/CodeSystem/version" :version "1.0.0"}]
             compose-pins)))))

(deftest expand-unpinned-test
  (testing "unpinned include uses default (latest) CodeSystem"
    (let [result (registry/valueset-expand nil
                   {:url "http://hl7.org/fhir/test/ValueSet/version-all"})
          codes (set (map :code (:concepts result)))]
      ;; Default version is 1.2.0 (registered as default)
      (is (contains? codes "code3") "code3 from v1.2.0 should be present")
      (is (empty? (:compose-pins result)) "no compose pins for unpinned VS"))))

;; ---------------------------------------------------------------------------
;; 5. Version mismatch issues
;; ---------------------------------------------------------------------------

(deftest version-mismatch-existing-version-test
  (testing "caller version 1.0.0, VS resolves to 1.2.0 → error mismatch"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/version-all"
                    :system "http://hl7.org/fhir/test/CodeSystem/version"
                    :code "code1"
                    :version "1.0.0"
                    :input-mode :coding})]
      (assert-validate-result result)
      (is (false? (:result result)))
      (is (contains? (issue-codes result) "vs-invalid"))
      ;; Both versions exist → severity is error
      (is (some #(and (= "vs-invalid" (:details-code %))
                      (= "error" (:severity %)))
                (:issues result))))))

(deftest version-mismatch-unknown-version-test
  (testing "caller version 9.9.9 (doesn't exist) → warning mismatch + error unknown"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"
                    :system "http://hl7.org/fhir/test/CodeSystem/simple"
                    :code "code1"
                    :version "9.9.9"
                    :input-mode :coding})]
      (assert-validate-result result)
      (is (false? (:result result)))
      ;; UNKNOWN_CODESYSTEM_VERSION is the primary error
      (is (contains? (issue-codes result) "not-found"))
      ;; VALUESET_VALUE_MISMATCH_DEFAULT is a warning (not error)
      (is (some #(and (= "vs-invalid" (:details-code %))
                      (= "warning" (:severity %)))
                (:issues result))))))

;; ---------------------------------------------------------------------------
;; 6. CS status warnings
;; ---------------------------------------------------------------------------

(deftest cs-status-warnings-test
  (testing "draft CodeSystem validation via ValueSet includes status warning"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/draft-vs"
                    :system "http://hl7.org/fhir/test/CodeSystem/draft"
                    :code "A"
                    :input-mode :coding})]
      (assert-validate-result result)
      (is (true? (:result result)))
      (is (some #(= "status-check" (:details-code %)) (:issues result))
          "draft CS should emit status-check warning"))))

;; ---------------------------------------------------------------------------
;; 7. Inactive handling
;; ---------------------------------------------------------------------------

(deftest inactive-code-validate-test
  (testing "inactive code in VS returns :inactive flag"
    (let [result (registry/valueset-validate-code nil
                   {:url "http://hl7.org/fhir/test/ValueSet/simple-all"
                    :system "http://hl7.org/fhir/test/CodeSystem/simple"
                    :code "code2"
                    :input-mode :coding})]
      (assert-validate-result result)
      (is (true? (:result result)))
      (is (true? (:inactive result))))))

;; ---------------------------------------------------------------------------
;; 8. Explicit HAPI converters — only spec'd fields are serialized
;; ---------------------------------------------------------------------------

(deftest validate-result-parameters-test
  (testing "validate-result->parameters includes only FHIR fields"
    (let [result {:result true :display "Alpha" :code :A
                  :system "http://example.com" :version "1.0"
                  ;; Internal keys that should NOT appear in output
                  :not-found nil :coding-index 0 :input-mode :coding}
          params (fhir/validate-result->parameters result)]
      (is (some? params))
      (let [names (set (map #(.getName %) (.getParameter params)))]
        (is (contains? names "result"))
        (is (contains? names "display"))
        (is (contains? names "system"))
        (is (not (contains? names "not-found")))
        (is (not (contains? names "coding-index")))
        (is (not (contains? names "input-mode"))))))

  (testing "validate-result->parameters omits nil optional fields"
    (let [result {:result false :code :X :system "http://example.com"
                  :message "not found"
                  :issues [{:severity "error" :type "not-found"
                            :details-code "not-found" :text "not found"}]}
          params (fhir/validate-result->parameters result)
          names (set (map #(.getName %) (.getParameter params)))]
      (is (contains? names "result"))
      (is (contains? names "message"))
      (is (contains? names "issues"))
      (is (not (contains? names "display")))
      (is (not (contains? names "version"))))))

(deftest lookup-result-parameters-test
  (testing "lookup-result->parameters serializes properties and designations"
    (let [result {:name "TestCS" :version "1.0" :display "Alpha"
                  :system "http://example.com" :code :A
                  :properties [{:code :inactive :value false}
                               {:code :parent :value :B :description "Beta"}]
                  :designations [{:language :en :value "Alpha"}]}
          params (fhir/lookup-result->parameters result)
          names (vec (map #(.getName %) (.getParameter params)))]
      (is (= "name" (first names)))
      (is (some #(= "property" %) names))
      (is (some #(= "designation" %) names)))))
