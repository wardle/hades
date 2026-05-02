(ns com.eldrix.hades.impl.loaders.fhir
  "FHIR JSON → seq of fhir-data.

  A pure data producer: parses FHIR JSON resources (from in-memory
  maps or filesystem paths) and emits a flat seq of `::protos/fhir-data`
  variants tagged by `:type`. Storage-neutral; downstream indexers
  consume the seq.

  Variants:
    :codesystem-meta   one per CodeSystem header (incl. supplements)
    :concept           one per concept (recursively flattened)
    :valueset          one per ValueSet
    :conceptmap        one per ConceptMap
    :skipped           diagnostic for unsupported / .index.json /
                       nested-Bundle entries

  Indexers ignore `:skipped`; boot drivers surface them in the load
  report. Symlinks are followed (admin/server-side loader). Hard
  failures (parse error, oversized file) throw `ex-info`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.eldrix.hades.impl.fhir-extract :as fhir-extract])
  (:import (java.io File)))

(def default-max-bytes
  "Default per-file size cap. Override per call via `:max-bytes`."
  (* 64 1024 1024))

;; ---------------------------------------------------------------------------
;; Resource → fhir-data
;; ---------------------------------------------------------------------------

(defn- skipped [source-path resource-type reason]
  {:type :skipped :source-path source-path
   :resource-type resource-type :reason reason})

