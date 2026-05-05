(ns com.eldrix.hades.impl.http-matrix-test
  "Cross-terminology HTTP request matrix.

  Boots a real Hades FHIR server fronting SNOMED CT, LOINC and FHIR
  conformance packages, then drives a data-table of GET / POST cases
  through every operation. The matrix's job is structural: each case
  asserts a non-5xx status and a sane response shape — a 5xx anywhere
  is a bug. The reason this lives next to the conformance suite is to
  cover paths the IG fixtures don't (malformed codes, unknown systems,
  unknown ValueSets, both servable layouts for FHIR packages).

  The same matrix runs against two FHIR servable layouts so that the
  in-memory and SQLite providers stay in lockstep:

    * `http-matrix-against-in-memory-fhir`
    * `http-matrix-against-sqlite-fhir`

  Tagged `^:live`; needs every fixture that participates."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]))

;; ---------------------------------------------------------------------------
;; Stable identifiers
;;
;; SNOMED: pinned International release — concept ids are durable.
;; LOINC : 8480-6 (Systolic blood pressure) — present in every LOINC
;;         release ≥ 2.0.
;; FHIR  : `v3-AdministrativeGender` is shipped by hl7.terminology.r4
;;         under both CodeSystem and ValueSet URLs. Codes F/M/UN are
;;         stable for the lifetime of the v3 namespace.
;; ---------------------------------------------------------------------------

(def ^:private snomed-system "http://snomed.info/sct")
(def ^:private loinc-system  "http://loinc.org")
(def ^:private fhir-cs       "http://terminology.hl7.org/CodeSystem/v3-AdministrativeGender")
(def ^:private fhir-vs       "http://terminology.hl7.org/ValueSet/v3-AdministrativeGender")

;; A hierarchical FHIR CodeSystem used to exercise the `property=parent`
;; filter on the FHIR providers (in-memory + SQLite). `ActClassExposure`
;; sits one level under `ActClass`.
(def ^:private fhir-hier-cs   "http://terminology.hl7.org/CodeSystem/conceptdomains")
(def ^:private fhir-hier-code "ActClassExposure")

;; A synthetic CodeSystem used to drive `tx-resource` overlay rows. The
;; URL doesn't collide with anything in the base service, and the codes
;; are chosen so the assertions can't be satisfied by the overlay
;; happening to overlap with SNOMED/LOINC/FHIR pkg content.
(def ^:private overlay-cs-url "http://example.com/tx/overlay-cs")
(def ^:private overlay-cs
  {"resourceType"  "CodeSystem"
   "url"           overlay-cs-url "version" "1.0" "status" "active"
   "content"       "complete" "caseSensitive" true
   "concept"       [{"code" "OV1" "display" "Overlay One"}]})

;; ---------------------------------------------------------------------------
;; Body inspectors. Each predicate takes the parsed JSON body and
;; returns truthy on match. Mirrors the helpers in
;; `impl.server-test` — kept inline so this namespace is self-contained.
;; ---------------------------------------------------------------------------

(defn- get-param [body nm]
  (some (fn [p] (when (= nm (get p "name")) p))
        (get body "parameter")))

(defn- result-true?  [body] (true?  (get (get-param body "result") "valueBoolean")))
(defn- result-false? [body] (false? (get (get-param body "result") "valueBoolean")))
(defn- has-issues?   [body] (some? (get (get-param body "issues") "resource")))
(defn- has-display?  [body] (some? (get (get-param body "display") "valueString")))
(defn- operation-outcome? [body] (= "OperationOutcome" (get body "resourceType")))
(defn- valueset-resource? [body] (= "ValueSet" (get body "resourceType")))

(defn- issues-of-severity?
  "Truthy when the `issues` Parameters part carries at least one
  OperationOutcome.issue with the given severity."
  [body severity]
  (some (fn [iss] (= severity (get iss "severity")))
        (get-in (get (get-param body "issues") "resource") ["issue"] [])))

(defn- has-warning-issue? [body] (issues-of-severity? body "warning"))
(defn- has-error-issue?   [body] (issues-of-severity? body "error"))

