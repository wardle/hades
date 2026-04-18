(ns com.eldrix.hades.conformance-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
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
(def ^:private snomed-rf2-dir ".hades/tx-ecosystem/tx-source/snomed")
(def ^:private snomed-db-default ".hades/snomed-conformance.db")
(def ^:private snomed-rev-path ".hades/snomed-conformance.rev")
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

(defn- tx-ecosystem-rev
  "Return the current HEAD commit hash of the tx-ecosystem clone, or nil."
  []
  (let [git-dir (io/file test-data-dir ".git")]
    (when (.exists git-dir)
      (let [pb (ProcessBuilder. ["git" "rev-parse" "HEAD"])
            _ (.directory pb (io/file test-data-dir))
            process (.start pb)
            rev (-> (.getInputStream process) slurp str/trim)]
        (when (zero? (.waitFor process))
          rev)))))

(defn build-snomed-db!
  "Build the SNOMED conformance database from the tx-ecosystem RF2 subset.
  Rebuilds if the database is missing or the tx-ecosystem repo has been updated."
  []
  (ensure-test-data!)
  (let [db-file (io/file snomed-db-default)
        rev-file (io/file snomed-rev-path)
        current-rev (tx-ecosystem-rev)
        cached-rev (when (.exists rev-file) (str/trim (slurp rev-file)))
        stale? (or (not (.exists db-file))
                   (not= current-rev cached-rev))]
    (when stale?
      (when (.exists db-file)
        (log/info "SNOMED subset has changed, rebuilding conformance database")
        (run! io/delete-file (reverse (file-seq db-file))))
      (log/info "Building SNOMED conformance database from test subset")
      (hermes/import-snomed (str db-file) [snomed-rf2-dir])
      (hermes/index (str db-file))
      (hermes/compact (str db-file))
      (spit rev-file current-rev)
      (log/info "SNOMED conformance database ready" {:path (str db-file)}))
    (str db-file)))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defn- free-port []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

(defn start!
  "Start a Hades server with Hermes. Stores state for subsequent calls.
  With no arguments, builds the SNOMED database from the tx-ecosystem subset.
  With a path, uses that pre-existing database directly.
  Returns the server URL."
  [& {:keys [snomed port] :or {port 0}}]
  (when @state
    (throw (ex-info "Server already running. Call (stop!) first." {})))
  (let [snomed-db-path (or snomed (build-snomed-db!))
        port (if (zero? port) (free-port) port)
        svc (hermes/open snomed-db-path)
        snomed-svc (snomed/->HermesService svc)
        srv (server/make-server svc {:port port :max-expansion-size 1000})]
    (registry/register-codesystem "http://snomed.info/sct" snomed-svc)
    (registry/register-codesystem "http://snomed.info/sct|*" snomed-svc)
    (registry/register-codesystem "sct" snomed-svc)
    (registry/register-valueset "http://snomed.info/sct" snomed-svc)
    (registry/register-valueset "http://snomed.info/sct|*" snomed-svc)
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
      (throw (ex-info "No server to restart. Call (start!) first." {})))
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
    (start! :snomed snomed-db-path :port port)))

(defn server-url
  "Return the URL of the running server, or nil."
  []
  (:url @state))

;; ---------------------------------------------------------------------------
;; Test case index — loads test-cases.json once for enriching results
;; ---------------------------------------------------------------------------

(defn- load-test-cases
  "Load the test-cases.json index. Returns a map of test-name -> test case info."
  []
  (let [f (io/file test-data-dir "tests" "test-cases.json")]
    (when (.exists f)
      (let [data (json/read-str (slurp f) :key-fn keyword)]
        (into {}
              (for [suite (:suites data)
                    tc (:tests suite)
                    :let [full-name (str (:name suite) "/" (:name tc))]]
                [full-name
                 {:operation (:operation tc)
                  :request   (:request tc)
                  :response  (:response tc)
                  :profile   (:profile tc)
                  :http-code (:http-code tc)
                  :setup     (:setup suite [])}]))))))

