(ns com.eldrix.hades.http
  "Pedestal HTTP layer for the Hades FHIR terminology server.

  Responsibilities:
    - route requests to per-operation handler interceptors
    - parse GET query parameters and POST `Parameters` resources
    - build the request-scoped overlay ctx from `tx-resource` parameters
    - run per-operation response interceptors to shape result maps into
      FHIR JSON resources (Parameters, ValueSet, OperationOutcome) with the
      correct HTTP status
    - serialise response bodies to FHIR JSON via charred"
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.fhir-codesystem :as fhir-cs]
            [com.eldrix.hades.fhir-valueset :as fhir-vs]
            [com.eldrix.hades.metadata :as metadata]
            [com.eldrix.hades.protocols :as protos]
            [com.eldrix.hades.registry :as registry]
            [com.eldrix.hades.wire :as wire]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.service.interceptors :as interceptors])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------------------
;; Query-string parsing
;; ---------------------------------------------------------------------------

(defn- decode [^String s]
  (when s (URLDecoder/decode s StandardCharsets/UTF_8)))

(defn parse-query
  "Parse a URL query string into a multimap {name [value ...]}. Values are
  URL-decoded strings. Returns an empty map for nil/empty input."
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

;; ---------------------------------------------------------------------------
;; Parameters (POST body) parsing
;; ---------------------------------------------------------------------------

(def ^:private value-keys
  ["valueString" "valueUri" "valueCanonical" "valueCode" "valueId"
   "valueBoolean" "valueInteger" "valueDecimal"
   "valueCoding" "valueCodeableConcept" "resource"])

(defn- param-value
  "Extract the typed value from a Parameters.parameter entry. Returns the
  first present value from the known valueXxx / resource keys. Uses
  contains? rather than a truthy fallback so `valueBoolean false` and
  `valueInteger 0` are preserved."
  [p]
  (when p
    (reduce (fn [_ k] (when (contains? p k) (reduced (get p k))))
            nil value-keys)))

(defn- param-value-str
  "Coerce a parameter value to string (or nil). Booleans and numbers are
  stringified; complex values (Coding, CodeableConcept, resources) return nil."
  [p]
  (let [v (param-value p)]
    (cond
      (nil? v)     nil
      (string? v)  v
      (boolean? v) (str v)
      (number? v)  (str v)
      :else        nil)))

(defn- entries
  "Collect Parameters.parameter entries with a given name."
  [parameters nm]
  (filter #(= nm (get % "name")) parameters))

(defn- parse-post-body
  "Parse a Parameters POST body (bytes or string) into a seq of parameter
  entries. Returns nil when the body is empty or unparseable."
  [body]
  (when body
    (try
      (let [s      (cond (string? body) body
                         (bytes? body)  (String. ^bytes body StandardCharsets/UTF_8)
                         :else          (slurp body))
            parsed (when (seq s) (charred/read-json s))]
        (get parsed "parameter"))
      (catch Exception e
        (log/debug "Failed to parse Parameters body" {:error (.getMessage e)})
        nil))))

;; ---------------------------------------------------------------------------
;; Unified parameter access — works for GET query params and POST Parameters.
;; ---------------------------------------------------------------------------

(defn- get-first
  "Return the first value for parameter `nm` from GET query or POST body."
  [{:keys [query-params post-params] :as _params} nm]
  (or (first (get query-params nm))
      (first (keep param-value-str (entries post-params nm)))))

(defn- get-bool [params nm]
  (let [v (get-first params nm)]
    (cond
      (nil? v)                   nil
      (boolean? v)               v
      (= "true" (str/lower-case (str v)))  true
      (= "false" (str/lower-case (str v))) false
      :else                      nil)))

(defn- get-int [params nm]
  (when-let [v (get-first params nm)]
    (cond
      (number? v) (int v)
      :else       (try (Integer/parseInt (str v)) (catch Exception _ nil)))))

(defn- get-all
  "Return all values for parameter `nm` from GET query or POST body.
  For POST, only string-typed values are returned."
  [{:keys [query-params post-params]} nm]
  (let [from-query (get query-params nm [])
        from-post  (keep param-value-str (entries post-params nm))]
    (into (vec from-query) from-post)))

(defn- post-coding
  "Extract a Coding-valued parameter from POST. Returns a string-keyed map or
  nil. Handles both `valueCoding` and `part` structures."
  [params nm]
  (when-let [p (first (entries (:post-params params) nm))]
    (or (get p "valueCoding")
        ;; FHIR allows Coding to be represented as parts too, but the HAPI-
        ;; native form is valueCoding. Only this is surfaced currently.
        nil)))

(defn- post-codeable-concept
  "Extract a CodeableConcept-valued parameter from POST. Returns a
  string-keyed map or nil."
  [params nm]
  (when-let [p (first (entries (:post-params params) nm))]
    (get p "valueCodeableConcept")))

(defn- post-resources
  "Return all `resource` entries for parameter `nm` from POST."
  [params nm]
  (keep #(get % "resource") (entries (:post-params params) nm)))

;; ---------------------------------------------------------------------------
;; Request context assembly
;; ---------------------------------------------------------------------------

(defn- read-body
  "Read a request body into a byte array. Accepts nil, bytes, string or an
  InputStream/readable source."
  [{:keys [body]}]
  (cond
    (nil? body)    nil
    (bytes? body)  body
    (string? body) (.getBytes ^String body StandardCharsets/UTF_8)
    :else          (with-open [in (io/input-stream body)]
                     (.readAllBytes in))))

(defn- params-for-request
  "Merge GET query-params and POST parameter entries into a unified structure.
  Called by the tx-ctx interceptor; result is stored under :hades/params."
  [request]
  (let [qmap (parse-query (:query-string request))
        post (when (= :post (:request-method request))
               (parse-post-body (read-body request)))]
    {:query-params qmap
     :post-params  (or post [])}))

(defn- collect-version-param [params key]
  (let [values (get-all params key)]
    (when (seq values)
      (registry/parse-version-param values))))

(defn- request-flags
  "Extract request-scoped flags (version control, display language, etc.)
  from the unified parameter map into the ctx :request map. The display
  validation mode defaults to strict (lenient=false) so that display
  mismatches are surfaced as errors; callers opt into lenient validation
  explicitly via `lenient-display-validation=true`."
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

(defn build-tx-ctx
  "Build an overlay ctx from a seq of resource maps (plain string-keyed).

  Three-pass: regular CodeSystems first, then supplements, then ValueSets.

  Use from the REPL for direct testing with overlays:
    (def ctx (http/build-tx-ctx [cs-map vs-map ...]))
    (registry/valueset-validate-code ctx {:url ... :code ... :system ...})"
  [resource-maps]
  (when (seq resource-maps)
    (let [code-systems (filter #(= "CodeSystem" (get % "resourceType")) resource-maps)
          value-sets   (filter #(= "ValueSet" (get % "resourceType")) resource-maps)
          supplements  (filter #(= "supplement" (get % "content")) code-systems)
          regular-cs   (remove #(= "supplement" (get % "content")) code-systems)
          ctx (reduce (fn [ctx m]
                        (let [url     (get m "url")
                              version (get m "version")
                              fcs     (fhir-cs/make-fhir-code-system m)]
                          (cond-> (-> ctx
                                      (assoc-in [:codesystems url] fcs)
                                      (assoc-in [:valuesets url] fcs))
                            version
                            (-> (assoc-in [:codesystems (str url "|" version)] fcs)
                                (assoc-in [:valuesets (str url "|" version)] fcs)))))
                      {} regular-cs)
          ctx (reduce (fn [ctx supp]
                        (let [base-url     (get supp "supplements")
                              supp-url     (get supp "url")
                              supp-version (get supp "version")
                              supp-fcs     (fhir-cs/make-fhir-code-system supp)
                              ctx          (cond-> ctx
                                             supp-url
                                             (assoc-in [:codesystems supp-url] supp-fcs)
                                             (and supp-url supp-version)
                                             (assoc-in [:codesystems (str supp-url "|" supp-version)] supp-fcs))]
                          (if-let [base-cs (or (get-in ctx [:codesystems base-url])
                                               (registry/codesystem base-url))]
                            (let [merged  (fhir-cs/apply-supplement base-cs supp)
                                  version (get supp "version")]
                              (cond-> (-> ctx
                                          (assoc-in [:codesystems base-url] merged)
                                          (assoc-in [:valuesets base-url] merged))
                                version
                                (-> (assoc-in [:codesystems (str base-url "|" version)] merged)
                                    (assoc-in [:valuesets (str base-url "|" version)] merged))))
                            ctx)))
                      ctx supplements)]
      (reduce (fn [ctx m]
                (let [url     (get m "url")
                      version (get m "version")
                      fvs     (fhir-vs/make-fhir-value-set m)]
                  (cond-> (assoc-in ctx [:valuesets url] fvs)
                    version (assoc-in [:valuesets (str url "|" version)] fvs))))
              ctx value-sets))))

;; ---------------------------------------------------------------------------
;; Supplement check (shared across validate-code and expand paths)
;; ---------------------------------------------------------------------------

(defn- supplements-missing-response
  "If any referenced supplement is missing, return a 404 OperationOutcome
  response map; otherwise nil."
  [ctx vs-impl]
  (when-let [{:keys [message issues]} (registry/check-supplement-refs ctx vs-impl)]
    {:status 404
     :body   (wire/operation-outcome
               (or issues [{:severity "error" :type "not-found" :text message}]))}))

;; ---------------------------------------------------------------------------
;; Shared interceptors
;; ---------------------------------------------------------------------------

(def content-negotiation
  "Serialise a map response body to FHIR JSON and set Content-Type. Only
  transforms maps; strings/nil/byte-array bodies are left alone."
  {:name  ::content-negotiation
   :leave (fn [context]
            (let [body (get-in context [:response :body])]
              (if (map? body)
                (-> context
                    (assoc-in [:response :body]
                              (charred/write-json-str body))
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
                 500)
        issues (or (:issues data)
                   [{:severity     "error"
                     :type         (if t (name t) "exception")
                     :details-code (:details-code data)
                     :text         (or (ex-message ex) "Internal server error")}])]
    {:status status
     :body   (wire/operation-outcome issues)}))

(def catch-all-error
  "Catch-all error interceptor. Converts unhandled exceptions into 500/4xx
  OperationOutcome responses. Exceptions with `(ex-data)` carrying `:type`
  and `:issues` produce the corresponding HTTP status."
  {:name  ::catch-all-error
   :error (fn [context ex]
            (log/error ex "Unhandled exception")
            (assoc context :response (exception-response ex)))})

(defn- pick-display-language [params request ctx]
  (or (get-first params "displayLanguage")
      (accept-language request)
      (get-in ctx [:request :display-language])))

(def tx-ctx
  "Build the overlay ctx from the request and attach it (and the parameter
  structure) to the request map."
  {:name  ::tx-ctx
   :enter (fn [context]
            (let [request  (:request context)
                  params   (params-for-request request)
                  tx-res   (post-resources params "tx-resource")
                  overlay  (build-tx-ctx tx-res)
                  req-map  (merge registry/default-request
                                   (request-flags params))
                  ctx      (assoc overlay :request req-map)]
              (-> context
                  (assoc-in [:request :hades/params] params)
                  (assoc-in [:request :hades/ctx] ctx))))})

;; ---------------------------------------------------------------------------
;; CodeSystem $lookup
;; ---------------------------------------------------------------------------

(defn- cs-lookup-enter [context]
  (let [request  (:request context)
        params   (:hades/params request)
        ctx      (:hades/ctx request)
        coding   (post-coding params "coding")
        system   (or (get-first params "system")
                     (get coding "system"))
        code     (or (get-first params "code")
                     (get coding "code"))
        version  (get-first params "version")
        display-lang (pick-display-language params request ctx)]
    (if-let [supp-err (supplements-missing-response ctx nil)]
      (assoc context :response supp-err)
      (let [lookup-params (cond-> {:system system :code code}
                            version      (assoc :version version)
                            display-lang (assoc :displayLanguage display-lang))
            result (when (and system code)
                     (registry/codesystem-lookup ctx lookup-params))]
        (-> context
            (assoc-in [:request :hades/lookup-params] lookup-params)
            (assoc :response {:status 200 :body result}))))))

(defn- cs-lookup-leave [context]
  (let [response (:response context)]
    (cond
      (not= 200 (:status response))
      context

      (nil? (:body response))
      (let [{:keys [system code]} (get-in context [:request :hades/lookup-params])
            ctx (get-in context [:request :hades/ctx])
            message (if (registry/codesystem ctx system)
                      (str "Unknown code '" code "' in code system '" system "'")
                      (str "Unknown code system: " system))]
        (assoc context :response
               {:status 404
                :body   (wire/operation-outcome
                          [{:severity "error" :type "not-found" :text message}])}))

      (map? (:body response))
      (update-in context [:response :body] wire/lookup->parameters)

      :else
      context)))

(def cs-lookup-handler
  {:name ::cs-lookup-handler :enter cs-lookup-enter})

(def cs-lookup-response
  {:name ::cs-lookup-response :leave cs-lookup-leave})

;; ---------------------------------------------------------------------------
;; CodeSystem $validate-code
;; ---------------------------------------------------------------------------

(defn- cs-validate-enter [context]
  (let [request  (:request context)
        params   (:hades/params request)
        ctx      (:hades/ctx request)]
    (if-let [supp-err (supplements-missing-response ctx nil)]
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
            display-lang (pick-display-language params request ctx)
            vp       (cond-> {:system system :code code}
                       display      (assoc :display display)
                       version      (assoc :version version)
                       display-lang (assoc :displayLanguage display-lang))
            result       (registry/codesystem-validate-code ctx vp)
            input-mode   (if coding? :coding :code)
            result-final (if (:issues result)
                           (update result :issues wire/adjust-issue-expressions input-mode nil)
                           result)]
        (assoc context :response {:status 200 :body result-final})))))

(defn- cs-validate-leave [context]
  (if (= 200 (get-in context [:response :status]))
    (update-in context [:response :body] wire/validate->parameters)
    context))

(def cs-validate-handler
  {:name ::cs-validate-handler :enter cs-validate-enter})

(def cs-validate-response
  {:name ::cs-validate-response :leave cs-validate-leave})

;; ---------------------------------------------------------------------------
;; CodeSystem $subsumes
;; ---------------------------------------------------------------------------

(defn- cs-subsumes-enter [context]
  (let [request (:request context)
        params  (:hades/params request)
        ctx     (:hades/ctx request)
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
        result  (when subs-params (registry/codesystem-subsumes ctx subs-params))]
    (assoc context :response {:status 200 :body result})))

(defn- cs-subsumes-leave [context]
  (if (= 200 (get-in context [:response :status]))
    (update-in context [:response :body] wire/subsumes->parameters)
    context))

(def cs-subsumes-handler
  {:name ::cs-subsumes-handler :enter cs-subsumes-enter})

(def cs-subsumes-response
  {:name ::cs-subsumes-response :leave cs-subsumes-leave})

;; ---------------------------------------------------------------------------
;; ValueSet $validate-code
;; ---------------------------------------------------------------------------

(defn- cc-codings
  "Pull a coding-seq out of a string-keyed CodeableConcept map, coercing to
  the keyword-keyed shape protocol impls expect."
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
  (let [request  (:request context)
        params   (:hades/params request)
        ctx      (:hades/ctx request)
        url      (get-first params "url")
        vs-impl  (when url (registry/valueset ctx url))]
    (if-let [supp-err (supplements-missing-response ctx vs-impl)]
      (assoc context :response supp-err)
      (let [cc        (post-codeable-concept params "codeableConcept")
            codings   (seq (get cc "coding"))
            coding    (post-coding params "coding")
            coding?   (and coding (get coding "code"))
            value-set-version (get-in ctx [:request :value-set-version])
            display-lang (pick-display-language params request ctx)
            base      (cond-> {:url url}
                        value-set-version (assoc :valueSetVersion value-set-version)
                        display-lang      (assoc :displayLanguage display-lang))
            result    (cond
                        codings
                        (let [r (registry/valueset-validate-codeableconcept ctx
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
                          (registry/valueset-validate-code ctx
                            (cond-> (assoc base :system system :code code)
                              display (assoc :display display)
                              version (assoc :version version)))))
            input-mode (cond codings :codeableConcept coding? :coding :else :code)
            result' (if (:issues result)
                      (update result :issues wire/adjust-issue-expressions input-mode nil)
                      result)]
        (-> context
            (assoc-in [:request :hades/input-mode] input-mode)
            (assoc :response {:status 200 :body result'}))))))

(defn- vs-validate-leave [context]
  (let [response (:response context)
        result   (:body response)]
    (cond
      (not= 200 (:status response))
      context

      (:not-found result)
      (assoc context :response
             {:status 404
              :body   (wire/operation-outcome (:issues result))})

      :else
      (update-in context [:response :body] wire/validate->parameters))))

(def vs-validate-handler
  {:name ::vs-validate-handler :enter vs-validate-enter})

(def vs-validate-response
  {:name ::vs-validate-response :leave vs-validate-leave})

;; ---------------------------------------------------------------------------
;; ValueSet $expand
;; ---------------------------------------------------------------------------

(defn- vs-expand-enter [context]
  (let [request  (:request context)
        params   (:hades/params request)
        ctx      (:hades/ctx request)
        url      (get-first params "url")
        vs-impl  (when url (registry/valueset ctx url))]
    (if-let [supp-err (supplements-missing-response ctx vs-impl)]
      (assoc context :response supp-err)
      (let [active-only? (get-bool params "activeOnly")
            filter-value (get-first params "filter")
            display-lang (pick-display-language params request ctx)
            include-desig? (get-bool params "includeDesignations")
            count-value    (get-int params "count")
            offset-value   (get-int params "offset")
            exclude-nested-present? (some? (get-first params "excludeNested"))
            exclude-nested? (let [v (get-bool params "excludeNested")]
                              (if (nil? v) true v))
            req-properties (get-in ctx [:request :properties])
            expand-params  (cond-> {:url             url
                                    :activeOnly      active-only?
                                    :filter          filter-value
                                    :displayLanguage display-lang}
                             (seq req-properties) (assoc :properties req-properties))
            result (registry/valueset-expand ctx expand-params)]
        (-> context
            (assoc-in [:request :hades/expand-context]
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

(def ^:private max-expansion-size-key :hades/max-expansion-size)

(defn- vs-expand-leave [context]
  (let [response (:response context)
        result   (:body response)
        request  (:request context)
        ctx      (:hades/ctx request)
        exp-ctx  (:hades/expand-context request)
        url      (:url exp-ctx)
        max-size (get request max-expansion-size-key)
        error-issue (first (filter #(= "error" (:severity %)) (:issues result)))]
    (cond
      (not= 200 (:status response))
      context

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
      (let [{:keys [check-system-version force-system-version system-version]}
            (:request ctx)
            used-cs  (:used-codesystems result)
            compose-pinned (into #{} (keep :system) (:compose-pins result))
            vs-meta  (when-let [vs (registry/valueset ctx url)] (protos/vs-resource vs {}))
            vs-version-uri (let [v (:version vs-meta)]
                             (if v (str url "|" v) url))
            version-error (when check-system-version
                            (some (fn [{cs-uri :uri}]
                                    (let [[sys resolved] (registry/parse-versioned-uri cs-uri)]
                                      (when-let [pattern (get check-system-version sys)]
                                        (when (and resolved
                                                   (not (registry/version-matches? pattern resolved)))
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
                                     (into (wire/build-echo-params
                                             (assoc exp-ctx :display-language effective-lang))))
                {:keys [count-value offset-value include-designations?]} exp-ctx
                paged-result (cond-> result
                               offset-value (update :concepts #(drop offset-value %))
                               count-value  (update :concepts #(take count-value %))
                               :always      (update :concepts vec))
                vs-map (wire/expansion->valueset paged-result
                         {:vs-meta vs-meta
                          :url url
                          :offset-value offset-value
                          :expansion-params expansion-params
                          :include-designations? include-designations?})]
            (assoc context :response {:status 200 :body vs-map})))))))

(def vs-expand-handler
  {:name ::vs-expand-handler :enter vs-expand-enter})

(def vs-expand-response
  {:name ::vs-expand-response :leave vs-expand-leave})

;; ---------------------------------------------------------------------------
;; ConceptMap $translate (stub)
;; ---------------------------------------------------------------------------

(defn- cm-translate-enter [context]
  (assoc context :response
         {:status 200
          :body   (wire/lookup->parameters {:system nil :code nil :display nil})}))

(def cm-translate-handler
  {:name ::cm-translate-handler :enter cm-translate-enter})

;; ---------------------------------------------------------------------------
;; Metadata endpoints
;; ---------------------------------------------------------------------------

(defn- server-url-for [request]
  (let [scheme (name (:scheme request :http))
        host   (or (get-in request [:headers "host"]) "localhost")]
    (str scheme "://" host "/fhir")))

(defn- metadata-enter [context]
  (let [request (:request context)
        mode    (first (get (parse-query (:query-string request)) "mode"))
        body    (if (= "terminology" mode)
                  (metadata/terminology-capabilities)
                  (metadata/capability-statement {:url (server-url-for request)}))]
    (assoc context :response {:status 200 :body body})))

(def metadata-handler
  {:name ::metadata-handler :enter metadata-enter})

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(def ^:private common-in  [content-negotiation catch-all-error tx-ctx])

(defn- wrap-handler [op-interceptors]
  (into [] (concat common-in op-interceptors)))

(defn- with-max-expansion
  "Inject the server-level max-expansion-size into the Pedestal request.
  This lets `vs-expand-leave` see the limit without global state."
  [max-size]
  {:name ::with-max-expansion
   :enter (fn [context]
            (assoc-in context [:request max-expansion-size-key] max-size))})

(defn routes [{:keys [max-expansion-size]}]
  (let [max-inj (with-max-expansion max-expansion-size)]
    #{["/fhir/metadata"                   :get  (wrap-handler [metadata-handler])                                         :route-name ::metadata]

      ["/fhir/CodeSystem/$lookup"         :get  (wrap-handler [cs-lookup-response cs-lookup-handler])                     :route-name ::cs-lookup-get]
      ["/fhir/CodeSystem/$lookup"         :post (wrap-handler [cs-lookup-response cs-lookup-handler])                     :route-name ::cs-lookup-post]

      ["/fhir/CodeSystem/$validate-code"  :get  (wrap-handler [cs-validate-response cs-validate-handler])                 :route-name ::cs-validate-get]
      ["/fhir/CodeSystem/$validate-code"  :post (wrap-handler [cs-validate-response cs-validate-handler])                 :route-name ::cs-validate-post]

      ["/fhir/CodeSystem/$subsumes"       :get  (wrap-handler [cs-subsumes-response cs-subsumes-handler])                 :route-name ::cs-subsumes-get]
      ["/fhir/CodeSystem/$subsumes"       :post (wrap-handler [cs-subsumes-response cs-subsumes-handler])                 :route-name ::cs-subsumes-post]

      ["/fhir/ValueSet/$validate-code"    :get  (wrap-handler [vs-validate-response vs-validate-handler])                 :route-name ::vs-validate-get]
      ["/fhir/ValueSet/$validate-code"    :post (wrap-handler [vs-validate-response vs-validate-handler])                 :route-name ::vs-validate-post]

      ["/fhir/ValueSet/$expand"           :get  (wrap-handler [max-inj vs-expand-response vs-expand-handler])             :route-name ::vs-expand-get]
      ["/fhir/ValueSet/$expand"           :post (wrap-handler [max-inj vs-expand-response vs-expand-handler])             :route-name ::vs-expand-post]

      ["/fhir/ConceptMap/$translate"      :get  (wrap-handler [cm-translate-handler])                                     :route-name ::cm-translate-get]
      ["/fhir/ConceptMap/$translate"      :post (wrap-handler [cm-translate-handler])                                     :route-name ::cm-translate-post]}))

;; ---------------------------------------------------------------------------
;; Server
;; ---------------------------------------------------------------------------

(defn make-server
  "Create an unstarted Hades FHIR server connector. Use `start!` and `stop!`
  to control the lifecycle.

  Options:
    :port               — TCP port (default 8080)
    :host               — bind address (default \"0.0.0.0\")
    :max-expansion-size — soft limit on ValueSet expansion total (default 10000)"
  [opts]
  (let [opts (merge {:port 8080 :host "0.0.0.0" :max-expansion-size 10000} opts)]
    (-> (conn/default-connector-map (:host opts) (:port opts))
        (conn/with-interceptors
          [interceptors/not-found
           (cors/allow-origin {:creds true :allowed-origins (constantly true)})])
        (conn/with-routes (routes opts))
        (jetty/create-connector {:join? false}))))

(defn start!
  "Start a Hades server connector; returns the connector."
  [connector]
  (log/info "Starting Hades FHIR server")
  (conn/start! connector))

(defn stop!
  "Stop a Hades server connector; returns the connector."
  [connector]
  (log/info "Stopping Hades FHIR server")
  (conn/stop! connector))
