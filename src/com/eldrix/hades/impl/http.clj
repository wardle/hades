(ns com.eldrix.hades.impl.http
  "Pedestal HTTP layer for the Hades FHIR terminology server."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.providers.common.canonical :as canonical]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.providers.common.fhir-loader :as loaders]
            [com.eldrix.hades.impl.metadata :as metadata]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.impl.wire :as wire]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as ring]
            [io.pedestal.http.tracing :as tracing])
  (:import (java.nio.charset StandardCharsets)
           (java.sql SQLTransientConnectionException)))

(set! *warn-on-reflection* true)

(def ^:private write-json-fn
  "Cached charred writer. Configured once at boot so the option map is
  unpacked ahead of the hot path; charred recommends caching the fn
  rather than calling `write-json-str` per request. FHIR-friendly:
  no escape on `/` or JS separators."
  (charred/write-json-fn {:escape-js-separators false
                          :escape-slash         false}))

(defn- write-json-str
  ^String [data]
  (let [sw (java.io.StringWriter. 1024)]
    (write-json-fn sw data)
    (str sw)))

(defn- payload-too-large [size limit]
  (ex-info (str "Request body exceeds the configured limit of " limit " bytes (got " size ")")
           {:type         :payload-too-large
            :details-code "too-costly"}))

(defn- enforce-limit!
  "Throw `:payload-too-large` when `bs` exceeds `limit`; return `bs` otherwise."
  ^bytes [^bytes bs ^long limit]
  (when (> (alength bs) limit)
    (throw (payload-too-large (alength bs) limit)))
  bs)

(defn- read-bounded
  "Read up to `limit` bytes from `in` and return them; throw
  `:payload-too-large` if the stream has more. Asks for `limit + 1`
  bytes so a full result reveals the overflow. The JDK handles
  buffering internally; no intermediate copy."
  [^java.io.InputStream in ^long limit]
  (-> (.readNBytes in (if (< limit Integer/MAX_VALUE)
                        (unchecked-int (inc limit))
                        Integer/MAX_VALUE))
      (enforce-limit! limit)))

(defn- read-body
  [{:keys [body] :as request}]
  (let [limit (or (::max-body-bytes request) Long/MAX_VALUE)]
    (cond
      (nil? body)    nil
      (bytes? body)  (enforce-limit! body limit)
      (string? body) (enforce-limit! (.getBytes ^String body StandardCharsets/UTF_8) limit)
      :else          (with-open [in (io/input-stream body)]
                       (read-bounded in limit)))))

;; ---------------------------------------------------------------------------
;; FHIR Parameter accessors
;; ---------------------------------------------------------------------------

(def ^:private value-keys
  ["valueString" "valueUri" "valueUrl" "valueCanonical" "valueCode" "valueId"
   "valueBoolean" "valueInteger" "valueDecimal"
   "valueCoding" "valueCodeableConcept" "resource"])

(defn- param-value
  [p]
  (when p
    (reduce (fn [_ k] (when (contains? p k) (reduced (get p k))))
            nil value-keys)))

(defn- param-value-str
  "String-coerce the typed value of a single Parameter map. Returns nil
  for nested types (Coding / CodeableConcept / resource) — those have
  dedicated readers (`fhir-param-coding`, etc.)."
  [p]
  (let [v (param-value p)]
    (cond
      (nil? v)     nil
      (string? v)  v
      (boolean? v) (str v)
      (number? v)  (str v)
      :else        nil)))

