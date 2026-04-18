(ns com.eldrix.hades.registry
  "Dynamic registration of HL7 FHIR terminology services.

  Each codesystem and valueset is registered using id or uri.
  Each conceptmap is registered by tuples of id/uri and uri representing source
  and target respectively.

  In general, identifiers are represented as uris, but there is additional
  support for server-specific well-known identifiers. This means a given
  operation could use a URL encoded identifier or a property encoding the uri
  of the codesystem or valueset. For example, the following are equivalent:
  - [base]/ConceptMap/$translate
  - [base]/ConceptMap/[id]/$translate

  Registration by local (logical) id:
  CodeSystem.id: The logical id on the system that holds the CodeSystem resource
  instance - this typically is expected to change as the resource moves from
  server to server. The location URI is constructed by appending the logical id
  to the server base address where the instance is found and the resource type.
  This URI should be a resolvable URL by which the resource instance may be
  retrieved, usually from a FHIR server, and it may be a relative reference
  typically to the server base URL.

  Registration by URI:
  CodeSystem.url: The canonical URL that never changes for this code system - it
  is the same in every copy. The element is named url rather than uri for legacy
  reasons and to strongly encourage providing a resolvable URL as the identifier
  whenever possible. This canonical URL is used to refer to all instances of
  this particular code system across all servers and systems. Ideally, this URI
  should be a URL which resolves to the location of the master version of the
  code system, though this is not always possible."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hades.protocols :as protos]
            [lambdaisland.uri :as uri])
  (:import (com.eldrix.hades.protocols CodeSystem ConceptMap ValueSet)
           (java.net URI URISyntaxException)))


;; registered codesystems, valuesets and concept map providers
;; we don't use multimethods or other approaches for dynamic polymorphism
;; because we need to be able to report on registered providers at runtime
;; TODO: switch to simply using immutable data on startup?
(def codesystems (atom {}))
(def valuesets (atom {}))
(def conceptmaps (atom {}))


(s/def ::uri string?)
(s/def ::url ::uri)
(s/def ::system ::uri)
(s/def ::value string?)
(s/def ::identifier (s/keys :req-un [::system ::value]))
(s/def ::identifiers (s/coll-of ::identifier))
(s/def ::name string?)
(s/def ::title string?)
(s/def ::description string?)
(s/def ::codesystem (s/keys :req-un [::url ::identifiers]
                            :opt-un [::name ::title ::description]))