(defn- property-codes
  "Return the set of property codes returned in a $lookup Parameters
  body. Each `property` parameter has a `part` with a `code` part
  whose value lives under `valueCode` (or `valueString`)."
  [body]
  (into #{}
        (keep (fn [p]
                (when (= "property" (get p "name"))
                  (some (fn [pp]
                          (when (= "code" (get pp "name"))
                            (or (get pp "valueCode") (get pp "valueString"))))
                        (get p "part")))))
        (get body "parameter")))

(defn- only-property-codes?
  "Predicate: every `property` part returned is in `allowed`."
  [allowed]
  (fn [body]
    (let [returned (property-codes body)]
      (and (seq returned)
           (every? allowed returned)))))

(defn- no-property-codes?
  "Predicate: none of `forbidden` appear in the returned `property` codes.
  Locks down the per-code filter so a `_property=…` request can't drag
  in adjacent typed properties."
  [forbidden]
  (fn [body]
    (let [returned (property-codes body)]
      (every? (complement forbidden) returned))))

(defn- contains-property-codes?
  "Predicate: every code in `required` appears in the returned
  `property` codes."
  [required]
  (fn [body]
    (let [returned (property-codes body)]
      (every? returned required))))

(defn- subsumes-outcome=? [expected]
  ;; The wire layer emits the outcome as `valueString` today
  ;; (see `wire/subsumes->parameters` + `param-auto`); accept either
  ;; encoding so this predicate doesn't break if the typed-value
  ;; inference is tightened to `valueCode`.
  (fn [body] (let [p (get-param body "outcome")]
               (= expected (or (get p "valueCode") (get p "valueString"))))))

;; ---------------------------------------------------------------------------
;; Case → HTTP request
;;
;; A case is data:
;;   :method       — :get or :post
;;   :op           — :cs-lookup | :cs-validate-code | :cs-subsumes
;;                   | :vs-expand | :vs-validate-code | :cm-translate
;;   :system :code — the coding under test
;;   :url          — the canonical (ValueSet for $expand /
;;                   $vs-validate-code, ConceptMap for $translate, or
;;                   the CodeSystem url for some $validate-code shapes)
;;   :code-a :code-b — for $subsumes
;;   :target       — for $translate
;;   :extras       — extra query params for GET (e.g. {:count "5"}),
;;                    flat string→string. POST cases use `:extra-params`.
;;   :extra-params — extra FHIR Parameters parts. Each entry is
;;                    `{:name :value-key :value}` for primitives or
;;                    `{:name :resource <fhir-map>}` for inline
;;                    resources (POST-only). Primitives flatten to
;;                    query-string for GET; resources are skipped on GET.
;;   :expect       — {:status :outcome? :result? :body-pred?}
;;   :reason       — short note explaining why the case exists
;; ---------------------------------------------------------------------------

(def ^:private op->path
  {:cs-lookup         "/CodeSystem/$lookup"
   :cs-validate-code  "/CodeSystem/$validate-code"
   :cs-subsumes       "/CodeSystem/$subsumes"
   :vs-expand         "/ValueSet/$expand"
   :vs-validate-code  "/ValueSet/$validate-code"
   :cm-translate      "/ConceptMap/$translate"})

(defn- url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn- query-string [pairs]
  (->> pairs
       (keep (fn [[k v]] (when v (str k "=" (url-encode v)))))
       (str/join "&")))

(defn- extra-param->query-pair
  "Flatten one `:extra-params` entry into a `[name value-string]` pair
  for the query string. Resource-valued params are skipped — GETs
  can't carry inline resources."
  [{:keys [name value resource]}]
  (when (and name (nil? resource) (some? value))
    [name (str value)]))

(defn- extra-param->parameter
  "Map one `:extra-params` entry to a FHIR Parameters parameter."
  [{:keys [name value-key value resource]}]
  (cond
    resource         {:name name :resource resource}
    (boolean? value) {:name name :valueBoolean value}
    (integer? value) {:name name :valueInteger value}
    value-key        {(keyword value-key) value :name name}
    :else            {:name name :valueString (str value)}))