(defn- entries
  [parameters nm]
  (filter #(= nm (get % "name")) parameters))

(defn fhir-param
  "First entry named `nm`, stringified."
  [fhir-params nm]
  (when (seq fhir-params)
    (some param-value-str (entries fhir-params nm))))

(defn fhir-param-all
  "All entries named `nm`, stringified. Repeats are preserved as a vector."
  [fhir-params nm]
  (when (seq fhir-params)
    (into [] (keep param-value-str) (entries fhir-params nm))))

(defn fhir-param-coding
  "First entry named `nm`, returning its `valueCoding` map or nil."
  [fhir-params nm]
  (when (seq fhir-params)
    (some #(get % "valueCoding") (entries fhir-params nm))))

(defn fhir-param-codeable-concept
  "First entry named `nm`, returning its `valueCodeableConcept` map or nil."
  [fhir-params nm]
  (when (seq fhir-params)
    (some #(get % "valueCodeableConcept") (entries fhir-params nm))))

(defn fhir-param-resources
  "All entries named `nm`, returning a seq of their `resource` maps."
  [fhir-params nm]
  (when (seq fhir-params)
    (keep #(get % "resource") (entries fhir-params nm))))

;; ---------------------------------------------------------------------------
;; Type coercions for stringified values.
;; ---------------------------------------------------------------------------

(defn- parse-bool [s]
  (cond
    (nil? s)     nil
    (boolean? s) s
    :else        (case (str/lower-case (str s))
                   "true"  true
                   "false" false
                   nil)))

(defn- parse-int [s]
  (cond
    (nil? s)    nil
    (number? s) (int s)
    :else       (try (Integer/parseInt (str s)) (catch Exception _ nil))))

(defn- build-overlay-providers
  "Build provider impls and supplement entries from a seq of FHIR JSON
  resource maps (string-keyed). Returns `{:providers :supplements}`."
  [resource-maps]
  (when (seq resource-maps)
    (let [fhir-data (loaders/resources->fhir-data resource-maps :tx-resource)
          {:keys [providers supplements]}
          (load-fhir/build-from-fhir-data fhir-data)]
      {:providers   providers
       :supplements supplements})))

(defn- supplements-missing-response
  "If any required supplement canonical is unresolved, return a 404
  OperationOutcome response map; otherwise nil."
  [svc vs-url params]
  (when-let [{:keys [message issues]} (composite/check-supplement-refs svc vs-url params)]
    {:status 404
     :body   (wire/operation-outcome
              (or issues [{:severity "error" :type "not-found" :text message}]))}))

;; ---------------------------------------------------------------------------
;; Cross-cutting interceptors
;; ---------------------------------------------------------------------------

(def log-request
  "Time each request and log a single line on the way out: method, uri,
  remote ip, status, elapsed ms."
  {:name  ::log-request
   :enter (fn [ctx] (assoc ctx ::start (System/nanoTime)))
   :leave (fn [{:keys [request response] ::keys [start] :as ctx}]
            (log/trace "request"
                       {:method (-> request :request-method name)
                        :uri    (:uri request)
                        :ip     (:remote-addr request)
                        :status (:status response)
                        :ms     (long (/ (- (System/nanoTime) start) 1e6))})
            ctx)})

(def render-json
  "Serialise map response bodies to FHIR JSON via the cached charred
  writer and set the FHIR content-type header. Non-map bodies pass
  through untouched."
  {:name  ::render-json
   :leave (fn [context]
            (let [body (get-in context [:response :body])]
              (if (map? body)
                (-> context
                    (assoc-in [:response :body]
                              (write-json-str body))
                    (assoc-in [:response :headers "Content-Type"]
                              "application/fhir+json; charset=utf-8"))
                context)))})

(defn- issue->status
  "Map a FHIR `OperationOutcome.issue.type` to an HTTP status. Used
  when a handler completes normally but the result carries an
  error-severity issue. Mirrors `exception-response`'s keyword-keyed
  mapping so a typed failure surfaced via the result map and the same
  failure raised as `ex-info` produce the same status."
  [issue]
  (case (:type issue)
    "not-found"     404
    "not-supported" 501
    "processing"    422
    "invalid"       422
    "exception"     500
    422))

(defn- exception-response
  [^Throwable ex]
  (let [data   (ex-data ex)
        t      (:type data)
        status (case t
                 :not-found     404
                 :not-supported 501
                 :processing    422
                 :invalid       422
                 :bad-request   400
                 :payload-too-large 413
                 500)
        issues (or (:issues data)
                   [{:severity     "error"
                     :type         (if t (name t) "exception")
                     :details-code (:details-code data)
                     :text         (or (ex-message ex) "Internal server error")}])]
    {:status status
     :body   (wire/operation-outcome issues)}))

(defn- saturation-cause
  "Walk the cause chain and return the first SQLTransientConnectionException
  found, or nil. HikariCP throws this when `connectionTimeout` elapses
  with no connection available."
  ^Throwable [^Throwable ex]
  (loop [t ex]
    (cond
      (nil? t) nil
      (instance? SQLTransientConnectionException t) t
      :else (recur (.getCause t)))))

(defn- saturation-response []
  ;; 503 + Retry-After: 1 + throttled OperationOutcome — the load-shedding
  ;; signal a client can back off on. Body shape matches every other error
  ;; path, so clients parse it the same way.
  {:status  503
   :headers {"Retry-After" "1"}
   :body    (wire/operation-outcome
             [{:severity "error"
               :type     "throttled"
               :text     "The server is temporarily out of database connection capacity. Please retry."}])})

(def catch-all-error
  "Map any thrown exception to a FHIR OperationOutcome response: typed
  ex-info `:type` keys steer the HTTP status, HikariCP saturation maps
  to 503 + `Retry-After: 1`, and unknown exceptions log + 500."
  {:name  ::catch-all-error
   :error (fn [context ex]
            (if-let [cause (saturation-cause ex)]
              ;; Saturation is high-volume during overload: log without a stack
              ;; trace so the signal isn't drowned. The Hikari pool name is in
              ;; the cause's message.
              (do (log/warn "connection pool saturated:" (.getMessage cause))
                  (assoc context :response (saturation-response)))
              (let [t     (:type (ex-data ex))
                    cause (or (.getCause ^Throwable ex) ex)
                    msg   (or (ex-message cause) (ex-message ex))]
                (if t
                  (log/info "Handled" t msg)
                  (log/error ex "Unhandled exception"))
                (assoc context :response (exception-response ex)))))})

(defn inject-svc
  "Inject the configured service onto each request as `::svc`."
  [svc]
  {:name ::inject-svc
   :enter (fn [context]
            (assoc-in context [:request ::svc] svc))})

(defn- fhir-json? [request]
  (when-let [ct (get-in request [:headers "content-type"])]
    (str/includes? (str/lower-case ct) "application/fhir+json")))

(defn- read-fhir-parameters
  "Bounded slurp + charred parse of a FHIR `Parameters` body. Returns the
  vector under `parameter`, or nil for an empty body."
  [request]
  (when-let [bs (read-body request)]
    (let [s      (String. ^bytes bs StandardCharsets/UTF_8)
          parsed (try
                   (when (seq s) (charred/read-json s))
                   (catch Exception e
                     (throw (ex-info (str "Malformed Parameters body: " (.getMessage e))
                                     {:type         :bad-request
                                      :details-code "invalid"}
                                     e))))]
      (get parsed "parameter"))))

(defn- query->fhir-entries
  "Coerce the parsed `:query-params` map into FHIR Parameter entries.
  Single string values become one entry; repeated values become one
  entry per value (preserving order)."
  [query-params]
  (reduce-kv
   (fn [acc k v]
     (let [vs (if (sequential? v) v [v])]
       (into acc (map (fn [x] {"name" k "valueString" x})) vs)))
   [] (or query-params {})))

(def parse-fhir-params
  "Normalise the request's operation parameters into a single
  `::fhir-params` vector of FHIR Parameter entries:
    - GET query string → synthesised `valueString` entries (first)
    - POST `application/fhir+json` body → parsed entries (after)
  Honours `::max-body-bytes` for body reads."
  {:name  ::parse-fhir-params
   :enter (fn [{:keys [request] :as context}]
            (let [from-query (query->fhir-entries (:query-params request))
                  from-body  (when (and (= :post (:request-method request))
                                        (fhir-json? request))
                               (read-fhir-parameters request))
                  combined   (into from-query (or from-body []))]
              (assoc-in context [:request ::fhir-params] combined)))})

(defn- inline-valueset-overlay
  "When the request carries an inline `valueSet` parameter and no
  caller-supplied `url`, build a single-provider overlay for it. Returns
  `{:providers [...] :overlay-url ...}` or nil."
  [fhir-params]
  (when (nil? (fhir-param fhir-params "url"))
    (when-let [inline-vs (first (fhir-param-resources fhir-params "valueSet"))]
      (let [synth-url (or (get inline-vs "url")
                          (str "urn:hades:inline:" (java.util.UUID/randomUUID)))
            vs-map    (cond-> inline-vs
                        (nil? (get inline-vs "url")) (assoc "url" synth-url))
            provider  (load-fhir/from-fhir vs-map)]
        {:providers [provider] :overlay-url synth-url}))))

(def tx-overlay
  "Overlay any request-scoped CodeSystem / ValueSet / ConceptMap
  resources onto `::svc`:
    - `tx-resource` Parameters entries → providers + supplement entries
    - inline `valueSet` (POST `$expand` only) → single provider with a
      synthetic URL exposed as `::overlay-url`

  Reads `::fhir-params`."
  {:name  ::tx-overlay
   :enter (fn [{:keys [request] :as context}]
            (let [fhir-params  (::fhir-params request)
                  tx-built     (build-overlay-providers
                                (fhir-param-resources fhir-params "tx-resource"))
                  inline       (inline-valueset-overlay fhir-params)
                  providers    (into (vec (:providers tx-built))
                                     (:providers inline))
                  supplements  (:supplements tx-built)]
              (cond-> context
                (seq providers)
                (update-in [:request ::svc]
                           #(hades/with-overlays % providers
                              (cond-> {} supplements (assoc :supplements supplements))))

                (:overlay-url inline)
                (assoc-in [:request ::overlay-url] (:overlay-url inline)))))})

(defn- collect-version-param [fhir-params nm]
  (let [values (fhir-param-all fhir-params nm)]
    (when (seq values)
      (canonical/parse-version-param values))))

(def request-flags
  "Extract request-scoped operation flags (`system-version`,
  `force-system-version`, `check-system-version`, `useSupplement`,
  `property`, `valueSetVersion`, `displayLanguage`,
  `lenient-display-validation`) from `::fhir-params` into `::flags`.
  Handlers pass these to `core/*` ops via `merge-flags`."
  {:name  ::request-flags
   :enter (fn [{:keys [request] :as context}]
            (let [fhir-params (::fhir-params request)
                  supps       (into (fhir-param-all fhir-params "useSupplement")
                                    (fhir-param-all fhir-params "useSupplements"))
                  props       (fhir-param-all fhir-params "property")
                  lenient     (parse-bool (fhir-param fhir-params "lenient-display-validation"))
                  sys-ver     (collect-version-param fhir-params "system-version")
                  force-ver   (collect-version-param fhir-params "force-system-version")
                  check-ver   (collect-version-param fhir-params "check-system-version")
                  vs-version  (fhir-param fhir-params "valueSetVersion")
                  display-lang (fhir-param fhir-params "displayLanguage")
                  flags       (cond-> {:lenient-display-validation (boolean lenient)}
                                sys-ver       (assoc :system-version sys-ver)
                                force-ver     (assoc :force-system-version force-ver)
                                check-ver     (assoc :check-system-version check-ver)
                                (seq supps)   (assoc :use-supplements supps)
                                (seq props)   (assoc :properties props)
                                vs-version    (assoc :value-set-version vs-version)
                                display-lang  (assoc :display-language display-lang))]
              (assoc-in context [:request ::flags] flags)))})

(defn- pick-display-language
  "Effective display language: the `:display-language` flag if set
  (parsed from the `displayLanguage` operation parameter), else the
  request's `Accept-Language` header."
  [request flags]
  (or (:display-language flags)
      (get-in request [:headers "accept-language"])))

(defn merge-flags
  "Merge request flags into operation params (flat). Used by every
  handler before delegating to `core/*`."
  [op-params flags]
  (merge (select-keys flags
                      [:lenient-display-validation
                       :system-version
                       :force-system-version
                       :check-system-version
                       :use-supplements
                       :properties])
         op-params))

;; ---------------------------------------------------------------------------
;; CodeSystem $lookup
;; ---------------------------------------------------------------------------

(defn- cs-lookup
  [{::keys [svc flags fhir-params] :as request}]
  (let [coding   (fhir-param-coding fhir-params "coding")
        system   (or (fhir-param fhir-params "system") (get coding "system"))
        code     (or (fhir-param fhir-params "code")   (get coding "code"))
        version  (fhir-param fhir-params "version")
        display-lang (pick-display-language request flags)]
    (or (supplements-missing-response svc nil flags)
        (let [lookup-params (-> (cond-> {:system system :code code}
                                  version      (assoc :version version)
                                  display-lang (assoc :displayLanguage display-lang))
                                (merge-flags flags))
              result (hades/lookup svc lookup-params)]
          (if (:not-found result)
            {:status 404 :body (wire/operation-outcome (:issues result))}
            {:status 200 :body (wire/lookup->parameters result)})))))

;; ---------------------------------------------------------------------------
;; CodeSystem $validate-code
;; ---------------------------------------------------------------------------

(defn- cs-validate-code
  [{::keys [svc flags fhir-params] :as request}]
  (or (supplements-missing-response svc nil flags)
      (let [coding   (fhir-param-coding fhir-params "coding")
            coding?  (and coding (get coding "code"))
            system   (or (fhir-param fhir-params "url")
                         (fhir-param fhir-params "system")
                         (when coding? (get coding "system")))
            code     (or (fhir-param fhir-params "code")
                         (when coding? (get coding "code")))
            display  (or (fhir-param fhir-params "display")
                         (when coding? (get coding "display")))
            version  (fhir-param fhir-params "version")
            display-lang (pick-display-language request flags)
            vp       (-> (cond-> {:system system :code code}
                           display      (assoc :display display)
                           version      (assoc :version version)
                           display-lang (assoc :displayLanguage display-lang))
                         (merge-flags flags))
            input-mode (if coding? :coding :code)
            result     (hades/validate-code svc vp)
            result'    (if (:issues result)
                         (update result :issues wire/adjust-issue-expressions input-mode nil)
                         result)]
        {:status 200 :body (wire/validate->parameters result')})))

;; ---------------------------------------------------------------------------
;; CodeSystem $subsumes
;; ---------------------------------------------------------------------------

(defn- cs-subsumes
  [{::keys [svc fhir-params]}]
  (let [codingA (fhir-param-coding fhir-params "codingA")
        codingB (fhir-param-coding fhir-params "codingB")
        codeA   (fhir-param fhir-params "codeA")
        codeB   (fhir-param fhir-params "codeB")
        system  (fhir-param fhir-params "system")
        subs-params (cond
                      (and codeA codeB system)
                      {:systemA system :codeA codeA :systemB system :codeB codeB}
                      (and codingA codingB)
                      {:systemA (get codingA "system") :codeA (get codingA "code")
                       :systemB (get codingB "system") :codeB (get codingB "code")})]
    (if subs-params
      (let [result (hades/subsumes svc subs-params)]
        (cond
          (:not-found result)
          {:status 404 :body (wire/operation-outcome (:issues result))}

          (seq (:issues result))
          {:status (issue->status (first (:issues result)))
           :body   (wire/operation-outcome (:issues result))}

          :else
          {:status 200 :body (wire/subsumes->parameters result)}))
      {:status 400
       :body   (wire/operation-outcome
                [{:severity "error" :type "invalid"
                  :text "$subsumes requires either (codeA, codeB, system) or (codingA, codingB)"}])})))

;; ---------------------------------------------------------------------------
;; ValueSet $validate-code
;; ---------------------------------------------------------------------------

(defn- cc-codings
  [cc system-version]
  (mapv (fn [c]
          (let [sys  (get c "system")
                code (get c "code")
                disp (get c "display")
                ver  (or system-version (get c "version"))]
            (cond-> {:system sys :code code}
              disp (assoc :display disp)
              ver  (assoc :version ver))))
        (get cc "coding" [])))

(defn- vs-validate-code
  [{::keys [svc flags fhir-params] :as request}]
  (let [url (fhir-param fhir-params "url")]
    (or (supplements-missing-response svc url flags)
        (let [cc      (fhir-param-codeable-concept fhir-params "codeableConcept")
              codings (seq (get cc "coding"))
              coding  (fhir-param-coding fhir-params "coding")
              coding? (and coding (get coding "code"))
              value-set-version (:value-set-version flags)
              display-lang (pick-display-language request flags)
              base    (-> (cond-> {:url url}
                            value-set-version (assoc :valueSetVersion value-set-version)
                            display-lang      (assoc :displayLanguage display-lang))
                          (merge-flags flags))
              result  (cond
                        codings
                        (-> (hades/validate-codeable-concept svc
                                                             (cc-codings cc (fhir-param fhir-params "systemVersion"))
                                                             base)
                            (assoc :codeableConcept cc))

                        :else
                        (let [system  (or (fhir-param fhir-params "system")
                                          (when coding? (get coding "system")))
                              code    (or (fhir-param fhir-params "code")
                                          (when coding? (get coding "code")))
                              display (or (fhir-param fhir-params "display")
                                          (when coding? (get coding "display")))
                              version (or (fhir-param fhir-params "systemVersion")
                                          (when coding? (get coding "version")))]
                          (hades/validate-code svc
                                               (cond-> (assoc base :system system :code code)
                                                 display (assoc :display display)
                                                 version (assoc :version version)))))
              input-mode (cond codings :codeableConcept coding? :coding :else :code)
              result' (if (:issues result)
                        (update result :issues wire/adjust-issue-expressions input-mode nil)
                        result)]
          (if (:not-found result')
            {:status 404 :body (wire/operation-outcome (:issues result'))}
            {:status 200 :body (wire/validate->parameters result')})))))

;; ---------------------------------------------------------------------------
;; ValueSet $expand
;; ---------------------------------------------------------------------------

(defn- vs-expand
  [{::keys [svc flags fhir-params overlay-url max-expansion-size] :as request}]
  (let [url            (or (fhir-param fhir-params "url") overlay-url)]
    (or (supplements-missing-response svc url flags)
        (let [active-only?    (parse-bool (fhir-param fhir-params "activeOnly"))
              filter-value    (fhir-param fhir-params "filter")
              display-lang    (pick-display-language request flags)
              include-desig?  (parse-bool (fhir-param fhir-params "includeDesignations"))
              count-value     (parse-int  (fhir-param fhir-params "count"))
              offset-value    (parse-int  (fhir-param fhir-params "offset"))
              exclude-nested-input    (fhir-param fhir-params "excludeNested")
              exclude-nested-present? (some? exclude-nested-input)
              exclude-nested? (let [v (parse-bool exclude-nested-input)]
                                (if (nil? v) true v))
              req-properties  (:properties flags)
              expand-params   (-> (cond-> {:url             url
                                           :activeOnly      active-only?
                                           :filter          filter-value
                                           :displayLanguage display-lang
                                           :count           count-value
                                           :offset          offset-value}
                                    (seq req-properties) (assoc :properties req-properties))
                                  (merge-flags flags))
              result          (hades/expand svc expand-params)
              error-issue     (first (filter #(= "error" (:severity %)) (:issues result)))]
          (cond
            (nil? result)
            {:status 404
             :body   (wire/operation-outcome
                      [{:severity "error" :type "not-found"
                        :text (str "A definition for the value Set '" url "' could not be found")}])}

            error-issue
            {:status (issue->status error-issue) :body (wire/operation-outcome (:issues result))}

            (and max-expansion-size (nil? count-value) (> (or (:total result) 0) max-expansion-size))
            {:status 422
             :body   (wire/operation-outcome
                      [{:severity "error" :type "too-costly"
                        :text (str "The value set '" url "' expansion has too many codes to display (>"
                                   max-expansion-size ")")}])}

            :else
            (let [{:keys [check-system-version force-system-version system-version]} flags
                  used-cs        (:used-codesystems result)
                  compose-pinned (into #{} (keep :system) (:compose-pins result))
                  vs-meta        (when-let [vs (composite/find-valueset svc url)]
                                   (protos/vs-resource vs {:url url}))
                  vs-version-uri (let [v (:version vs-meta)]
                                   (if v (str url "|" v) url))
                  version-error  (when check-system-version
                                   (some (fn [{cs-uri :uri}]
                                           (let [[sys resolved] (canonical/parse-versioned-uri cs-uri)]
                                             (when-let [pattern (get check-system-version sys)]
                                               (when (and resolved
                                                          (not (canonical/version-matches? pattern resolved)))
                                                 {:severity "error" :type "exception"
                                                  :details-code "version-error"
                                                  :text (str "The version '" resolved "' is not allowed for system '"
                                                             sys "': required to be '" pattern
                                                             "' by a version-check parameter")}))))
                                         used-cs))]
              (if version-error
                {:status 422 :body (wire/operation-outcome [version-error])}
                (let [effective-lang   (or display-lang (:display-language result))
                      expansion-params (-> (wire/build-version-echo-params
                                            {:force-system-version force-system-version
                                             :system-version system-version
                                             :check-system-version check-system-version
                                             :compose-pinned compose-pinned})
                                           (into (wire/build-cs-warning-params used-cs))
                                           (into (wire/build-vs-warning-params vs-meta vs-version-uri))
                                           (into (wire/build-used-codesystem-params used-cs))
                                           (into (wire/build-issues-param (:issues result)))
                                           (into (wire/build-echo-params
                                                  {:url url
                                                   :active-only? active-only?
                                                   :filter-value filter-value
                                                   :count-value count-value
                                                   :offset-value offset-value
                                                   :include-designations? include-desig?
                                                   :exclude-nested-present? exclude-nested-present?
                                                   :exclude-nested? exclude-nested?
                                                   :display-language effective-lang})))
                      paged-result     (update result :concepts vec)
                      vs-map (wire/expansion->valueset paged-result
                                                       {:vs-meta vs-meta
                                                        :url url
                                                        :offset-value offset-value
                                                        :expansion-params expansion-params
                                                        :include-designations? include-desig?})]
                  {:status 200 :body vs-map}))))))))

;; ---------------------------------------------------------------------------
;; ConceptMap $translate
;; ---------------------------------------------------------------------------

(defn- cm-translate
  [{::keys [svc flags fhir-params]}]
  (let [coding  (fhir-param-coding fhir-params "coding")
        url     (fhir-param fhir-params "url")
        system  (or (fhir-param fhir-params "system")  (get coding "system"))
        code    (or (fhir-param fhir-params "code")    (get coding "code"))
        version (or (fhir-param fhir-params "version") (get coding "version"))
        target  (or (fhir-param fhir-params "target")
                    (fhir-param fhir-params "targetsystem"))
        tparams (-> (cond-> {}
                      url     (assoc :url url)
                      system  (assoc :system system)
                      code    (assoc :code code)
                      version (assoc :version version)
                      target  (assoc :target target))
                    (merge-flags flags))
        result  (when (and code (or url system))
                  (hades/translate svc tparams))]
    (if (map? result)
      {:status 200 :body (wire/translate->parameters result)}
      {:status 404
       :body   (wire/operation-outcome
                [{:severity "error" :type "not-found"
                  :text (str "A definition for ConceptMap '" (or url system)
                             "' could not be found")}])})))

;; ---------------------------------------------------------------------------
;; Search — FHIR REST search on CodeSystem and ValueSet
;; ---------------------------------------------------------------------------

(def ^:private search-string-fields #{"name" "title" "description"})
(def ^:private search-token-fields  #{"url" "version" "status"})
(def ^:private string-modes         {""         :starts-with
                                     "exact"    :exact
                                     "contains" :contains})

(def ^:private default-count 10)
(def ^:private max-count     1000)

(defn- resolve-count
  [requested]
  (-> (or requested default-count) (max 0) (min max-count)))

(defn- split-modifier [^String k]
  (let [idx (str/index-of k ":")]
    (if idx [(subs k 0 idx) (subs k (inc idx))] [k ""])))

(defn- modifier-error [field mod]
  {:severity "error" :type "invalid"
   :details-code "MSG_PARAM_MODIFIER_INVALID"
   :text (str "Modifier ':" mod "' is not supported on parameter '" field "'")})

(defn- parse-nat-int [s]
  (try (let [n (Integer/parseInt s)] (when-not (neg? n) n))
       (catch Exception _ nil)))

(defn- parse-search-entry [acc k v]
  (let [[field mod] (split-modifier k)]
    (cond
      (search-string-fields field)
      (if-let [parsed-mode (string-modes mod)]
        (-> acc
            (assoc-in [:params (keyword field)] v)
            (assoc-in [:params (keyword (str field "-mode"))] parsed-mode))
        (update acc :errors conj (modifier-error field mod)))

      (search-token-fields field)
      (if (= "" mod)
        (assoc-in acc [:params (keyword field)] v)
        (update acc :errors conj (modifier-error field mod)))

      (and (= "" mod) (#{"_count" "_offset"} field))
      (if-let [n (parse-nat-int v)]
        (assoc-in acc [:params (keyword field)] n)
        acc)

      (and (= "" mod) (= "_summary" field))
      (assoc-in acc [:params :_summary] v)

      :else (update acc :unknown conj k))))

(defn- ->multimap
  "Coerce a parsed-params map (single string or vector of strings as
  values) into a uniform `{string → [strings]}` shape so
  `reduce-search-entries` always sees vectors."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc k (if (sequential? v) (vec v) [v])))
             {} (or m {})))

(defn- reduce-search-entries [multimap]
  (reduce-kv
   (fn [acc k vals] (parse-search-entry acc k (first vals)))
   {:params {} :unknown [] :errors []}
   multimap))

(defn- apply-defaults
  "Apply HTTP-layer defaults to parsed search params: clamp `_count`
  (FHIR convention default 10, hard cap 1000); default `_summary=true`
  for ValueSet so unfiltered browse ships the lighter shape (3.2 MB
  with summary vs 121 MB without, on the smoke catalogue). CodeSystem
  has no comparable shape pressure (957 KB unfiltered) so its default
  is unchanged."
  [params resource-type]
  (cond-> (update params :_count resolve-count)
    (and (= "ValueSet" resource-type) (nil? (:_summary params)))
    (assoc :_summary "true")))

(defn parse-search-params
  "Per-resource-type search parameter interceptor: parses FHIR REST
  search modifiers from the request's `:query-params` + `:form-params`
  and applies server-policy defaults. Stores the parsed shape on the
  request as `::search-params`."
  [resource-type]
  {:name  ::parse-search-params
   :enter (fn [{:keys [request] :as context}]
            (let [merged (merge-with into
                                     (->multimap (:query-params request))
                                     (->multimap (:form-params request)))
                  parsed (reduce-search-entries merged)
                  parsed (update parsed :params apply-defaults resource-type)]
              (assoc-in context [:request ::search-params] parsed)))})

(defn- prefer-strict? [request]
  (when-let [hdr (get-in request [:headers "prefer"])]
    (boolean (re-find #"(?i)handling\s*=\s*strict" hdr))))

(defn- request-self-link [request]
  (let [scheme (name (:scheme request :http))
        host   (or (get-in request [:headers "host"]) "localhost")
        uri    (:uri request)
        qs     (:query-string request)]
    (cond-> (str scheme "://" host uri)
      (not (str/blank? qs)) (str "?" qs))))

(defn- search-response
  [request resource-type search-fn resource->map]
  (let [{:keys [params unknown errors]} (::search-params request)
        svc (::svc request)]
    (cond
      (seq errors)
      {:status 400 :body (wire/operation-outcome errors)}

      (and (seq unknown) (prefer-strict? request))
      {:status 400
       :body   (wire/operation-outcome
                (mapv (fn [k]
                        {:severity "error" :type "not-supported"
                         :details-code "MSG_PARAM_UNKNOWN"
                         :text (str "Unknown search parameter '" k "'")})
                      unknown))}

      :else
      {:status 200
       :body   (wire/search-bundle
                (search-fn svc params)
                {:type          resource-type
                 :self-link     (request-self-link request)
                 :resource->map resource->map})})))

(defn- cs-search [request]
  (search-response request "CodeSystem"
                   hades/search-code-systems
                   wire/cs-resource->map))

(defn- vs-search [request]
  (search-response request "ValueSet"
                   hades/search-value-sets
                   wire/vs-resource->map))

;; ---------------------------------------------------------------------------
;; Metadata endpoints
;; ---------------------------------------------------------------------------

(defn- server-url-for [request]
  (let [scheme (name (:scheme request :http))
        host   (or (get-in request [:headers "host"]) "localhost")]
    (str scheme "://" host "/fhir")))

(defn- query-param
  "First value of a query-string parameter. Handles both shapes
  Pedestal hands back: a single string (one occurrence) or a vector
  of strings (repeated)."
  [request nm]
  (let [v (get-in request [:query-params nm])]
    (if (sequential? v) (first v) v)))

(defn- metadata
  [{::keys [svc] :as request}]
  (let [body (if (= "terminology" (query-param request "mode"))
               (metadata/terminology-capabilities svc)
               (metadata/capability-statement {:url (server-url-for request)}))]
    {:status 200 :body body}))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn- max-expansion
  "Soft cap on `$expand` result size: oversize unconstrained expansions
  return 422 `too-costly`. The cap is exposed to the handler as
  `::max-expansion-size`."
  [max-size]
  {:name ::max-expansion
   :enter (fn [context]
            (assoc-in context [:request ::max-expansion-size] max-size))})

(defn- max-body
  "Hard cap on POST body size: requests over `max-bytes` return 413
  `too-costly`. The cap is exposed to body readers as `::max-body-bytes`."
  [max-bytes]
  {:name ::max-body
   :enter (fn [context]
            (assoc-in context [:request ::max-body-bytes] max-bytes))})

(defn routes [{:keys [max-expansion-size]}]
  (let [parse-params (ring/params)
        op-base     [parse-params parse-fhir-params tx-overlay request-flags]
        cs-search-i [parse-params (parse-search-params "CodeSystem")]
        vs-search-i [parse-params (parse-search-params "ValueSet")]
        max-expand  (max-expansion max-expansion-size)]
    #{["/fhir/metadata"                   :get  [parse-params metadata]                              :route-name ::metadata]

      ["/fhir/CodeSystem/$lookup"         :get  (conj op-base cs-lookup)                            :route-name ::cs-lookup-get]
      ["/fhir/CodeSystem/$lookup"         :post (conj op-base cs-lookup)                            :route-name ::cs-lookup-post]

      ["/fhir/CodeSystem/$validate-code"  :get  (conj op-base cs-validate-code)                     :route-name ::cs-validate-get]
      ["/fhir/CodeSystem/$validate-code"  :post (conj op-base cs-validate-code)                     :route-name ::cs-validate-post]

      ["/fhir/CodeSystem/$subsumes"       :get  (conj op-base cs-subsumes)                          :route-name ::cs-subsumes-get]
      ["/fhir/CodeSystem/$subsumes"       :post (conj op-base cs-subsumes)                          :route-name ::cs-subsumes-post]

      ["/fhir/ValueSet/$validate-code"    :get  (conj op-base vs-validate-code)                     :route-name ::vs-validate-get]
      ["/fhir/ValueSet/$validate-code"    :post (conj op-base vs-validate-code)                     :route-name ::vs-validate-post]

      ["/fhir/ValueSet/$expand"           :get  (conj op-base max-expand vs-expand)                 :route-name ::vs-expand-get]
      ["/fhir/ValueSet/$expand"           :post (conj op-base max-expand vs-expand)                 :route-name ::vs-expand-post]

      ["/fhir/ConceptMap/$translate"      :get  (conj op-base cm-translate)                         :route-name ::cm-translate-get]
      ["/fhir/ConceptMap/$translate"      :post (conj op-base cm-translate)                         :route-name ::cm-translate-post]

      ["/fhir/CodeSystem"                 :get  (conj cs-search-i cs-search)                        :route-name ::cs-search-get]
      ["/fhir/CodeSystem/_search"         :post (conj cs-search-i cs-search)                        :route-name ::cs-search-post]
      ["/fhir/ValueSet"                   :get  (conj vs-search-i vs-search)                        :route-name ::vs-search-get]
      ["/fhir/ValueSet/_search"           :post (conj vs-search-i vs-search)                        :route-name ::vs-search-post]}))

(defn- fhir-error-response
  [status issues]
  {:status  status
   :headers {"Content-Type" "application/fhir+json; charset=utf-8"}
   :body    (write-json-str (wire/operation-outcome issues))})

(def fhir-not-found
  "Catch failures on the way out and return a FHIR JSON OperationOutcome
  instead of Pedestal's plain-text default. Two branches:

   - `:response` missing — no route matched. 404 not-found.
   - `:response` present with nil `:body` — a handler ran but returned
     an empty body. 500 exception. Architectural invariant: handlers
     must always return a body; this branch is a defensive backstop
     that surfaces such bugs honestly rather than disguising them as
     a routing failure."
  {:name  ::fhir-not-found
   :leave (fn [context]
            (cond
              (nil? (:response context))
              (let [path (get-in context [:request :uri])]
                (assoc context :response
                       (fhir-error-response
                        404
                        [{:severity "error" :type "not-found"
                          :text (str "No endpoint matches path '" path "'")}])))

              (nil? (get-in context [:response :body]))
              (let [path (get-in context [:request :uri])]
                (log/warn "handler returned empty body" {:uri path})
                (assoc context :response
                       (fhir-error-response
                        500
                        [{:severity "error" :type "exception"
                          :text (str "Internal error: handler returned empty body for '" path "'")}])))

              :else context))})

(defn make-server
  "Create an unstarted Hades FHIR server connector wired to `svc`.

  Options:
    :port               — listen port (default 8080)
    :host               — bind address (default 0.0.0.0)
    :max-expansion-size — soft cap on $expand result count (default 10000)
    :max-body-bytes     — hard cap on POST body size in bytes; requests
                          over this return 413 (default 16 MiB)"
  [svc opts]
  (let [opts (merge {:port 8080 :host "0.0.0.0"
                     :max-expansion-size 10000
                     :max-body-bytes (* 16 1024 1024)}
                    opts)]
    (-> (conn/default-connector-map (:host opts) (:port opts))
        (conn/with-interceptors [(tracing/request-tracing-interceptor)
                                 log-request
                                 render-json
                                 catch-all-error
                                 (max-body (:max-body-bytes opts))
                                 (inject-svc svc)
                                 fhir-not-found])
        (conn/with-routes (routes opts))
        (jetty/create-connector {:join? false}))))

(defn start!
  [connector]
  (log/info "Starting Hades FHIR server")
  (conn/start! connector))

(defn stop!
  [connector]
  (log/info "Stopping Hades FHIR server")
  (conn/stop! connector))
