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

;; ValueSets keyed off LOINC. `observation-codes` is the canonical
;; FHIR R4 entry point — its compose is `{include [{system http://loinc.org}]}`,
;; so a `$expand` against it routes through the compose engine to the
;; LOINC CodeSystem provider. `loinc.org/vs` is the LOINC IG's implicit
;; "all of LOINC" URL (parallel to `snomed.info/sct?fhir_vs`).
(def ^:private loinc-observation-vs "http://hl7.org/fhir/ValueSet/observation-codes")
(def ^:private loinc-implicit-vs    "http://loinc.org/vs")
(def ^:private loinc-hgb-code       "718-7")
(def ^:private loinc-hgb-de-display "Hämoglobin [Masse/Volumen] in Blut")
(def ^:private loinc-part-snomed-map
  "http://loinc.org/cm/part-related-code-mapping/http%3A%2F%2Fsnomed.info%2Fsct")
(def ^:private loinc-ieee-map "http://loinc.org/cm/ieee-medical-device")
(def ^:private loinc-rsna-rid-map "http://loinc.org/cm/rsna-playbook/rid")
(def ^:private loinc-rsna-rpid-map "http://loinc.org/cm/rsna-playbook/rpid")
(def ^:private ieee-system "urn:iso:std:iso:11073:10101")
(def ^:private radlex-system "http://www.radlex.org")
(def ^:private rsna-playbook-system "http://www.rsna.org/RadLex_Playbook")

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

(def ^:private case-insensitive-cs-url "http://example.com/tx/case-insensitive-cs")
(def ^:private case-insensitive-vs-url "http://example.com/tx/case-insensitive-vs")
(def ^:private case-insensitive-cs
  {"resourceType" "CodeSystem"
   "url" case-insensitive-cs-url "version" "1.0" "status" "active"
   "content" "complete" "caseSensitive" false
   "concept" [{"code" "code1" "display" "Display 1"}]})
(def ^:private case-insensitive-vs
  {"resourceType" "ValueSet"
   "url" case-insensitive-vs-url "version" "1.0" "status" "active"
   "compose" {"include" [{"system" case-insensitive-cs-url}]}})

(def ^:private unsupported-loinc-filter-vs
  {"resourceType" "ValueSet"
   "url" "http://example.com/vs/unsupported-loinc-filter"
   "status" "active"
   "compose" {"include" [{"system" loinc-system
                          "filter" [{"property" "not-a-loinc-filter"
                                     "op" "="
                                     "value" "x"}]}]}})

(def ^:private tx-benchmark-ex07-vs
  {"resourceType" "ValueSet"
   "compose" {"include" [{"system" snomed-system}
                         {"system" loinc-system}
                         {"system" "http://www.nlm.nih.gov/research/umls/rxnorm"}]}})

(def ^:private ips-pregnancy-status-map
  "http://hl7.org/fhir/uv/ips/ConceptMap/loinc-pregnancy-status-to-snomed-ct-uv-ips")

(def ^:private ips-smoking-status-map
  "http://hl7.org/fhir/uv/ips/ConceptMap/loinc-smoking-status-to-snomed-ct-uv-ips")

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
(defn- parameters-resource? [body] (= "Parameters" (get body "resourceType")))
(defn- operation-outcome? [body] (= "OperationOutcome" (get body "resourceType")))
(defn- valueset-resource? [body] (= "ValueSet" (get body "resourceType")))

(defn- lookup-display [body] (get (get-param body "display") "valueString"))

(defn- param-value
  [body nm value-key]
  (get (get-param body nm) value-key))

(defn- param-value=? [nm value-key expected]
  (fn [body] (= expected (param-value body nm value-key))))

