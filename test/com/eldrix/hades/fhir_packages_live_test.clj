(ns com.eldrix.hades.fhir-packages-live-test
  "Live parity tests for real FHIR packages: load the same set of
  packages into the in-memory provider and the SQLite container and
  assert that both engines enumerate, lookup, validate and expand
  identically.

  Tagged `^:live` — needs `.hades/fhir-cache/` (cached package tarballs)
  and `.hades/fhir-tx.db` (the combined FTRM container of the same
  packages). Both are provisioned by the FHIR-packages recipe in
  `doc/development.md`; the `--dist` set must match `fixtures/fhir-packages`."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.protocols :as protos]))

(def ^:dynamic *mem-svc* nil)
(def ^:dynamic *sql-svc* nil)

(defn parity-fixture [f]
  ;; In-memory side opens the cached package tarballs (extracted on open);
  ;; SQLite side opens the combined FTRM container built from the same set.
  (let [mem-svc (hades/open (fixtures/fhir-package-archives))
        sql-svc (hades/open [fixtures/fhir-tx-db-path])]
    (binding [*mem-svc* mem-svc *sql-svc* sql-svc]
      (try (f)
          (finally
            (hades/close mem-svc)
            (hades/close sql-svc))))))

(use-fixtures :once parity-fixture)

(defn- url-version-set [metadata]
  (->> metadata (map (juxt :url :version)) set))

(defn- match-code?
  [result code]
  (some #(= code (:code %)) (:matches result)))

(defn- first-code-from-cs
  "Pull one code from a CodeSystem via `cs-expand*` with no filters,
  bounded by `:max-hits 1`. Returns nil when the CS exposes no concepts
  (e.g. content=not-present)."
  [svc system]
  (when-let [cs (composite/find-codesystem svc system)]
    (-> (protos/cs-expand* cs {:system system :max-hits 1})
        :concepts first :code)))

(deftest ^:live cs-metadata-parity
  (testing "in-memory and SQLite engines list the same CodeSystems"
    (let [mem-cs (url-version-set (protos/cs-metadata *mem-svc* {}))
          sql-cs (url-version-set (protos/cs-metadata *sql-svc* {}))]
      (is (pos? (count mem-cs)) "expected non-empty CodeSystem catalogue")
      (is (= mem-cs sql-cs)
          (str "Engines disagree on CodeSystem catalogue. "
               "in-memory only: " (pr-str (set/difference mem-cs sql-cs))
               "; sqlite only: "  (pr-str (set/difference sql-cs mem-cs)))))))

(deftest ^:live vs-metadata-parity
  (testing "in-memory and SQLite engines list the same ValueSets"
    (let [mem-vs (url-version-set (protos/vs-metadata *mem-svc* {}))
          sql-vs (url-version-set (protos/vs-metadata *sql-svc* {}))]
      (is (pos? (count mem-vs)) "expected non-empty ValueSet catalogue")
      (is (= mem-vs sql-vs)
          (str "Engines disagree on ValueSet catalogue. "
               "in-memory only: " (pr-str (set/difference mem-vs sql-vs))
               "; sqlite only: "  (pr-str (set/difference sql-vs mem-vs)))))))

(deftest ^:live cm-metadata-parity
  (testing "in-memory and SQLite engines list the same ConceptMaps"
    (is (= (url-version-set (protos/cm-metadata *mem-svc* {}))
           (url-version-set (protos/cm-metadata *sql-svc* {}))))))

(deftest ^:live cm-translate-by-system-target
  (let [cases [{:label "administrative gender to v3 by target CodeSystem"
                :params {:system "http://hl7.org/fhir/administrative-gender"
                         :target "http://terminology.hl7.org/CodeSystem/v3-AdministrativeGender"
                         :code "male"}
                :code "M"}
               {:label "administrative gender to v3 by target ValueSet"
                :params {:system "http://hl7.org/fhir/administrative-gender"
                         :target "http://terminology.hl7.org/ValueSet/v3-AdministrativeGender"
                         :code "male"}
                :code "M"}
               {:label "address-use to v3 by target"
                :params {:system "http://hl7.org/fhir/address-use"
                         :target "http://terminology.hl7.org/ValueSet/v3-AddressUse"
                         :code "home"}
                :code "H"}
               {:label "contact-point-use to v2 by target"
                :params {:system "http://hl7.org/fhir/contact-point-use"
                         :target "http://terminology.hl7.org/CodeSystem/v2-0201"
                         :code "mobile"}
                :code "PRS"}
               {:label "IPS pregnancy LOINC answer to SNOMED by target"
                :params {:system "http://loinc.org"
                         :target "http://snomed.info/sct"
                         :code "LA15173-0"}
                :code "77386006"}
               {:label "IPS smoking LOINC answer to SNOMED by target"
                :params {:system "http://loinc.org"
                         :target "http://snomed.info/sct"
                         :code "LA18976-3"}
                :code "449868002"}]]
    (doseq [{:keys [label params code]} cases]
      (testing (str label " (in-memory)")
        (let [result (hades/translate *mem-svc* params)]
          (is (true? (:result result)) (pr-str result))
          (is (match-code? result code) (pr-str (:matches result)))))
      (testing (str label " (SQLite)")
        (let [result (hades/translate *sql-svc* params)]
          (is (true? (:result result)) (pr-str result))
          (is (match-code? result code) (pr-str (:matches result))))))))

(deftest ^:live cs-lookup-parity-sampled
  (testing "$lookup agrees on the first concept of a sample of CodeSystems"
    ;; Sample the first 10 CodeSystems that expose at least one concept.
    ;; Skip empty / not-present CSes — there's nothing to lookup. We rely
    ;; on metadata-parity above to prove the catalogue itself matches;
    ;; here we exercise the read path.
    (let [samples (->> (protos/cs-metadata *mem-svc* {})
                       (keep (fn [{:keys [url version]}]
                               (when-let [code (first-code-from-cs *mem-svc* url)]
                                 [url version code])))
                       (take 10))]
      (is (pos? (count samples))
          "expected at least one CodeSystem with concepts to sample")
      (doseq [[system version code] samples]
        (let [params {:system system :version version :code code}
              m (hades/lookup *mem-svc* params)
              s (hades/lookup *sql-svc* params)]
          (is (= (:display m) (:display s))
              (str "display mismatch for " system "|" version " #" code
                   ": mem=" (pr-str (:display m)) " sql=" (pr-str (:display s))))
          (is (= (:code m) (:code s))
              (str "code echo mismatch for " system "|" version " #" code)))))))

(deftest ^:live cs-validate-code-parity-sampled
  (testing "$validate-code agrees on a sample of known-good codes"
    (let [samples (->> (protos/cs-metadata *mem-svc* {})
                       (keep (fn [{:keys [url version]}]
                               (when-let [code (first-code-from-cs *mem-svc* url)]
                                 [url version code])))
                       (take 10))]
      (doseq [[system version code] samples]
        (let [params {:system system :version version :code code}
              m (hades/validate-code *mem-svc* params)
              s (hades/validate-code *sql-svc* params)]
          (is (= (boolean (:result m)) (boolean (:result s)))
              (str "result mismatch for " system "|" version " #" code))
          (is (= (:display m) (:display s))
              (str "display mismatch for " system "|" version " #" code)))))))
