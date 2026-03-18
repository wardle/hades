(ns com.eldrix.hades.conformance-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.eldrix.hades.registry :as registry]
            [com.eldrix.hades.server :as server]
            [com.eldrix.hades.snomed :as snomed]
            [com.eldrix.hermes.core :as hermes])
  (:import (java.net ServerSocket)
           (java.time Instant LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)
           (org.hl7.fhir.r5.model TestReport TestReport$TestReportTestComponent
                                   TestReport$TestActionComponent)
           (org.hl7.fhir.utilities.json.parser JsonParser)
           (org.hl7.fhir.validation.special TxTester TxTester$InternalTxLoader)))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private test-data-dir ".hades/tx-ecosystem")
(def ^:private test-data-repo "https://github.com/HL7/fhir-tx-ecosystem-ig.git")
(def ^:private baseline-path "test/resources/conformance-baseline.json")
(def ^:private results-dir "test/resources/conformance")
(def ^:private externals-path "resources/messages-hades.json")

;; ---------------------------------------------------------------------------
;; Server state
;; ---------------------------------------------------------------------------

(defonce ^:private state (atom nil))

;; ---------------------------------------------------------------------------
;; Test data management
;; ---------------------------------------------------------------------------

(defn ensure-test-data!
  "Download the tx-ecosystem test data if not already present.
  Performs a shallow clone to minimize download size."
  []
  (let [dir (io/file test-data-dir)]
    (when-not (.exists (io/file dir "tests" "test-cases.json"))
      (log/info "Downloading tx-ecosystem test data to" test-data-dir)
      (let [pb (ProcessBuilder. ["git" "clone" "--depth" "1"
                                 test-data-repo (str dir)])
            process (-> pb (.inheritIO) (.start))]
        (when-not (zero? (.waitFor process))
          (throw (ex-info "Failed to clone tx-ecosystem test data"
                          {:repo test-data-repo :path test-data-dir})))))
    test-data-dir))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defn- free-port []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

(defn start!
  "Start a Hades server with Hermes. Stores state for subsequent calls.
  Returns the server URL."
  [snomed-db-path & {:keys [port] :or {port 0}}]
  (when @state
    (throw (ex-info "Server already running. Call (stop!) first." {})))
  (ensure-test-data!)
  (let [port (if (zero? port) (free-port) port)
        svc (hermes/open snomed-db-path)
        snomed-svc (snomed/->HermesService svc)
        srv (server/make-server svc {:port port})]
    (registry/register-codesystem "http://snomed.info/sct" snomed-svc)
    (registry/register-codesystem "sct" snomed-svc)
    (registry/register-valueset "http://snomed.info/sct" snomed-svc)
    (registry/register-valueset "sct" snomed-svc)
    (.start srv)
    (let [url (str "http://localhost:" port "/fhir")]
      (reset! state {:server srv :port port :svc svc
                     :snomed-db-path snomed-db-path :url url})
      (println (format "Server started: %s" url))
      url)))

(defn stop!
  "Stop the running test server and close Hermes."
  []
  (when-let [{:keys [server svc]} @state]
    (when server (.stop server))
    (when svc (.close svc))
    (reset! state nil)
    (println "Server stopped.")))

(defn restart!
  "Stop, reload Hades namespaces, and restart the server."
  []
  (let [{:keys [snomed-db-path port]} @state]
    (when-not snomed-db-path
      (throw (ex-info "No server to restart. Call (start! path) first." {})))
    (stop!)
    (doseq [ns-sym '[com.eldrix.hades.protocols
                     com.eldrix.hades.fhir
                     com.eldrix.hades.snomed
                     com.eldrix.hades.compose
                     com.eldrix.hades.fhir-codesystem
                     com.eldrix.hades.fhir-valueset
                     com.eldrix.hades.registry
                     com.eldrix.hades.server]]
      (require ns-sym :reload))
    (start! snomed-db-path :port port)))

(defn server-url
  "Return the URL of the running server, or nil."
  []
  (:url @state))

;; ---------------------------------------------------------------------------
;; TestReport parsing
;; ---------------------------------------------------------------------------

(defn- action-result
  ^String [^TestReport$TestActionComponent action]
  (cond
    (.hasOperation action)
    (some-> (.getOperation action) (.getResult) (.toCode))
    (.hasAssert action)
    (some-> (.getAssert action) (.getResult) (.toCode))
    :else nil))

(defn- action-message
  [^TestReport$TestActionComponent action]
  (cond
    (.hasOperation action)
    (when (.hasMessage (.getOperation action))
      (.getMessage (.getOperation action)))
    (.hasAssert action)
    (when (.hasMessage (.getAssert action))
      (.getMessage (.getAssert action)))
    :else nil))