(defn- operation-outcome-detail-codes [body]
  (into #{}
        (keep (fn [issue]
                (some #(get % "code") (get-in issue ["details" "coding"]))))
        (get body "issue")))

(defn- operation-outcome-has-detail-code? [code]
  (fn [body] (contains? (operation-outcome-detail-codes body) code)))

(defn- translate-match-codes
  "Return the target concept codes in a $translate Parameters body."
  [body]
  (into #{}
        (keep (fn [p]
                (when (= "match" (get p "name"))
                  (some (fn [pp]
                          (when (= "concept" (get pp "name"))
                            (get-in pp ["valueCoding" "code"])))
                        (get p "part")))))
        (get body "parameter")))

(defn- translate-match-code? [code]
  (fn [body] (contains? (translate-match-codes body) code)))

(defn- translate-matches
  [body]
  (mapv (fn [p]
          (let [parts (get p "part")
                equivalence (some #(when (= "equivalence" (get % "name"))
                                      (get % "valueCode"))
                                  parts)
                coding (some #(when (= "concept" (get % "name"))
                                (get % "valueCoding"))
                             parts)]
            {:equivalence equivalence
             :system (get coding "system")
             :code (get coding "code")}))
        (filter #(= "match" (get % "name")) (get body "parameter"))))

(defn- translate-match?
  [expected]
  (fn [body] (some #(= expected (select-keys % (keys expected)))
                   (translate-matches body))))

(defn- expansion-codes
  "Return the set of `code` strings in `expansion.contains` of a
  `$expand` ValueSet response body."
  [body]
  (into #{} (keep #(get % "code")) (get-in body ["expansion" "contains"] [])))

(defn- expansion-has-results? [body]
  (seq (get-in body ["expansion" "contains"])))

(defn- expansion-total [body]
  (get-in body ["expansion" "total"]))

(defn- expansion-total-at-least? [minimum]
  (fn [body]
    (let [total (expansion-total body)]
      (and (integer? total) (<= minimum total)))))

(defn- expansion-contains?
  "Predicate: every code in `required` appears in the response's
  expansion.contains. Locks down `$expand` cases that need to confirm a
  filter / compose include actually surfaced specific concepts (not just
  that the body shape is a ValueSet)."
  [required]
  (fn [body]
    (let [present (expansion-codes body)]
      (every? present required))))

(defn- display-equals?
  "Predicate: `$lookup`'s `display` parameter equals `expected`. Used to
  confirm the right designation type was selected — distinct from
  `has-display?`, which only checks presence."
  [expected]
  (fn [body] (= expected (lookup-display body))))

(defn- issues-of-severity?
  "Truthy when the `issues` Parameters part carries at least one
  OperationOutcome.issue with the given severity."
  [body severity]
  (some (fn [iss] (= severity (get iss "severity")))
        (get-in (get (get-param body "issues") "resource") ["issue"] [])))

(defn- parameter-issue-detail-codes [body]
  (into #{}
        (keep (fn [issue]
                (some #(get % "code") (get-in issue ["details" "coding"]))))
        (get-in (get (get-param body "issues") "resource") ["issue"] [])))

(defn- has-warning-issue? [body] (issues-of-severity? body "warning"))
(defn- has-error-issue?   [body] (issues-of-severity? body "error"))
(defn- has-information-issue? [body] (issues-of-severity? body "information"))

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
      (when (:information? expect)
        (is (has-information-issue? body) (str label " — information issue expected")))
      (when (:display? expect)
        (is (has-display? body) (str label " — display parameter expected")))
      (when (:parameters? expect)
        (is (parameters-resource? body) (str label " — Parameters body expected")))
      (when (:outcome? expect)
        (is (operation-outcome? body) (str label " — OperationOutcome body expected")))
      (when-let [code (:outcome-detail-code expect)]
        (is ((operation-outcome-has-detail-code? code) body)
            (str label " — OperationOutcome detail code=" code
                 " expected; got " (operation-outcome-detail-codes body))))
      (when (:valueset? expect)
        (is (valueset-resource? body) (str label " — ValueSet body expected")))
      (when (:expansion-results? expect)
        (is (expansion-has-results? body)
            (str label " — non-empty expansion expected")))
      (when-let [minimum (:expansion-total-at-least expect)]
        (is ((expansion-total-at-least? minimum) body)
            (str label " — expansion.total >= " minimum
                 " expected; got " (pr-str (expansion-total body)))))
      (when-let [expected (:param-code expect)]
        (is ((param-value=? "code" "valueCode" expected) body)
            (str label " — Parameters code=" expected " expected")))
      (when-let [expected (:param-system expect)]
        (is ((param-value=? "system" "valueUri" expected) body)
            (str label " — Parameters system=" expected " expected")))
      (when-let [expected (:normalized-code expect)]
        (is ((param-value=? "normalized-code" "valueCode" expected) body)
            (str label " — Parameters normalized-code=" expected " expected")))
      (when-let [expected (:issue-detail-code expect)]
        (is (contains? (parameter-issue-detail-codes body) expected)
            (str label " — Parameters issues detail code=" expected
                 " expected; got " (parameter-issue-detail-codes body))))
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
                 (property-codes body))))
      (when-let [required (:expansion-contains expect)]
        (is ((expansion-contains? required) body)
            (str label " — expansion expected to contain " required
                 "; got " (expansion-codes body))))
      (when-let [d (:display-equals expect)]
        (is ((display-equals? d) body)
            (str label " — expected display=" (pr-str d)
                 ", got " (pr-str (lookup-display body)))))
      (when-let [code (:translate-match-code expect)]
        (is ((translate-match-code? code) body)
            (str label " — translate expected match code=" code
                 "; got " (translate-match-codes body))))
      (when-let [match (:translate-match expect)]
        (is ((translate-match? match) body)
            (str label " — translate expected match " match
                 "; got " (translate-matches body)))))))

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
    :expect {:status 200 :result false :issues? true}}

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

   {:terminology :loinc :method :get  :op :cs-lookup
    :system loinc-system :code "8867-4"
    :reason "tx-benchmark LK02 preflight: Heart rate lookup echoes code + system"
    :expect {:status 200
             :parameters? true
             :param-code "8867-4"
             :param-system loinc-system}}

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
    :expect {:status 404 :outcome? true}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url "http://loinc.org/cm/map-to"
    :system loinc-system :code "1009-0" :target loinc-system
    :reason "MapTo.csv replacement ConceptMap"
    :expect {:status 200 :result true :translate-match-code "1007-4"}}

   {:terminology :loinc :method :post :op :cm-translate
    :url "http://loinc.org/cm/map-to"
    :system loinc-system :code "1009-0" :target loinc-system
    :reason "MapTo.csv replacement ConceptMap via POST Parameters"
    :expect {:status 200 :result true :translate-match-code "1007-4"}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url loinc-part-snomed-map
    :system loinc-system :code "LP14449-0" :target snomed-system
    :reason "PartRelatedCodeMapping.csv forward external mapping"
    :expect {:status 200 :result true
             :translate-match {:equivalence "equivalent"
                               :system snomed-system
                               :code "38082009"}}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url loinc-part-snomed-map
    :system snomed-system :code "38082009" :target loinc-system
    :reason "PartRelatedCodeMapping.csv reverse external mapping"
    :expect {:status 200 :result true
             :translate-match {:equivalence "equivalent"
                               :system loinc-system
                               :code "LP14449-0"}}}

   {:terminology :loinc :method :post :op :cm-translate
    :url loinc-part-snomed-map
    :system snomed-system :code "38082009" :target loinc-system
    :reason "PartRelatedCodeMapping.csv reverse external mapping via POST Parameters"
    :expect {:status 200 :result true
             :translate-match {:equivalence "equivalent"
                               :system loinc-system
                               :code "LP14449-0"}}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url loinc-part-snomed-map
    :system loinc-system :code "LP14448-2" :target snomed-system
    :reason "PartRelatedCodeMapping.csv preserves wider equivalence"
    :expect {:status 200 :result true
             :translate-match {:equivalence "wider"
                               :system snomed-system
                               :code "38082009"}}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url loinc-part-snomed-map
    :system snomed-system :code "38082009" :target loinc-system
    :reason "PartRelatedCodeMapping.csv inverts wider to narrower in reverse"
    :expect {:status 200 :result true
             :translate-match {:equivalence "narrower"
                               :system loinc-system
                               :code "LP14448-2"}}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url loinc-ieee-map
    :system loinc-system :code "11556-8" :target ieee-system
    :reason "IEEE medical device mapping forward"
    :expect {:status 200 :result true
             :translate-match {:equivalence "equivalent"
                               :system ieee-system
                               :code "160116"}}}

   {:terminology :loinc :method :post :op :cm-translate
    :url loinc-ieee-map
    :system loinc-system :code "11556-8" :target ieee-system
    :reason "IEEE medical device mapping forward via POST Parameters"
    :expect {:status 200 :result true
             :translate-match {:equivalence "equivalent"
                               :system ieee-system
                               :code "160116"}}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url loinc-rsna-rid-map
    :system loinc-system :code "24531-6" :target radlex-system
    :reason "RSNA playbook RID mapping forward"
    :expect {:status 200 :result true
             :translate-match {:equivalence "relatedto"
                               :system radlex-system
                               :code "RID431"}}}

   {:terminology :loinc :method :get  :op :cm-translate
    :url loinc-rsna-rpid-map
    :system rsna-playbook-system :code "RPID2142" :target loinc-system
    :reason "RSNA playbook RPID mapping reverse"
    :expect {:status 200 :result true
             :translate-match {:equivalence "relatedto"
                               :system loinc-system
                               :code "24531-6"}}}

   {:terminology :loinc :method :get :op :cm-translate
    :url ips-pregnancy-status-map
    :system loinc-system :code "LA15173-0" :target snomed-system
    :reason "tx-benchmark CM02 IPS LOINC pregnancy status map"
    :expect {:status 200 :result true
             :translate-match {:equivalence "equivalent"
                               :system snomed-system
                               :code "77386006"}}}

   {:terminology :loinc :method :get :op :cm-translate
    :url ips-smoking-status-map
    :system loinc-system :code "LA18976-3" :target snomed-system
    :reason "tx-benchmark CM02 IPS LOINC smoking status map"
    :expect {:status 200 :result true
             :translate-match {:equivalence "equivalent"
                               :system snomed-system
                               :code "449868002"}}}

   ;; --- displayLanguage: $lookup must pick LONG_COMMON_NAME, not the
   ;; first-by-row designation (which is CLASS — a LOINC axis). The de-DE
   ;; designations for 718-7 are: CLASS, COMPONENT, LONG_COMMON_NAME,
   ;; PROPERTY, SCALE_TYP, SYSTEM, TIME_ASPCT — all language-matched
   ;; equally; the picker must rank by use_code, not insertion order.
   {:terminology :loinc :method :get  :op :cs-lookup
    :system loinc-system :code loinc-hgb-code
    :extras {:displayLanguage "de-DE"}
    :reason "displayLanguage=de-DE → German LONG_COMMON_NAME, not CLASS axis"
    :expect {:status 200 :display-equals loinc-hgb-de-display}}

   ;; --- ValueSet $expand: full-stack compose path through observation-codes.
   ;; Both cases are designed to surface the compose-layer post-filter
   ;; bug: cs-expand* matches on a non-English designation (en-AU
   ;; LinguisticVariantDisplayName for "Haemoglobin", or both tokens
   ;; across the primary display for "hemoglobin blood"), then the
   ;; compose post-filter re-checks `str/includes?` against the English
   ;; primary display and drops the match.
   {:terminology :loinc :method :get  :op :vs-expand
    :url loinc-observation-vs
    :extras {:filter "Haemoglobin" :count "50"}
    :reason "British 'Haemoglobin' (designation match) must survive compose"
    :expect {:status 200 :valueset? true
             :expansion-contains #{loinc-hgb-code}}}

   {:terminology :loinc :method :get  :op :vs-expand
    :url loinc-observation-vs
    :extras {:filter "hemoglobin blood" :count "50"}
    :reason "multi-token AND across display must survive compose"
    :expect {:status 200 :valueset? true
             :expansion-contains #{loinc-hgb-code}}}

   ;; --- Implicit all-LOINC ValueSet URL. The LOINC IG defines this URL
   ;; as the all-LOINC implicit set.
   {:terminology :loinc :method :get  :op :vs-expand
    :url loinc-implicit-vs
   :extras {:filter "hemoglobin" :count "5"}
   :reason "implicit http://loinc.org/vs must resolve to all-of-LOINC"
   :expect {:status 200 :valueset? true
             :expansion-total-at-least 6
             :expansion-contains #{loinc-hgb-code}}}

   {:terminology :loinc :method :post :op :vs-expand
    :extra-params [{:name "valueSet" :resource tx-benchmark-ex07-vs}
                   {:name "filter" :value "amphetamine"}
                   {:name "count" :value 200}]
    :reason "tx-benchmark EX07 preflight: ad-hoc multi-system text expansion"
    :expect {:status 200 :valueset? true :expansion-results? true}}

   {:terminology :loinc :method :post :op :vs-expand
    :extra-params [{:name "valueSet" :resource unsupported-loinc-filter-vs}]
    :reason "unsupported compose filters surface an error, not an empty expansion"
    :expect {:status 422 :outcome? true :outcome-detail-code "vs-invalid"}}])

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
    :expect {:status 200 :result false :issues? true}}

   {:terminology :overlay :method :post :op :vs-validate-code
    :system case-insensitive-cs-url :code "CODE1"
    :extra-params [{:name "valueSet" :resource case-insensitive-vs}
                   {:name "tx-resource" :resource case-insensitive-cs}]
    :reason "conformance case/case-insensitive-code1-*: wrong-case code validates with normalized-code"
    :expect {:status 200
             :result true
             :normalized-code "code1"
             :information? true
             :issue-detail-code "code-rule"}}

   {:terminology :overlay :method :post :op :vs-validate-code
    :url case-insensitive-vs-url
    :extra-params [{:name "coding"
                    :value-key "valueCoding"
                    :value {:system case-insensitive-cs-url :code "CODE1"}}
                   {:name "tx-resource" :resource case-insensitive-cs}
                   {:name "tx-resource" :resource case-insensitive-vs}]
    :reason "tx-ecosystem case/case-insensitive-code1-2: Coding input preserves informational case issue"
    :expect {:status 200
             :result true
             :normalized-code "code1"
             :information? true
             :issue-detail-code "code-rule"}}])

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
  (let [svc (hades/open paths {:default-locale "en-US"})
        srv (fixtures/start-server svc)]
    (binding [*server* srv]
      (try (body-fn)
           (finally
             (fixtures/stop-server srv)
             (hades/close svc))))))

(defn- in-memory-fixture [f]
  (with-server (concat (fixtures/paths [:sct/conformance :loinc/v2_82])
                       (fixtures/fhir-package-archives!))
    f))

(defn- sqlite-fixture [f]
  (with-server (fixtures/paths [:sct/conformance :loinc/v2_82 :fhir/tx])
    f))

;; The two test vars install their own once-only fixture so the JVM
;; only opens the SNOMED/LOINC DBs twice for the whole namespace.

(deftest ^:live http-matrix-against-in-memory-fhir
  (in-memory-fixture
    #(run-matrix "in-memory FHIR" all-cases)))

(deftest ^:live http-matrix-against-sqlite-fhir
  (sqlite-fixture
    #(run-matrix "sqlite FHIR" all-cases)))
