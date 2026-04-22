(ns com.eldrix.hades.core
  "Command-line entry point for the Hades FHIR terminology server.

  Dispatches subcommands for SNOMED data acquisition (import/list/install/
  available), maintenance (index/compact/status), and running the server
  (serve). The terminology functionality is provided by Hermes; Hades wraps
  it in a FHIR-facing HTTP surface."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.cli :as cli]
            [com.eldrix.hades.registry :as registry]
            [com.eldrix.hades.server :as server]
            [com.eldrix.hades.snomed :as snomed]
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

(defn- register-snomed! [svc]
  (let [provider (snomed/->HermesService svc)]
    (registry/register-codesystem "http://snomed.info/sct" provider)
    (registry/register-codesystem "http://snomed.info/sct|*" provider)
    (registry/register-codesystem "sct" provider)
    (registry/register-valueset "http://snomed.info/sct" provider)
    (registry/register-valueset "http://snomed.info/sct|*" provider)
    (registry/register-valueset "sct" provider)
    (registry/register-concept-map-provider provider)))

;;; Subcommand implementations

(defn- import-from [{:keys [db]} args]
  (set-default-uncaught-exception-handler)
  (let [dirs (if (zero? (count args)) ["."] args)]
    (hermes/import-snomed db dirs :exclude importer/default-exclude)))

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
  (hermes/index db)
  (with-open [svc (hermes/open db {:quiet true})]
    (log-module-dependency-problems svc)))

(defn- compact [{:keys [db]} _]
  (hermes/compact db))

(defn- status [{:keys [db verbose modules refsets] fmt :format} _]
  (let [st (hermes/status db {:counts? true
                              :modules? (or verbose modules)
                              :installed-refsets? (or verbose refsets)
                              :log? false})]
    (case fmt
      :json (json/pprint st)
      (pp/pprint st))))

(defn- serve [{:keys [db port bind-address locale]} _]
  (set-default-uncaught-exception-handler)
  (log/info "env" (-> (System/getProperties)
                      (select-keys ["os.name" "os.arch" "os.version" "java.vm.name" "java.vm.version"])
                      (update-keys keyword)))
  (let [svc (hermes/open db (cond-> {} locale (assoc :default-locale locale)))
        server-opts (cond-> {:port port}
                      bind-address (assoc :host bind-address))]
    (log-module-dependency-problems svc)
    (register-snomed! svc)
    (log/info "starting Hades FHIR terminology server" server-opts)
    (server/start! (server/make-server server-opts))))

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
  (def svc (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (def srv (server/start! (server/make-server {:port 8080})))
  (server/stop! srv)

  (hermes/search svc {:s "mnd"})
  (hermes/concept svc 24700007)
  (hermes/preferred-synonym svc 233753001 "en")
  (hermes/release-information svc)
  (keys (hermes/extended-concept svc 138875005)))