(defn- ->get-path
  [{:keys [op url system code code-a code-b target extras extra-params]}]
  (let [base  (op->path op)
        pairs (cond-> []
                url     (conj ["url" url])
                system  (conj ["system" system])
                code    (conj ["code" code])
                code-a  (conj ["codeA" code-a])
                code-b  (conj ["codeB" code-b])
                target  (conj ["target" target])
                :always (into (mapv (fn [[k v]] [(name k) v]) extras))
                :always (into (keep extra-param->query-pair extra-params)))
        qs    (query-string pairs)]
    (if (seq qs) (str base "?" qs) base)))

(defn- parameters-body
  [{:keys [url system code code-a code-b target extras extra-params]}]
  {:resourceType "Parameters"
   :parameter
   (-> []
       (cond->
         url     (conj {:name "url"     :valueUri url})
         system  (conj {:name "system"  :valueUri system})
         code    (conj {:name "code"    :valueCode code})
         code-a  (conj {:name "codeA"   :valueCode code-a})
         code-b  (conj {:name "codeB"   :valueCode code-b})
         target  (conj {:name "target"  :valueUri target}))
       (into (mapv (fn [[k v]] {:name (name k) :valueString v}) extras))
       (into (mapv extra-param->parameter extra-params)))})

(defn- case->request
  [{:keys [method op] :as c}]
  (case method
    :get  {:method :get :path (->get-path c)}
    :post {:method :post :path (op->path op) :body (parameters-body c)}))

;; ---------------------------------------------------------------------------
;; Case runner
;; ---------------------------------------------------------------------------

(def ^:dynamic *server* nil)

(defn- request! [req] (fixtures/request! (:url *server*) req))

(defn- case-label [{:keys [reason method op terminology system code url]}]
  (str terminology " " (str/upper-case (name method)) " " (name op)
       (when system (str " sys=" system))
       (when url    (str " url=" url))
       (when code   (str " code=" code))
       (when reason (str " (" reason ")"))))

(defn- check-case
  [layout-name {:keys [expect] :as c}]
  (let [label (str layout-name " | " (case-label c))
        req   (case->request c)
        {:keys [status body]} (request! req)]
    (testing label
      (is (not (<= 500 status 599))
          (str label " — server-error status " status
               "; bodies that 5xx are always a bug"))
      (when-let [s (:status expect)]
        (is (= s status) (str label " — expected status " s ", got " status)))
      (when (true? (:result expect))
        (is (result-true? body) (str label " — result=true expected")))
      (when (false? (:result expect))
        (is (result-false? body) (str label " — result=false expected")))
      (when (:issues? expect)
        (is (has-issues? body) (str label " — issues parameter expected")))
      (when (:warning? expect)
        (is (has-warning-issue? body) (str label " — warning issue expected")))
      (when (:error? expect)
        (is (has-error-issue? body) (str label " — error issue expected")))
      (when (:display? expect)
        (is (has-display? body) (str label " — display parameter expected")))
      (when (:outcome? expect)
        (is (operation-outcome? body) (str label " — OperationOutcome body expected")))
      (when (:valueset? expect)
        (is (valueset-resource? body) (str label " — ValueSet body expected")))
      (when-let [oc (:subsumes expect)]
        (is ((subsumes-outcome=? oc) body)
            (str label " — subsumes outcome=" oc " expected")))
      (when-let [allowed (:only-property-codes expect)]
        (is ((only-property-codes? allowed) body)
            (str label " — only property codes " allowed " expected; got "
                 (property-codes body))))
      (when-let [forbidden (:no-property-codes expect)]
        (is ((no-property-codes? forbidden) body)
            (str label " — none of " forbidden " expected; got "
                 (property-codes body))))
      (when-let [required (:contains-property-codes expect)]
        (is ((contains-property-codes? required) body)
            (str label " — all of " required " expected; got "
                 (property-codes body)))))))

;; ---------------------------------------------------------------------------
;; Matrix — one row per case. New cases are a new row.
;; ---------------------------------------------------------------------------

