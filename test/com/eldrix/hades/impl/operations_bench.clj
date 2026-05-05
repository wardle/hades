(ns com.eldrix.hades.impl.operations-bench
  "Criterium micro-benchmarks for Hades' FHIR terminology operations.

  Measurements bypass HTTP parsing and wire serialisation so changes in
  domain logic register cleanly. Each entry in `operations` is a
  `{:id :fn}` map; `:fn` is a one-arg function taking the single Hades
  service opened at the top of the deftest. The service is built from
  the pinned SNOMED + LOINC fixtures, so thunks just choose the system
  URL they hit (composite dispatches to the right provider).

  Run via `clj -M:bench`. Provision the pinned fixtures per CLAUDE.md;
  `hades/open` raises file-not-found if absent."
  (:require [clojure.test :refer [deftest]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.compose :as compose]
            [criterium.core :as crit]))

(set! *warn-on-reflection* true)

(def ^:private snomed-uri "http://snomed.info/sct")
(def ^:private loinc-uri  "http://loinc.org")

;; ─── Well-known SNOMED anchors ─────────────────────────────────────────────

(def ^:private diabetes-mellitus  73211009)
(def ^:private type-2-dm          44054006)
(def ^:private disease            64572001)
(def ^:private influenza          6142004)
(def ^:private clinical-finding   404684003)
(def ^:private asthma             195967001)
(def ^:private multiple-sclerosis 24700007)
(def ^:private procedure          71388002)

(defn- expand-url [suffix] (str snomed-uri "?fhir_vs=" suffix))

;; ─── Benchmark catalogue ───────────────────────────────────────────────────

