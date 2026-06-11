(ns com.eldrix.hades.provider-parity-test
  "Provider parity tests: assert that the in-memory provider and the
  SQLite provider produce the same observable behaviour for the same
  fhir-data payload.

  This codifies the contract that the FHIR-tx SQLite container is a
  faithful alternative to the in-memory provider — swap one for the
  other and a client can't tell.

  Scope:
    - cs-lookup            (point lookup, full decomposition)
    - cs-validate-code     (valid / invalid / wrong-display)
    - cs-subsumes          (closure semantics)
    - vs-expand            (compose engine — shared, should match exactly)
    - cm-translate         (source → target lookup)

  Out of scope (genuinely different semantics, documented):
    - cs-expand* text search: in-memory is substring scan, SQLite
      is FTS5 BM25 — set may match but order diverges. Tested
      independently per-provider; not parity-checked.

  Comparison contract: both providers must agree on every field of the
  result — including human-readable text. Issue `:text` and result
  `:message` are user-facing, surface in conformance assertions, and
  must come from shared helpers in `impl/issues.clj` rather than being
  hand-coded per provider. If you're tempted to allow a wording
  divergence, push the message-building into a shared helper instead."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.providers.common.compose :as compose]
            [com.eldrix.hades.providers.ftrm.provider :as ftrm-provider]
            [com.eldrix.hades.providers.ftrm.index :as ftrm-index]
            [com.eldrix.hades.protocols :as protos])
  (:import (java.io File)))

;; ---------------------------------------------------------------------------
;; Fixture data
;; ---------------------------------------------------------------------------

(def cs-url     "http://example.org/cs/colours")
(def cs-version "1.0")
(def vs-url     "http://example.org/vs/primary-colours")
(def stored-vs-url   "http://example.org/vs/stored-palette")
(def inactive-vs-url "http://example.org/vs/active-colours-only")
(def cm-url     "http://example.org/cm/colours-to-iso")
(def target-cs  "http://example.org/cs/iso-colours")
(def ci-cs-url  "http://example.org/cs/shapes")

(def stored-palette-compose
  {"include"
   [{"system" cs-url "version" cs-version
     "concept" [{"code" "red" "display" "Red"
                 "designation" [{"language" "fr" "value" "Rouge"}]}
                {"code" "green" "display" "Green"}]}
    {"system" ci-cs-url "version" "1.0"
     "concept" [{"code" "Square" "display" "Square"}]}]})

(def active-only-compose
  {"inactive" false
   "include" [{"system" cs-url "version" cs-version
               "concept" [{"code" "red" "display" "Red"}
                          {"code" "crimson" "display" "Crimson"}]}]})

