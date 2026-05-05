(ns com.eldrix.hades.sqlite-vs-inmemory-bench
  "One-shot comparison of the SQLite container vs the in-memory provider
  on the FHIR R4 standard terminology bundles.

  Provision (once):

    mkdir -p .hades/fhir-r4-defs && cd .hades/fhir-r4-defs
    curl -sL -o defs.zip http://hl7.org/fhir/R4/definitions.json.zip
    unzip -q -o defs.zip
    mkdir -p ../fhir-r4-terminology
    cp conceptmaps.json v2-tables.json v3-codesystems.json valuesets.json ../fhir-r4-terminology/

  Run:

    clj -X:bench :nses '[com.eldrix.hades.sqlite-vs-inmemory-bench]'
    ;; or in REPL:
    (require 'com.eldrix.hades.sqlite-vs-inmemory-bench :reload)
    (com.eldrix.hades.sqlite-vs-inmemory-bench/run!)"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.index.sqlite :as sqlite-index]
            [com.eldrix.hades.impl.loaders.fhir :as fhir-loader]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.db :as db]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider])
  (:import (java.io File)))

(def ^:private terminology-dir ".hades/fhir-r4-terminology")
(def ^:private out-db ".hades/fhir-r4-terminology.db")

(defn- now-ms [] (System/currentTimeMillis))

(defmacro time-ms [& body]
  `(let [t# (now-ms)
         r# (do ~@body)]
     {:result r# :ms (- (now-ms) t#)}))

(defn- file-size-mb [^String p]
  (let [^File f (io/file p)]
    (when (.exists f)
      (double (/ (.length f) 1024 1024)))))

(defn- delete-quietly [^String p]
  (let [^File f (io/file p)] (when (.exists f) (.delete f))))

;; ---------------------------------------------------------------------------
;; Pick a representative payload for query benches.
;; ---------------------------------------------------------------------------

;; FHIR core CodeSystem with a small, well-known set of codes.
(def ^:private bench-system "http://hl7.org/fhir/administrative-gender")
(def ^:private bench-codes ["male" "female" "other" "unknown"])

(defn- exercise-cs [cs-impl]
  (doseq [code bench-codes]
    (protos/cs-lookup cs-impl {:system bench-system :code code})
    (protos/cs-validate-code cs-impl {:system bench-system :code code})))

(defn- exercise-find-matches [cs-impl]
  (protos/cs-find-matches cs-impl
    {:system bench-system :text "female" :max-hits 5}))

;; ---------------------------------------------------------------------------
;; SQLite path
;; ---------------------------------------------------------------------------

(defn- build-sqlite! []
  (delete-quietly out-db)
  (let [load (time-ms (vec (fhir-loader/load-paths terminology-dir)))
        data (:result load)
        build (time-ms (sqlite-index/build! out-db data {:loader-type "fhir-r4-defs"}))
        index (time-ms (sqlite-index/index! out-db))
        size (file-size-mb out-db)
        kinds (frequencies (map :type data))]
    {:load-ms (:ms load)
     :build-ms (:ms build)
     :index-ms (:ms index)
     :db-size-mb size
     :resource-counts (select-keys kinds [:codesystem-meta :valueset :conceptmap :concept :skipped])}))

(defn- bench-sqlite []
  (let [build (build-sqlite!)
        open (time-ms (sqlite-provider/open-providers out-db))
        {:keys [codesystem valueset conceptmap datasource]} (:result open)]
    (try
      (let [register (time-ms (composite/from-providers
                                (filterv some? [codesystem valueset conceptmap])))
            svc      (:result register)
            cs       (composite/find-codesystem svc bench-system)
            _warm    (time-ms (exercise-cs cs))
            queries  (time-ms (dotimes [_ 100] (exercise-cs cs)))
            fts      (time-ms (dotimes [_ 50] (exercise-find-matches cs)))]
        (merge build
               {:open-ms (:ms open)
                :register-ms (:ms register)
                :warm-100x-ms (:ms queries)
                :fts-50x-ms (:ms fts)
                :representative-cs-found? (some? cs)}))
      (finally
        (db/close! datasource)))))

;; ---------------------------------------------------------------------------
;; In-memory path
;; ---------------------------------------------------------------------------

(defn- bench-inmemory []
  (let [load (time-ms (vec (fhir-loader/load-paths terminology-dir)))
        data (:result load)
        register (time-ms
                   (let [{:keys [providers supplements]} (load-fhir/build-from-fhir-data data)]
                     (composite/from-providers providers {:supplements supplements})))
        svc (:result register)
        cs (composite/find-codesystem svc bench-system)
        warm (time-ms (exercise-cs cs))
        queries (time-ms (dotimes [_ 100] (exercise-cs cs)))
        fts (time-ms (dotimes [_ 50] (exercise-find-matches cs)))
        jvm-mb (let [rt (Runtime/getRuntime)]
                 (do (System/gc) (Thread/sleep 100)
                     (double (/ (- (.totalMemory rt) (.freeMemory rt)) 1024 1024))))]
    {:load-ms (:ms load)
     :register-ms (:ms register)
     :warm-100x-ms (:ms queries)
     :fts-50x-ms (:ms fts)
     :jvm-heap-used-mb jvm-mb
     :representative-cs-found? (some? cs)}))

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(defn run!
  "Run both benches and pretty-print a comparison."
  [& _]
  (println "\n--- SQLite container ---")
  (let [s (bench-sqlite)]
    (pp/pprint s)
    (println "\n--- In-memory provider ---")
    (let [m (bench-inmemory)]
      (pp/pprint m)
      (println "\n--- Headline ---")
      (println (format "SQLite build (load+index): %d ms, file: %.1f MB"
                       (+ (:load-ms s) (:build-ms s))
                       (or (:db-size-mb s) 0.0)))
      (println (format "SQLite open + register:    %d ms"
                       (+ (:open-ms s) (:register-ms s))))
      (println (format "In-mem load + register:    %d ms, heap delta: ~%.1f MB"
                       (+ (:load-ms m) (:register-ms m))
                       (or (:jvm-heap-used-mb m) 0.0)))
      (println (format "Hot lookups (100 cycles):  SQLite %d ms vs in-mem %d ms"
                       (:warm-100x-ms s) (:warm-100x-ms m)))
      (println (format "FTS / text find-matches:   SQLite %d ms vs in-mem %d ms (50 calls)"
                       (:fts-50x-ms s) (:fts-50x-ms m))))))
