(ns com.eldrix.hades.startup-budget-test
  "Startup-budget gate. Functional tests (`clj -M:test`) and conformance
  assert *outputs*; they are blind to *cost*. A correct-but-eager change
  that materialises O(all-resources) work at service construction — the
  regression that produced a >30s CLI startup and an unresponsive MCP
  server — passes every output assertion. This namespace closes that gap
  with explicit wall-clock budgets on the two startup paths:

    1. the CLI `--help` / parse path, which must boot nothing (always-on,
       no fixtures), and
    2. `hades/open` plus the boot catalogue listing against the pinned
       SNOMED CT International DB (`^:live`, runs where the DB exists).

  Budgets are deliberately generous: they pass comfortably for healthy
  startup and only trip on an order-of-magnitude regression. Tighten them
  only with benchmark evidence."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.cli :as cli]))

;; Healthy `--help` is pure string building (microseconds); a regression
;; that boots a service to render help would blow this by orders of magnitude.
(def ^:private help-budget-ms 200)

;; Opening the pinned ~2.2 GB Hermes DB legitimately takes a few seconds
;; (LMDB env + Lucene readers). The regression took >30s. 12s catches the
;; regression class with margin for a cold cache / loaded CI host.
(def ^:private open-budget-ms 12000)

;; The boot catalogue (distinct served resources) walks every provider's
;; metadata once. It must be a cheap listing, not a per-resource resolve.
(def ^:private catalogue-budget-ms 5000)

(defn- elapsed-ms
  "Run `thunk`, returning `[result elapsed-ms]`."
  [thunk]
  (let [start (System/nanoTime)
        result (thunk)]
    [result (/ (- (System/nanoTime) start) 1e6)]))

(deftest help-path-boots-nothing
  (testing "every command's --help parses to the help branch"
    (doseq [cmd ["serve" "install" "import" "status" "mcp" "available"]]
      (is (:help (:options (cli/parse-cli [cmd "--help"])))
          (str cmd " --help must resolve to the help branch")))
    (is (:help (:options (cli/parse-cli ["--help"])))
        "bare --help must resolve to the help branch"))
  (testing "parse + command-list rendering stays under the help budget"
    (let [[_ ms] (elapsed-ms (fn []
                               (cli/parse-cli ["serve" "--help"])
                               (cli/format-commands)))]
      (is (< ms help-budget-ms)
          (format "help path took %.1fms (budget %dms) — is something opening a service to render help?"
                  (double ms) help-budget-ms)))))

(deftest ^:live open-stays-within-budget
  (let [path fixtures/snomed-db-path]
    (if-not (.exists (io/file path))
      (println "SKIP open-stays-within-budget: pinned SNOMED DB absent at" path
               "- see CLAUDE.md 'Conformance / integration test data'")
      (let [[svc open-ms] (elapsed-ms #(hades/open [path]))]
        (try
          (testing "hades/open stays within the startup budget"
            (is (< open-ms open-budget-ms)
                (format "hades/open took %.0fms (budget %dms) — eager work at construction?"
                        (double open-ms) open-budget-ms)))
          (testing "boot catalogue listing is a cheap metadata walk"
            (let [[_ cat-ms] (elapsed-ms #(hades/metadata svc))]
              (is (< cat-ms catalogue-budget-ms)
                  (format "catalogue listing took %.0fms (budget %dms) — per-resource resolve in the metadata path?"
                          (double cat-ms) catalogue-budget-ms))))
          (finally (hades/close svc)))))))