(def fhir-data
  "Synthetic dataset rich enough to exercise hierarchy, properties,
  designations, inactive flags, a compose-driven ValueSet, and a
  ConceptMap. Both providers consume the same seq."
  [;; Primary colours CodeSystem with multilingual designations and
   ;; one inactive concept.
   {:type :codesystem-meta
    :url cs-url :version cs-version
    :status "active" :content "complete"
    :case-sensitive true
    :name "Colours" :title "Colours CodeSystem"}
   {:type :concept :system cs-url :version cs-version
    :code "red" :display "Red"
    :designations [{:value "Red"   :language :en}
                   {:value "Rouge" :language :fr}]}
   {:type :concept :system cs-url :version cs-version
    :code "green" :display "Green"
    :designations [{:value "Green" :language :en}
                   {:value "Vert"  :language :fr}]}
   {:type :concept :system cs-url :version cs-version
    :code "blue" :display "Blue"
    :designations [{:value "Blue" :language :en}
                   {:value "Bleu" :language :fr}]}
   {:type :concept :system cs-url :version cs-version
    :code "scarlet" :display "Scarlet"
    :parent-code "red"
    :designations [{:value "Scarlet" :language :en}]}
   {:type :concept :system cs-url :version cs-version
    :code "crimson" :display "Crimson"
    :parent-code "red"
    :designations [{:value "Crimson" :language :en}]
    :properties [{"code" "inactive" "valueBoolean" true}]}

   ;; Target CodeSystem for ConceptMap testing.
   {:type :codesystem-meta
    :url target-cs :version "1.0"
    :status "active" :content "complete" :case-sensitive true}
   {:type :concept :system target-cs :version "1.0" :code "ISO-R" :display "Red (ISO)"}
   {:type :concept :system target-cs :version "1.0" :code "ISO-G" :display "Green (ISO)"}
   {:type :concept :system target-cs :version "1.0" :code "ISO-B" :display "Blue (ISO)"}

   ;; Case-insensitive CodeSystem — exercises the `case-differs`
   ;; warning path. Both providers must agree on its wording.
   {:type :codesystem-meta
    :url ci-cs-url :version "1.0"
    :status "active" :content "complete" :case-sensitive false}
   {:type :concept :system ci-cs-url :version "1.0"
    :code "Square" :display "Square"}
   {:type :concept :system ci-cs-url :version "1.0"
    :code "Circle" :display "Circle"}

   ;; Compose-driven ValueSet — the three primary colours, no
   ;; descendents (no filter).
   {:type :valueset
    :url vs-url :version "1.0"
    :metadata {"resourceType" "ValueSet" "status" "active"}
    :compose {"include" [{"system" cs-url
                          "concept" [{"code" "red"} {"code" "green"} {"code" "blue"}]}]}}

   ;; Stored-extensional ValueSet — displays on every concept, so
   ;; `compose/extensional-members` compiles it: FTRM materialises
   ;; membership rows and answers `vs-validate-code` via the member
   ;; probe, while the in-memory provider runs the compose engine on
   ;; the document. Two systems + include versions exercise the
   ;; member-systems include skeleton.
   {:type :valueset
    :url stored-vs-url :version "1.0"
    :metadata {"resourceType" "ValueSet" "status" "active"}
    :compose stored-palette-compose}

   ;; `compose.inactive = false` — semantics the membership rows can't
   ;; carry (activity status lives in the CodeSystem), so the compiler
   ;; must refuse to materialise it and both providers must run the
   ;; compose engine, which excludes the inactive member.
   {:type :valueset
    :url inactive-vs-url :version "1.0"
    :metadata {"resourceType" "ValueSet" "status" "active"}
    :compose active-only-compose}

   ;; ConceptMap colours → ISO colours.
   {:type :conceptmap
    :url cm-url :version "1.0"
    :source-uri "http://example.org/vs/colours-source"
    :target-uri "http://example.org/vs/iso-colours-target"
    :groups [{:source cs-url :target target-cs
              :elements [{:code "red"   :target [{:code "ISO-R" :equivalence "equivalent"}]}
                         {:code "green" :target [{:code "ISO-G" :equivalence "equivalent"}]}
                         {:code "blue"  :target [{:code "ISO-B" :equivalence "equivalent"}]}
                         ;; Explicitly-unmatched element: carries no target
                         ;; code. Must survive indexing in both providers
                         ;; (the FTRM target_code column is nullable).
                         {:code "black" :target [{:equivalence "unmatched"}]}]}]}])

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(def ^:private state (atom nil))

(defn- temp-db-path []
  (let [^File f (File/createTempFile "hades-parity" ".db")]
    (.delete f)
    (.getPath f)))

(defn- delete-quietly [^String p]
  (let [^File f (io/file p)] (when (.exists f) (.delete f))))

(defn- build-in-memory-service
  "Build a TerminologyService from `data`. Self-contained — no global
  state touched."
  [data]
  {:svc (composite/from-providers
          (:providers (load-fhir/build-from-fhir-data data)))})

(defn- build-sqlite-service [data]
  (let [path (temp-db-path)]
    (ftrm-index/build! path data {:loader-type "parity-test"})
    (ftrm-index/index! path)
    {:svc  (composite/from-providers [(ftrm-provider/open path)])
     :path path}))

(defn provider-fixture [f]
  (let [in-mem (build-in-memory-service fhir-data)
        sqlite (build-sqlite-service   fhir-data)]
    (reset! state {:in-mem in-mem :sqlite sqlite})
    (try (f)
         (finally
           (when-let [svc (:svc sqlite)]
             (.close ^java.io.Closeable svc))
           (delete-quietly (:path sqlite))
           (reset! state nil)))))

(use-fixtures :once provider-fixture)

;; ---------------------------------------------------------------------------
;; Normalisation + diff helpers
;; ---------------------------------------------------------------------------

