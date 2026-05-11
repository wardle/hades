(ns com.eldrix.hades.cmd
  "Command-line entry point for the Hades FHIR terminology server."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.impl.cli :as cli]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.http :as http]
            [com.eldrix.hades.impl.mcp.server :as mcp-server]
            [com.eldrix.hades.impl.fhir-package :as fhir-package]
            [com.eldrix.hades.impl.index.sqlite :as sqlite-index]
            [com.eldrix.hades.impl.loaders.fhir :as fhir-loader]
            [com.eldrix.hades.impl.loaders.loinc :as loinc-loader]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.db :as sqlite-db]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.download :as download]
            [com.eldrix.hermes.importer :as importer])
  (:import (ch.qos.logback.classic AsyncAppender Logger LoggerContext)
           (ch.qos.logback.core.joran.util ConfigurationWatchListUtil)
           (clojure.lang ExceptionInfo)
           (java.io File)
           (java.net ConnectException)
           (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (org.slf4j LoggerFactory)))

(set! *warn-on-reflection* true)

(defn- write-local-date [^LocalDate o ^Appendable out _options]
  (.append out \")
  (.append out (.format DateTimeFormatter/ISO_DATE o))
  (.append out \"))

(extend LocalDate json/JSONWriter {:-write write-local-date})

(defn- set-default-uncaught-exception-handler []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (log/error ex "Uncaught exception on" (.getName thread))))))

(defn- log-module-dependency-problems [svc]
  (doseq [dep (hermes/module-dependency-problems svc)]
    (log/warn "module dependency mismatch" dep)))

;;; CLI errors

(defn- cli-error
  "Throw a user-facing CLI error with a short message and a `:reason`
  recognised by `-main`. The message is what the user sees; the reason
  is for programmatic dispatch."
  ([reason msg] (cli-error reason msg {}))
  ([reason msg data]
   (throw (ex-info msg (assoc data :reason reason)))))

;;; Subcommand implementations

(defn- summarise-resources
  "Group imported resources by type for a startup log."
  [resources]
  (let [by-type (group-by :resource-type resources)]
    {:codesystems (count (get by-type "CodeSystem" []))
     :valuesets   (count (get by-type "ValueSet" []))
     :conceptmaps (count (get by-type "ConceptMap" []))}))

(defn- import-rf2! [file dirs]
  (log/info "rf2 import" {:file file :sources (count dirs) :paths (vec dirs)})
  (hermes/import-snomed file dirs :exclude importer/default-exclude))

(defn- import-loinc! [file roots]
  (let [ch     (loinc-loader/stream-releases roots)
        result (sqlite-index/import-fhir-data file ch {:loader-type "loinc-csv"})]
    (log/info "loinc import complete"
              (merge {:file file :sources (count roots)}
                     (summarise-resources (:resources result))))
    result))

(defn- import-fhir-json! [file files]
  (let [result (sqlite-index/build! file #(fhir-loader/load-files files)
                                    {:loader-type "fhir-json"})]
    (log/info "fhir-json import complete"
              (merge {:file file :files (count files)}
                     (summarise-resources (:resources result))))
    result))

(s/fdef index-one
  :args (s/cat :entry ::sources/entry))

(defn- index-one [{:keys [kind ^File file ^File dir]}]
  (case kind
    :hermes-db
    (let [path (.getPath dir)]
      (println (str "  → index: " path))
      (hermes/index path)
      (with-open [svc (hermes/open path {:quiet true})]
        (log-module-dependency-problems svc)))
    :fhir-tx-db
    (let [path (.getPath file)]
      (println (str "  → index: " path))
      (sqlite-index/index! path))
    ;; Release-only entries (rf2, loinc) — silently skipped so a chained
    ;; pipeline that mixes release sources with built artefacts doesn't
    ;; fail spuriously. fhir-json gets here too but indexing it is a no-op.
    nil))

(s/fdef compact-one
  :args (s/cat :entry ::sources/entry))

(defn- compact-one [{:keys [kind ^File file ^File dir]}]
  (case kind
    :hermes-db  (let [path (.getPath dir)]  (println (str "  → compact: " path)) (hermes/compact path))
    :fhir-tx-db (let [path (.getPath file)] (println (str "  → vacuum: " path))  (sqlite-db/vacuum! path))
    nil))

(defn- files-or-throw [paths]
  (let [files (vec (mapcat sources/tx-file-seq paths))]
    (if (seq files)
      files
      (cli-error :unknown-source-kind
                 (str "Couldn't find any terminology sources under "
                      (str/join ", " paths))
                 {:paths (vec paths)}))))

