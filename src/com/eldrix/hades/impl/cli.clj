(ns com.eldrix.hades.impl.cli
  "Command-line option parsing for the Hades FHIR terminology server.

  Single positional rule: bare arguments are always filesystem paths.
  Installable identifiers (SNOMED distributions, FHIR packages) are
  carried via repeatable `--dist` flags only — there is no auto-
  promotion. This keeps every command's argument shape predictable:
  every positional argument is a path, every distribution id is a flag."
  (:require [clojure.core.match :as match]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(set! *warn-on-reflection* true)

(def cache-dir-opt
  [nil "--cache-dir PATH" "Download cache directory (optional)"
   :default-fn (fn [_] (System/getProperty "java.io.tmpdir"))
   :default-desc ""])

(def fhir-cache-dir-opt
  [nil "--cache-dir PATH" "FHIR package download cache directory (optional)"
   :default-fn (fn [_] (str (System/getProperty "java.io.tmpdir") "/hades-fhir-packages"))
   :default-desc ""])

(def uk-trud-opts
  [[nil "--api-key PATH" "File containing the TRUD API key"
    :missing "--api-key PATH is required for uk.nhs distributions"]
   cache-dir-opt])

(def registry-opt
  [nil "--registry URL" "FHIR package registry base URL (replaces the default registry chain)"])

(def fhir-opts
  [fhir-cache-dir-opt registry-opt])

(def mlds-opts
  [[nil "--username USER" "MLDS username"
    :missing "--username USER is required for MLDS distributions"]
   [nil "--password PATH" "File containing the MLDS password"
    :missing "--password PATH is required for MLDS distributions"]])

(defn bare-id
  "Strip an optional `@<version>` suffix from an installable identifier."
  [s]
  (when s (first (str/split s #"@" 2))))

(defn parse-dist
  "Parse a `--dist` value `id[@version]` into `{:id ... :version ...}`."
  [s]
  (let [[id version] (str/split s #"@" 2)]
    {:id id :version version}))

(defn snomed-distribution?
  "SNOMED CT distribution ids are `<region>.<provider>/<id>`."
  [s]
  (let [b (bare-id s)]
    (boolean (and b (str/includes? b "/")))))

(defn fhir-package?
  "FHIR package ids are reverse-DNS lowercase: `hl7.fhir.r4.core`."
  [s]
  (let [b (bare-id s)]
    (boolean (and b (re-matches #"[a-z][a-z0-9._-]*" b)))))

(defn distribution-opts
  "Per-provider options for an installable id (auth, cache, etc.).
  When an `install` line names multiple ids, the union of their option
  sets is accepted."
  [id]
  (cond
    (snomed-distribution? id)
    (match/match (str/split (bare-id id) #"\p{Punct}")
      ["uk" "nhs" & _] uk-trud-opts
      [_ "mlds" & _] mlds-opts
      :else nil)

    (fhir-package? id)
    fhir-opts))

(def all-options
  {:port         ["-p" "--port PORT" "Port number"
                  :default 8080
                  :parse-fn parse-long
                  :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   :bind-address ["-a" "--bind-address ADDR" "Address to bind"]
   :locale       [nil "--locale LOCALE" "Sets server default locale (e.g. en-GB)"]
   :format       [nil "--format FMT" "Output format ('json' or 'edn')"
                  :parse-fn keyword :validate [#{:json :edn} "Format must be 'json' or 'edn'"]]
   :default      [nil "--default URL=VERSION" "Bind a bare canonical URL to VERSION. Repeatable."
                  :multi true :default {} :default-desc ""
                  :parse-fn #(str/split % #"=" 2)
                  :validate [#(= 2 (count %)) "Must be URL=VERSION"]
                  :update-fn (fn [m [k v]] (assoc m k v))]
   :dist         [nil "--dist DIST" "Distribution id (optional @<version>). Repeatable."
                  :multi true :default [] :default-desc ""
                  :parse-fn parse-dist
                  :update-fn conj]
   :no-index     [nil "--no-index" "Skip auto-indexing"]
   :progress     [nil "--progress" "Show progress reporting"]
   :help         ["-h" "--help"]})

(defn option [opt] (all-options opt))

(defn- dist-values
  "Pull `--dist VALUE` and `--dist=VALUE` pairs out of `args` without
  invoking the full option parser. Used to figure out which extra
  distribution-specific options to include before the real parse runs."
  [args]
  (loop [acc [] [a & more] args]
    (cond
      (nil? a)               acc
      (= "--dist" a)         (if (and (seq more) (not (str/starts-with? (first more) "-")))
                               (recur (conj acc (first more)) (rest more))
                               (recur acc more))
      (str/starts-with? a "--dist=")
      (recur (conj acc (subs a (count "--dist="))) more)
      :else                  (recur acc more))))

(defn make-distribution-options
  "Return CLI options for a distribution command, combining the given base
  options with any distribution-specific options inferred from `--dist`
  values present in `args`. Pulls `--dist` values directly from the raw
  arg list so unknown distribution-specific flags don't cause a chicken-
  and-egg problem during option assembly."
  [base-opts args]
  (let [dist-opts (distinct (mapcat distribution-opts (dist-values args)))]
    (into base-opts dist-opts)))

(def commands*
  [{:cmd  "list" :usage "list <paths...>"
    :args {:min 1 :hint "<paths...>"}
    :desc "List importable contents (auto-detects sources)."
    :long (str/join \newline
            ["List the contents of each path. Source kind is auto-detected:"
             ""
             "  SNOMED RF2 release directory"
             "  LOINC release directory (LoincTableCore present)"
             "  FHIR JSON resources directory"
             "  FHIR-tx SQLite container"
             "  Hermes (LMDB) database"
             ""
             "Useful before `import` to confirm hades will pick up what you expect."
             ""
             "Examples:"
             "  hades list /path/to/snomed-rf2/"
             "  hades list /path/to/fhir-packages/"])
    :opts [(option :help)]}
   {:cmd  "import" :usage "import <dest-db> <sources...>"
    :args {:min 2 :hint "<dest-db> <sources...>"}
    :desc "Import sources into a destination database."
    :long (str/join \newline
            ["Import one or more local terminology sources into a destination"
             "database. The first positional argument is the destination path;"
             "subsequent positionals are sources. Source kind is auto-detected"
             "(SNOMED RF2, LOINC release, or FHIR JSON / FHIR package)."
             "Archive sources (.tgz/.tar.gz/.tar/.zip) are extracted to a"
             "temporary directory and imported from there. Existing Hades"
             "databases (Hermes, FHIR-tx) are not importable — use `serve`"
             "directly."
             ""
             "Auto-indexes the destination after import — the resulting DB is"
             "queryable by `serve` immediately. Pass `--no-index` to skip when"
             "you'll be calling install/import again into the same database;"
             "finish that workflow with `hades index <db>` or one final"
             "unflagged install/import."
             ""
             "Examples:"
             "  hades import snomed.db /path/to/snomed-rf2/"
             "  hades import loinc.db /path/to/Loinc_2.81/"
             "  hades import fhir.db  packages/hl7.fhir.r4.core-4.0.1/package"
             "  hades import fhir.db  packages/hl7.fhir.r4.core-4.0.1.tgz"
             "  hades import --no-index snomed.db /path/to/intl-rf2/"])
    :opts [(option :no-index) (option :help)]}
   {:cmd  "available" :usage "available [--dist <id>...]"
    :desc "List installable distributions, or releases for one."
    :long (str/join \newline
            ["Lists all available distributions, or when one or more `--dist`"
             "ids are given, lists releases/versions for each. Listing"
             "specific releases may require credentials (TRUD api-key, MLDS"
             "username/password) because some registries authenticate"
             "read access."
             ""
             "Examples:"
             "  hades available"
             "  hades available --dist uk.nhs/sct-clinical --api-key trud.txt"
             "  hades available --dist hl7.fhir.r4.core"])
    :opts #(make-distribution-options [(option :dist) (option :progress) (option :help)] %)}
   {:cmd  "install" :usage "install <dest-db> --dist <id>..."
    :args {:min 1 :max 1 :hint "<dest-db>"}
    :requires-opt :dist
    :desc "Download and import one or more distributions into a destination database."
    :long (str/join \newline
            ["Download a distribution and import it into the destination database"
             "given as the sole positional argument. Distributions are named via"
             "repeatable `--dist` flags; an id may carry an optional @<version>"
             "(e.g. uk.nhs/sct-clinical@2025-02-01, hl7.fhir.r4.core@4.0.1)."
             ""
             "Some distributions need additional parameters (TRUD api-key, MLDS"
             "credentials). Run `hades install --dist <id> --help` to see what a"
             "specific distribution accepts."
             ""
             "Auto-indexes the destination after install — the resulting DB is"
             "queryable by `serve` immediately. Pass `--no-index` to skip when"
             "you'll be calling install/import again into the same database;"
             "finish that workflow with `hades index <db>` or one final"
             "unflagged install/import."
             ""
             "Examples:"
             "  hades install snomed.db --dist uk.nhs/sct-clinical --api-key trud.txt"
             "  hades install fhir.db   --dist hl7.fhir.r4.core@4.0.1"
             "  hades install --no-index snomed.db --dist uk.nhs/sct-clinical --api-key trud.txt"])
    :opts #(make-distribution-options [(option :dist) (option :progress) (option :no-index) (option :help)] %)}
   {:cmd  "index" :usage "index <paths...>"
    :args {:min 1 :hint "<paths...>"}
    :desc "Build search indices on each database."
    :long (str/join \newline
            ["Build search indices on each database. Required after import"
             "for both SNOMED (Hermes) databases and FHIR-tx SQLite containers"
             "— `$expand` text filters and `descendant-of` rely on it."
             "Release source directories (RF2, LOINC, FHIR JSON) are silently"
             "skipped so chains like `import index compact <db> <release-dir>`"
             "work uniformly."
             ""
             "Examples:"
             "  hades index snomed.db"
             "  hades install index compact snomed.db --dist uk.nhs/sct-clinical \\"
             "                                       --api-key trud.txt"
             "  hades import index compact loinc.db /path/to/Loinc_2.81/"])
    :opts [(option :help)]}
   {:cmd  "compact" :usage "compact <paths...>"
    :args {:min 1 :hint "<paths...>"}
    :desc "Reduce on-disk size of each database."
    :long (str/join \newline
            ["Reduce on-disk size of each database:"
             ""
             "  Hermes (LMDB):    compact (rewrites the env, drops freed pages)."
             "  FHIR-tx SQLite:   VACUUM."
             ""
             "Run after import + index, or after a release upgrade. Compact is"
             "safe but takes time and disk (a temporary copy is written). Release"
             "source directories are silently skipped."
             ""
             "Examples:"
             "  hades compact snomed.db"])
    :opts [(option :help)]}
   {:cmd  "status" :usage "status <paths...>"
    :args {:min 1 :hint "<paths...>"}
    :desc "Show service status across given paths."
    :long (str/join \newline
            ["Boot the same service that `serve` would across the given paths and"
             "report what the composite catalogue knows: CodeSystems, ValueSets,"
             "ConceptMaps."
             ""
             "Useful to verify a multi-source setup before exposing it over HTTP,"
             "and as a smoke test after install + index + compact."
             ""
             "Examples:"
             "  hades status snomed.db"
             "  hades status snomed.db loinc.db --format json"])
    :opts [(option :format) (option :help)]}
   {:cmd  "serve" :usage "serve <paths...>"
    :args {:min 1 :hint "<paths...>"}
    :desc "Start the FHIR terminology server."
    :long (str/join \newline
            ["Start the Hades FHIR terminology server. Each positional path is a"
             "source; kind is auto-detected:"
             ""
             "  Hermes (LMDB) database              -> SNOMED provider"
             "  FHIR-tx SQLite container            -> CodeSystem/ValueSet/CM"
             "  Directory of FHIR JSON resources    -> in-memory provider"
             "  Extracted FHIR package (.../package)-> in-memory provider"
             "  FHIR package archive (.tgz/.zip)    -> in-memory provider"
             ""
             "Archive paths (.tgz/.tar.gz/.tar/.zip) are extracted to a"
             "temporary directory, read into memory, then removed."
             ""
             "Multiple paths combine — the composite dispatches by canonical URL."
             "Use --default URL=VERSION when multiple providers serve the same"
             "bare CodeSystem or ValueSet URL."
             ""
             "Examples:"
             "  hades serve snomed.db --port 8080"
             "  hades serve intl.db uk.db --default http://snomed.info/sct=http://snomed.info/sct/900000000000207008/version/20250201"
             "  hades serve snomed.db loinc.db packages/hl7.fhir.r4.core/package"
             "  hades serve snomed.db packages/hl7.fhir.r4.core-4.0.1.tgz"])
    :opts [(option :port) (option :bind-address) (option :locale)
           (option :default) (option :help)]}
   {:cmd  "mcp" :usage "mcp <paths...>"
    :args {:min 1 :hint "<paths...>"}
    :desc "Start the MCP (Model Context Protocol) server over stdio."
    :long (str/join \newline
            ["Start a Model Context Protocol server over stdio. Exposes Hades'"
             "FHIR terminology operations as MCP tools to any compliant host"
             "(Claude Desktop, Claude Code, Cursor). Each positional path is a"
             "source — kind is auto-detected, identical to `serve`."
             ""
             "Use --default URL=VERSION when multiple providers serve the same"
             "bare CodeSystem or ValueSet URL."
             ""
             "Examples:"
             "  hades mcp snomed.db"
             "  hades mcp snomed.db loinc.db packages/hl7.fhir.r4.core/package"])
    :opts [(option :locale) (option :default) (option :help)]}])

(def commands
  (reduce (fn [acc {:keys [cmd] :as v}] (assoc acc cmd v)) {} commands*))

(def all-commands (set (map :cmd commands*)))

(defn- usage-width []
  (apply max (map (comp count :usage) commands*)))

(defn format-command [{:keys [cmd usage desc]}]
  (let [w (max 14 (usage-width))]
    (str " " (format (str "%-" w "s") (or usage cmd)) " - " desc)))

(defn format-commands []
  (->> commands* (map format-command) (str/join \newline)))

(defn opts-for-commands
  ([args] (opts-for-commands args #{}))
  ([args exclude]
   (->> (filter all-commands args)
        (map commands)
        (map :opts)
        (mapcat #(if (fn? %) (% args) %))
        (remove exclude)
        ;; Dedupe by long-opt string. When the same option appears
        ;; with different annotations prefer the longer (most specified)
        ;; variant — tools.cli applies it once across the merged set.
        (group-by second)
        vals
        (map (partial apply max-key count))
        (sort-by second))))

(defn validate-invocation
  "Return nil if `actual-args` and `opts` satisfy `cmd-entry`'s declarative
  spec, otherwise an error message string. Spec keys:
    `:args` — `{:min N :max M :hint STR}`
    `:requires-opt` — option key that must be present (truthy & non-empty)."
  [{:keys [cmd args requires-opt]} actual-args opts]
  (let [{:keys [min max hint]} args
        n (count actual-args)
        suffix (when hint (str ": " hint))
        present? (fn [v] (and v (or (not (coll? v)) (seq v))))]
    (cond
      (and min (< n min))
      (str "'" cmd "' requires "
           (cond
             (and max (= max min)) (str "exactly " min " argument" (when (> min 1) "s"))
             (= 1 min) "at least one argument"
             :else (str "at least " min " arguments"))
           suffix)

      (and max (> n max))
      (str "'" cmd "' takes "
           (if (zero? max) "no positional arguments"
               (str "at most " max " argument" (when (> max 1) "s")))
           suffix)

      (and requires-opt (not (present? (get opts requires-opt))))
      (str "'" cmd "' requires --" (name requires-opt)))))

(defn parse-cli
  "Parse command-line arguments. Returns a map with:
    :cmds       — vector of commands requested, in order
    :options    — parsed flag values
    :arguments  — positional arguments (paths only — bare args are never
                  promoted to distribution ids)
    :errors     — sequence of errors (may be nil)
    :summary    — formatted option summary for help"
  ([args] (parse-cli args {}))
  ([args {:keys [exclude-opts]}]
   (let [excluded (set (keep all-options exclude-opts))
         cmds (filterv all-commands args)
         opts (or (seq (opts-for-commands args excluded)) [(option :help)])]
     (-> args
         (cli/parse-opts opts)
         (update :arguments #(remove (set cmds) %))
         (assoc :cmds cmds)))))