(defn- cs-resource->fhir-data [cs-map source-path]
  (let [behavioural (fhir-extract/behavioural-fields cs-map)
        meta-entry  (-> behavioural
                        (assoc :type :codesystem-meta
                               :property-defs (vec (get cs-map "property"))
                               :filter-defs   (vec (get cs-map "filter"))
                               :metadata      (fhir-extract/cs-passthrough-metadata cs-map)
                               :source-path   source-path)
                        ;; FHIR default: case-sensitive when absent.
                        (update :case-sensitive #(if (nil? %) true %)))
        concepts    (map (fn [c]
                           (assoc c :type :concept
                                    :system  (:url behavioural)
                                    :version (:version behavioural)
                                    :source-path source-path))
                         (fhir-extract/flatten-concepts (get cs-map "concept")))]
    (cons meta-entry concepts)))

(defn- vs-resource->fhir-data [vs-map source-path]
  [(cond-> {:type :valueset
            :url      (get vs-map "url")
            :metadata (fhir-extract/vs-passthrough-metadata vs-map)
            :source-path source-path}
     (get vs-map "version")   (assoc :version   (get vs-map "version"))
     (get vs-map "compose")   (assoc :compose   (get vs-map "compose"))
     (get vs-map "expansion") (assoc :expansion (get vs-map "expansion")))])

(defn- cm-group->fhir-data [g]
  (cond-> {:source         (get g "source")
           :source-version (get g "sourceVersion")
           :target         (get g "target")
           :target-version (get g "targetVersion")
           :elements       (mapv (fn [e]
                                   {:code    (get e "code")
                                    :display (get e "display")
                                    :target  (mapv (fn [t]
                                                     (cond-> {:code        (get t "code")
                                                              :display     (get t "display")
                                                              :equivalence (get t "equivalence")}
                                                       (get t "comment")   (assoc :comment    (get t "comment"))
                                                       (get t "dependsOn") (assoc :depends-on (get t "dependsOn"))
                                                       (get t "product")   (assoc :product    (get t "product"))))
                                                   (get e "target"))})
                                 (get g "element"))}
    (get g "unmapped")
    (assoc :unmapped {:mode    (get-in g ["unmapped" "mode"])
                      :code    (get-in g ["unmapped" "code"])
                      :display (get-in g ["unmapped" "display"])
                      :url     (get-in g ["unmapped" "url"])})))

(defn- cm-resource->fhir-data [cm-map source-path]
  [{:type           :conceptmap
    :url            (get cm-map "url")
    :version        (get cm-map "version")
    :source-uri     (or (get cm-map "sourceUri") (get cm-map "sourceCanonical"))
    :source-version (get cm-map "sourceVersion")
    :target-uri     (or (get cm-map "targetUri") (get cm-map "targetCanonical"))
    :target-version (get cm-map "targetVersion")
    :metadata       cm-map
    :groups         (mapv cm-group->fhir-data (get cm-map "group"))
    :source-path    source-path}])

(declare resource->fhir-data)

(defn- bundle->fhir-data [bundle source-path]
  (mapcat (fn [entry]
            (let [resource (get entry "resource")]
              (cond
                (nil? resource) nil
                (= "Bundle" (get resource "resourceType"))
                [(skipped source-path "Bundle" :nested-bundle)]
                :else (resource->fhir-data resource source-path))))
          (get bundle "entry")))

(defn resource->fhir-data
  "Convert one parsed FHIR JSON resource into a seq of fhir-data.

  `source-path` defaults to `:tx-resource` (the sentinel for in-memory
  POST bodies); pass a filesystem path string for file-backed loads."
  ([resource] (resource->fhir-data resource :tx-resource))
  ([resource source-path]
   (case (get resource "resourceType")
     "CodeSystem" (cs-resource->fhir-data resource source-path)
     "ValueSet"   (vs-resource->fhir-data resource source-path)
     "ConceptMap" (cm-resource->fhir-data resource source-path)
     "Bundle"     (bundle->fhir-data resource source-path)
     [(skipped source-path (get resource "resourceType") :unsupported-resource-type)])))

(defn resources->fhir-data
  "Convert a seq of parsed FHIR JSON resources into a flat seq of
  fhir-data."
  ([resources] (resources->fhir-data resources :tx-resource))
  ([resources source-path]
   (mapcat #(resource->fhir-data % source-path) resources)))

;; ---------------------------------------------------------------------------
;; Filesystem walk
;; ---------------------------------------------------------------------------

(defn- json-file? [^File f]
  (and (.isFile f) (str/ends-with? (str/lower-case (.getName f)) ".json")))

(defn- read-json-skipping-bom
  "Skip a UTF-8 BOM (U+FEFF) if present, then read JSON. FHIR NPM
  packages occasionally include BOM-prefixed sidecar files (e.g.
  openapi specs) alongside resources; without this, `clojure.data.json`
  rejects them with 'unexpected character'."
  [^java.io.Reader r]
  (.mark r 1)
  (let [c (.read r)]
    (when-not (or (= c -1) (= c 0xFEFF))
      (.reset r)))
  (json/read r :bigdec false))

(defn- file->fhir-data [^File f ^long max-bytes]
  (when (json-file? f)
    (cond
      (= ".index.json" (.getName f))
      [(skipped (.getPath f) nil :index-json)]

      (> (.length f) max-bytes)
      (throw (ex-info (str "FHIR resource exceeds size limit: " (.getPath f)
                           " (" (.length f) " bytes > " max-bytes ")")
                      {:reason :max-bytes-exceeded :source-path (.getPath f)
                       :size   (.length f) :max-bytes max-bytes}))

      :else
      (try
        (with-open [r (io/reader f)]
          (resource->fhir-data (read-json-skipping-bom r) (.getPath f)))
        (catch Exception _
          ;; A directory walk may pick up sidecar JSON that isn't a FHIR
          ;; resource (openapi specs, project manifests). Treat parse
          ;; failures as soft skips so one malformed file doesn't abort
          ;; ingestion of the rest of the directory.
          [(skipped (.getPath f) nil :parse-error)])))))

(defn load-paths
  "Walk `root` (a path string, `File`, or `Path`) and return a flat
  seq of fhir-data. `root` may be a single `.json` file or a directory.

  Symlinks are followed: the operator chose `root`, so files reachable
  from it are in scope by definition.

  Soft skips (`.index.json`, unsupported `resourceType`, nested Bundle,
  malformed JSON sidecars) appear as `:type :skipped` entries in the
  returned seq with one of `:reason` values `:index-json`,
  `:unsupported-resource-type`, `:nested-bundle`, `:parse-error`.
  Hard failures (oversized file) throw `ex-info` with `:reason
  :max-bytes-exceeded`.

  Options:
    :max-bytes  per-file cap (default `default-max-bytes`, 64 MiB)."
  ([root] (load-paths root nil))
  ([root {:keys [max-bytes]}]
   (let [^File canonical-root (.getCanonicalFile (io/file root))
         max (long (or max-bytes default-max-bytes))]
     (mapcat #(file->fhir-data % max) (file-seq canonical-root)))))