(defn- build-index [_opts paths]
  (run! index-one (files-or-throw paths)))

(defn- import-into!
  "Import release sources into the destination database `file`.

  Each input path is walked for recognisable files: RF2 components,
  LOINC table-core markers, FHIR JSON resources. A TRUD bundle with
  several sibling RF2 trees yields RF2 entries across all of them
  (Hermes recurses from each unique parent dir, so non-standard
  layouts work). RF2 (Hermes LMDB+Lucene) and FHIR-data (FTRM SQLite)
  are different storage formats and cannot share one `file`."
  [file paths]
  (let [files     (files-or-throw paths)
        ;; database-only = built artefact, can't be an import source
        unimport  (filter #(and (:database? %) (not (:importable? %))) files)
        rf2-dirs  (->> files (filter #(= :rf2 (:kind %))) (map :dir) distinct
                       (mapv #(.getPath ^File %)))
        loinc-dirs (->> files (filter #(= :loinc (:kind %))) (map :dir) distinct
                        (mapv #(.getPath ^File %)))
        fhir-json-files (->> files (filter #(= :fhir-json (:kind %))) (mapv :file))]
    (when (seq unimport)
      (cli-error ::unimportable-kind
                 (str "Cannot import already-built artefacts: "
                      (str/join ", " (map #(.getPath ^File (:file %)) unimport))
                      ". Use `serve` to open them directly.")
                 {:paths (mapv #(.getPath ^File (:file %)) unimport)}))
    (when (and (seq rf2-dirs) (or (seq loinc-dirs) (seq fhir-json-files)))
      (cli-error ::mixed-sources
                 (str "Cannot mix SNOMED RF2 with LOINC / FHIR JSON in one "
                      "import — they target different database formats.")
                 {:rf2 rf2-dirs :loinc loinc-dirs
                  :fhir-json (mapv #(.getPath ^File %) fhir-json-files)}))
    (when (seq rf2-dirs)        (import-rf2!       file rf2-dirs))
    (when (seq loinc-dirs)      (import-loinc!     file loinc-dirs))
    (when (seq fhir-json-files) (import-fhir-json! file fhir-json-files))))

(defn- import-from
  "`import <dest-db> <sources...>` — first positional is destination."
  [{:keys [no-index]} [dest & srcs]]
  (import-into! dest srcs)
  (when-not no-index
    (run! index-one (files-or-throw [dest]))))

(defn- list-rf2 [dir]
  (let [files (importer/importable-files dir)
        heading (str "| RF2 files in " dir ": " (count files) " |")
        banner (str/join (repeat (count heading) "="))]
    (println "\n" banner "\n" heading "\n" banner)
    (pp/print-table (map #(select-keys % [:filename :component :version-date
                                          :format :content-subtype :content-type])
                         files))))

(defn- list-loinc [dir]
  (let [ch (loinc-loader/stream-release dir)
        counts (loop [counts {}]
                 (if-let [batch (async/<!! ch)]
                   (let [events (if (map? batch) [batch] batch)]
                     (when-let [err (some #(when (= :stream-error (:type %)) %) events)]
                       (throw (:ex err)))
                     (recur (reduce (fn [counts fd]
                                      (update counts (:type fd) (fnil inc 0)))
                                    counts
                                    events)))
                   counts))
        heading (str "| LOINC release at " dir " |")
        banner (str/join (repeat (count heading) "="))]
    (println "\n" banner "\n" heading "\n" banner)
    (pp/print-table [{:resource "CodeSystem" :count (get counts :codesystem-meta 0)}
                     {:resource "concepts"   :count (get counts :concept 0)}
                     {:resource "ValueSets (AnswerLists)" :count (get counts :valueset 0)}
                     {:resource "ConceptMaps (MapTo)"     :count (get counts :conceptmap 0)}])))

(defn- list-fhir-json [files]
  (let [data (fhir-loader/load-files files)
        cs (->> data (filter #(= :codesystem-meta (:type %)))
                (map #(select-keys % [:url :version])))
        vs (->> data (filter #(= :valueset (:type %)))
                (map #(select-keys % [:url :version])))
        cm (->> data (filter #(= :conceptmap (:type %)))
                (map #(select-keys % [:url :version])))
        skipped (filter #(= :skipped (:type %)) data)]
    (println (str "\n=== FHIR JSON resources (" (count files) " file"
                  (when (not= 1 (count files)) "s") ") ==="))
    (println (str "  CodeSystems: " (count cs)
                  "  ValueSets: "   (count vs)
                  "  ConceptMaps: " (count cm)
                  "  (skipped: "    (count skipped) ")"))
    (when (seq cs) (println "\nCodeSystems:") (pp/print-table cs))
    (when (seq vs) (println "\nValueSets:")   (pp/print-table vs))
    (when (seq cm) (println "\nConceptMaps:") (pp/print-table cm))))

(defn- list-fhir-tx-db [path]
  (let [ds (sqlite-db/open path)]
    (try
      (let [resources (sqlite-db/list-resources ds)]
        (println (str "\n=== FHIR terminology container " path " ==="))
        (println (str "  Resources: " (count resources)))
        (pp/print-table (map #(select-keys % [:resource-type :url :version :concept-count])
                             resources)))
      (finally (sqlite-db/close! ds)))))

(defn- list-hermes-db [path]
  (let [st (hermes/status path {:counts? true :log? false})]
    (println (str "\n=== Hermes SNOMED database " path " ==="))
    (pp/pprint st)))

(defn- list-under [path]
  (try
    (let [files      (sources/tx-file-seq path)
          rf2-dirs   (->> files (filter #(= :rf2 (:kind %))) (map :dir) distinct
                          (mapv #(.getPath ^File %)))
          loinc-dirs (->> files (filter #(= :loinc (:kind %))) (map :dir) distinct
                          (mapv #(.getPath ^File %)))
          fhir-files (->> files (filter #(= :fhir-json (:kind %))) (mapv :file))
          fhir-tx    (filter #(= :fhir-tx-db (:kind %)) files)
          hermes     (filter #(= :hermes-db  (:kind %)) files)]
      (println (str "\n# " path
                    " — RF2: " (count rf2-dirs)
                    "  LOINC: " (count loinc-dirs)
                    "  FHIR JSON: " (count fhir-files)
                    "  FTRM: " (count fhir-tx)
                    "  Hermes-DB: " (count hermes)))
      (if (empty? files)
        (println "  ! no recognised terminology sources")
        (do (run! list-rf2        rf2-dirs)
            (run! list-loinc      loinc-dirs)
            (when (seq fhir-files)
              (list-fhir-json fhir-files))
            (run! list-fhir-tx-db (map #(.getPath ^File (:file %)) fhir-tx))
            (run! list-hermes-db  (map #(.getPath ^File (:dir %))  hermes)))))
    (catch ExceptionInfo e
      (println (str "  ! " path ": " (ex-message e))))))

(defn- list-from [_ args]
  (run! list-under args))

(defn- fetch-source!
  "Resolve a parsed `--dist` value to a local directory of importable resources.
  SNOMED → RF2 release dir via Hermes; FHIR package → unpacked tarball."
  [opts {:keys [id version]}]
  (cond
    (cli/snomed-distribution? id)
    (try
      (some-> (download/download id (cond-> (dissoc opts :dist)
                                      version (assoc :release-date version)))
              .toString)
      (catch IllegalArgumentException _
        (cli-error ::unknown-distribution
                   (str "Unrecognised SNOMED distribution: " id ". "
                        "Run `hades available` for a list."))))

    (cli/fhir-package? id)
    (let [v (or version
                (try (get-in (fhir-package/metadata id) [:dist-tags :latest])
                     (catch ExceptionInfo _ nil))
                (cli-error ::unknown-distribution
                           (str "Unrecognised FHIR package or no version found: " id)))]
      (.getPath ^File (fhir-package/download! id v (:cache-dir opts))))

    :else
    (cli-error ::unknown-distribution
               (str "Unrecognised distribution identifier: " id ". "
                    "Expected `<region>.<provider>/<id>` (SNOMED) "
                    "or reverse-DNS lowercase (FHIR package)."))))

(defn- list-source!
  "Print the available versions for one parsed `--dist` value. Returns
  true on success, false if the registry/listing failed or the id was
  unrecognised."
  [opts {:keys [id]}]
  (println (str "\n=== " id " ==="))
  (try
    (cond
      (cli/snomed-distribution? id)
      (do (download/download id (-> opts (dissoc :dist) (assoc :release-date "list")))
          true)

      (cli/fhir-package? id)
      (do (fhir-package/print-versions id) true)

      :else
      (do (println (str "  ! unrecognised id: " id)) false))
    (catch IllegalArgumentException _
      (println (str "  ! unrecognised distribution: " id))
      false)
    (catch ExceptionInfo e
      (println (str "  ! " (ex-message e)))
      false)))

(defn- install
  "`install <dest-db> --dist <id>...`"
  [{:keys [dist] :as opts} [dest]]
  (try
    (doseq [d dist]
      (when-let [path (fetch-source! opts d)]
        (import-into! dest [path])))
    (when-not (:no-index opts)
      (run! index-one (files-or-throw [dest])))
    (catch ConnectException e
      (cli-error ::network-error
                 (str "Could not connect to remote server: " (ex-message e))))))

(defn- available [{:keys [dist] :as opts} args]
  (when (seq args)
    (println (str "  ! ignoring positional argument(s) (use --dist for ids): "
                  (str/join " " args))))
  (if (seq dist)
    ;; Run every listing so the user sees results for the good ids; return
    ;; false if any failed so -main can exit non-zero (scripts can rely on it).
    (let [results (mapv #(list-source! opts %) dist)]
      (when (some false? results) false))
    (do
      (println "\n=== SNOMED CT distributions ===")
      (download/print-providers)
      (println "\n=== FHIR packages ===")
      (fhir-package/print-known)
      (println "")
      (println "List versions:   hades available --dist <id>")
      (println "Install:         hades install <db> --dist <id>[@<version>]"))))

(defn- compact [_opts paths]
  (run! compact-one (files-or-throw paths)))

(defn- log-catalogue-summary [svc]
  (log/info "service catalogue"
            {:codesystem-count (count (vec (protos/cs-metadata svc {})))
             :valueset-count   (count (vec (protos/vs-metadata svc {})))
             :conceptmap-count (count (vec (protos/cm-metadata svc {})))}))

(defn- build-svc
  "Open CLI positional paths as a Hades service."
  [{:keys [default locale]} paths]
  (let [svc (hades/open paths (cond-> {:defaults default}
                                locale (assoc :default-locale locale)))]
    (log-catalogue-summary svc)
    svc))

(defn- print-status-table [{:keys [codesystems valuesets conceptmaps]}]
  (let [more-line (fn [n shown]
                    (when (> n shown)
                      (println (str "  … and " (- n shown) " more"))))]
    (println "\n=== Hades terminology service status ===")
    (println (str "  CodeSystems: " (count codesystems)))
    (println (str "  ValueSets:   " (count valuesets)))
    (println (str "  ConceptMaps: " (count conceptmaps)))
    (when (seq codesystems)
      (println "\nCodeSystems:")
      (pp/print-table (take 50 codesystems))
      (more-line (count codesystems) 50))
    (when (seq valuesets)
      (println "\nValueSets (first 20):")
      (pp/print-table (take 20 valuesets))
      (more-line (count valuesets) 20))
    (when (seq conceptmaps)
      (println "\nConceptMaps:")
      (pp/print-table (take 30 conceptmaps)))))

(defn- status [{fmt :format :as opts} args]
  (let [svc (build-svc opts args)]
    (try
      (let [st {:codesystems (vec (sort-by (juxt :url :version) (protos/cs-metadata svc {})))
                :valuesets   (vec (sort-by (juxt :url :version) (protos/vs-metadata svc {})))
                :conceptmaps (vec (protos/cm-metadata svc {}))}]
        (case fmt
          :json (println (json/write-str st :escape-slash false :indent true))
          :edn  (pp/pprint st)
          (print-status-table st)))
      (finally
        (hades/close svc)))))

(defn- write-metadata [meta path]
  (spit path (with-out-str (json/pprint meta)))
  (log/info "wrote service metadata" {:path path}))

(defn- logback-config-source [^LoggerContext ctx]
  (or (some-> (ConfigurationWatchListUtil/getMainWatchURL ctx) str)
      (System/getProperty "logback.configurationFile")
      (some-> (.. Thread currentThread getContextClassLoader
                  (getResource "logback.xml"))
              str)))

(defn- describe-logback []
  (let [ctx ^LoggerContext (LoggerFactory/getILoggerFactory)
        root (.getLogger ctx Logger/ROOT_LOGGER_NAME)
        appenders (iterator-seq (.iteratorForAppenders root))]
    {:config    (logback-config-source ctx)
     :appenders (mapv (fn [^ch.qos.logback.core.Appender a]
                        {:name (.getName a) :class (.getName (class a))})
                      appenders)
     :async?    (boolean (some #(instance? AsyncAppender %) appenders))}))

(defn- log-logback-config! []
  (let [info (describe-logback)]
    (log/info "logback" info)
    (when-not (:async? info)
      (log/warn (str "synchronous log appender — under load, console writes can block "
                     "request threads. Use -Dlogback.configurationFile=logback-async.xml "
                     "for async logging.")))))

(defn- serve [{:keys [metadata-out port bind-address] :as opts} args]
  (set-default-uncaught-exception-handler)
  (log-logback-config!)
  (log/info "env" (-> (System/getProperties)
                      (select-keys ["os.name" "os.arch" "os.version" "java.vm.name" "java.vm.version"])
                      (update-keys keyword)))
  (let [svc (build-svc opts args)
        server-opts (cond-> {:port port}
                      bind-address (assoc :host bind-address))]
    (when metadata-out (write-metadata (hades/metadata svc) metadata-out))
    (log/info "starting Hades FHIR terminology server" server-opts)
    (http/start! (http/make-server svc server-opts))))

(defn- mcp [opts args]
  (set-default-uncaught-exception-handler)
  (let [svc (build-svc opts args)]
    (try
      (mcp-server/start! svc)
      (finally
        (hades/close svc)))))

(def ^:private commands
  {"import"    {:fn import-from}
   "list"      {:fn list-from}
   "install"   {:fn install}
   "available" {:fn available}
   "index"     {:fn build-index}
   "compact"   {:fn compact}
   "serve"     {:fn serve}
   "status"    {:fn status}
   "mcp"       {:fn mcp}})

(defn- usage
  ([options-summary]
   (->> ["Usage: hades [options] 'commands'"
         ""
         "For more help on a command, use hades --help 'command'"
         ""
         "Options:"
         options-summary
         ""
         "Commands:"
         (cli/format-commands)]
        (str/join \newline)))
  ([options-summary cmds]
   (let [n (count cmds)
         cmds' (map cli/commands cmds)
         {cmd-usage :usage cmd :cmd desc :desc long-desc :long} (first cmds')]
     (->> [(str "Usage: hades [options] " (if (= 1 n) (or cmd-usage cmd) (str/join " " cmds)))
           ""
           (if (= 1 n)
             (or long-desc desc)
             (str/join \newline (map cli/format-command cmds')))
           ""
           "Options:"
           options-summary]
          (str/join \newline)))))

(defn- exit [status-code msg]
  (when msg (println msg))
  (System/exit status-code))

(defn- print-error [msg]
  (binding [*out* *err*]
    (println (str "ERROR: " msg))))

(defn- friendly-error?
  "Return true if `e` carries a recognised `:reason` we should print as a
  one-line error rather than a stack trace."
  [e]
  (and (instance? ExceptionInfo e)
       (#{::mixed-sources
          ::unimportable-kind
          ::unknown-distribution
          ::network-error
          :com.eldrix.hades.impl.paths/release-source-not-served
          :unknown-source-kind}     ; raised from files-or-throw / paths/bundle-for-path
        (:reason (ex-data e)))))

(defn- invoke-command [cmd-name opts args]
  (try
    (if-let [err (cli/validate-invocation (cli/commands cmd-name) args opts)]
      (do (print-error err) false)
      ((:fn (commands cmd-name)) opts args))
    (catch ExceptionInfo e
      (if (friendly-error? e)
        (do (print-error (ex-message e)) false)
        (throw e)))))

(defn- unknown-commands [args]
  ;; Tokens that look like a bare command (no leading `-`, no `=`) but
  ;; don't match any known command. With no commands at all the user
  ;; gets the top-level usage instead of a per-token complaint.
  (->> args
       (remove #(or (str/starts-with? % "-")
                    (str/includes? % "=")))
       (remove cli/all-commands)))

(defn -main [& args]
  (let [{:keys [cmds options arguments summary errors]}
        (cli/parse-cli args)]
    (cond
      ;; --help wins over everything else, including missing :missing flags
      (and (seq cmds) (:help options))
      (do (println (usage summary cmds)) (System/exit 0))

      (:help options)
      (do (println (usage summary)) (System/exit 0))

      errors
      (exit 2 (str (str/join \newline (map #(str "ERROR: " %) errors))
                   "\n\n" (usage summary cmds)))

      (empty? cmds)
      (let [unknown (unknown-commands args)]
        (when (seq unknown)
          (print-error (str "unknown command: " (str/join " " unknown))))
        (exit 2 (usage summary)))

      :else
      (loop [[cmd & more] cmds]
        (when cmd
          (let [ok (invoke-command cmd options arguments)]
            (if (false? ok)
              (System/exit 1)
              (recur more))))))))

(comment
  (def svc (hades/open ["/path/to/snomed.db"]))
  (def srv (http/start! (http/make-server svc {:port 8080})))
  (http/stop! srv)
  (hades/close svc))
