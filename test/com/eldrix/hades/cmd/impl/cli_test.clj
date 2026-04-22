(ns com.eldrix.hades.cmd.impl.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.cmd.impl.cli :as cli]))

(def cli-tests
  [{:s    "Legacy download command line arguments (pair form)"
    :args ["--db" "snomed.db" "download" "uk.nhs/sct-clinical" "api-key" "api-key.txt" "cache-dir" "/var/tmp"]
    :test (fn [parsed]
            (is (= "snomed.db" (get-in parsed [:options :db])))
            (is (= ["uk.nhs/sct-clinical"] (get-in parsed [:options :dist])))
            (is (= "api-key.txt" (get-in parsed [:options :api-key])))
            (is (= "/var/tmp" (get-in parsed [:options :cache-dir])))
            (is (nil? (:errors parsed)))
            (is (seq (:warnings parsed)) "download is deprecated, must warn"))}
   {:s    "Legacy download with multiple distributions"
    :args ["--db" "snomed.db" "download"
           "uk.nhs/sct-clinical" "uk.nhs/sct-drug-ext"
           "api-key" "api-key.txt" "cache-dir" "/var/tmp"]
    :test (fn [parsed]
            (is (= ["uk.nhs/sct-clinical" "uk.nhs/sct-drug-ext"]
                   (get-in parsed [:options :dist])))
            (is (nil? (:errors parsed)))
            (is (seq (:warnings parsed))))}
   {:s    "install without --db is rejected"
    :args ["install" "--dist" "uk.nhs/sct-clinical" "--dist" "uk.nhs/sct-drug-ext"]
    :test (fn [parsed] (is (:errors parsed)))}
   {:s    "install with --db and --api-key=… pair form"
    :args ["--db" "snomed.db" "install" "--dist" "uk.nhs/sct-clinical" "--api-key=api-key.txt"]
    :test (fn [parsed]
            (is (= "snomed.db" (get-in parsed [:options :db])))
            (is (= "api-key.txt" (get-in parsed [:options :api-key])))
            (is (nil? (:errors parsed))))}
   {:s    "install accepts --release-date"
    :args ["--db" "snomed.db" "install" "uk.nhs/sct-clinical"
           "--api-key=api-key.txt" "--release-date" "2023-01-01"]
    :test (fn [parsed]
            (is (= "2023-01-01" (get-in parsed [:options :release-date]))))}
   {:s    "import with positional path argument"
    :args (str/split "--db snomed.db import /Downloads/snomed-2021/" #" ")
    :test (fn [{:keys [cmds options arguments]}]
            (is (= ["import"] cmds))
            (is (= "snomed.db" (:db options)))
            (is (= ["/Downloads/snomed-2021/"] arguments)))}
   {:s    "compact"
    :args ["--db" "snomed.db" "compact"]
    :test (fn [{:keys [cmds options]}]
            (is (= ["compact"] cmds))
            (is (= "snomed.db" (:db options))))}
   {:s    "index"
    :args ["--db" "snomed.db" "index"]
    :test (fn [{:keys [cmds options]}]
            (is (= ["index"] cmds))
            (is (= "snomed.db" (:db options))))}
   {:s    "status without --db is rejected"
    :args ["status"]
    :test (fn [{:keys [errors]}] (is (seq errors)))}
   {:s    "status with --db"
    :args ["status" "--db" "snomed.db"]
    :test (fn [{:keys [cmds options errors]}]
            (is (nil? errors))
            (is (= ["status"] cmds))
            (is (= "snomed.db" (:db options))))}
   {:s    "status --format edn"
    :args ["status" "--db" "snomed.db" "--format" "edn"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= :edn (:format options))))}
   {:s    "status --format rejects unknown value"
    :args ["status" "--db" "snomed.db" "--format" "yaml"]
    :test (fn [{:keys [errors]}] (is (seq errors)))}
   {:s    "serve with --port parsed as number"
    :args ["--db" "snomed.db" "--port" "8090" "serve"]
    :test (fn [{:keys [cmds options errors]}]
            (is (nil? errors))
            (is (= ["serve"] cmds))
            (is (= "snomed.db" (:db options)))
            (is (= 8090 (:port options))))}
   {:s    "serve --port out of range is rejected"
    :args ["--db" "snomed.db" "--port" "70000" "serve"]
    :test (fn [{:keys [errors]}] (is (seq errors)))}
   {:s    "serve defaults to port 8080"
    :args ["--db" "snomed.db" "serve"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= 8080 (:port options))))}
   {:s    "serve with --bind-address and --locale"
    :args ["--db" "snomed.db" "serve" "--bind-address" "127.0.0.1" "--locale" "en-GB"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= "127.0.0.1" (:bind-address options)))
            (is (= "en-GB" (:locale options))))}
   {:s    "serve does not leak distribution options"
    :args ["serve" "--db" "snomed.db"]
    :test (fn [{:keys [options]}]
            (is (not (contains? options :dist))
                "serve must not include :dist in its option set"))}
   {:s    "list with no paths parses cleanly"
    :args ["list"]
    :test (fn [{:keys [cmds errors]}]
            (is (nil? errors))
            (is (= ["list"] cmds)))}
   {:s    "available with no --dist parses cleanly"
    :args ["available"]
    :test (fn [{:keys [cmds errors]}]
            (is (nil? errors))
            (is (= ["available"] cmds)))}
   {:s    "bare uk.nhs/sct-clinical is treated as an implicit --dist"
    :args ["--db" "snomed.db" "install" "uk.nhs/sct-clinical" "--api-key=api-key.txt"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= ["uk.nhs/sct-clinical"] (:dist options))))}
   {:s    "multiple commands are preserved in order"
    :args ["install" "--db=snomed.db" "uk.nhs/sct-clinical" "index" "compact" "serve"
           "--api-key=api-key.txt" "--port" "8090"]
    :test (fn [{:keys [cmds options errors]}]
            (is (nil? errors))
            (is (= ["install" "index" "compact" "serve"] cmds))
            (is (= 8090 (:port options))))}
   {:s    "no command yields a help-only option set"
    :args []
    :test (fn [{:keys [cmds errors]}]
            (is (empty? cmds))
            (is (nil? errors)))}
   {:s    "unknown flag is rejected"
    :args ["serve" "--db" "snomed.db" "--nonsense"]
    :test (fn [{:keys [errors]}]
            (is (some #(re-find #"nonsense" %) errors)))}])

(deftest test-parse-cli-options
  (doseq [{s :s args :args cfg :cfg test-fn :test} cli-tests]
    (testing s
      (test-fn (cli/parse-cli args (or cfg {}))))))

(deftest test-known-commands
  (testing "All documented commands are dispatchable"
    (is (= #{"list" "import" "available" "download" "install"
             "index" "compact" "status" "serve"}
           cli/all-commands))))