(def operations
  [;; --- $lookup -----------------------------------------------------------
   {:id :lookup/minimal
    :fn (fn [svc]
          (hades/lookup svc {:system snomed-uri :code (str multiple-sclerosis)}))}
   {:id :lookup/full
    :fn (fn [svc]
          (hades/lookup svc {:system snomed-uri :code (str multiple-sclerosis)
                             :property ["designation" "parent" "child" "definition"]}))}

   ;; --- $validate-code ----------------------------------------------------
   {:id :validate-code/plain
    :fn (fn [svc]
          (hades/validate-code svc {:system snomed-uri :code (str diabetes-mellitus)}))}
   {:id :validate-code/display
    :fn (fn [svc]
          (hades/validate-code svc {:system snomed-uri :code (str diabetes-mellitus)
                                    :display "Diabetes mellitus"}))}
   {:id :validate-code/against-valueset
    :fn (fn [svc]
          (hades/validate-code svc
            {:url    (expand-url (str "ecl/<<" disease))
             :system snomed-uri :code (str diabetes-mellitus)}))}

   ;; --- $subsumes — all four branches -------------------------------------
   {:id :subsumes/equivalent
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str diabetes-mellitus)
                               :systemB snomed-uri :codeB (str diabetes-mellitus)}))}
   {:id :subsumes/subsumes
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str disease)
                               :systemB snomed-uri :codeB (str influenza)}))}
   {:id :subsumes/subsumed-by
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str type-2-dm)
                               :systemB snomed-uri :codeB (str diabetes-mellitus)}))}
   ;; Historical-equivalence fallback — exercises the unrelated-pair branch
   ;; that walks the historical-association refsets.
   {:id :subsumes/unrelated
    :fn (fn [svc]
          (hades/subsumes svc {:systemA snomed-uri :codeA (str asthma)
                               :systemB snomed-uri :codeB (str influenza)}))}

   ;; --- $translate --------------------------------------------------------
   ;; 56485000 is a retired concept with a SAME-AS pointing to 398390002 —
   ;; exercises the real historical-association lookup rather than the
   ;; no-hits fast path. Uses the REPLACED-BY historical refset
   ;; (900000000000526001) to match the implicit ConceptMap shape that
   ;; tx-benchmark's CM01 sends.
   {:id :translate/historical
    :fn (fn [svc]
          (hades/translate svc
            {:url    (str snomed-uri "?fhir_cm=900000000000526001")
             :system snomed-uri :code "56485000"
             :target snomed-uri}))}

   ;; --- $expand via implicit-VS URLs --------------------------------------
   {:id :expand/ecl-small
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" diabetes-mellitus)) :count 50}))}
   {:id :expand/ecl-medium
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" asthma)) :count 100}))}
   {:id :expand/ecl-large
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding)) :count 100}))}
   {:id :expand/isa
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "isa/" clinical-finding)) :count 100}))}
   {:id :expand/text-filter-rare
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding))
                             :filter "asthma" :count 20}))}
   {:id :expand/text-filter-common
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding))
                             :filter "pain" :count 20}))}
   {:id :expand/paged
    :fn (fn [svc]
          (hades/expand svc {:url (expand-url (str "ecl/<<" clinical-finding))
                             :offset 1000 :count 20}))}
   ;; Mirrors tx-benchmark's EX03 — implicit "all of SNOMED" VS + text filter.
   ;; The shape that regressed from p95 128 ms to 11 s at 50 VUs in the
   ;; preflight comparison.
   {:id :expand/all-snomed-text-filter
    :fn (fn [svc]
          (hades/expand svc {:url (str snomed-uri "?fhir_vs")
                             :filter "diabetes" :count 100}))}

   ;; --- $expand via hand-built compose (bypasses URL parsing) -------------
   {:id :compose/is-a
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}]}]}
            {:count 100}))}
   ;; Mirrors tx-benchmark's EX05 — focus concept + attribute refinement.
   {:id :compose/refinement
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}
                                   {"property" "363698007" "op" "=" "value" "89187006"}]}]}
            {:count 50}))}
   {:id :compose/multi-include
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}]}
                        {"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str procedure)}]}]}
            {:count 100}))}
   {:id :compose/include-exclude
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}]}]
             "exclude" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str asthma)}]}]}
            {:count 100}))}
   ;; Mirrors tx-benchmark EX07 — system-only include + text filter, no ECL.
   {:id :compose/all-snomed-text-filter
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri}]}
            {:filter "pain" :count 200}))}
   ;; Mirrors tx-benchmark EX08 — is-a + attribute refinement + text filter.
   {:id :compose/refinement-text-filter
    :fn (fn [svc]
          (compose/expand-compose svc
            {"include" [{"system" snomed-uri
                         "filter" [{"property" "concept" "op" "is-a" "value" (str clinical-finding)}
                                   {"property" "116676008" "op" "=" "value" "72704001"}]}]}
            {:filter "fracture" :count 50}))}

   ;; --- $find-matches via SQLite LOINC ------------------------------------
   ;;
   ;; `cs-find-matches` for SQLite catalogues fans out per surviving row to
   ;; fetch designations (when `displayLanguage` is set) and properties
   ;; (when a regex post-filter targets a property). LOINC 2.81 carries
   ;; ~66 multi-language designation rows per code, so `displayLanguage`
   ;; makes per-row designation reads dominate the cost profile (the 52%
   ;; slice flagged in todo.txt). These benches isolate that hot path.
   {:id :find-matches/loinc-text-no-lang
    :fn (fn [svc]
          (hades/find-matches svc
            {:system loinc-uri :text "glucose" :max-hits 50}))}
   {:id :find-matches/loinc-text-with-lang
    :fn (fn [svc]
          (hades/find-matches svc
            {:system loinc-uri :text "glucose" :max-hits 50 :displayLanguage "en"}))}
   {:id :find-matches/loinc-text-large-with-lang
    :fn (fn [svc]
          (hades/find-matches svc
            {:system loinc-uri :text "glucose" :max-hits 200 :displayLanguage "en"}))}
   {:id :find-matches/loinc-text-small-no-lang
    :fn (fn [svc]
          (hades/find-matches svc
            {:system loinc-uri :text "glucose" :max-hits 10}))}])

;; ─── Test entry point (clj -M:bench) ──────────────────────────────────────
;;
;; Two modes, picked via JVM system property `hades.bench.mode`:
;;
;;   :quick (default)  full criterium quick-bench — ~5s per entry.
;;   :smoke            criterium quick-bench with `:samples 1`,
;;                     `:warmup-jit-period 0` and a 100ms target
;;                     execution window — fast sanity check (~hundreds
;;                     of ms per entry).
;;
;;   clj -M:bench -J-Dhades.bench.mode=smoke

(def ^:private smoke-opts
  ;; Criterium needs ≥3 samples to compute quartiles/outliers; trim
  ;; warmup and per-sample window to the floor instead.
  [:samples 3
   :warmup-jit-period (* 50 1000000)
   :target-execution-time (* 50 1000000)
   :max-gc-attempts 0])

(defn- bench-mode []
  (keyword (System/getProperty "hades.bench.mode" "quick")))

(defn- run-one [mode f svc]
  (case mode
    :quick (crit/quick-benchmark* (fn [] (f svc)) {})
    :smoke (crit/quick-benchmark* (fn [] (f svc)) (apply hash-map smoke-opts))))

(deftest operations-bench
  (let [svc  (hades/open [fixtures/snomed-db-path fixtures/loinc-db-path])
        mode (bench-mode)]
    (try
      (doseq [{:keys [id fn]} operations]
        (println "\n***" id)
        (crit/report-result (run-one mode fn svc)))
      (finally
        (hades/close svc)))))
