(ns com.eldrix.hades.impl.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.cli :as cli]))

(def cli-tests
  [;; install: positional path = destination, --dist for ids
   {:s    "install requires --dist for the id and a destination path positional"
    :args ["install" "snomed.db" "--dist" "uk.nhs/sct-clinical" "--api-key=api-key.txt"]
    :test (fn [{:keys [cmds options arguments errors]}]
            (is (nil? errors))
            (is (= ["install"] cmds))
            (is (= ["snomed.db"] (vec arguments)))
            (is (= [{:id "uk.nhs/sct-clinical" :version nil}] (:dist options)))
            (is (= "api-key.txt" (:api-key options))))}

   {:s    "install carries the @version suffix through to :dist"
    :args ["install" "snomed.db" "--dist" "uk.nhs/sct-clinical@2025-02-01" "--api-key=api-key.txt"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= [{:id "uk.nhs/sct-clinical" :version "2025-02-01"}] (:dist options))))}

   {:s    "install with FHIR package id (no slash) via --dist"
    :args ["install" "fhir.db" "--dist" "hl7.fhir.r4.core@4.0.1"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= [{:id "hl7.fhir.r4.core" :version "4.0.1"}] (:dist options))))}

   {:s    "install uk.nhs/* without --api-key fails at parse time"
    :args ["install" "snomed.db" "--dist" "uk.nhs/sct-clinical"]
    :test (fn [{:keys [errors]}]
            (is (some #(re-find #"--api-key" %) errors)))}

   {:s    "install of mlds distribution without --username/--password fails at parse time"
    :args ["install" "snomed.db" "--dist" "ihtsdo.mlds/167"]
    :test (fn [{:keys [errors]}]
            (is (some #(re-find #"--username" %) errors))
            (is (some #(re-find #"--password" %) errors)))}

   ;; available: --dist only; positional ids are NOT promoted any more
   {:s    "available with no --dist parses cleanly"
    :args ["available"]
    :test (fn [{:keys [cmds errors options]}]
            (is (nil? errors))
            (is (= ["available"] cmds))
            (is (empty? (:dist options))))}

   {:s    "available with --dist for FHIR package"
    :args ["available" "--dist" "hl7.fhir.r4.core"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= [{:id "hl7.fhir.r4.core" :version nil}] (:dist options))))}

   {:s    "available with --dist for SNOMED requires --api-key"
    :args ["available" "--dist" "uk.nhs/sct-clinical"]
    :test (fn [{:keys [errors]}]
            (is (some #(re-find #"--api-key" %) errors)))}

   {:s    "bare positional is NEVER promoted to --dist for available"
    :args ["available" "uk.nhs/sct-clinical"]
    :test (fn [{:keys [arguments options]}]
            (is (= ["uk.nhs/sct-clinical"] (vec arguments))
                "positional remains positional — not promoted")
            (is (empty? (:dist options))
                "no --dist values when none were specified explicitly"))}

   ;; import: dest + sources, all positional. Parse-time only counts
   ;; positionals — the at-least-2 rule is enforced at command entry.
   {:s    "import with 0 positionals parses cleanly (impl enforces arity)"
    :args ["import"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["import"] cmds))
            (is (empty? arguments)))}

   {:s    "import with 1 positional parses cleanly (impl enforces arity)"
    :args ["import" "snomed.db"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["import"] cmds))
            (is (= ["snomed.db"] (vec arguments))))}

   {:s    "import with 2 positionals: dest + one source"
    :args ["import" "snomed.db" "/Downloads/snomed-2021/"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["import"] cmds))
            (is (= ["snomed.db" "/Downloads/snomed-2021/"] (vec arguments))))}

   {:s    "import accepts multiple source paths after destination"
    :args ["import" "snomed.db" "/intl" "/us" "/uk"]
    :test (fn [{:keys [cmds arguments]}]
            (is (= ["import"] cmds))
            (is (= ["snomed.db" "/intl" "/us" "/uk"] (vec arguments))))}

   {:s    "import does not accept -f"
    :args ["import" "-f" "snomed.db" "/some/src"]
    :test (fn [{:keys [errors]}]
            (is (some #(re-find #"-f" %) errors)))}

   ;; status / index / compact / serve: positional paths, no -f
   {:s    "compact with positional path"
    :args ["compact" "snomed.db"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["compact"] cmds))
            (is (= ["snomed.db"] (vec arguments))))}

   {:s    "index with multiple positional paths"
    :args ["index" "snomed.db" "loinc.db"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["index"] cmds))
            (is (= ["snomed.db" "loinc.db"] (vec arguments))))}

   {:s    "index does not accept -f"
    :args ["index" "-f" "snomed.db"]
    :test (fn [{:keys [errors]}] (is (some #(re-find #"-f" %) errors)))}

   {:s    "status without paths parses cleanly (mandatory check at command entry)"
    :args ["status"]
    :test (fn [{:keys [errors arguments]}]
            (is (nil? errors))
            (is (empty? arguments)))}

   {:s    "status with positional paths"
    :args ["status" "snomed.db" "loinc.db"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["status"] cmds))
            (is (= ["snomed.db" "loinc.db"] (vec arguments))))}

   {:s    "status accepts -f? no, --file is gone everywhere"
    :args ["status" "-f" "snomed.db"]
    :test (fn [{:keys [errors]}] (is (some #(re-find #"-f" %) errors)))}

   {:s    "status --format edn"
    :args ["status" "snomed.db" "--format" "edn"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= :edn (:format options))))}

   {:s    "status --format json"
    :args ["status" "snomed.db" "--format" "json"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= :json (:format options))))}

   {:s    "status --format rejects unknown value"
    :args ["status" "snomed.db" "--format" "yaml"]
    :test (fn [{:keys [errors]}] (is (seq errors)))}

   {:s    "serve with --port parsed as number; positional paths"
    :args ["serve" "snomed.db" "--port" "8090"]
    :test (fn [{:keys [cmds arguments options errors]}]
            (is (nil? errors))
            (is (= ["serve"] cmds))
            (is (= ["snomed.db"] (vec arguments)))
            (is (= 8090 (:port options))))}

   {:s    "serve does not accept -f"
    :args ["serve" "-f" "snomed.db"]
    :test (fn [{:keys [errors]}] (is (some #(re-find #"-f" %) errors)))}

   {:s    "serve with multiple positional paths"
    :args ["serve" "snomed.db" "loinc.db" "/some/pkg"]
    :test (fn [{:keys [arguments errors options]}]
            (is (nil? errors))
            (is (= ["snomed.db" "loinc.db" "/some/pkg"] (vec arguments)))
            (is (not (contains? options :dist))
                "serve must not include :dist in its option set"))}

   {:s    "serve --port out of range is rejected"
    :args ["serve" "snomed.db" "--port" "70000"]
    :test (fn [{:keys [errors]}] (is (seq errors)))}

   {:s    "serve defaults to port 8080"
    :args ["serve" "snomed.db"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= 8080 (:port options))))}

   {:s    "serve with --bind-address and --locale"
    :args ["serve" "snomed.db" "--bind-address" "127.0.0.1" "--locale" "en-GB"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= "127.0.0.1" (:bind-address options)))
            (is (= "en-GB" (:locale options))))}

   {:s    "serve accepts repeatable --default URL=VERSION bindings"
    :args ["serve" "intl.db" "uk.db"
           "--default" "http://snomed.info/sct=http://snomed.info/sct/900000000000207008/version/20250201"
           "--default=http://example.com/cs=2.0"]
    :test (fn [{:keys [options errors]}]
            (is (nil? errors))
            (is (= {"http://snomed.info/sct" "http://snomed.info/sct/900000000000207008/version/20250201"
                    "http://example.com/cs" "2.0"}
                   (:default options))))}

   {:s    "--default rejects values without `=`"
    :args ["serve" "snomed.db" "--default" "no-equals-sign"]
    :test (fn [{:keys [errors]}]
            (is (some #(re-find #"URL=VERSION" %) errors)))}

   {:s    "list with no paths parses cleanly (impl enforces the at-least-one rule)"
    :args ["list"]
    :test (fn [{:keys [cmds errors]}]
            (is (nil? errors))
            (is (= ["list"] cmds)))}

   {:s    "list with positional paths"
    :args ["list" "snomed.db" "loinc.db"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["list"] cmds))
            (is (= ["snomed.db" "loinc.db"] (vec arguments))))}

   ;; Chained commands
   {:s    "multiple commands are preserved in order"
    :args ["install" "index" "compact" "serve" "snomed.db"
           "--dist" "uk.nhs/sct-clinical" "--api-key=api-key.txt" "--port" "8090"]
    :test (fn [{:keys [cmds arguments options errors]}]
            (is (nil? errors))
            (is (= ["install" "index" "compact" "serve"] cmds))
            (is (= ["snomed.db"] (vec arguments)))
            (is (= 8090 (:port options))))}

   {:s    "chained `install index compact` shares the destination positional"
    :args ["install" "index" "compact" "snomed.db"
           "--dist" "uk.nhs/sct-clinical" "--api-key=api-key.txt"]
    :test (fn [{:keys [cmds arguments errors]}]
            (is (nil? errors))
            (is (= ["install" "index" "compact"] cmds))
            (is (= ["snomed.db"] (vec arguments))))}

   {:s    "no command yields a help-only option set"
    :args []
    :test (fn [{:keys [cmds errors]}]
            (is (empty? cmds))
            (is (nil? errors)))}

   {:s    "unknown flag is rejected"
    :args ["serve" "snomed.db" "--nonsense"]
    :test (fn [{:keys [errors]}]
            (is (some #(re-find #"nonsense" %) errors)))}])

(deftest test-parse-cli-options
  (doseq [{s :s args :args cfg :cfg test-fn :test} cli-tests]
    (testing s
      (test-fn (cli/parse-cli args (or cfg {}))))))

(deftest test-known-commands
  (testing "All documented commands are dispatchable"
    (is (= #{"list" "import" "available" "install"
             "index" "compact" "status" "serve" "mcp"}
           cli/all-commands))))

(deftest test-id-shape-helpers
  (testing "snomed-distribution? matches slash form"
    (is (cli/snomed-distribution? "uk.nhs/sct-clinical"))
    (is (cli/snomed-distribution? "uk.nhs/sct-clinical@2025-02-01"))
    (is (not (cli/snomed-distribution? "hl7.fhir.r4.core")))
    (is (not (cli/snomed-distribution? "hl7.fhir.r4.core@4.0.1"))))
  (testing "fhir-package? matches reverse-DNS lowercase"
    (is (cli/fhir-package? "hl7.fhir.r4.core"))
    (is (cli/fhir-package? "hl7.fhir.r4.core@4.0.1"))
    (is (not (cli/fhir-package? "uk.nhs/sct-clinical"))))
  (testing "bare-id strips @version"
    (is (= "uk.nhs/sct-clinical" (cli/bare-id "uk.nhs/sct-clinical@2025-02-01")))
    (is (= "hl7.fhir.r4.core" (cli/bare-id "hl7.fhir.r4.core@4.0.1")))
    (is (= "hl7.fhir.r4.core" (cli/bare-id "hl7.fhir.r4.core")))))