(defn- load-test-case-json
  "Load a JSON file from the test data directory."
  [path]
  (let [f (io/file test-data-dir "tests" path)]
    (when (.exists f)
      (try
        (json/read-str (slurp f) :key-fn keyword)
        (catch Exception e
          (log/debug "Failed to parse test JSON" {:path path :error (.getMessage e)})
          nil)))))

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

(defn- parse-action-message
  "Parse a TxTester error message into structured fields.
  Returns the message map with :message and optional :path, :expected, :actual."
  [^String msg]
  (when msg
    (let [base {:message msg}]
      (cond
        ;; "string property values differ at .foo\nExpected:\"x\" for test\nActual:\"y\""
        (str/includes? msg "property values differ at ")
        (let [path (second (re-find #"differ at ([.\w\[\]]+)" msg))
              expected (second (re-find #"Expected:\"(.+?)\"" msg))
              actual (second (re-find #"Actual\s*:\"(.+?)\"" msg))]
          (cond-> (assoc base :path path)
            expected (assoc :expected expected)
            actual (assoc :actual actual)))

        ;; "array item count differs at .foo\nExpected:\"5\" for test\nActual:\"3\""
        (str/includes? msg "array item count differs at ")
        (let [path (second (re-find #"differs at ([.\w\[\]]+)" msg))
              expected (second (re-find #"Expected:\"(.+?)\"" msg))
              actual (second (re-find #"Actual\s*:\"(.+?)\"" msg))]
          (cond-> (assoc base :path path)
            expected (assoc :expected expected)
            actual (assoc :actual actual)))

        ;; "Response Code fail: should be '4xx' but is '200'"
        (str/includes? msg "Response Code fail")
        (let [expected (second (re-find #"should be '(.+?)'" msg))
              actual (second (re-find #"but is '(.+?)'" msg))]
          (cond-> (assoc base :path "HTTP status")
            expected (assoc :expected expected)
            actual (assoc :actual actual)))

        ;; "properties differ at .foo: missing property bar"
        (str/includes? msg "missing property")
        (let [path (second (re-find #"differ at ([.\w\[\]]+)" msg))
              prop (second (re-find #"missing property (\w+)" msg))]
          (cond-> (assoc base :path path)
            prop (assoc :expected (str "property " prop) :actual "missing")))

        ;; "Unexpected Node found in array at '.foo' at index N"
        (str/includes? msg "Unexpected Node")
        (let [path (second (re-find #"at '([^']+)'" msg))
              idx (second (re-find #"at index (\d+)" msg))]
          (cond-> base
            path (assoc :path path)
            idx (assoc :actual (str "unexpected node at index " idx))))

        ;; "The expected item at .foo at index N was not found"
        (str/includes? msg "was not found")
        (let [path (second (re-find #"at ([.\w\[\]]+) at index" msg))]
          (cond-> base
            path (assoc :path path :actual "not found")))

        :else base))))

(defn- test-suite
  "Extract suite name from a test name like \"suite/test-name\"."
  [test-name]
  (let [idx (.indexOf ^String test-name "/")]
    (if (pos? idx)
      (subs test-name 0 idx)
      test-name)))

(defn- parse-test-component
  "Parse a TestReport test component into a rich result map."
  [^TestReport$TestReportTestComponent test-comp test-index]
  (let [name' (.getName test-comp)
        actions (.getAction test-comp)
        results (mapv action-result actions)
        failed? (some #(contains? #{"fail" "error"} %) results)
        skipped? (and (not failed?) (every? #(= "skip" %) results))
        parsed-actions (mapv (fn [^TestReport$TestActionComponent a]
                               (let [result (action-result a)
                                     msg (action-message a)]
                                 (merge {:result result}
                                        (parse-action-message msg))))
                             actions)
        tc-info (get test-index name')]
    (cond-> {:name name'
             :suite (test-suite name')
             :status (cond skipped? "skip" failed? "fail" :else "pass")
             :actions parsed-actions}
      (:operation tc-info) (assoc :operation (:operation tc-info))
      (:http-code tc-info) (assoc :expected-http-code (:http-code tc-info)))))

(defn- enrich-failure
  "For a failed test, load expected response from test data."
  [test-result test-index]
  (if (not= "fail" (:status test-result))
    test-result
    (let [tc-info (get test-index (:name test-result))]
      (cond-> test-result
        (:response tc-info) (assoc :expected-response
                                   (load-test-case-json (:response tc-info)))))))

(defn- parse-test-report
  "Parse a TxTester TestReport into structured results with rich failure data."
  [^TestReport report]
  (when report
    (let [test-index (load-test-cases)
          tests (mapv #(parse-test-component % test-index) (.getTest report))
          tests (mapv #(enrich-failure % test-index) tests)
          passed (count (filter #(= "pass" (:status %)) tests))
          failed (count (filter #(= "fail" (:status %)) tests))
          skipped (count (filter #(= "skip" (:status %)) tests))]
      {:total (count tests)
       :passed passed
       :failed failed
       :skipped skipped
       :timestamp (str (Instant/now))
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
;; Result querying — pure functions on results data
;; ---------------------------------------------------------------------------

(defn suite-summary
  "Group results by suite, showing pass/fail counts per suite.
  Excludes suites where all tests were skipped."
  [results]
  (->> (:tests results)
       (remove #(= "skip" (:status %)))
       (group-by :suite)
       (map (fn [[suite tests]]
              (let [passed (count (filter #(= "pass" (:status %)) tests))
                    total (count tests)]
                {:suite suite :passed passed :total total})))
       (sort-by (juxt (comp - :passed) :suite))))

(defn failures
  "Return failed tests from results. Optionally filter by suite."
  ([results]
   (filter #(= "fail" (:status %)) (:tests results)))
  ([results suite]
   (filter #(and (= "fail" (:status %))
                 (= suite (:suite %)))
           (:tests results))))

(defn- first-failing-action
  "Return the first failing/erroring action from a test, or nil."
  [test]
  (->> (:actions test)
       (filter #(contains? #{"fail" "error"} (:result %)))
       first))

(defn cluster-failures
  "Group failed tests by their first failing action's (path, expected, actual)
  signature. Returns a seq of {:signature {...} :count N :tests [...]} sorted
  by count descending — high-count clusters are the highest-leverage fixes.
  Optionally filter by suite."
  ([results] (cluster-failures results nil))
  ([results suite]
   (let [fails (if suite (failures results suite) (failures results))]
     (->> fails
          (group-by #(select-keys (first-failing-action %)
                                  [:path :expected :actual]))
          (map (fn [[sig tests]]
                 {:signature sig
                  :count     (count tests)
                  :tests     (mapv :name tests)}))
          (sort-by (comp - :count))))))

(defn diff
  "Compare two results, returning {:gained [...] :lost [...]} test names.
  Ignores skipped tests in both results."
  [old-results new-results]
  (let [non-skip (fn [results] (remove #(= "skip" (:status %)) (:tests results)))
        old-passed (set (map :name (filter #(= "pass" (:status %)) (non-skip old-results))))
        new-passed (set (map :name (filter #(= "pass" (:status %)) (non-skip new-results))))]
    {:gained (sort (clojure.set/difference new-passed old-passed))
     :lost   (sort (clojure.set/difference old-passed new-passed))}))

;; ---------------------------------------------------------------------------
;; Printing — human-readable output
;; ---------------------------------------------------------------------------

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

(defn print-failures
  "Print failed tests with structured expected/actual details.
  Optionally filter by suite."
  ([results] (print-failures results nil))
  ([results suite]
   (let [failed (if suite (failures results suite) (failures results))]
     (println (format "\nFailed: %d" (count failed)))
     (doseq [{:keys [name operation actions]} failed]
       (println (format "\n  FAIL: %s%s" name (if operation (str "  (" operation ")") "")))
       (doseq [{:keys [result message path expected actual]} actions
               :when (contains? #{"fail" "error"} result)]
         (if (and path (or expected actual))
           (do
             (println (format "    at %s" path))
             (when expected (println (format "      expected: %s" expected)))
             (when actual   (println (format "      actual:   %s" actual))))
           (when message
             (println (format "    %s" message)))))))))

(defn print-detail
  "Print full detail for a single test, including expected response JSON."
  [results test-name]
  (if-let [test (first (filter #(= test-name (:name %)) (:tests results)))]
    (do
      (println (format "\nTest: %s" (:name test)))
      (println (format "Suite: %s" (:suite test)))
      (println (format "Status: %s" (:status test)))
      (when (:operation test)
        (println (format "Operation: %s" (:operation test))))
      (when (= "fail" (:status test))
        (println "\nFailure details:")
        (doseq [{:keys [result message path expected actual]} (:actions test)
                :when (contains? #{"fail" "error"} result)]
          (if (and path (or expected actual))
            (do
              (println (format "  at %s" path))
              (when expected (println (format "    expected: %s" expected)))
              (when actual   (println (format "    actual:   %s" actual))))
            (when message
              (println (format "  %s" message)))))
        (when-let [resp (:expected-response test)]
          (println "\nExpected response:")
          (println (json/write-str resp :indent true)))))
    (println (format "Test not found: %s" test-name))))

(defn print-clusters
  "Print failed tests grouped by failure signature. Each cluster shows the
  parsed (path, expected, actual) and the names of the affected tests. The
  highest-count cluster is your highest-leverage fix candidate.
  Optionally filter by suite."
  ([results] (print-clusters results nil))
  ([results suite]
   (let [clusters (cluster-failures results suite)
         show-tests 5]
     (println (format "\nFailure clusters%s: %d distinct, %d failures total"
                      (if suite (str " in " suite) "")
                      (count clusters)
                      (reduce + (map :count clusters))))
     (doseq [{:keys [signature count tests]} clusters
             :let [{:keys [path expected actual]} signature]]
       (println (format "\n  [%dx] at %s" count (or path "?")))
       (when expected (println (format "        expected: %s" expected)))
       (when actual   (println (format "        actual:   %s" actual)))
       (doseq [t (take show-tests tests)]
         (println (format "        - %s" t)))
       (when (> count show-tests)
         (println (format "        ... and %d more" (- count show-tests))))))))

(defn print-diff
  "Print gained/lost tests between two results."
  [old-results new-results]
  (let [{:keys [gained lost]} (diff old-results new-results)]
    (println (format "\nPassed: %d -> %d" (:passed old-results) (:passed new-results)))
    (when (seq gained)
      (println (format "\n  GAINED (%d):" (count gained)))
      (doseq [n gained] (println (format "    + %s" n))))
    (when (seq lost)
      (println (format "\n  LOST (%d):" (count lost)))
      (doseq [n lost]
        (let [test (first (filter #(= n (:name %)) (:tests new-results)))
              action (->> (:actions test)
                          (filter #(contains? #{"fail" "error"} (:result %)))
                          first)]
          (println (format "    - %s" n))
          (when (:path action)
            (println (format "      at %s: expected %s, got %s"
                             (:path action)
                             (or (:expected action) "?")
                             (or (:actual action) "?")))))))
    (when (and (empty? gained) (empty? lost))
      (println "  No change."))))

;; ---------------------------------------------------------------------------
;; Persistence
;; ---------------------------------------------------------------------------

(defn- timestamp []
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss")
           (LocalDateTime/ofInstant (Instant/now) (ZoneId/systemDefault))))

(defn- results->json
  "Convert results to a JSON-serializable map, preserving full failure detail."
  [results]
  {:timestamp (:timestamp results)
   :total     (:total results)
   :passed    (:passed results)
   :failed    (:failed results)
   :skipped   (or (:skipped results) 0)
   :tests     (mapv (fn [{:keys [name suite status operation actions expected-response]}]
                      (cond-> {:name name :suite suite :status status}
                        operation (assoc :operation operation)
                        (seq actions)
                        (assoc :actions
                               (mapv (fn [{:keys [result message path expected actual]}]
                                       (cond-> {:result result}
                                         message  (assoc :message message)
                                         path     (assoc :path path)
                                         expected (assoc :expected expected)
                                         actual   (assoc :actual actual)))
                                     actions))
                        expected-response (assoc :expected-response expected-response)))
                    (:tests results))})

(defn save-results!
  "Save results: timestamped archive + latest.json."
  [results]
  (let [dir (io/file results-dir)]
    (.mkdirs dir)
    (let [report (results->json results)
          report-json (json/write-str report :indent true)
          ts-path (str (io/file dir (str (timestamp) ".json")))
          latest-path (str (io/file dir "latest.json"))]
      (spit ts-path report-json)
      (spit latest-path report-json)
      (println (format "Results saved: %s" ts-path)))))

(defn load-results
  "Load results from a specific file path."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (json/read-str (slurp f) :key-fn keyword))))

(defn load-latest
  "Load the most recent conformance results."
  []
  (load-results (str (io/file results-dir "latest.json"))))

(defn load-baseline
  "Load the conformance baseline."
  []
  (load-results baseline-path))

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
(s/def ::run-args (s/keys :opt-un [::snomed ::output-dir ::update-baseline]))

(defn run
  "Run conformance tests. Intended as an exec-fn for clj -X:conformance.

  Optional:
    :snomed           — path to pre-existing Hermes snomed.db (default: auto-build)
    :update-baseline  — when true, updates the baseline file (default false)

  Examples:
    clj -X:conformance
    clj -X:conformance :snomed '\"path/to/snomed.db\"'
    clj -X:conformance :update-baseline true"
  [{:keys [snomed update-baseline]
    :or   {update-baseline false}}]
  (when (and snomed (not (s/valid? ::snomed snomed)))
    (println (format "SNOMED database not found: %s" snomed))
    (System/exit 1))
  (start! :snomed snomed)
  (try
    (let [results (run-tests)
          prev (load-latest)
          baseline (load-baseline)]
      (print-suites results)
      (println)
      (print-failures results)
      (when prev (print-diff prev results))
      (save-results! results)
      (when baseline
        (println (format "\nBaseline: %d passed" (:passed baseline)))
        (when (< (:passed results) (:passed baseline))
          (println "REGRESSION: pass count decreased!")
          (System/exit 1)))
      (when update-baseline
        (save-baseline! results)))
    (finally
      (stop!))))

;; ---------------------------------------------------------------------------
;; REPL testing with overlays
;; ---------------------------------------------------------------------------

(defn load-test-resources
  "Load test resource files from the tx-ecosystem test data as Clojure maps.
  Accepts either a suite name (loads all resources for that suite) or specific
  file paths relative to the test data dir."
  [suite-or-files]
  (let [base (io/file test-data-dir "tests")
        files (if (string? suite-or-files)
                (let [suite-dir (io/file base suite-or-files)]
                  (->> (.listFiles suite-dir)
                       (filter #(and (.isFile %)
                                     (.endsWith (.getName %) ".json")
                                     (not (.contains (.getName %) "request"))
                                     (not (.contains (.getName %) "response"))
                                     (not (.contains (.getName %) "test-cases"))))
                       (sort-by #(.getName %))))
                (map #(io/file base %) suite-or-files))]
    (->> files
         (keep (fn [f]
                 (try
                   (let [m (json/read-str (slurp f))]
                     (when (#{"CodeSystem" "ValueSet" "ConceptMap"} (get m "resourceType"))
                       m))
                   (catch Exception _ nil))))
         vec)))

(defn make-test-ctx
  "Build an overlay context from test resources for REPL testing."
  ([resources] (make-test-ctx resources nil))
  ([resources request-params]
   (let [overlay (server/build-tx-ctx resources)]
     (assoc overlay :request (merge registry/default-request request-params)))))

(defn- find-test-case
  "Find a test case definition by name. Returns {:test tc :suite suite-def}."
  [test-name]
  (let [test-cases (json/read-str (slurp (io/file test-data-dir "tests" "test-cases.json")))]
    (some (fn [suite]
            (some (fn [tc]
                    (when (= test-name (get tc "name"))
                      {:test tc :suite suite}))
                  (get suite "tests")))
          (get test-cases "suites"))))

(defn list-tests
  "List all conformance tests (or those in one suite). Returns a vec of
  {:name :suite :operation :http-code} maps."
  ([] (list-tests nil))
  ([suite-filter]
   (let [data (json/read-str (slurp (io/file test-data-dir "tests" "test-cases.json"))
                             :key-fn keyword)]
     (vec (for [suite (:suites data)
                :when (or (nil? suite-filter) (= suite-filter (:name suite)))
                tc (:tests suite)]
            {:name      (:name tc)
             :suite     (:name suite)
             :operation (:operation tc)
             :http-code (:http-code tc)})))))

(defn test-info
  "Return everything about a test: the case definition, suite setup files,
  the parsed request and expected response, and the parsed setup resources.
  Replaces the need to `cat` or grep test fixture files from the shell."
  [test-name]
  (when-let [{:keys [test suite]} (find-test-case test-name)]
    (let [suite-name (get suite "name")
          setup-files (get suite "setup" [])
          setup-resources (vec (keep load-test-case-json setup-files))]
      {:name            test-name
       :suite           suite-name
       :operation       (get test "operation")
       :http-code       (get test "http-code")
       :request-path    (get test "request")
       :response-path   (get test "response")
       :request         (when-let [p (get test "request")] (load-test-case-json p))
       :expected        (when-let [p (get test "response")] (load-test-case-json p))
       :setup-files     setup-files
       :setup-resources setup-resources})))

(defn- operation->endpoint
  "Map a test operation name to the correct FHIR endpoint."
  [op base-url]
  (case op
    "validate-code"    (str base-url "/ValueSet/$validate-code")
    "cs-validate-code" (str base-url "/CodeSystem/$validate-code")
    "expand"           (str base-url "/ValueSet/$expand")
    "lookup"           (str base-url "/CodeSystem/$lookup")
    "translate"        (str base-url "/ConceptMap/$translate")
    (str base-url "/ValueSet/$" op)))

(defn replay-test
  "Replay a conformance test by name, sending the exact request TxTester would
  send (including all tx-resources) to the running server via HTTP. Returns a
  map with :request, :expected, :actual, :status for comparison."
  [test-name]
  (when-not (server-url)
    (throw (ex-info "No server running. Call (start! path) first." {})))
  (let [{:keys [test suite]} (find-test-case test-name)
        _ (when-not test (throw (ex-info (str "Test case not found: " test-name) {})))
        op (get test "operation")
        req-path (get test "request")
        resp-path (get test "response")
        profile-path (get test "profile")
        setup-files (get suite "setup" [])
        resources (vec (keep (fn [path]
                               (let [m (load-test-case-json path)]
                                 (when (#{"CodeSystem" "ValueSet" "ConceptMap"}
                                        (:resourceType m))
                                   m)))
                             setup-files))
        request-json (load-test-case-json req-path)
        profile-json (when profile-path (load-test-case-json profile-path))
        merged-params (cond-> (vec (:parameter request-json))
                        profile-json (into (:parameter profile-json)))
        full-params (into merged-params
                      (map (fn [r] {:name "tx-resource" :resource r}))
                      resources)
        full-request (assoc request-json :parameter full-params)
        endpoint (operation->endpoint op (server-url))
        body-bytes (.getBytes ^String (json/write-str full-request) "UTF-8")
        conn (doto ^java.net.HttpURLConnection
                   (.openConnection (java.net.URL. endpoint))
               (.setRequestMethod "POST")
               (.setDoOutput true)
               (.setRequestProperty "Content-Type" "application/fhir+json")
               (.setRequestProperty "Accept" "application/fhir+json"))
        _ (with-open [os (.getOutputStream conn)]
            (.write os body-bytes))
        status (.getResponseCode conn)
        response-str (with-open [is (if (>= status 400)
                                      (.getErrorStream conn)
                                      (.getInputStream conn))]
                       (slurp is))
        actual (json/read-str response-str :key-fn keyword)
        expected (load-test-case-json resp-path)]
    {:test-name test-name
     :operation op
     :status status
     :request full-request
     :actual actual
     :expected expected}))
