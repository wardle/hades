(ns com.eldrix.hades.vsac-parity-live-test
  "Live FTRM-vs-in-memory parity for VSAC.

  The `us.nlm.vsac` package physically ships ~7,600 ValueSets twice (once
  at the package top level, once under `$root/`/`other/` subdirectories),
  so the raw file count (~16,700) is nearly double the distinct
  `(url, version)` count (9,071). Both providers must collapse the
  duplicates to the same catalogue and expand identically — that is the
  invariant this test guards.

  SNOMED CT International + LOINC are loaded alongside VSAC so that VSAC's
  SNOMED/LOINC-referencing ValueSets expand to real concept sets. Without
  them every expansion is empty and an empty-vs-empty comparison passes
  vacuously — `vsac-expansion-parity-sampled` asserts a non-empty floor
  precisely to prevent that.

  Tagged `^:live` — needs `.hades/snomed-intl-20250201.db`,
  `.hades/loinc-2.82.db`, the SQLite container `.hades/vsac-0.24.db`, and
  the unpacked package `.hades/fhir-packages/us.nlm.vsac-0.24.0/package`."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.protocols :as protos]))

(def ^:dynamic *mem* nil)
(def ^:dynamic *sql* nil)

(defn parity-fixture [f]
  (let [base [fixtures/snomed-db-path fixtures/loinc-db-path]
        mem  (hades/open (conj base fixtures/vsac-package-dir))
        sql  (hades/open (conj base fixtures/vsac-db-path))]
    (binding [*mem* mem *sql* sql]
      (try (f)
           (finally
             (hades/close mem)
             (hades/close sql))))))

(use-fixtures :once parity-fixture)

(defn- vsac-url? [url] (and url (.contains ^String url "cts.nlm.nih.gov")))

(defn- vsac-vs [svc]
  (->> (protos/vs-metadata svc {})
       (map (juxt :url :version))
       (filter (comp vsac-url? first))
       set))

(deftest ^:live vsac-catalogue-parity
  (testing "both engines collapse the duplicate-laden package to the same VSAC catalogue"
    (let [mem-vs (vsac-vs *mem*)
          sql-vs (vsac-vs *sql*)]
      (is (pos? (count mem-vs)) "expected a non-empty VSAC catalogue")
      (is (= mem-vs sql-vs)
          (str "Engines disagree on VSAC ValueSet catalogue. "
               "in-memory only: " (pr-str (take 5 (set/difference mem-vs sql-vs)))
               "; sqlite only: "  (pr-str (take 5 (set/difference sql-vs mem-vs))))))))

(defn- expansion
  ([svc url] (expansion svc url nil))
  ([svc url params]
   (let [r (hades/expand svc (merge {:url url :count 1000000} params))]
     {:total (:total r)
      :codes (set (map (juxt :system :code) (:concepts r)))})))

(deftest ^:live vsac-expansion-parity-sampled
  (testing "both engines expand a sample of VSAC ValueSets to identical totals and concept sets"
    (let [urls    (->> (vsac-vs *mem*) (map first) sort (take-nth 100) (take 50))
          results (for [u urls]
                    (let [a (expansion *mem* u)
                          b (expansion *sql* u)]
                      {:url u :a a :b b
                       :match (and (= (:total a) (:total b))
                                   (= (:codes a) (:codes b)))}))
          nonempty (filter #(pos? (or (get-in % [:a :total]) 0)) results)
          mismatches (remove :match results)]
      (is (pos? (count urls)) "expected VSAC ValueSets to sample")
      (is (>= (count nonempty) 5)
          (str "guard against a vacuous pass: fewer than 5 non-empty expansions "
               "in the sample (" (count nonempty) "/" (count results) "). "
               "Are SNOMED/LOINC fixtures loaded?"))
      (is (empty? mismatches)
          (str "Engines disagree on expansion for: "
               (pr-str (for [m (take 10 mismatches)]
                         [(:url m) :mem-total (get-in m [:a :total])
                          :sql-total (get-in m [:b :total])])))))))

(deftest ^:live vsac-enrichment-parity-sampled
  (testing "both engines agree under activeOnly / properties — the lazy
            enrichment path (`expand-include-concepts`), which the
            stored-extensional fast path deliberately does not serve"
    (let [urls (->> (vsac-vs *mem*) (map first) sort (take-nth 200) (take 12))]
      (doseq [params [{:activeOnly true} {:properties ["inactive"]}]]
        (let [results    (for [u urls]
                           (let [a (expansion *mem* u params)
                                 b (expansion *sql* u params)]
                             {:url u :a a :b b
                              :match (and (= (:total a) (:total b))
                                          (= (:codes a) (:codes b)))}))
              ;; `:total` is omitted on this path, so the vacuous-pass
              ;; guard keys off the concept set, not the total.
              nonempty   (filter #(seq (get-in % [:a :codes])) results)
              mismatches (remove :match results)]
          (is (>= (count nonempty) 3)
              (str "guard against a vacuous pass under " (pr-str params)
                   ": fewer than 3 non-empty expansions ("
                   (count nonempty) "/" (count results) ")"))
          (is (empty? mismatches)
              (str "Engines disagree under " (pr-str params) " for: "
                   (pr-str (for [m (take 10 mismatches)]
                             [(:url m) :mem-codes (count (get-in m [:a :codes]))
                              :sql-codes (count (get-in m [:b :codes]))])))))))))