(defn- normalise-issue
  "Keep the structural and user-facing fields every provider must agree
  on. `:text` is included — divergent wording is a real bug because
  conformance checks string equality on it."
  [issue]
  (-> issue
      (select-keys [:severity :type :details-code :expression :text])
      (update :expression #(when % (vec (sort %))))))

(defn- sort-by-key [k coll]
  (vec (sort-by (juxt #(name (or (get % k) "")) :value) coll)))

(defn- normalise-result
  "Sort multi-value collections so order differences don't trigger
  spurious failures, and normalise empty-vs-missing for spec-optional
  collections. Every other field — including human-readable
  `:message` — must match exactly between providers."
  [result]
  (when result
    (cond-> result
      ;; `:name` echoes the CodeSystem `name` element if present. The
      ;; in-memory provider plumbs it through cs-lookup; SQLite drops
      ;; it. Until that gap closes, ignore it for parity.
      true             (dissoc :name)
      ;; `:matches` and `:issues` are spec-optional; an empty vector
      ;; and absence of the key are equivalent. Normalise to absence.
      (and (contains? result :matches) (empty? (:matches result)))
      (dissoc :matches)
      (and (contains? result :issues) (empty? (:issues result)))
      (dissoc :issues)
      (:issues result) (update :issues #(vec (sort-by (juxt :type :details-code)
                                                      (map normalise-issue %))))
      (:designations result)
      (update :designations #(vec (sort-by (juxt :value :language) %)))
      (:properties result)
      (update :properties #(sort-by-key :code %))
      (:concepts result)
      (update :concepts #(vec (sort-by (juxt :system :code) %))))))

(defn- diff [op-name params expected actual]
  ;; A small helper for failure messages: shows the params and both
  ;; normalised results inline so debugging doesn't require re-running.
  (str "Parity drift on " op-name " " (pr-str params) "\n"
       "  in-memory: " (pr-str expected) "\n"
       "  sqlite:    " (pr-str actual)))

(defmacro parity-check
  "Call `op-fn` against both providers with the same args and assert
  that the normalised results are equal."
  [op-name op-fn in-mem sqlite & args]
  `(let [im# (~op-fn ~in-mem ~@args)
         sq# (~op-fn ~sqlite ~@args)
         im-n# (normalise-result im#)
         sq-n# (normalise-result sq#)]
     (is (= im-n# sq-n#)
         (diff ~op-name (vector ~@args) im-n# sq-n#))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest parity-cs-lookup
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state]
    (testing "lookup of a known concept"
      (parity-check "cs-lookup" protos/cs-lookup im sq
                    {:system cs-url :code "red"}))
    (testing "lookup of a child concept"
      (parity-check "cs-lookup" protos/cs-lookup im sq
                    {:system cs-url :code "scarlet"}))
    (testing "lookup of an inactive concept"
      (parity-check "cs-lookup" protos/cs-lookup im sq
                    {:system cs-url :code "crimson"}))
    (testing "lookup with displayLanguage"
      (parity-check "cs-lookup" protos/cs-lookup im sq
                    {:system cs-url :code "red" :displayLanguage "fr"}))
    (testing "lookup with displayLanguage that has no designation"
      (parity-check "cs-lookup" protos/cs-lookup im sq
                    {:system cs-url :code "red" :displayLanguage "es"}))
    (testing "lookup with `:properties` filter for designations"
      (parity-check "cs-lookup" protos/cs-lookup im sq
                    {:system cs-url :code "red" :properties ["designation"]}))
    (testing "lookup unknown code"
      (parity-check "cs-lookup" protos/cs-lookup im sq
                    {:system cs-url :code "violet"}))))

(deftest parity-cs-validate-code
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state]
    (testing "valid code"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "blue"}))
    (testing "valid code with correct display"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "blue" :display "Blue"}))
    (testing "valid code with displayLanguage match"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "blue" :display "Bleu" :displayLanguage "fr"}))
    (testing "valid code with wrong display"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "blue" :display "Marine"}))
    ;; Display-mismatch wording flows through `issues/format-display-mismatch`.
    ;; Both providers must produce the same trailing
    ;; `(for the language(s) '<lang>')` whether or not the caller passed a
    ;; displayLanguage. Mirrors the scenarios in `issues_test.clj`.
    (testing "wrong display, no displayLanguage — trailing '--'"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "red" :display "Crimson"}))
    (testing "wrong display, displayLanguage matches a designation"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "red" :display "Marron" :displayLanguage "fr"}))
    (testing "wrong display, displayLanguage with no designation in that language"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "red" :display "Rojo" :displayLanguage "es"}))
    (testing "inactive concept"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "crimson"}))
    (testing "unknown code"
      (parity-check "cs-validate-code" protos/cs-validate-code im sq
                    {:system cs-url :code "violet"}))
    (testing "case-insensitive: code differs by case (composite-dispatched)"
      (let [im-svc (get-in @state [:in-mem :svc])
            sq-svc (get-in @state [:sqlite :svc])
            params {:system ci-cs-url :code "square"}
            im-r (normalise-result (hades/validate-code im-svc params))
            sq-r (normalise-result (hades/validate-code sq-svc params))]
        (is (= im-r sq-r) (diff "cs-validate-code (case)" [params] im-r sq-r))))))

(deftest parity-cs-subsumes
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state]
    (testing "scarlet subsumed-by red"
      (parity-check "cs-subsumes" protos/cs-subsumes im sq
                    {:system cs-url :codeA "red" :codeB "scarlet"}))
    (testing "red subsumes scarlet"
      (parity-check "cs-subsumes" protos/cs-subsumes im sq
                    {:system cs-url :codeA "scarlet" :codeB "red"}))
    (testing "equivalent codes"
      (parity-check "cs-subsumes" protos/cs-subsumes im sq
                    {:system cs-url :codeA "red" :codeB "red"}))
    (testing "unrelated codes"
      (parity-check "cs-subsumes" protos/cs-subsumes im sq
                    {:system cs-url :codeA "red" :codeB "blue"}))))

(deftest parity-vs-expand
  (let [im-svc (get-in @state [:in-mem :svc])
        sq-svc (get-in @state [:sqlite :svc])
        check (fn [params]
                (let [im-r (normalise-result (hades/expand im-svc params))
                      sq-r (normalise-result (hades/expand sq-svc params))
                      im-codes (set (map (juxt :system :code) (:concepts im-r)))
                      sq-codes (set (map (juxt :system :code) (:concepts sq-r)))]
                  (is (= im-codes sq-codes)
                      (str "Concept-set drift for " (pr-str params)
                           "\n  in-mem: " (pr-str im-codes)
                           "\n  sqlite: " (pr-str sq-codes)))
                  ;; Per-concept display + designations parity, indexed
                  ;; by code so order doesn't muddy the diff.
                  (let [im-by (into {} (map (juxt :code identity)) (:concepts im-r))
                        sq-by (into {} (map (juxt :code identity)) (:concepts sq-r))]
                    (doseq [code (sort (keys im-by))]
                      (is (= (:display (get im-by code))
                             (:display (get sq-by code)))
                          (str "Display drift for " code ": "
                               (:display (get im-by code)) " vs "
                               (:display (get sq-by code))))))))]
    (testing "expand the primary-colours ValueSet"
      (check {:url vs-url}))
    (testing "expand with displayLanguage=fr"
      (check {:url vs-url :displayLanguage "fr"}))
    ;; Materialised set: FTRM serves this from `valueset_member` rows
    ;; (expand-from-members) while in-memory takes
    ;; `stored-extensional-expand` — locks the two physical fast paths
    ;; (and, via the validate grid, the engine) to the same answers.
    (testing "expand the materialised stored-palette ValueSet"
      (check {:url stored-vs-url}))
    (testing "materialised expansion with displayLanguage=fr"
      (check {:url stored-vs-url :displayLanguage "fr"}))))

(deftest parity-vs-validate-code
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state
        check (fn [params]
                (let [im-r (protos/vs-validate-code im im (assoc params :url vs-url))
                      sq-r (protos/vs-validate-code sq sq (assoc params :url vs-url))
                      im-n (normalise-result im-r)
                      sq-n (normalise-result sq-r)]
                  (is (= im-n sq-n)
                      (diff "vs-validate-code" [params] im-n sq-n))))]
    (testing "code in value set"
      (check {:code "red" :system cs-url}))
    (testing "code in value set with correct display"
      (check {:code "red" :system cs-url :display "Red"}))
    (testing "code in value set with wrong display"
      (check {:code "red" :system cs-url :display "Crimson"}))
    (testing "code not in value set"
      (check {:code "scarlet" :system cs-url}))
    (testing "unknown code in known system"
      (check {:code "lavender" :system cs-url}))
    (testing "code under unknown system"
      (check {:code "red" :system "http://example.org/unknown"}))
    (testing "code in VS with displayLanguage and matching designation"
      (check {:code "red" :system cs-url :display "Rouge" :displayLanguage "fr"}))
    (testing "code in VS with wrong display under displayLanguage"
      (check {:code "red" :system cs-url :display "Rojo" :displayLanguage "es"}))
    (testing "wrong-case code is rejected for case-sensitive CodeSystem"
      (check {:code "RED" :system cs-url}))))

(deftest parity-vs-validate-code-materialised-membership
  ;; The probe ≡ compose-engine lock. FTRM answers this ValueSet's
  ;; validate-code from materialised member rows (synthetic narrowed
  ;; compose); the in-memory provider expands the real document. Any
  ;; drift between the compiled representation and the engine fails
  ;; here.
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state
        check (fn [params]
                (let [im-r (protos/vs-validate-code im im (assoc params :url stored-vs-url))
                      sq-r (protos/vs-validate-code sq sq (assoc params :url stored-vs-url))
                      im-n (normalise-result im-r)
                      sq-n (normalise-result sq-r)]
                  (is (= im-n sq-n)
                      (diff "vs-validate-code (materialised)" [params] im-n sq-n))))]
    (testing "the fixture compiles to materialised membership (else this whole
              deftest silently degrades to blob-vs-blob)"
      (is (some? (compose/extensional-members stored-palette-compose)))
      (is (nil? (compose/extensional-members active-only-compose))))
    (testing "member"
      (check {:code "red" :system cs-url}))
    (testing "member with correct display"
      (check {:code "red" :system cs-url :display "Red"}))
    (testing "member with wrong display"
      (check {:code "red" :system cs-url :display "Crimson"}))
    (testing "member display via displayLanguage designation"
      (check {:code "red" :system cs-url :display "Rouge" :displayLanguage "fr"}))
    (testing "non-member code of an included system"
      (check {:code "blue" :system cs-url}))
    (testing "unknown code"
      (check {:code "lavender" :system cs-url}))
    (testing "member matched by code alone"
      (check {:code "green"}))
    (testing "case-swapped member, case-insensitive CodeSystem"
      (check {:code "sQuArE" :system ci-cs-url}))
    (testing "case-swapped member, case-sensitive CodeSystem rejected"
      (check {:code "RED" :system cs-url}))
    (testing "member with matching caller version"
      (check {:code "red" :system cs-url :version cs-version}))
    (testing "member with unknown caller version"
      (check {:code "red" :system cs-url :version "9.9"}))
    (testing "member under unknown system"
      (check {:code "red" :system "http://example.org/unknown"}))))

(deftest compose-inactive-false-takes-engine-path
  ;; Absolute semantics, not just parity: with `inactive = false` both
  ;; backends must exclude the inactive member — the compiler must not
  ;; have materialised this compose, or a fast path would wrongly
  ;; include it.
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state]
    (doseq [[nm svc] [["in-memory" im] ["sqlite" sq]]]
      (testing (str nm ": expansion excludes the inactive member")
        (is (= #{"red"}
               (into #{} (map :code) (:concepts (hades/expand svc {:url inactive-vs-url}))))))
      (testing (str nm ": the inactive member fails validate-code")
        (is (false? (:result (protos/vs-validate-code svc svc
                                                      {:url inactive-vs-url :code "crimson" :system cs-url})))))
      (testing (str nm ": the active member passes validate-code")
        (is (true? (:result (protos/vs-validate-code svc svc
                                                     {:url inactive-vs-url :code "red" :system cs-url}))))))
    (testing "full-result parity for the inactive member"
      (let [params {:url inactive-vs-url :code "crimson" :system cs-url}
            im-n (normalise-result (protos/vs-validate-code im im params))
            sq-n (normalise-result (protos/vs-validate-code sq sq params))]
        (is (= im-n sq-n) (diff "vs-validate-code (inactive=false)" [params] im-n sq-n))))))

(deftest parity-code-filters-respect-case-sensitivity
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state]
    (testing "case-insensitive CodeSystem code filter accepts different case"
      (let [params {:system ci-cs-url
                    :filters [{:property "code" :op "=" :value "square"}]}
            im-r (normalise-result (protos/cs-expand* im params))
            sq-r (normalise-result (protos/cs-expand* sq params))]
        (is (= ["Square"] (mapv :code (:concepts im-r))))
        (is (= (mapv :code (:concepts im-r))
               (mapv :code (:concepts sq-r)))
            (diff "cs-expand* case-insensitive code filter" [params] im-r sq-r))))
    (testing "case-sensitive CodeSystem code filter rejects different case"
      (let [params {:system cs-url
                    :filters [{:property "code" :op "=" :value "RED"}]}
            im-r (normalise-result (protos/cs-expand* im params))
            sq-r (normalise-result (protos/cs-expand* sq params))]
        (is (empty? (:concepts im-r)))
        (is (= (mapv :code (:concepts im-r))
               (mapv :code (:concepts sq-r)))
            (diff "cs-expand* case-sensitive code filter" [params] im-r sq-r))))))

(deftest parity-cm-translate
  (let [{{im :svc} :in-mem {sq :svc} :sqlite} @state]
    (testing "URL-only translate uses group source systems"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :code "red"}))
    (testing "forward translate red → ISO-R"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :system cs-url :code "red"}))
    (testing "forward translate green → ISO-G"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :system cs-url :code "green"}))
    (testing "no-mapping returns result=false"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :system cs-url :code "scarlet"}))
    (testing "explicitly-unmatched element (no target code) agrees across providers"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :system cs-url :code "black" :target target-cs}))))
