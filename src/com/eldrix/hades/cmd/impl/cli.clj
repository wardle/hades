(ns com.eldrix.hades.cmd.impl.cli
  "Command-line option parsing for the Hades FHIR terminology server."
  (:require [clojure.core.match :as match]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

;; Distribution-specific option sets: when the user names a SNOMED distribution
;; like `uk.nhs/sct-clinical`, pull in the flags that distribution's download
;; provider needs (TRUD API key, MLDS credentials, …).

(def uk-trud-opts
  [[nil "--api-key API-KEY-PATH" "Path to a file containing TRUD API key"
    :missing "Missing TRUD API key"]
   [nil "--cache-dir PATH" "Path to a download cache (optional)"
    :default-fn (fn [_] (System/getProperty "java.io.tmpdir"))
    :default-desc ""]
   [nil "--release-date DATE" "Date of release, ISO 8601. e.g. \"2022-02-03\" (optional)"]])

(def mlds-opts
  [[nil "--username USERNAME" "Username for MLDS"
    :missing "Missing username"]
   [nil "--password PASSWORD_FILE" "Path to a file containing password for MLDS"
    :missing "Missing password"]
   [nil "--release-date DATE" "Date of release, ISO 8601. e.g. \"2022-02-03\" (optional)"]])

(defn distribution-opts
  [id]
  (match/match (str/split id #"\p{Punct}")
    ["uk" "nhs" & _] uk-trud-opts
    [_ "mlds" & _] mlds-opts
    :else nil))

(def install-parameters
  #{"api-key" "cache-dir" "release-date" "username" "password"})

(def re-install-parameters
  (re-pattern (str "(" (str/join "|" install-parameters) ")=.*")))

(def all-options
  {:db           ["-d" "--db PATH" "Path to SNOMED database directory"]
   :port         ["-p" "--port PORT" "Port number"
                  :default 8080
                  :parse-fn parse-long
                  :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   :bind-address ["-a" "--bind-address BIND_ADDRESS" "Address to bind"]
   :locale       [nil "--locale LOCALE" "Set default / fallback locale (e.g. en-GB)"]
   :format       [nil "--format FMT" "Format for status output ('json' or 'edn')"
                  :parse-fn keyword :validate [#{:json :edn} "Format must be 'json' or 'edn'"]]
   :dist         [nil "--dist DST" "Distribution(s) e.g. uk.nhs/sct-clinical"
                  :multi true :default [] :update-fn conj :default-desc ""]
   :verbose      ["-v" "--verbose"]
   :progress     [nil "--progress" "Turn on progress reporting"]
   :help         ["-h" "--help"]})

(defn option
  ([opt] (option opt nil))
  ([opt extra-params] (into (all-options opt) (mapcat seq extra-params))))

(def db-mandatory {:missing "No path to database directory specified (use --db)"})

(defn make-distribution-options
  "Return CLI options for a distribution command, combining the given base
  options with any distribution-specific options detected from args."
  [base-opts args]
  (let [{:keys [arguments options]} (cli/parse-opts args base-opts)
        possible (into (set (:dist options)) (set arguments))
        dist-opts (distinct (mapcat distribution-opts possible))]
    (into base-opts dist-opts)))

(defn expand-legacy-parameters
  "Accept `api-key foo` or `api-key=foo` style pairs as synonyms for the
  `--api-key foo` flag. Preserved from hermes for compatibility."
  [args]
  (reduce (fn [acc v]
            (cond
              (install-parameters v)
              (conj acc (str "--" v))
              (re-matches re-install-parameters v)
              (let [[k v] (str/split v #"=")]
                (conj acc (str "--" k) v))
              :else
              (conj acc v)))
          []
          args))

(defn parse-legacy-distributions
  "Treat bare arguments that look like distribution names (e.g. `uk.nhs/sct-clinical`)
  as implicit `--dist` values."
  [{:keys [arguments] :as parsed}]
  (if-let [dists (seq (filter distribution-opts arguments))]
    (-> parsed
        (assoc :arguments (remove (set dists) arguments))
        (update-in [:options :dist] into dists))
    parsed))

(def commands*
  [{:cmd  "list" :usage "list [paths]"
    :desc "List importable files from the path(s) specified."
    :opts [(option :help)]}
   {:cmd  "import" :usage "import [paths]"
    :desc "Import SNOMED distribution files from the path(s) specified."
    :opts [(option :db db-mandatory) (option :help)]}
   {:cmd  "available" :desc "List available distributions, or releases for 'install'."
    :opts #(make-distribution-options [(option :dist) (option :progress) (option :help)] %)}
   {:cmd  "download" :usage "download [dists]" :deprecated true :warning "Use 'install' instead."
    :desc "Download and install specified distributions."
    :opts #(make-distribution-options [(option :db db-mandatory) (option :dist) (option :progress) (option :help)] %)}
   {:cmd  "install" :desc "Download and install specified SNOMED distribution(s)."
    :opts #(make-distribution-options [(option :db db-mandatory) (option :dist) (option :progress) (option :help)] %)}
   {:cmd  "index" :desc "Build search indices for a SNOMED database."
    :opts [(option :db db-mandatory) (option :help)]}
   {:cmd  "compact" :desc "Compact a SNOMED database."
    :opts [(option :db db-mandatory) (option :help)]}
   {:cmd  "status" :desc "Display status information for a SNOMED database."
    :opts [(option :db db-mandatory)
           (option :format)
           [nil "--modules" "Show installed modules"]
           [nil "--refsets" "Show installed refsets"]
           (option :help)]}
   {:cmd  "serve" :desc "Start the Hades FHIR terminology server."
    :opts [(option :db db-mandatory) (option :port) (option :bind-address)
           (option :locale) (option :help)]}])

(def commands
  (reduce (fn [acc {:keys [cmd] :as v}] (assoc acc cmd v)) {} commands*))

(def all-commands (set (map :cmd commands*)))

(defn format-command [{:keys [cmd usage desc]}]
  (str " " (format "%-14s" (or usage cmd)) " - " desc))

(defn format-commands []
  (->> commands*
       (remove :deprecated)
       (map format-command)
       (str/join \newline)))

(defn- warn-if-deprecated
  [{:keys [cmds] :as parsed}]
  (let [warnings (->> cmds
                      (map commands)
                      (filter :deprecated)
                      (map #(str "Command '" (:cmd %) "' is deprecated. " (:warning %))))]
    (if (seq warnings)
      (update parsed :warnings (fnil conj []) warnings)
      parsed)))

(defn opts-for-commands
  ([args] (opts-for-commands args #{}))
  ([args exclude]
   (->> (filter all-commands args)
        (map commands)
        (map :opts)
        (mapcat #(if (fn? %) (% args) %))
        (remove exclude)
        distinct
        (sort-by second))))

(defn parse-cli
  "Parse command-line arguments. Returns a map with:
    :cmds       — vector of commands requested, in order
    :options    — parsed parameters
    :arguments  — remaining arguments
    :warnings   — sequence of warnings (may be nil)
    :errors     — sequence of errors (may be nil)"
  ([args] (parse-cli args {}))
  ([args {:keys [exclude-opts]}]
   (let [excluded (set (keep all-options exclude-opts))
         cmds (filterv all-commands args)
         opts (or (seq (opts-for-commands args excluded)) [(option :help)])]
     (-> args
         expand-legacy-parameters
         (cli/parse-opts opts)
         (update :arguments #(remove (set cmds) %))
         (assoc :cmds cmds)
         parse-legacy-distributions
         warn-if-deprecated))))
