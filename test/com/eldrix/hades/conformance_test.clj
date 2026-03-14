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

(def ^:private default-test-data-dir ".hades/tx-ecosystem")
(def ^:private default-results-dir "test/resources/conformance")
(def ^:private test-data-repo "https://github.com/HL7/fhir-tx-ecosystem-ig.git")

;; ---------------------------------------------------------------------------
;; Test data management
;; ---------------------------------------------------------------------------

(defn- ensure-test-data!
  "Download the tx-ecosystem test data if not already present.
  Performs a shallow clone to minimize download size."
  [test-data-dir]
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

(defn- free-port
  "Find a free port on localhost."
  []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

(defn- start-test-server!
  "Start a Hades server with Hermes on a random port.
  Returns {:keys [server port svc]}."
  [hermes-db-path]
  (let [port (free-port)
        svc (hermes/open hermes-db-path)
        snomed-svc (snomed/->HermesService svc)
        srv (server/make-server svc {:port port})]
    (registry/register-codesystem "http://snomed.info/sct" snomed-svc)
    (registry/register-codesystem "sct" snomed-svc)
    (registry/register-valueset "http://snomed.info/sct" snomed-svc)
    (registry/register-valueset "sct" snomed-svc)
    (.start srv)
    {:server srv :port port :svc svc}))

(defn- stop-test-server!
  "Stop the test server and close the Hermes service."
  [{:keys [server svc]}]
  (when server (.stop server))
  (when svc (.close svc)))

;; ---------------------------------------------------------------------------
;; TestReport parsing
;; ---------------------------------------------------------------------------

(defn- action-result
  "Extract the result code from a test action (operation or assert)."
  ^String [^TestReport$TestActionComponent action]
  (cond
    (.hasOperation action)
    (some-> (.getOperation action) (.getResult) (.toCode))
    (.hasAssert action)
    (some-> (.getAssert action) (.getResult) (.toCode))
    :else nil))

(defn- action-message
  "Extract the message from a test action."
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
  "Parse a single TestReportTestComponent into a Clojure map."
  [^TestReport$TestReportTestComponent test-comp]
  (let [actions (.getAction test-comp)
        results (mapv action-result actions)
        failed? (some #(contains? #{"fail" "error"} %) results)]
    {:name (.getName test-comp)
     :description (when (.hasDescription test-comp) (.getDescription test-comp))
     :status (if failed? "fail" "pass")
     :actions (mapv (fn [^TestReport$TestActionComponent a]
                      {:result (action-result a)
                       :message (action-message a)})
                    actions)}))

(defn parse-test-report
  "Parse a FHIR TestReport into a Clojure map with:
  :total, :passed, :failed — counts
  :tests — vec of {:name :description :status :actions}"
  [^TestReport report]
  (when report
    (let [tests (mapv parse-test-component (.getTest report))
          passed (count (filter #(= "pass" (:status %)) tests))
          failed (count (filter #(= "fail" (:status %)) tests))]
      {:total (count tests)
       :passed passed
       :failed failed
       :tests tests})))

;; ---------------------------------------------------------------------------
;; Core runner
;; ---------------------------------------------------------------------------

(defn load-externals
  "Load the externals JSON file as a JsonObject."
  [path]
  (JsonParser/parseObject (io/file path)))

(defn run-conformance-tests
  "Run the tx-ecosystem conformance tests.
  Options:
    :server-url     — URL of the running server (e.g. http://localhost:8080/fhir)
    :test-data-path — path to tx-ecosystem test data folder
    :externals-path — path to messages-hades.json (optional)
    :modes          — set of mode strings (default #{\"general\" \"snomed\"})
    :filter         — test name filter pattern (optional)"
  [{:keys [server-url test-data-path externals-path modes filter]
    :or {modes #{"general" "snomed"}}}]
  (let [folder (.getCanonicalPath (io/file test-data-path "tests"))
        loader (TxTester$InternalTxLoader. folder)
        externals (when externals-path (load-externals externals-path))
        tester (TxTester. loader server-url true externals "4.0.1")]
    (.execute tester (set modes) filter)
    (parse-test-report (.getTestReport tester))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- timestamp []
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss")
           (LocalDateTime/ofInstant (Instant/now) (ZoneId/systemDefault))))

(defn- results->report
  "Convert results to a serializable report with metadata."
  [results]
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

(defn- load-report
  "Load a conformance report from a JSON file."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (json/read-str (slurp f) :key-fn keyword))))

(defn- diff-reports
  "Compare two reports, returning {:gained [...] :lost [...]} test names."
  [old-report new-report]
  (let [old-passed (set (map :name (filter #(= "pass" (:status %)) (:tests old-report))))
        new-passed (set (map :name (filter #(= "pass" (:status %)) (:tests new-report))))]
    {:gained (sort (clojure.set/difference new-passed old-passed))
     :lost   (sort (clojure.set/difference old-passed new-passed))}))

(defn print-summary
  "Print a human-readable summary of conformance test results.
  When prev-report is provided, also prints gained/lost diff."
  ([results] (print-summary results nil))
  ([{:keys [total passed failed tests] :as _results} prev-report]
   (println (format "\n=== Conformance Test Results (%s) ===" (timestamp)))
   (println (format "Total: %d  Passed: %d  Failed: %d" total passed failed))
   (when prev-report
     (let [new-report (results->report _results)
           {:keys [gained lost]} (diff-reports prev-report new-report)]
       (when (seq gained)
         (println (format "\n  GAINED (%d):" (count gained)))
         (doseq [n gained] (println (format "    + %s" n))))
       (when (seq lost)
         (println (format "\n  LOST (%d):" (count lost)))
         (doseq [n lost]
           (let [test (first (filter #(= n (:name %)) tests))
                 msg (->> (:actions test)
                          (filter #(contains? #{"fail" "error"} (:result %)))
                          (keep :message)
                          first)]
             (println (format "    - %s" n))
             (when msg
               (println (format "      %s" (subs msg 0 (min 200 (count msg)))))))))))
   (when (pos? failed)
     (println "\nFailed tests:")
     (doseq [{:keys [name actions]} (filter #(= "fail" (:status %)) tests)]
       (println (format "  FAIL: %s" name))
       (doseq [{:keys [result message]} actions
               :when (contains? #{"fail" "error"} result)]
         (when message
           (println (format "        %s" message))))))
   (println)))

(defn save-results!
  "Save detailed conformance results: timestamped archive + latest.json.
  Returns the report map."
  [results results-dir]
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
  "Load the most recent conformance report from a results directory."
  [results-dir]
  (load-report (str (io/file results-dir "latest.json"))))

(defn load-baseline
  "Load the conformance baseline from test resources."
  []
  (when-let [resource (io/resource "conformance-baseline.json")]
    (json/read-str (slurp resource) :key-fn keyword)))

(defn save-baseline!
  "Save conformance results as the new baseline."
  [results path]
  (spit path (json/write-str {:total (:total results)
                               :passed (:passed results)
                               :failed (:failed results)}
                              :indent true)))

;; ---------------------------------------------------------------------------
;; exec-fn entry point (for -X alias)
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
    :update-baseline — when true, updates the baseline file (default true)

  Examples:
    clj -X:conformance :snomed '\"/path/to/snomed.db\"' :output-dir '\"test/resources/conformance\"'
    clj -X:conformance :snomed '\"/path/to/snomed.db\"' :output-dir '\"reports\"' :update-baseline false"
  [{:keys [snomed output-dir update-baseline]
    :or   {update-baseline true}
    :as   args}]
  (when-not (s/valid? ::run-args args)
    (println "Invalid arguments:")
    (println (s/explain-str ::run-args args))
    (println "\nUsage: clj -X:conformance :snomed '\"path/to/snomed.db\"' :output-dir '\"test/resources/conformance\"'")
    (System/exit 1))
  (let [_ (ensure-test-data! default-test-data-dir)
        {:keys [port] :as test-server} (start-test-server! snomed)]
    (try
      (let [externals-path (let [f (io/file "resources/messages-hades.json")]
                             (when (.exists f) (str f)))
            results (run-conformance-tests
                      {:server-url     (str "http://localhost:" port "/fhir")
                       :test-data-path default-test-data-dir
                       :externals-path externals-path})
            prev-report (load-latest output-dir)
            baseline (load-baseline)]
        (print-summary results prev-report)
        (save-results! results output-dir)
        (println (format "Baseline: %d passed" (or (:passed baseline) 0)))
        (when (and baseline (pos? (:passed baseline))
                   (< (:passed results) (:passed baseline)))
          (println "REGRESSION: pass count decreased!")
          (System/exit 1))
        (when update-baseline
          (save-baseline! results "test/resources/conformance-baseline.json")
          (println "Baseline updated.")))
      (finally
        (stop-test-server! test-server)))))

