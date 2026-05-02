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
            [io.pedestal.http.jetty :as jetty])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(def ^:private json-write-opts
  {:escape-js-separators false
   :escape-slash         false})

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

(def content-negotiation
  {:name  ::content-negotiation
   :leave (fn [context]
            (let [body (get-in context [:response :body])]
              (if (map? body)
                (-> context
                    (assoc-in [:response :body]
                              (charred/write-json-str body json-write-opts))
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

(def catch-all-error
  {:name  ::catch-all-error
   :error (fn [context ex]
            (let [data  (when (instance? clojure.lang.ExceptionInfo ex) (ex-data ex))
                  t     (:type data)
                  cause (or (.getCause ^Throwable ex) ex)
                  msg   (or (ex-message cause) (ex-message ex))]
              (if t
                (log/info "Handled" t msg)
                (log/error ex "Unhandled exception")))
            (assoc context :response (exception-response ex)))})

(defn- with-svc
  "Inject the configured service onto each request as ::svc. The
  base service is shared across requests; per-request overlays derive
  from it via `with-overlays`."
  [svc]
  {:name ::with-svc
   :enter (fn [context]
            (assoc-in context [:request ::svc] svc))})

(def ^:private overlay-param-markers
  ["system-version="
   "force-system-version="
   "check-system-version="
   "useSupplement"
   "property="
   "displayLanguage="
   "valueSetVersion="
   "lenient-display-validation="])

(defn- needs-full-ctx?
  [request]
  (or (= :post (:request-method request))
      (let [^String qs (:query-string request)]
        (and qs (boolean (some #(.contains qs ^String %) overlay-param-markers))))))

(def ^:private fast-path-flags (request-flags {:query-params {} :post-params []}))

(def derive-svc
  "Parse query/post params onto `::params` + `::flags`, and —
  when the request carries `tx-resource` Parameters — derive a per-request
  service via `core/with-overlays` and replace `::svc`."
  {:name  ::derive-svc
   :enter (fn [context]
            (let [request (:request context)
                  base-svc (::svc request)]
              (if (needs-full-ctx? request)
                (let [params  (params-for-request request)
                      tx-res  (post-resources params "tx-resource")
                      {:keys [providers supplements]} (build-overlay-providers tx-res)
                      svc     (if (seq providers)
                                (hades/with-overlays base-svc providers
                                  {:supplements supplements})
                                base-svc)
                      flags   (request-flags params)]
                  (-> context
                      (assoc-in [:request ::params] params)
                      (assoc-in [:request ::flags]  flags)
                      (assoc-in [:request ::svc]    svc)))
                (-> context
                    (assoc-in [:request ::params]
                              {:query-params (parse-query (:query-string request))
                               :post-params  []})
                    (assoc-in [:request ::flags] fast-path-flags)))))})

;; ---------------------------------------------------------------------------
;; Helpers shared across handlers
;; ---------------------------------------------------------------------------

(defn- merge-flags
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

(defn- cs-lookup-enter [context]
  (let [{::keys [svc params flags]} (:request context)
        coding   (post-coding params "coding")
        system   (or (get-first params "system") (get coding "system"))
        code     (or (get-first params "code") (get coding "code"))
        version  (get-first params "version")
        display-lang (pick-display-language params (:request context) flags)]
    (if-let [supp-err (supplements-missing-response svc nil flags)]
      (assoc context :response supp-err)
      (let [lookup-params (-> (cond-> {:system system :code code}
                                version      (assoc :version version)
                                display-lang (assoc :displayLanguage display-lang))
                              (merge-flags flags))
            result (when (and system code)
                     (hades/lookup svc lookup-params))]
        (-> context
            (assoc-in [:request ::lookup-params] lookup-params)
            (assoc :response {:status 200 :body result}))))))

(defn- cs-lookup-leave [context]
  (let [response (:response context)]
    (cond
      (not= 200 (:status response)) context

      (nil? (:body response))
      (let [{:keys [system code]} (get-in context [:request ::lookup-params])
            svc (get-in context [:request ::svc])
            message (if (composite/find-codesystem svc system)
                      (str "Unknown code '" code "' in code system '" system "'")
                      (str "Unknown code system: " system))]
        (assoc context :response
               {:status 404
                :body   (wire/operation-outcome
                          [{:severity "error" :type "not-found" :text message}])}))

      (map? (:body response))
      (update-in context [:response :body] wire/lookup->parameters)

      :else context)))

(def cs-lookup-handler  {:name ::cs-lookup-handler  :enter cs-lookup-enter})
(def cs-lookup-response {:name ::cs-lookup-response :leave cs-lookup-leave})

;; ---------------------------------------------------------------------------
;; CodeSystem $validate-code
;; ---------------------------------------------------------------------------

(defn- cs-validate-enter [context]
  (let [{::keys [svc params flags]} (:request context)]
    (if-let [supp-err (supplements-missing-response svc nil flags)]
      (assoc context :response supp-err)
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
            display-lang (pick-display-language params (:request context) flags)
            vp       (-> (cond-> {:system system :code code}
                           display      (assoc :display display)
                           version      (assoc :version version)
                           display-lang (assoc :displayLanguage display-lang))
                         (merge-flags flags))
            result       (hades/validate-code svc vp)
            input-mode   (if coding? :coding :code)
            result-final (if (:issues result)
                           (update result :issues wire/adjust-issue-expressions input-mode nil)
                           result)]
        (assoc context :response {:status 200 :body result-final})))))

(defn- cs-validate-leave [context]
  (if (= 200 (get-in context [:response :status]))
    (update-in context [:response :body] wire/validate->parameters)
    context))

(def cs-validate-handler  {:name ::cs-validate-handler  :enter cs-validate-enter})
(def cs-validate-response {:name ::cs-validate-response :leave cs-validate-leave})

;; ---------------------------------------------------------------------------
;; CodeSystem $subsumes
;; ---------------------------------------------------------------------------

(defn- cs-subsumes-enter [context]
  (let [{::keys [svc params]} (:request context)
        codingA (post-coding params "codingA")
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
    (assoc context :response {:status 200 :body result})))

(defn- cs-subsumes-leave [context]
  (if (= 200 (get-in context [:response :status]))
    (update-in context [:response :body] wire/subsumes->parameters)
    context))

(def cs-subsumes-handler  {:name ::cs-subsumes-handler  :enter cs-subsumes-enter})
(def cs-subsumes-response {:name ::cs-subsumes-response :leave cs-subsumes-leave})

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

(defn- vs-validate-enter [context]
  (let [{::keys [svc params flags]} (:request context)
        url (get-first params "url")]
    (if-let [supp-err (supplements-missing-response svc url flags)]
      (assoc context :response supp-err)
      (let [cc        (post-codeable-concept params "codeableConcept")
            codings   (seq (get cc "coding"))
            coding    (post-coding params "coding")
            coding?   (and coding (get coding "code"))
            value-set-version (:value-set-version flags)
            display-lang (pick-display-language params (:request context) flags)
            base      (-> (cond-> {:url url}
                            value-set-version (assoc :valueSetVersion value-set-version)
                            display-lang      (assoc :displayLanguage display-lang))
                          (merge-flags flags))
            result    (cond
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
        (-> context
            (assoc-in [:request ::input-mode] input-mode)
            (assoc :response {:status 200 :body result'}))))))

(defn- vs-validate-leave [context]
  (let [response (:response context)
        result   (:body response)]
    (cond
      (not= 200 (:status response)) context

      (:not-found result)
      (assoc context :response
             {:status 404
              :body   (wire/operation-outcome (:issues result))})

      :else
      (update-in context [:response :body] wire/validate->parameters))))

(def vs-validate-handler  {:name ::vs-validate-handler  :enter vs-validate-enter})
(def vs-validate-response {:name ::vs-validate-response :leave vs-validate-leave})

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

(defn- vs-expand-enter [context]
  (let [{::keys [params flags] base-svc ::svc} (:request context)
        url-param      (get-first params "url")
        [svc synth-url] (inline-valueset-svc base-svc params url-param)
        url            (or url-param synth-url)]
    (if-let [supp-err (supplements-missing-response svc url flags)]
      (assoc context :response supp-err)
      (let [active-only? (get-bool params "activeOnly")
            filter-value (get-first params "filter")
            display-lang (pick-display-language params (:request context) flags)
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
            result (hades/expand svc expand-params)]
        (-> context
            (assoc-in [:request ::svc] svc)
            (assoc-in [:request ::expand-context]
                      {:url url
                       :active-only? active-only?
                       :filter-value filter-value
                       :count-value count-value
                       :offset-value offset-value
                       :include-designations? include-desig?
                       :exclude-nested-present? exclude-nested-present?
                       :exclude-nested? exclude-nested?
                       :display-language display-lang})
            (assoc :response {:status 200 :body result}))))))

(def ^:private max-expansion-size-key ::max-expansion-size)

(defn- vs-expand-leave [context]
  (let [response (:response context)
        result   (:body response)
        request  (:request context)
        svc      (::svc request)
        flags    (::flags request)
        exp-ctx  (::expand-context request)
        url      (:url exp-ctx)
        max-size (get request max-expansion-size-key)
        error-issue (first (filter #(= "error" (:severity %)) (:issues result)))]
    (cond
      (not= 200 (:status response)) context

      (nil? result)
      (assoc context :response
             {:status 404
              :body   (wire/operation-outcome
                        [{:severity "error" :type "not-found"
                          :text (str "A definition for the value Set '" url "' could not be found")}])})

      error-issue
      (assoc context :response
             {:status 404
              :body   (wire/operation-outcome (:issues result))})

      (and max-size
           (nil? (:count-value exp-ctx))
           (> (or (:total result) 0) max-size))
      (let [msg (str "The value set '" url "' expansion has too many codes to display (>"
                     max-size ")")]
        (assoc context :response
               {:status 422
                :body   (wire/operation-outcome
                          [{:severity "error" :type "too-costly" :text msg}])}))

      :else
      (let [{:keys [check-system-version force-system-version system-version]} flags
            used-cs  (:used-codesystems result)
            compose-pinned (into #{} (keep :system) (:compose-pins result))
            vs-meta  (when-let [vs (composite/find-valueset svc url)]
                       (protos/vs-resource vs {}))
            vs-version-uri (let [v (:version vs-meta)]
                             (if v (str url "|" v) url))
            version-error (when check-system-version
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
          (assoc context :response
                 {:status 422
                  :body   (wire/operation-outcome [version-error])})
          (let [effective-lang (or (:display-language exp-ctx)
                                   (:display-language result))
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
                                             (assoc exp-ctx :display-language effective-lang))))
                {:keys [offset-value include-designations?]} exp-ctx
                paged-result (update result :concepts vec)
                vs-map (wire/expansion->valueset paged-result
                         {:vs-meta vs-meta
                          :url url
                          :offset-value offset-value
                          :expansion-params expansion-params
                          :include-designations? include-designations?})]
            (assoc context :response {:status 200 :body vs-map})))))))

(def vs-expand-handler  {:name ::vs-expand-handler  :enter vs-expand-enter})
(def vs-expand-response {:name ::vs-expand-response :leave vs-expand-leave})

;; ---------------------------------------------------------------------------
;; ConceptMap $translate
;; ---------------------------------------------------------------------------

(defn- cm-translate-enter [context]
  (let [{::keys [svc params flags]} (:request context)
        coding  (post-coding params "coding")
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
    (-> context
        (assoc-in [:request ::translate-params] tparams)
        (assoc :response {:status 200 :body result}))))

(defn- cm-translate-leave [context]
  (let [response (:response context)]
    (cond
      (not= 200 (:status response)) context

      (nil? (:body response))
      (let [{:keys [url system]} (get-in context [:request ::translate-params])
            target (or url system)
            msg (str "A definition for ConceptMap '" target "' could not be found")]
        (assoc context :response
               {:status 404
                :body   (wire/operation-outcome
                          [{:severity "error" :type "not-found" :text msg}])}))

      (map? (:body response))
      (update-in context [:response :body] wire/translate->parameters)

      :else context)))

(def cm-translate-handler  {:name ::cm-translate-handler  :enter cm-translate-enter})
(def cm-translate-response {:name ::cm-translate-response :leave cm-translate-leave})

;; ---------------------------------------------------------------------------
;; Metadata endpoints
;; ---------------------------------------------------------------------------

(defn- server-url-for [request]
  (let [scheme (name (:scheme request :http))
        host   (or (get-in request [:headers "host"]) "localhost")]
    (str scheme "://" host "/fhir")))

(defn- metadata-enter [context]
  (let [request (:request context)
        svc     (::svc request)
        mode    (first (get (parse-query (:query-string request)) "mode"))
        body    (if (= "terminology" mode)
                  (metadata/terminology-capabilities svc)
                  (metadata/capability-statement {:url (server-url-for request)}))]
    (assoc context :response {:status 200 :body body})))

(def metadata-handler {:name ::metadata-handler :enter metadata-enter})

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn- with-max-expansion
  [max-size]
  {:name ::with-max-expansion
   :enter (fn [context]
            (assoc-in context [:request max-expansion-size-key] max-size))})

(defn- with-max-body
  [max-bytes]
  {:name ::with-max-body
   :enter (fn [context]
            (assoc-in context [:request max-body-bytes-key] max-bytes))})

(defn routes [{:keys [max-expansion-size]}]
  (let [max-inj (with-max-expansion max-expansion-size)]
    #{["/fhir/metadata"                   :get  [metadata-handler]                                         :route-name ::metadata]

      ["/fhir/CodeSystem/$lookup"         :get  [cs-lookup-response cs-lookup-handler]                     :route-name ::cs-lookup-get]
      ["/fhir/CodeSystem/$lookup"         :post [cs-lookup-response cs-lookup-handler]                     :route-name ::cs-lookup-post]

      ["/fhir/CodeSystem/$validate-code"  :get  [cs-validate-response cs-validate-handler]                 :route-name ::cs-validate-get]
      ["/fhir/CodeSystem/$validate-code"  :post [cs-validate-response cs-validate-handler]                 :route-name ::cs-validate-post]

      ["/fhir/CodeSystem/$subsumes"       :get  [cs-subsumes-response cs-subsumes-handler]                 :route-name ::cs-subsumes-get]
      ["/fhir/CodeSystem/$subsumes"       :post [cs-subsumes-response cs-subsumes-handler]                 :route-name ::cs-subsumes-post]

      ["/fhir/ValueSet/$validate-code"    :get  [vs-validate-response vs-validate-handler]                 :route-name ::vs-validate-get]
      ["/fhir/ValueSet/$validate-code"    :post [vs-validate-response vs-validate-handler]                 :route-name ::vs-validate-post]

      ["/fhir/ValueSet/$expand"           :get  [max-inj vs-expand-response vs-expand-handler]             :route-name ::vs-expand-get]
      ["/fhir/ValueSet/$expand"           :post [max-inj vs-expand-response vs-expand-handler]             :route-name ::vs-expand-post]

      ["/fhir/ConceptMap/$translate"      :get  [cm-translate-response cm-translate-handler]               :route-name ::cm-translate-get]
      ["/fhir/ConceptMap/$translate"      :post [cm-translate-response cm-translate-handler]               :route-name ::cm-translate-post]}))

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
                        :body    (charred/write-json-str body json-write-opts)}))
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
        (conn/with-interceptors [content-negotiation catch-all-error
                                 (with-max-body (:max-body-bytes opts))
                                 (with-svc svc) derive-svc fhir-not-found])
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
