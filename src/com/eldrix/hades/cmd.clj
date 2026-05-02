(ns com.eldrix.hades.cmd
  "Command-line entry point for the Hades FHIR terminology server."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.impl.cli :as cli]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.http :as http]
            [com.eldrix.hades.impl.index.sqlite :as sqlite-index]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as fhir-loader]
            [com.eldrix.hades.impl.loaders.loinc :as loinc-loader]
            [com.eldrix.hades.impl.snomed :as snomed]
            [com.eldrix.hades.impl.sources :as sources]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.download :as download]
            [com.eldrix.hermes.importer :as importer])
  (:import (clojure.lang ExceptionInfo)
           (java.net ConnectException)
           (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

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

;;; Subcommand implementations

(defn- single-db!
  "Extract the single `--db` value from `db-vec`. Single-source
  commands (import, index, status, compact, install) require exactly
  one. Exits with a friendly message on 0 or >1."
  [cmd db-vec]
  (cond
    (zero? (count db-vec))
    (do (println (str "ERROR: '" cmd "' requires exactly one --db PATH"))
        (System/exit 1))
    (> (count db-vec) 1)
    (do (println (str "ERROR: '" cmd "' takes exactly one --db, got "
                      (count db-vec) ": " (vec db-vec)))
        (System/exit 1))
    :else
    (first db-vec)))

(defn- import-rf2! [db dirs]
  (hermes/import-snomed db dirs :exclude importer/default-exclude))

(defn- import-loinc! [db src]
  (let [data (loinc-loader/load-paths src)
        result (sqlite-index/build! db data {:loader-type "loinc-csv"})]
    (log/info "loinc import complete" {:db db :resources (:resources result)})
    result))

(defn- import-fhir-json! [db srcs]
  (let [data (mapcat fhir-loader/load-paths srcs)
        result (sqlite-index/build! db data {:loader-type "fhir-json"})]
    (log/info "fhir-json import complete" {:db db :resources (:resources result)})
    result))

(defn- import-from [{:keys [db]} args]
  (set-default-uncaught-exception-handler)
  (let [db (single-db! "import" db)
        dirs (if (zero? (count args)) ["."] args)
        ;; All sources must agree on kind — mixing RF2 and LOINC into
        ;; one `import` call doesn't make sense; the destination DB is
        ;; format-specific.
        kinds (set (map sources/detect! dirs))]
    (when (> (count kinds) 1)
      (throw (ex-info (str "Mixed source kinds in one import: " (vec kinds))
                      {:reason :mixed-sources :kinds kinds :paths (vec dirs)})))
    (case (first kinds)
      :rf2   (import-rf2! db dirs)
      :loinc (do (when (> (count dirs) 1)
                   (throw (ex-info "LOINC import takes a single source path"
                                   {:reason :too-many-sources :paths (vec dirs)})))
                 (import-loinc! db (first dirs)))
      :fhir-json (import-fhir-json! db dirs))))

(defn- list-from [_ args]
  (let [dirs (if (zero? (count args)) ["."] args)
        metadata (map #(select-keys % [:name :effectiveTime :deltaFromDate :deltaToDate])
                      (mapcat importer/all-metadata dirs))]
    (pp/print-table metadata)
    (doseq [dir dirs]
      (let [files (importer/importable-files dir)
            heading (str "| Distribution files in " dir ":" (count files) " |")
            banner (str/join (repeat (count heading) "="))]
        (println "\n" banner "\n" heading "\n" banner)
        (pp/print-table (map #(select-keys % [:filename :component :version-date :format :content-subtype :content-type]) files))))))

(defn- install [{:keys [dist] :as opts} _]
  (if-not (seq dist)
    (do (println "No distribution specified. Specify with --dist.")
        (download/print-providers))
    (try
      (doseq [distribution dist]
        (when-let [unzipped-path (download/download distribution (dissoc opts :dist))]
          (import-from opts [(.toString unzipped-path)])))
      (catch ExceptionInfo e
        (log/error (ex-message e) (or (ex-data e) {}))
        (throw e))
      (catch ConnectException e
        (log/error "could not connect to remote server" (or (ex-message e) {}))
        (throw e))
      (catch Exception e
        (log/error (ex-message e))
        (throw e)))))

(defn- available [{:keys [dist] :as opts} _]
  (if-not (seq dist)
    (download/print-providers)
    (install (assoc opts :release-date "list") [])))

(defn- build-index [{:keys [db]} _]
  (let [db (single-db! "index" db)]
    (hermes/index db)
    (with-open [svc (hermes/open db {:quiet true})]
      (log-module-dependency-problems svc))))

(defn- compact [{:keys [db]} _]
  (hermes/compact (single-db! "compact" db)))

(defn- status [{:keys [db verbose modules refsets] fmt :format} _]
  (let [db (single-db! "status" db)
        st (hermes/status db {:counts? true
                              :modules? (or verbose modules)
                              :installed-refsets? (or verbose refsets)
                              :log? false})]
    (case fmt
      :json (json/pprint st)
      (pp/pprint st))))

(defn- providers-for-path
  "Open a path as one or more provider impls, choosing a constructor
  by detection. Returns a map `{:providers :closers :naming-systems}`
  — `:naming-systems` is non-empty only when the source advertises one
  (currently the SQLite catalogue's OID/URN resolver)."
  [path]
  (case (sources/detect! path)
    :hermes-db
    (let [hermes-svc (hermes/open path)]
      {:providers [(snomed/->HermesService hermes-svc)]
       :closers   [#(.close hermes-svc)]})

    :fhir-tx-db
    (let [{:keys [datasource codesystem valueset conceptmap naming-system]}
          (sqlite-provider/open-providers path)]
      {:providers      (filterv some? [codesystem valueset conceptmap])
       :naming-systems (when naming-system [naming-system])
       :closers        (when (instance? java.io.Closeable datasource)
                         [#(.close ^java.io.Closeable datasource)])})

    :fhir-json
    {:providers (vec (keep load-fhir/from-fhir (fhir-loader/load-paths path)))}

    (throw (ex-info (str "Path is a release source — run `import` first: " path)
                    {:path path}))))

(defn- providers-for-resources
  "Open a directory of FHIR JSON resources as in-memory providers.
  Always treats `path` as a directory of FHIR JSON regardless of
  detection — that's the explicit `--resources` semantics."
  [path]
  (let [data (fhir-loader/load-paths path)
        {:keys [providers supplements]}
        (load-fhir/build-from-fhir-data data)]
    {:providers   providers
     :supplements supplements}))

(defn- accumulate
  [acc f path]
  (merge-with into acc (f path)))

(defn- path-providers [path]
  (let [{:keys [providers closers naming-systems]} (providers-for-path path)]
    {:providers      (or providers [])
     :closers        (or closers [])
     :naming-systems (or naming-systems [])}))

(defn- resources-providers [path]
  (select-keys (providers-for-resources path) [:providers :supplements]))

(defn- build-svc
  "Translate CLI options into provider impls, then `hades/open` them."
  [{:keys [db resources]} positional-sources]
  (when-not (or (seq db) (seq resources) (seq positional-sources))
    (println "ERROR: 'serve' requires --db, --resources, or one or more positional source paths")
    (System/exit 1))
  (let [empty {:providers [] :closers [] :supplements [] :naming-systems []}
        from-paths     (reduce #(accumulate %1 path-providers %2)
                               empty (concat db positional-sources))
        from-resources (reduce #(accumulate %1 resources-providers %2)
                               empty resources)
        merged         (merge-with into from-paths from-resources)]
    (hades/open (:providers merged)
                {:supplements    (:supplements merged)
                 :closers        (:closers merged)
                 :naming-systems (:naming-systems merged)})))

(defn- write-metadata [meta path]
  (spit path (with-out-str (json/pprint meta)))
  (log/info "wrote service metadata" {:path path}))

(defn- serve [{:keys [metadata-out port bind-address] :as opts} positional-sources]
  (set-default-uncaught-exception-handler)
  (log/info "env" (-> (System/getProperties)
                      (select-keys ["os.name" "os.arch" "os.version" "java.vm.name" "java.vm.version"])
                      (update-keys keyword)))
  (let [svc (build-svc opts positional-sources)
        server-opts (cond-> {:port port}
                      bind-address (assoc :host bind-address))]
    (when metadata-out (write-metadata (hades/metadata svc) metadata-out))
    (log/info "starting Hades FHIR terminology server" server-opts)
    (http/start! (http/make-server svc server-opts))))

(def ^:private commands
  {"import"    {:fn import-from}
   "list"      {:fn list-from}
   "download"  {:fn install}
   "install"   {:fn install}
   "available" {:fn available}
   "index"     {:fn build-index}
   "compact"   {:fn compact}
   "serve"     {:fn serve}
   "status"    {:fn status}})

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
         {cmd-usage :usage cmd :cmd desc :desc} (first cmds')]
     (->> [(str "Usage: hades [options] " (if (= 1 n) (or cmd-usage cmd) (str/join " " cmds)))
           ""
           (if (= 1 n) desc (str/join \newline (map cli/format-command cmds')))
           ""
           "Options:"
           options-summary]
          (str/join \newline)))))

(defn- exit [status-code msg]
  (println msg)
  (System/exit status-code))

(defn- invoke-command [cmd opts args]
  (if-let [f (:fn cmd)]
    (f opts args)
    (exit 1 "ERROR: not implemented")))

(defn -main [& args]
  (let [{:keys [cmds options arguments summary errors warnings]}
        (cli/parse-cli args)]
    (doseq [warning warnings] (log/warn warning))
    (cond
      (and (seq cmds) (:help options))
      (println (usage summary cmds))
      (:help options)
      (println (usage summary))
      (empty? cmds)
      (exit 1 (usage summary))
      errors
      (exit 1 (str (str/join \newline (map #(str "ERROR: " %) errors)) "\n\n" (usage summary cmds)))
      :else
      (doseq [cmd cmds]
        (invoke-command (commands cmd) options arguments)))))

(comment
  (def svc (hades/open [(snomed/->HermesService (hermes/open "/path/to/snomed.db"))]))
  (def srv (http/start! (http/make-server svc {:port 8080})))
  (http/stop! srv)
  (hades/close svc))