(def ^:private snomed-cases
  [;; Happy paths — every operation Hades implements for SNOMED.
   {:terminology :snomed :method :get  :op :cs-lookup
    :system snomed-system :code "73211009"
    :expect {:status 200 :display? true}}

   {:terminology :snomed :method :post :op :cs-lookup
    :system snomed-system :code "73211009"
    :expect {:status 200 :display? true}}

   {:terminology :snomed :method :get  :op :cs-validate-code
    :system snomed-system :code "73211009"
    :expect {:status 200 :result true}}

   {:terminology :snomed :method :post :op :cs-validate-code
    :system snomed-system :code "73211009"
    :expect {:status 200 :result true}}

   {:terminology :snomed :method :get  :op :cs-subsumes
    :system snomed-system :code-a "73211009" :code-b "73211009"
    :expect {:status 200 :subsumes "equivalent"}}

   {:terminology :snomed :method :get  :op :vs-expand
    :url (str snomed-system "?fhir_vs=isa/73211009")
    :extras {:count "5"}
    :expect {:status 200 :valueset? true}}

   {:terminology :snomed :method :get  :op :cm-translate
    :url (str snomed-system "?fhir_cm=900000000000526001")
    :system snomed-system :code "225983005" :target snomed-system
    :expect {:status 200 :result true}}

   ;; Client-error paths.
   {:terminology :snomed :method :get  :op :cs-validate-code
    :system snomed-system :code "999999999"
    :reason "unknown numeric concept id → result=false + issues, no 500"
    :expect {:status 200 :result false :issues? true}}

   {:terminology :snomed :method :get  :op :cs-validate-code
    :system snomed-system :code "NOT-A-CODE"
    :reason "regression: hermes parse-failure must not 500"
    :expect {:status 200 :result false :issues? true}}

   {:terminology :snomed :method :post :op :cs-validate-code
    :system snomed-system :code "NOT-A-CODE"
    :reason "regression — POST body path"
    :expect {:status 200 :result false :issues? true}}

   {:terminology :snomed :method :get  :op :cs-validate-code
    :system snomed-system :code "12345 ::not even valid SCG"
    :reason "regression: garbled SCG must not 500"
    :expect {:status 200 :result false}}

   {:terminology :snomed :method :get  :op :cs-lookup
    :system snomed-system :code "999999999"
    :expect {:status 404 :outcome? true}}

   {:terminology :snomed :method :get  :op :cs-lookup
    :system "http://example.com/fake-snomed" :code "1"
    :reason "unknown system surfaces as 404, not 500"
    :expect {:status 404 :outcome? true}}

   {:terminology :snomed :method :get  :op :cm-translate
    :url "http://example.com/fake-cm"
    :system snomed-system :code "73211009" :target snomed-system
    :expect {:status 404 :outcome? true}}

   ;; $lookup with `property=` filter — restricts which property/slice
   ;; codes the response carries. Spec: "A property... that the client
   ;; wishes to be returned in the output."
   {:terminology :snomed :method :get  :op :cs-lookup
    :system snomed-system :code "73211009"
    :extra-params [{:name "property" :value "parent"}]
    :reason "property=parent must restrict response to parent slices only"
    :expect {:status 200 :only-property-codes #{"parent"}}}

   ;; Per-code typed filter: asking only for `sufficientlyDefined` must
   ;; not drag in `inactive` (or any other typed concept property).
   ;; The provider must gate every named typed property individually.
   {:terminology :snomed :method :get  :op :cs-lookup
    :system snomed-system :code "73211009"
    :extra-params [{:name "property" :value "sufficientlyDefined"}]
    :reason "property=sufficientlyDefined must not return :inactive"
    :expect {:status 200
             :only-property-codes #{"sufficientlyDefined"}
             :no-property-codes   #{"inactive" "parent" "child"}}}

   ;; FHIR `*` wildcard — equivalent to no filter. Caller still expects
   ;; the full property set (parent+inactive+sufficientlyDefined for a
   ;; SNOMED concept with parents).
   {:terminology :snomed :method :get  :op :cs-lookup
    :system snomed-system :code "73211009"
    :extra-params [{:name "property" :value "*"}]
    :reason "property=* must behave as no filter"
    :expect {:status 200
             :contains-property-codes #{"parent" "inactive" "sufficientlyDefined"}}}

   ;; Subsumes — directional correctness.
   ;; 64572001 (Disease) is a transitive ancestor of 73211009
   ;; (Diabetes mellitus); 24700007 (Multiple sclerosis) is unrelated.
   {:terminology :snomed :method :get  :op :cs-subsumes
    :system snomed-system :code-a "64572001" :code-b "73211009"
    :reason "ancestor codeA → outcome=subsumes"
    :expect {:status 200 :subsumes "subsumes"}}

   {:terminology :snomed :method :get  :op :cs-subsumes
    :system snomed-system :code-a "73211009" :code-b "64572001"
    :reason "descendant codeA → outcome=subsumed-by"
    :expect {:status 200 :subsumes "subsumed-by"}}

   {:terminology :snomed :method :get  :op :cs-subsumes
    :system snomed-system :code-a "73211009" :code-b "24700007"
    :reason "unrelated disorders → outcome=not-subsumed"
    :expect {:status 200 :subsumes "not-subsumed"}}])

(def ^:private loinc-cases
  [{:terminology :loinc :method :get  :op :cs-lookup
    :system loinc-system :code "8480-6"
    :expect {:status 200 :display? true}}

   {:terminology :loinc :method :post :op :cs-lookup
    :system loinc-system :code "8480-6"
    :expect {:status 200 :display? true}}

   {:terminology :loinc :method :get  :op :cs-validate-code
    :system loinc-system :code "8480-6"
    :expect {:status 200 :result true}}

   {:terminology :loinc :method :post :op :cs-validate-code
    :system loinc-system :code "8480-6"
    :expect {:status 200 :result true}}

   {:terminology :loinc :method :get  :op :cs-validate-code
    :system loinc-system :code "NOT-A-LOINC"
    :reason "malformed LOINC code surfaces cleanly"
    :expect {:status 200 :result false}}

   {:terminology :loinc :method :get  :op :cs-lookup
    :system loinc-system :code "0000000-0"
    :expect {:status 404 :outcome? true}}])

(def ^:private fhir-cases
  [{:terminology :fhir :method :get  :op :cs-lookup
    :system fhir-cs :code "F"
    :expect {:status 200 :display? true}}

   {:terminology :fhir :method :post :op :cs-lookup
    :system fhir-cs :code "F"
    :expect {:status 200 :display? true}}

   {:terminology :fhir :method :get  :op :cs-validate-code
    :system fhir-cs :code "F"
    :expect {:status 200 :result true}}

   {:terminology :fhir :method :post :op :cs-validate-code
    :system fhir-cs :code "F"
    :expect {:status 200 :result true}}

   {:terminology :fhir :method :get  :op :cs-validate-code
    :system fhir-cs :code "ALIEN"
    :expect {:status 200 :result false :issues? true}}

   {:terminology :fhir :method :get  :op :vs-expand
    :url fhir-vs
    :expect {:status 200 :valueset? true}}

   {:terminology :fhir :method :get  :op :vs-validate-code
    :url fhir-vs :system fhir-cs :code "F"
    :expect {:status 200 :result true}}

   {:terminology :fhir :method :post :op :vs-validate-code
    :url fhir-vs :system fhir-cs :code "F"
    :expect {:status 200 :result true}}

   {:terminology :fhir :method :get  :op :vs-validate-code
    :url fhir-vs :system fhir-cs :code "ALIEN"
    :expect {:status 200 :result false}}

   {:terminology :fhir :method :get  :op :vs-validate-code
    :url "http://example.com/fake-vs"
    :system fhir-cs :code "F"
    :reason "unknown ValueSet surfaces as 404, not 500"
    :expect {:status 404 :outcome? true}}

   {:terminology :fhir :method :get  :op :cs-lookup
    :system "http://example.com/fake-cs" :code "x"
    :expect {:status 404 :outcome? true}}

   ;; $lookup with `property=` filter against a hierarchical FHIR CS.
   ;; Probes whichever FHIR provider serves the layout (in-memory or SQLite).
   ;; The `:no-property-codes` clause locks parity with SQLite: when a
   ;; caller asks only for slice keys, `:inactive` (a typed concept
   ;; property) must not be returned.
   {:terminology :fhir :method :get  :op :cs-lookup
    :system fhir-hier-cs :code fhir-hier-code
    :extra-params [{:name "property" :value "parent"}]
    :reason "property=parent must restrict response to parent slices only"
    :expect {:status 200
             :only-property-codes #{"parent"}
             :no-property-codes   #{"inactive"}}}

   ;; Display correctness — exercises the FHIR CodeSystem provider's
   ;; display-comparison path (not just code presence). v3-AdministrativeGender
   ;; concept F has display "Female".
   {:terminology :fhir :method :get  :op :cs-validate-code
    :system fhir-cs :code "F"
    :extra-params [{:name "display" :value "Female"}]
    :reason "matching display → result=true, no display-mismatch issue"
    :expect {:status 200 :result true}}

   {:terminology :fhir :method :get  :op :cs-validate-code
    :system fhir-cs :code "F"
    :extra-params [{:name "display" :value "WRONG_DISPLAY"}]
    :reason "wrong display → result=false + invalid-display error"
    :expect {:status 200 :result false :issues? true :error? true}}])

(def ^:private tx-resource-cases
  ;; Request-scoped CodeSystem overlay via `tx-resource`. Proves the
  ;; derive-svc interceptor folds an inline CodeSystem onto the base
  ;; service for `$validate-code` (existing tx-resource tests cover
  ;; `$lookup` only). Layout-independent.
  [{:terminology :overlay :method :post :op :cs-validate-code
    :system overlay-cs-url :code "OV1"
    :extra-params [{:name "tx-resource" :resource overlay-cs}]
    :reason "validate-code resolves through request-scoped overlay"
    :expect {:status 200 :result true}}

   {:terminology :overlay :method :post :op :cs-validate-code
    :system overlay-cs-url :code "DOES-NOT-EXIST"
    :extra-params [{:name "tx-resource" :resource overlay-cs}]
    :reason "overlay still reports unknown codes — not a 5xx"
    :expect {:status 200 :result false :issues? true}}

   {:terminology :overlay :method :get  :op :cs-validate-code
    :system overlay-cs-url :code "OV1"
    :reason "without tx-resource the overlay system is unknown — no leakage"
    :expect {:status 200 :result false :issues? true}}])

(def ^:private all-cases
  (into [] cat [snomed-cases loinc-cases fhir-cases tx-resource-cases]))

;; ---------------------------------------------------------------------------
;; Server fixtures — one matrix run per FHIR servable layout.
;; ---------------------------------------------------------------------------

(defn- run-matrix [layout-name cases]
  (doseq [c cases] (check-case layout-name c)))

(defn- with-server
  "Open a Hades service over `paths`, start an HTTP server, run `body-fn`
  against `*server*`, then tear down."
  [paths body-fn]
  (let [svc (hades/open paths)
        srv (fixtures/start-server svc)]
    (binding [*server* srv]
      (try (body-fn)
           (finally
             (fixtures/stop-server srv)
             (hades/close svc))))))

(defn- in-memory-fixture [f]
  (fixtures/assert-snomed-db!)
  (fixtures/assert-loinc-db!)
  (fixtures/assert-fhir-packages!)
  (with-server [fixtures/snomed-db-path
                fixtures/loinc-db-path
                fixtures/fhir-packages-dir]
    f))

(defn- sqlite-fixture [f]
  (fixtures/assert-snomed-db!)
  (fixtures/assert-loinc-db!)
  (fixtures/assert-fhir-packages!)
  (with-server [fixtures/snomed-db-path
                fixtures/loinc-db-path
                fixtures/fhir-smoke-db-path]
    f))

;; The two test vars install their own once-only fixture so the JVM
;; only opens the SNOMED/LOINC DBs twice for the whole namespace.

(deftest ^:live http-matrix-against-in-memory-fhir
  (in-memory-fixture
    #(run-matrix "in-memory FHIR" all-cases)))

(deftest ^:live http-matrix-against-sqlite-fhir
  (sqlite-fixture
    #(run-matrix "sqlite FHIR" all-cases)))
