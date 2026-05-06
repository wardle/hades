(ns com.eldrix.hades.impl.http
  "Pedestal HTTP layer for the Hades FHIR terminology server.

  Each handler reads `svc` (a `TerminologyService`) from the Pedestal
  context, parses operation parameters from the request, calls the
  matching `core/*` operation, and lets the per-operation `:leave`
  interceptor shape the response. tx-resource / inline ValueSet
  parameters are folded into a derived service via
  `core/with-overlays` for the lifetime of the request."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.canonical :as canonical]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as loaders]
            [com.eldrix.hades.impl.metadata :as metadata]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.wire :as wire]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.tracing :as tracing])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
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
    (.toString sw)))

(defn- decode [^String s]
  (when s (URLDecoder/decode s StandardCharsets/UTF_8)))

(defn parse-query
  "Parse a URL query string into a multimap {name [value ...]}."
  [^String qs]
  (if (str/blank? qs)
    {}
    (reduce (fn [acc pair]
              (let [[k v] (str/split pair #"=" 2)
                    nm    (decode k)]
                (if (and nm v)
                  (update acc nm (fnil conj []) (decode v))
                  acc)))
            {}
            (str/split qs #"&"))))

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

(defn- parse-post-body
  [body]
  (when body
    (let [s (cond (string? body) body
                  (bytes? body)  (String. ^bytes body StandardCharsets/UTF_8)
                  :else          (slurp body))
          parsed (try
                   (when (seq s) (charred/read-json s))
                   (catch Exception e
                     (throw (ex-info (str "Malformed Parameters body: " (.getMessage e))
                                     {:type         :bad-request
                                      :details-code "invalid"}
                                     e))))]
      (get parsed "parameter"))))

(defn- get-first
  [{:keys [query-params post-params] :as _params} nm]
  (or (first (get query-params nm))
      (when (seq post-params)
        (first (keep param-value-str (entries post-params nm))))))

(defn- get-bool [params nm]
  (let [v (get-first params nm)]
    (cond
      (nil? v)                              nil
      (boolean? v)                          v
      (= "true" (str/lower-case (str v)))   true
      (= "false" (str/lower-case (str v)))  false
      :else                                 nil)))

(defn- get-int [params nm]
  (when-let [v (get-first params nm)]
    (cond
      (number? v) (int v)
      :else       (try (Integer/parseInt (str v)) (catch Exception _ nil)))))

(defn- get-all
  [{:keys [query-params post-params]} nm]
  (let [from-query (vec (get query-params nm []))]
    (if (seq post-params)
      (into from-query (keep param-value-str (entries post-params nm)))
      from-query)))

(defn- post-coding
  [params nm]
  (let [ps (:post-params params)]
    (when (seq ps)
      (when-let [p (first (entries ps nm))]
        (or (get p "valueCoding") nil)))))

(defn- post-codeable-concept
  [params nm]
  (let [ps (:post-params params)]
    (when (seq ps)
      (when-let [p (first (entries ps nm))]
        (get p "valueCodeableConcept")))))

(defn- post-resources
  [params nm]
  (let [ps (:post-params params)]
    (when (seq ps)
      (keep #(get % "resource") (entries ps nm)))))

(def ^:private max-body-bytes-key ::max-body-bytes)

(defn- payload-too-large [size limit]
  (ex-info (str "Request body exceeds the configured limit of " limit " bytes (got " size ")")
           {:type         :payload-too-large
            :details-code "too-costly"}))

(defn- read-bounded
  "Read up to `limit` bytes from `in`. If the stream has more data
  available, throw `:type :payload-too-large` rather than allocate the
  rest. Returns a byte array."
  [^java.io.InputStream in ^long limit]
  (let [out (java.io.ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buf)]
        (cond
          (neg? n) (.toByteArray out)
          (> (+ total n) limit) (throw (payload-too-large (+ total n) limit))
          :else (do (.write out buf 0 n) (recur (+ total n))))))))

(defn- read-body
  [{:keys [body] :as request}]
  (let [limit (or (get request max-body-bytes-key) Long/MAX_VALUE)]
    (cond
      (nil? body)    nil
      (bytes? body)  (if (> (alength ^bytes body) limit)
                       (throw (payload-too-large (alength ^bytes body) limit))
                       body)
      (string? body) (let [bs (.getBytes ^String body StandardCharsets/UTF_8)]
                       (if (> (alength bs) limit)
                         (throw (payload-too-large (alength bs) limit))
                         bs))
      :else          (with-open [in (io/input-stream body)]
                       (read-bounded in limit)))))

(defn- params-for-request
  [request]
  (let [qmap (parse-query (:query-string request))
        post (when (= :post (:request-method request))
               (parse-post-body (read-body request)))]
    {:query-params qmap
     :post-params  (or post [])}))

(defn- collect-version-param [params key]
  (let [values (get-all params key)]
    (when (seq values)
      (canonical/parse-version-param values))))

(defn- request-flags
  "Extract request-scoped flags into a flat map. These flow as keys in
  the operation `params` map alongside `:system :code` etc."
  [params]
  (let [supps (into (vec (get-all params "useSupplement"))
                    (get-all params "useSupplements"))
        props (get-all params "property")
        lenient (get-bool params "lenient-display-validation")]
    (cond-> {:lenient-display-validation (boolean lenient)}
      (seq (get-all params "system-version"))
      (assoc :system-version (collect-version-param params "system-version"))

      (seq (get-all params "force-system-version"))
      (assoc :force-system-version (collect-version-param params "force-system-version"))

      (seq (get-all params "check-system-version"))
      (assoc :check-system-version (collect-version-param params "check-system-version"))

      (seq supps) (assoc :use-supplements supps)
      (seq props) (assoc :properties props)

      (get-first params "valueSetVersion")
      (assoc :value-set-version (get-first params "valueSetVersion"))

      (get-first params "displayLanguage")
      (assoc :display-language (get-first params "displayLanguage")))))

(defn- accept-language [request]
  (get-in request [:headers "accept-language"]))

(defn- pick-display-language [params request flags]
  (or (get-first params "displayLanguage")
      (accept-language request)
      (:display-language flags)))

;; ---------------------------------------------------------------------------
;; tx-resource overlay — parse inbound FHIR Parameters that include
;; transient CodeSystem/ValueSet/ConceptMap resources, build providers
;; from them, and attach as a derived service for the request.
;; ---------------------------------------------------------------------------

(defn- build-overlay-providers
  "Build provider impls and supplement entries from a seq of FHIR JSON
  resource maps (string-keyed). Returns `{:providers :supplements}`."
  [resource-maps]
  (when (seq resource-maps)
    (let [fhir-data (loaders/resources->fhir-data resource-maps :tx-resource)
          {:keys [providers supplements] :as _result}
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
;; Shared interceptors
;; ---------------------------------------------------------------------------

(def log-request
  {:name  ::log-request
   :enter (fn [ctx] (assoc ctx ::start (System/nanoTime)))
   :leave (fn [{:keys [request response] ::keys [start] :as ctx}]
            (log/info "request"
                      {:method (-> request :request-method name)
                       :uri    (:uri request)
                       :ip     (:remote-addr request)
                       :status (:status response)
                       :ms     (long (/ (- (System/nanoTime) start) 1e6))})
            ctx)})

(def content-negotiation
  {:name  ::content-negotiation
   :leave (fn [context]
            (let [body (get-in context [:response :body])]
              (if (map? body)
                (-> context
                    (assoc-in [:response :body]
                              (write-json-str body))
                    (assoc-in [:response :headers "Content-Type"]
                              "application/fhir+json; charset=utf-8"))
                context)))})

(defn- exception-response
  [^Throwable ex]
  (let [data   (when (instance? clojure.lang.ExceptionInfo ex) (ex-data ex))
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
  {:name  ::catch-all-error
   :error (fn [context ex]
            (if-let [cause (saturation-cause ex)]
              ;; Saturation is high-volume during overload: log without a stack
              ;; trace so the signal isn't drowned. The Hikari pool name is in
              ;; the cause's message.
              (do (log/warn "connection pool saturated:" (.getMessage cause))
                  (assoc context :response (saturation-response)))
              (let [data  (when (instance? clojure.lang.ExceptionInfo ex) (ex-data ex))
                    t     (:type data)
                    cause (or (.getCause ^Throwable ex) ex)
                    msg   (or (ex-message cause) (ex-message ex))]
                (if t
                  (log/info "Handled" t msg)
                  (log/error ex "Unhandled exception"))
                (assoc context :response (exception-response ex)))))})

(defn inject-svc
  "Inject the configured service onto each request as ::svc. The
  base service is shared across requests; per-request overlays derive
  from it via `with-overlays`."
  [svc]
  {:name ::inject-svc
   :enter (fn [context]
            (assoc-in context [:request ::svc] svc))})

(def tx-overlay
  "Parse query/post params onto `::params` + `::flags`, and — when the
  request carries `tx-resource` Parameters — derive a per-request service
  via `core/with-overlays` and replace `::svc`. Mounted globally; runs
  on every operation."
  {:name  ::tx-overlay
   :enter (fn [{:keys [request] :as context}]
            (let [params   (params-for-request request)
                  tx-res   (post-resources params "tx-resource")
                  {:keys [providers supplements]} (build-overlay-providers tx-res)]
              (cond-> (-> context
                          (assoc-in [:request ::params] params)
                          (assoc-in [:request ::flags]  (request-flags params)))
                ;; if we have any overlay providers, create a new hades service with them
                (seq providers)
                (update-in [:request ::svc] #(hades/with-overlays % providers {:supplements supplements})))))})

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
  [{::keys [svc params flags] :as request}]
  (let [coding   (post-coding params "coding")
        system   (or (get-first params "system") (get coding "system"))
        code     (or (get-first params "code") (get coding "code"))
        version  (get-first params "version")
        display-lang (pick-display-language params request flags)]
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
  [{::keys [svc params flags] :as request}]
  (or (supplements-missing-response svc nil flags)
      (let [coding   (post-coding params "coding")
            coding?  (and coding (get coding "code"))
            system   (or (get-first params "url")
                         (get-first params "system")
                         (when coding? (get coding "system")))
            code     (or (get-first params "code")
                         (when coding? (get coding "code")))
            display  (or (get-first params "display")
                         (when coding? (get coding "display")))
            version  (get-first params "version")
            display-lang (pick-display-language params request flags)
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
  [{::keys [svc params]}]
  (let [codingA (post-coding params "codingA")
        codingB (post-coding params "codingB")
        codeA   (get-first params "codeA")
        codeB   (get-first params "codeB")
        system  (get-first params "system")
        subs-params (cond
                      (and codeA codeB system)
                      {:systemA system :codeA codeA :systemB system :codeB codeB}
                      (and codingA codingB)
                      {:systemA (get codingA "system") :codeA (get codingA "code")
                       :systemB (get codingB "system") :codeB (get codingB "code")})
        result  (when subs-params (hades/subsumes svc subs-params))]
    {:status 200 :body (when result (wire/subsumes->parameters result))}))

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
  [{::keys [svc params flags] :as request}]
  (let [url (get-first params "url")]
    (or (supplements-missing-response svc url flags)
        (let [cc      (post-codeable-concept params "codeableConcept")
              codings (seq (get cc "coding"))
              coding  (post-coding params "coding")
              coding? (and coding (get coding "code"))
              value-set-version (:value-set-version flags)
              display-lang (pick-display-language params request flags)
              base    (-> (cond-> {:url url}
                            value-set-version (assoc :valueSetVersion value-set-version)
                            display-lang      (assoc :displayLanguage display-lang))
                          (merge-flags flags))
              result  (cond
                        codings
                        (let [r (hades/validate-codeable-concept svc
                                                                 (cc-codings cc (get-first params "systemVersion"))
                                                                 base)]
                          (assoc r :codeableConcept cc))

                        :else
                        (let [system  (or (get-first params "system")
                                          (when coding? (get coding "system")))
                              code    (or (get-first params "code")
                                          (when coding? (get coding "code")))
                              display (or (get-first params "display")
                                          (when coding? (get coding "display")))
                              version (or (get-first params "systemVersion")
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

(defn- inline-valueset-svc
  "When a POST `$expand` request carries an inline `valueSet` parameter
  and no `url`, layer it onto the service via `with-overlays`. Returns
  `[svc url]` (unchanged when no inline applies)."
  [svc params url-param]
  (if url-param
    [svc nil]
    (if-let [inline-vs (first (post-resources params "valueSet"))]
      (let [synth-url (or (get inline-vs "url")
                          (str "urn:hades:inline:" (java.util.UUID/randomUUID)))
            vs-map    (cond-> inline-vs
                        (nil? (get inline-vs "url")) (assoc "url" synth-url))
            provider  (load-fhir/from-fhir vs-map)]
        [(hades/with-overlays svc [provider]) synth-url])
      [svc nil])))

(defn- vs-expand
  [{::keys [params flags] base-svc ::svc :as request}]
  (let [url-param      (get-first params "url")
        [svc synth-url] (inline-valueset-svc base-svc params url-param)
        url            (or url-param synth-url)]
    (or (supplements-missing-response svc url flags)
        (let [active-only? (get-bool params "activeOnly")
              filter-value (get-first params "filter")
              display-lang (pick-display-language params request flags)
              include-desig? (get-bool params "includeDesignations")
              count-value    (get-int params "count")
              offset-value   (get-int params "offset")
              exclude-nested-present? (some? (get-first params "excludeNested"))
              exclude-nested? (let [v (get-bool params "excludeNested")]
                                (if (nil? v) true v))
              req-properties (:properties flags)
              expand-params  (-> (cond-> {:url             url
                                          :activeOnly      active-only?
                                          :filter          filter-value
                                          :displayLanguage display-lang
                                          :count           count-value
                                          :offset          offset-value}
                                   (seq req-properties) (assoc :properties req-properties))
                                 (merge-flags flags))
              result   (hades/expand svc expand-params)
              max-size (::max-expansion-size request)
              error-issue (first (filter #(= "error" (:severity %)) (:issues result)))]
          (cond
            (nil? result)
            {:status 404
             :body   (wire/operation-outcome
                      [{:severity "error" :type "not-found"
                        :text (str "A definition for the value Set '" url "' could not be found")}])}

            error-issue
            {:status 404 :body (wire/operation-outcome (:issues result))}

            (and max-size (nil? count-value) (> (or (:total result) 0) max-size))
            {:status 422
             :body   (wire/operation-outcome
                      [{:severity "error" :type "too-costly"
                        :text (str "The value set '" url "' expansion has too many codes to display (>"
                                   max-size ")")}])}

            :else
            (let [{:keys [check-system-version force-system-version system-version]} flags
                  used-cs        (:used-codesystems result)
                  compose-pinned (into #{} (keep :system) (:compose-pins result))
                  vs-meta        (when-let [vs (composite/find-valueset svc url)]
                                   (protos/vs-resource vs {}))
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
  [{::keys [svc params flags]}]
  (let [coding  (post-coding params "coding")
        url     (get-first params "url")
        system  (or (get-first params "system") (get coding "system"))
        code    (or (get-first params "code")   (get coding "code"))
        version (or (get-first params "version") (get coding "version"))
        target  (or (get-first params "target")
                    (get-first params "targetsystem"))
        tparams (-> (cond-> {}
                      url     (assoc :url url)
                      system  (assoc :system system)
                      code    (assoc :code code)
                      version (assoc :version version)
                      target  (assoc :target target))
                    (merge-flags flags))
        result  (when (and code (or url system))
                  (hades/translate svc tparams))]
    (cond
      (map? result)
      {:status 200 :body (wire/translate->parameters result)}

      :else
      {:status 404
       :body   (wire/operation-outcome
                [{:severity "error" :type "not-found"
                  :text (str "A definition for ConceptMap '" (or url system)
                             "' could not be found")}])})))

;; ---------------------------------------------------------------------------
;; Metadata endpoints
;; ---------------------------------------------------------------------------

(defn- server-url-for [request]
  (let [scheme (name (:scheme request :http))
        host   (or (get-in request [:headers "host"]) "localhost")]
    (str scheme "://" host "/fhir")))

(defn- metadata
  [{::keys [svc] :as request}]
  (let [mode (first (get (parse-query (:query-string request)) "mode"))
        body (if (= "terminology" mode)
               (metadata/terminology-capabilities svc)
               (metadata/capability-statement {:url (server-url-for request)}))]
    {:status 200 :body body}))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn- with-max-expansion
  [max-size]
  {:name ::with-max-expansion
   :enter (fn [context]
            (assoc-in context [:request ::max-expansion-size] max-size))})

(defn- with-max-body
  [max-bytes]
  {:name ::with-max-body
   :enter (fn [context]
            (assoc-in context [:request max-body-bytes-key] max-bytes))})

(defn routes [{:keys [max-expansion-size]}]
  (let [max-inj (with-max-expansion max-expansion-size)]
    #{["/fhir/metadata"                   :get  metadata                                                   :route-name ::metadata]

      ["/fhir/CodeSystem/$lookup"         :get  cs-lookup                                                  :route-name ::cs-lookup-get]
      ["/fhir/CodeSystem/$lookup"         :post cs-lookup                                                  :route-name ::cs-lookup-post]

      ["/fhir/CodeSystem/$validate-code"  :get  cs-validate-code                                             :route-name ::cs-validate-get]
      ["/fhir/CodeSystem/$validate-code"  :post cs-validate-code                                             :route-name ::cs-validate-post]

      ["/fhir/CodeSystem/$subsumes"       :get  cs-subsumes                                                 :route-name ::cs-subsumes-get]
      ["/fhir/CodeSystem/$subsumes"       :post cs-subsumes                                                 :route-name ::cs-subsumes-post]

      ["/fhir/ValueSet/$validate-code"    :get  vs-validate-code                                            :route-name ::vs-validate-get]
      ["/fhir/ValueSet/$validate-code"    :post vs-validate-code                                            :route-name ::vs-validate-post]

      ["/fhir/ValueSet/$expand"           :get  [max-inj vs-expand]                                         :route-name ::vs-expand-get]
      ["/fhir/ValueSet/$expand"           :post [max-inj vs-expand]                                         :route-name ::vs-expand-post]

      ["/fhir/ConceptMap/$translate"      :get  cm-translate                                                :route-name ::cm-translate-get]
      ["/fhir/ConceptMap/$translate"      :post cm-translate                                                :route-name ::cm-translate-post]}))

(def fhir-not-found
  {:name  ::fhir-not-found
   :leave (fn [context]
            (if (nil? (get-in context [:response :body]))
              (let [path (get-in context [:request :uri])
                    body (wire/operation-outcome
                          [{:severity "error" :type "not-found"
                            :text (str "No endpoint matches path '" path "'")}])]
                (assoc context :response
                       {:status  404
                        :headers {"Content-Type" "application/fhir+json; charset=utf-8"}
                        :body    (write-json-str body)}))
              context))})

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
                                 content-negotiation catch-all-error
                                 (with-max-body (:max-body-bytes opts))
                                 (inject-svc svc) tx-overlay fhir-not-found])
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
