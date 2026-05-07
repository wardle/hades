(ns com.eldrix.hades.tx-bench
  "In-process Criterium benchmarks against the same catalogue tx-benchmark
  loads (SNOMED + LOINC + the three pinned FHIR packages, in-memory).
  Use these for REPL-driven git-bisect of throughput regressions: open
  the service once, quick-bench an operation, `git checkout` and reload,
  re-bench."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [criterium.core :as crit]))

(def ^:dynamic *svc* nil)

(defn- svc-fixture [f]
  (let [svc (hades/open [fixtures/snomed-db-path
                         fixtures/loinc-db-path
                         ".hades/fhir-packages/hl7.terminology.r4-7.0.1/package"
                         ".hades/fhir-packages/hl7.fhir.us.core-6.1.0/package"
                         ".hades/fhir-packages/hl7.fhir.uv.ips-2.0.0/package"])]
    (binding [*svc* svc]
      (try (f) (finally (hades/close svc))))))

(use-fixtures :once svc-fixture)

(def ^:private snomed-uri "http://snomed.info/sct")
(def ^:private loinc-uri  "http://loinc.org")

(defn- bench [label thunk]
  (println "\n***" label)
  (crit/quick-bench (thunk)))

(deftest lk01-snomed-lookup
  (bench :lk01/snomed-lookup
    #(hades/lookup *svc* {:system snomed-uri :code "73211009"})))

(deftest lk02-loinc-lookup
  (bench :lk02/loinc-lookup
    #(hades/lookup *svc* {:system loinc-uri :code "8867-4"})))

(deftest vc01-snomed-validate
  (bench :vc01/snomed-validate
    #(hades/validate-code *svc* {:system snomed-uri :code "73211009"})))

(deftest ss01-snomed-subsumes
  (bench :ss01/snomed-subsumes
    #(hades/subsumes *svc* {:systemA snomed-uri :codeA "73211009"
                            :systemB snomed-uri :codeB "44054006"})))