(defn- parse-test-component
  [^TestReport$TestReportTestComponent test-comp]
  (let [actions (.getAction test-comp)
        results (mapv action-result actions)
        failed? (some #(contains? #{"fail" "error"} %) results)
        skipped? (and (not failed?) (every? #(= "skip" %) results))]
    {:name (.getName test-comp)
     :description (when (.hasDescription test-comp) (.getDescription test-comp))
     :status (cond skipped? "skip" failed? "fail" :else "pass")
     :actions (mapv (fn [^TestReport$TestActionComponent a]
                      {:result (action-result a)
                       :message (action-message a)})
                    actions)}))

(defn- parse-test-report
  [^TestReport report]
  (when report
    (let [tests (mapv parse-test-component (.getTest report))
          passed (count (filter #(= "pass" (:status %)) tests))
          failed (count (filter #(= "fail" (:status %)) tests))
          skipped (count (filter #(= "skip" (:status %)) tests))]
      {:total (count tests)
       :passed passed
       :failed failed
       :skipped skipped
       :tests tests})))

;; ---------------------------------------------------------------------------
;; Test execution
;; ---------------------------------------------------------------------------

(defn- load-externals [path]
  (let [f (io/file path)]
    (when (.exists f)
      (JsonParser/parseObject f))))

(defn run-tests
  "Run conformance tests against the running server.
  Options:
    :filter — test name filter pattern (e.g. \"language\")
    :modes  — set of mode strings (default #{\"general\" \"snomed\" \"flat\"})"
  [& {:keys [filter modes] :or {modes #{"general" "snomed" "flat"}}}]
  (when-not (server-url)
    (throw (ex-info "No server running. Call (start! path) first." {})))
  (let [folder (.getCanonicalPath (io/file test-data-dir "tests"))
        loader (TxTester$InternalTxLoader. folder)
        externals (load-externals externals-path)
        tester (TxTester. loader (server-url) true externals "4.0.1")]
    (.execute tester (set modes) filter)
    (parse-test-report (.getTestReport tester))))

;; ---------------------------------------------------------------------------
;; Result processing — pure functions on results data
;; ---------------------------------------------------------------------------

(defn- test-suite
  "Extract suite name from a test name like \"suite/test-name\"."
  [test-name]
  (let [idx (.indexOf ^String test-name "/")]
    (if (pos? idx)
      (subs test-name 0 idx)
      test-name)))

(defn suite-summary
  "Group results by suite, showing pass/fail counts per suite.
  Excludes suites where all tests were skipped."
  [results]
  (->> (:tests results)
       (remove #(= "skip" (:status %)))
       (group-by (comp test-suite :name))
       (map (fn [[suite tests]]
              (let [passed (count (filter #(= "pass" (:status %)) tests))
                    total (count tests)]
                {:suite suite :passed passed :total total})))
       (sort-by (juxt (comp - :passed) :suite))))

(defn print-suites
  "Print a per-suite summary table."
  [results]
  (let [suites (suite-summary results)
        run (- (:total results) (or (:skipped results) 0))]
    (println (format "\n%-30s %s" "Suite" "Passed/Total"))
    (println (apply str (repeat 45 "-")))
    (doseq [{:keys [suite passed total]} suites]
      (println (format "%-30s %d/%d" suite passed total)))
    (println (format "%-30s %d/%d" "TOTAL" (:passed results) run))))

(defn failures
  "Return only the failed tests from results. Optionally filter by suite."
  ([results]
   (filter #(= "fail" (:status %)) (:tests results)))
  ([results suite]
   (filter #(and (= "fail" (:status %))
                 (= suite (test-suite (:name %))))
           (:tests results))))

(defn diff
  "Compare two results, returning {:gained [...] :lost [...]} test names.
  Ignores skipped tests in both results."
  [old-results new-results]
  (let [non-skip (fn [results] (remove #(= "skip" (:status %)) (:tests results)))
        old-passed (set (map :name (filter #(= "pass" (:status %)) (non-skip old-results))))
        new-passed (set (map :name (filter #(= "pass" (:status %)) (non-skip new-results))))]
    {:gained (sort (clojure.set/difference new-passed old-passed))
     :lost   (sort (clojure.set/difference old-passed new-passed))}))

(defn print-diff
  "Print gained/lost tests between two results."
  [old-results new-results]
  (let [{:keys [gained lost]} (diff old-results new-results)]
    (println (format "\nPassed: %d → %d" (:passed old-results) (:passed new-results)))
    (when (seq gained)
      (println (format "\n  GAINED (%d):" (count gained)))
      (doseq [n gained] (println (format "    + %s" n))))
    (when (seq lost)
      (println (format "\n  LOST (%d):" (count lost)))
      (doseq [n lost]
        (let [test (first (filter #(= n (:name %)) (:tests new-results)))
              msg (->> (:actions test)
                       (filter #(contains? #{"fail" "error"} (:result %)))
                       (keep :message)
                       first)]
          (println (format "    - %s" n))
          (when msg
            (println (format "      %s" (subs msg 0 (min 200 (count msg)))))))))
    (when (and (empty? gained) (empty? lost))
      (println "  No change."))))

(defn print-failures
  "Print failed tests, optionally filtered by suite."
  ([results] (print-failures results nil))
  ([results suite]
   (let [failed (if suite (failures results suite) (failures results))]
     (println (format "\nFailed: %d" (count failed)))
     (doseq [{:keys [name actions]} failed]
       (println (format "  FAIL: %s" name))
       (doseq [{:keys [result message]} actions
               :when (contains? #{"fail" "error"} result)]
         (when message
           (println (format "        %s" message))))))))

;; ---------------------------------------------------------------------------
;; Persistence
;; ---------------------------------------------------------------------------

(defn- timestamp []
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss")
           (LocalDateTime/ofInstant (Instant/now) (ZoneId/systemDefault))))

(defn- results->report [results]
  {:timestamp (str (Instant/now))
   :total     (:total results)
   :passed    (:passed results)
   :failed    (:failed results)
   :tests     (mapv (fn [{:keys [name status actions]}]
                      {:name    name
                       :status  status
                       :actions (mapv (fn [{:keys [result message]}]
                                       (cond-> {:result result}
                                         message (assoc :message message)))
                                     actions)})
                    (:tests results))})

(defn save-results!
  "Save results: timestamped archive + latest.json. Returns the report map."
  [results]
  (let [dir (io/file results-dir)]
    (.mkdirs dir)
    (let [report (results->report results)
          report-json (json/write-str report :indent true)
          ts-path (str (io/file dir (str (timestamp) ".json")))
          latest-path (str (io/file dir "latest.json"))]
      (spit ts-path report-json)
      (spit latest-path report-json)
      (println (format "Results saved: %s" ts-path))
      report)))

(defn load-latest
  "Load the most recent conformance results."
  []
  (let [f (io/file results-dir "latest.json")]
    (when (.exists f)
      (json/read-str (slurp f) :key-fn keyword))))

(defn load-baseline
  "Load the conformance baseline."
  []
  (let [f (io/file baseline-path)]
    (when (.exists f)
      (json/read-str (slurp f) :key-fn keyword))))

(defn save-baseline!
  "Save results as the new baseline."
  [results]
  (let [skipped (or (:skipped results) 0)]
    (spit baseline-path (json/write-str {:total   (:total results)
                                          :passed  (:passed results)
                                          :failed  (:failed results)
                                          :skipped skipped}
                                         :indent true))
    (println (format "Baseline updated: %d/%d passed." (:passed results) (- (:total results) skipped)))))

;; ---------------------------------------------------------------------------
;; exec-fn entry point (for clj -X:conformance)
;; ---------------------------------------------------------------------------

(s/def ::snomed (s/and string? #(.exists (io/file %))))
(s/def ::output-dir string?)
(s/def ::update-baseline boolean?)
(s/def ::run-args (s/keys :req-un [::snomed ::output-dir]
                          :opt-un [::update-baseline]))

(defn run
  "Run conformance tests. Intended as an exec-fn for clj -X:conformance.

  Required:
    :snomed     — path to Hermes snomed.db
    :output-dir — directory for timestamped result files

  Optional:
    :update-baseline — when true, updates the baseline file (default false)

  Examples:
    clj -X:conformance :snomed '\"path/to/snomed.db\"' :output-dir '\"test/resources/conformance\"'
    clj -X:conformance :snomed '\"path/to/snomed.db\"' :output-dir '\"test/resources/conformance\"' :update-baseline true"
  [{:keys [snomed output-dir update-baseline]
    :or   {update-baseline false}
    :as   args}]
  (when-not (s/valid? ::run-args args)
    (println "Invalid arguments:")
    (println (s/explain-str ::run-args args))
    (println "\nUsage: clj -X:conformance :snomed '\"path/to/snomed.db\"' :output-dir '\"test/resources/conformance\"'")
    (System/exit 1))
  (start! snomed)
  (try
    (let [results (run-tests)
          prev (load-latest)
          baseline (load-baseline)]
      (print-suites results)
      (when prev (print-diff prev results))
      (save-results! results)
      (when baseline
        (println (format "Baseline: %d passed" (:passed baseline)))
        (when (< (:passed results) (:passed baseline))
          (println "REGRESSION: pass count decreased!")
          (System/exit 1)))
      (when update-baseline
        (save-baseline! results)))
    (finally
      (stop!))))