;; Request-scoped overlay context, built from tx-resource parameters.
;; Overlays are maps of {uri → protocol-impl}. All keys are optional;
;; nil and {} are equivalent (no overlay).
(s/def ::codesystems (s/map-of ::uri #(satisfies? protos/CodeSystem %)))
(s/def ::valuesets (s/map-of ::uri #(satisfies? protos/ValueSet %)))
(s/def ::conceptmaps (s/map-of ::uri #(satisfies? protos/ConceptMap %)))

;; Request-scoped parameters from the FHIR operation invocation.
(s/def ::lenient-display-validation boolean?)
(s/def ::system-version (s/map-of ::uri ::uri))
(s/def ::force-system-version (s/map-of ::uri ::uri))
(s/def ::check-system-version (s/map-of ::uri ::uri))
(def default-request
  "Default request parameters. Merged with actual request params at construction."
  {:lenient-display-validation true})

(s/def ::display-language (s/nilable string?))
(s/def ::value-set-version (s/nilable string?))
(s/def ::use-supplements (s/coll-of string?))
(s/def ::properties (s/coll-of string?))
(s/def ::request (s/keys :opt-un [::lenient-display-validation
                                  ::system-version
                                  ::force-system-version
                                  ::check-system-version
                                  ::display-language
                                  ::value-set-version
                                  ::use-supplements
                                  ::properties]))

(s/def ::ctx (s/nilable (s/keys :opt-un [::codesystems ::valuesets ::conceptmaps ::request])))

(defn uri-without-query
  [s]
  (str (assoc (uri/uri s) :query nil)))

(defn parse-versioned-uri
  "Split a FHIR canonical reference into [url version].
  'http://example.com/cs|1.0' => ['http://example.com/cs' '1.0']
  'http://example.com/cs'     => ['http://example.com/cs' nil]"
  [s]
  (when s
    (let [idx (.lastIndexOf ^String s "|")]
      (if (pos? idx)
        [(.substring ^String s 0 idx) (.substring ^String s (inc idx))]
        [s nil]))))

(defn versioned-uri
  "Construct a versioned canonical: url|version. Returns url if version is nil."
  [url version]
  (if version (str url "|" version) url))

(defn parse-version-param
  "Parse a seq of 'url|version' canonical strings into {url version} map."
  [params]
  (into {} (keep (fn [s] (let [[url ver] (parse-versioned-uri s)]
                            (when ver [url ver]))))
        params))

(defn version-matches?
  "Check if concrete version matches a pattern. 'x' segments are wildcards.
   '1.0.x' matches '1.0.0', '1.x.x' matches '1.2.0'.
   A shorter pattern is padded with 'x': '1.x' matches '1.0.0'."
  [pattern concrete]
  (when (and pattern concrete)
    (if (= pattern concrete)
      true
      (let [p-parts (clojure.string/split pattern #"\.")
            c-parts (clojure.string/split concrete #"\.")
            p-padded (if (< (count p-parts) (count c-parts))
                       (into (vec p-parts) (repeat (- (count c-parts) (count p-parts)) "x"))
                       p-parts)]
        (and (= (count p-padded) (count c-parts))
             (every? true? (map (fn [p c] (or (= "x" p) (= p c)))
                                p-padded c-parts)))))))

(defn available-versions
  "List all registered versions for a system URL."
  [ctx system]
  (let [prefix (str system "|")
        extract (fn [m]
                  (keep (fn [k]
                          (when (and (string? k) (.startsWith ^String k prefix))
                            (.substring ^String k (count prefix))))
                        (keys m)))]
    (distinct (concat (extract (:codesystems ctx))
                      (extract @codesystems)))))

(defn- semver-compare
  "Compare two version strings numerically by segment.
  '1.10.0' > '1.9.0' (unlike lexicographic compare)."
  [a b]
  (let [parse (fn [s] (mapv #(try (Integer/parseInt %) (catch Exception _ 0))
                             (clojure.string/split s #"\.")))
        pa (parse a)
        pb (parse b)
        max-len (max (count pa) (count pb))
        pad (fn [v] (into (vec v) (repeat (- max-len (count v)) 0)))]
    (compare (pad pa) (pad pb))))

(defn find-matching-version
  "Resolve a version pattern against available versions. If the pattern contains
   'x' wildcard segments, finds the latest matching registered version. Otherwise
   returns the pattern as-is."
  [ctx system pattern]
  (when pattern
    (if (some #(= "x" %) (clojure.string/split pattern #"\."))
      (reduce (fn [best v] (if (and (version-matches? pattern v)
                                    (or (nil? best) (pos? (semver-compare v best))))
                               v best))
              nil (available-versions ctx system))
      pattern)))

(defn register-codesystem
  "Register a codesystem implementation by virtue of URI and identifiers within
  the definition itself, and optionally, via a local, logical identifier."
  [uri-or-logical-id ^CodeSystem impl]
  (swap! codesystems assoc uri-or-logical-id impl))

(def ^:const wildcard-version "*")

(defn- lookup-impl
  "Look up an implementation from an overlay map and a global atom.
  Tries the key as-is, then url|version, then the wildcard url|*, then
  strips query params."
  [overlay-map global-atom key]
  (or (get overlay-map key)
      (get @global-atom key)
      (let [[base-url version] (parse-versioned-uri key)]
        (when version
          (or (get overlay-map (versioned-uri base-url version))
              (get @global-atom (versioned-uri base-url version))
              (get overlay-map (versioned-uri base-url wildcard-version))
              (get @global-atom (versioned-uri base-url wildcard-version)))))
      (when-let [uri (uri-without-query key)]
        (when (not= key uri)
          (or (get overlay-map uri)
              (get @global-atom uri))))))

(defn codesystem
  "Return a codesystem implementation for the given URI or logical id.
  Handles url|version syntax, wildcard url|* fallback, and query parameter
  stripping. When ctx is provided, its :codesystems overlay is checked before
  global atoms."
  (^CodeSystem [uri-or-logical-id] (codesystem nil uri-or-logical-id))
  (^CodeSystem [ctx uri-or-logical-id]
   (lookup-impl (:codesystems ctx) codesystems uri-or-logical-id)))

(defn register-valueset
  "Register a valueset implementation"
  [uri-or-logical-id ^ValueSet impl]
  (swap! valuesets assoc uri-or-logical-id impl))

(defn valueset
  "Return a valueset implementation for the given URI or logical id.
  Handles url|version syntax and query parameter fallback.
  When ctx is provided, its :valuesets overlay is checked before global atoms."
  (^ValueSet [uri-or-logical-id] (valueset nil uri-or-logical-id))
  (^ValueSet [ctx uri-or-logical-id]
   (lookup-impl (:valuesets ctx) valuesets uri-or-logical-id)))

(defn register-concept-map
  [source-uri target-uri ^ConceptMap impl]
  (swap! conceptmaps assoc (vector source-uri target-uri) impl))

(defn concept-map
  (^ConceptMap [uri-or-logical-id] (concept-map nil uri-or-logical-id))
  (^ConceptMap [ctx uri-or-logical-id]
   (or (get (:conceptmaps ctx) uri-or-logical-id)
       (if-let [cm (get @conceptmaps uri-or-logical-id)]
         cm
         (when-let [uri (uri-without-query uri-or-logical-id)]
           (when (not= uri-or-logical-id uri)
             (or (get (:conceptmaps ctx) uri)
                 (get @conceptmaps uri))))))))




(defn codesystem-resource
  ([params] (codesystem-resource nil params))
  ([ctx params]))

(s/fdef codesystem-lookup
  :args (s/cat :ctx ::ctx :params ::protos/codesystem-lookup))
(defn codesystem-lookup
  "Given a code/system, get additional details about the concept,
    including definition, status, designations, and properties. One of the
    products of this operation is a full decomposition of a code from a
    structured terminology."
  ([params] (codesystem-lookup nil params))
  ([ctx {:keys [system version] :as params}]
   (let [lookup-key (if version (versioned-uri system version) system)]
     (when-let [cs (codesystem ctx lookup-key)]
       (when-let [result (protos/cs-lookup cs params)]
         (assert (s/valid? ::protos/lookup-result result)
                 (str "cs-lookup returned invalid ::lookup-result: " (s/explain-str ::protos/lookup-result result)))
         result)))))

(defn- inactive-warning-issue [code status]
  {:severity     "warning"
   :type         "business-rule"
   :details-code "code-comment"
   :text         (str "The concept '" code "' has a status of " status " and its use should be reviewed")
   :expression   ["Coding"]})

(defn- add-inactive-warning
  "Add inactive concept warning(s) to a validate-code result when the concept is inactive.
  Always emits a generic 'status of inactive' warning; when the specific
  inactive-status differs (e.g. 'retired', 'deprecated'), emits an additional
  warning for that specific status."
  [result]
  (if (:inactive result)
    (let [code (name (:code result))
          specific (:inactive-status result)
          specific? (and specific (not= specific "inactive"))
          issues (cond-> [(inactive-warning-issue code "inactive")]
                   specific? (conj (inactive-warning-issue code specific)))
          msgs (cond-> [(:text (first issues))]
                 specific? (conj (:text (second issues))))
          combined (str/join "; " msgs)]
      (-> result
          (update :issues (fnil into []) issues)
          (update :message (fn [existing]
                             (if existing
                               (str existing "; " combined)
                               combined)))))
    result))

(defn codesystem-validate-code
  ([params] (codesystem-validate-code nil params))
  ([ctx {:keys [system code version] :as params}]
   (let [lookup-key (if version (versioned-uri system version) system)
         result (if-let [cs (codesystem ctx lookup-key)]
                  (-> (protos/cs-validate-code cs params)
                      (add-inactive-warning))
                  (let [msg (str "A definition for CodeSystem '" system "' could not be found, so the code cannot be validated")]
                    {:result  false
                     :code    (when code (keyword code))
                     :system  system
                     :message msg
                     :x-unknown-system system
                     :issues  [{:severity     "error"
                                :type         "not-found"
                                :details-code "not-found"
                                :text         msg
                                :expression   ["system"]}]}))]
     (assert (s/valid? ::protos/validate-result result)
             (str "cs-validate-code returned invalid ::validate-result: " (s/explain-str ::protos/validate-result result)))
     result)))

(defn codesystem-subsumes
  ([params] (codesystem-subsumes nil params))
  ([ctx {:keys [systemA systemB] :as params}]
   (when-not (= systemA systemB)
     (throw (ex-info "Currently, can only check subsumption within same codesystem" params)))
   (when-let [cs (codesystem ctx systemA)]
     (protos/cs-subsumes cs params))))

(defn codesystem-find-matches
  ([params] (codesystem-find-matches nil params))
  ([ctx {:keys [system version] :as params}]
   (let [lookup-key (if version (versioned-uri system version) system)]
     (when-let [cs (codesystem ctx lookup-key)]
       (protos/cs-find-matches cs params)))))

(defn codesystem-version
  "Get the version of a registered CodeSystem."
  ([system] (codesystem-version nil system))
  ([ctx system]
   (when-let [cs (codesystem ctx system)]
     (:version (protos/cs-resource cs {})))))

(defn check-supplement-refs
  "Validate that every supplement canonical referenced by an operation is
  registered. Combines op-level refs (`:use-supplements` in the request)
  with VS-level refs (`valueset-supplement` extensions on `vs-impl`).
  Returns nil when all supplements resolve; otherwise returns
  `{:message ::message :issues [::issue]}` for the first missing
  supplement, ready for the server to translate into a 4xx response."
  [ctx vs-impl]
  (let [op-refs (get-in ctx [:request :use-supplements])
        vs-refs (when vs-impl (:supplements (protos/vs-resource vs-impl {})))
        all-refs (distinct (concat op-refs vs-refs))
        missing (first (remove #(codesystem ctx %) all-refs))]
    (when missing
      (let [msg (str "Required supplement not found: " missing)]
        {:message msg
         :issues  [{:severity     "error"
                    :type         "not-found"
                    :details-code "not-found"
                    :text         msg}]}))))

(defn unknown-version-issue
  "Return an UNKNOWN_CODESYSTEM_VERSION issue if the caller's version doesn't
   correspond to a registered CS, or nil if the version exists. The optional
   `purpose` chooses the trailing message clause and defaults to the
   validate-code wording; pass `:expand` for the expand-context wording."
  ([ctx system version] (unknown-version-issue ctx system version :validate))
  ([ctx system version purpose]
   (when (and version system)
     (let [versioned-key (versioned-uri system version)]
       (when-not (codesystem ctx versioned-key)
         (let [valid (sort (available-versions ctx system))
               valid-str (if (seq valid)
                           (str ". Valid versions: " (clojure.string/join " or " valid))
                           "")
               clause (case purpose
                        :expand "the value set cannot be expanded"
                        "the code cannot be validated")
               base {:severity     "error"
                     :type         "not-found"
                     :details-code "not-found"
                     :text         (str "A definition for CodeSystem '" system "' version '" version
                                        "' could not be found, so " clause valid-str)}]
           ;; Expression points at the offending Coding for validate-code,
           ;; but the expand context error refers to the compose definition,
           ;; not a Coding — so omit the expression there.
           (cond-> base
             (not= purpose :expand) (assoc :expression ["Coding.system"]))))))))

(defn check-system-version-issue
  "Return a check-system-version error issue if the resolved version doesn't
   match the check pattern, or nil if the check passes."
  [ctx system resolved-version]
  (when-let [check-versions (get-in ctx [:request :check-system-version])]
    (when-let [check-pattern (get check-versions system)]
      (let [actual (or resolved-version (codesystem-version ctx system))]
        (when (and actual (not (version-matches? check-pattern actual)))
          {:severity     "error"
           :type         "exception"
           :details-code "version-error"
           :text         (str "The version '" actual "' is not allowed for system '"
                              system "': required to be '" check-pattern
                              "' by a version-check parameter")
           :expression   ["Coding.version"]})))))

(defn valueset-resource
  ([params] (valueset-resource nil params))
  ([ctx params]))

(defn valueset-expand
  ([params] (valueset-expand nil params))
  ([ctx {:keys [url valueSetVersion] :as params}]
   (let [lookup-key (if valueSetVersion (versioned-uri url valueSetVersion) url)]
     (when-let [vs (valueset ctx lookup-key)]
       (let [result (protos/vs-expand vs ctx params)]
         (assert (s/valid? ::protos/expansion-result result)
                 (str "vs-expand returned invalid ::expansion-result: " (s/explain-str ::protos/expansion-result result)))
         result)))))


(defn add-cs-status-warnings
  "Add informational issues for CodeSystem publication status (draft/retired/experimental)."
  [result ctx system]
  (if-let [cs (codesystem ctx system)]
    (let [meta (protos/cs-resource cs {})
          status (:status meta)
          experimental? (:experimental meta)
          version (:version meta)
          cs-ref (if version (str system "|" version) system)
          issues (cond-> []
                   (= "draft" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to draft CodeSystem " cs-ref)})
                   (= "retired" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to deprecated CodeSystem " cs-ref)})
                   experimental?
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to experimental CodeSystem " cs-ref)}))]
      (if (seq issues)
        (update result :issues (fn [existing] (vec (concat issues (or existing [])))))
        result))
    result))

(defn add-vs-status-warnings
  "Add informational issues for ValueSet publication status (retired = withdrawn)."
  [result ctx url]
  (if-let [vs (when url (valueset ctx url))]
    (let [meta (protos/vs-resource vs {})
          status (:status meta)
          version (:version meta)
          vs-ref (if version (str url "|" version) url)
          issues (cond-> []
                   (= "retired" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to withdrawn ValueSet " vs-ref)})
                   (= "draft" status)
                   (conj {:severity "information" :type "business-rule"
                          :details-code "status-check"
                          :text (str "Reference to draft ValueSet " vs-ref)}))]
      (if (seq issues)
        (update result :issues (fn [existing] (vec (concat (or existing []) issues))))
        result))
    result))

(defn valueset-validate-code
  ([params] (valueset-validate-code nil params))
  ([ctx {:keys [url system code valueSetVersion] :as params}]
   (let [vs-lookup (if url
                     (if valueSetVersion (versioned-uri url valueSetVersion) url)
                     (when system system))
         result (if-let [vs (when vs-lookup (valueset ctx vs-lookup))]
                  (protos/vs-validate-code vs ctx params)
                  (let [target (or vs-lookup url system)
                        msg (str "A definition for the value Set '" target "' could not be found")]
                    {:result    false
                     :not-found true
                     :code      (when code (keyword code))
                     :system    system
                     :message   msg
                     :issues    [{:severity     "error"
                                  :type         "not-found"
                                  :details-code "not-found"
                                  :text         msg}]}))]
     (assert (s/valid? ::protos/validate-result result)
             (str "vs-validate-code returned invalid ::validate-result: " (s/explain-str ::protos/validate-result result)))
     result)))

(defn valueset-validate-codeableconcept
  "Validate a CodeableConcept against a ValueSet. Iterates each coding,
  validates independently, and aggregates per the FHIR spec:
  - If any coding is valid, it provides the base result (but result may still
    be false if other codings have errors)
  - Per-coding not-in-vs issues are downgraded to this-code-not-in-vs (information)
  - If VS not found, propagates immediately
  - Returns ::protos/validate-result with all per-coding issues aggregated.

  `codings` is a seq of maps with :system :code :display :version.
  `base-params` is a map with :url and optionally :valueSetVersion :displayLanguage."
  [ctx codings base-params]
  (let [per-coding (map-indexed
                     (fn [idx coding-map]
                       (let [result (valueset-validate-code ctx
                                     (merge base-params
                                            (select-keys coding-map [:system :code :display :version])))]
                         ;; Tag issues with coding-index for expression adjustment,
                         ;; and downgrade not-in-vs to this-code-not-in-vs (informational)
                         ;; since in CC mode each coding's not-in-vs is secondary to
                         ;; the overall "no valid coding" error.
                         (cond-> (assoc result :coding-index idx)
                           (:issues result)
                           (update :issues (fn [issues]
                                             (mapv (fn [i]
                                                     (cond-> (assoc i :coding-index idx)
                                                       (= "not-in-vs" (:details-code i))
                                                       (assoc :details-code "this-code-not-in-vs"
                                                              :severity "information")))
                                                   issues))))))
                     codings)
        ;; VS not found? Propagate immediately.
        first-not-found (first (filter :not-found per-coding))]
    (if first-not-found
      first-not-found
      (let [valid (last (filter :result per-coding))
            invalid (remove :result per-coding)
            all-issues (vec (mapcat :issues invalid))
            cs-error-msgs (distinct (keep (fn [i] (when (= "invalid-code" (:details-code i)) (:text i)))
                                         all-issues))
            error-msg (first cs-error-msgs)]
        (if valid
          (cond-> valid
            (seq invalid) (assoc :result false)
            (seq all-issues) (update :issues (fnil into []) all-issues)
            error-msg (assoc :message error-msg))
          ;; No valid coding — check for version-issue results
          (let [version-issue-codes #{"vs-invalid" "version-error" "version-mismatch"}
                best-invalid (last (filter (fn [r]
                                             (and (:display r) (:system r)
                                                  (some #(contains? version-issue-codes (:details-code %))
                                                        (:issues r))
                                                  (not (some #(= "not-in-vs" (:details-code %))
                                                             (:issues r)))))
                                           per-coding))]
            (if best-invalid
              (assoc best-invalid :result false)
              (let [url (:url base-params)
                    vs-impl (valueset ctx url)
                    vs-ver (when vs-impl (:version (protos/vs-resource vs-impl {})))
                    vs-url-ver (if vs-ver (str url "|" vs-ver) url)
                    no-valid-msg (str "No valid coding was found for the value set '" vs-url-ver "'")
                    combined-msg (if (seq cs-error-msgs)
                                   (str no-valid-msg "; " (str/join "; " cs-error-msgs))
                                   no-valid-msg)
                    no-valid-issue {:severity "error" :type "code-invalid"
                                    :details-code "not-in-vs" :text no-valid-msg}]
                {:result  false
                 :message combined-msg
                 :issues  (into [no-valid-issue] all-issues)}))))))))

(defn conceptmap-resource
  ([params] (conceptmap-resource nil params))
  ([ctx params]))

(defn conceptmap-translate
  ([params] (conceptmap-translate nil params))
  ([ctx params]))