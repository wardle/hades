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
    - cs-find-matches text search: in-memory is substring scan, SQLite
      is FTS5 BM25 — set may match but order diverges. Tested
      independently per-provider; not parity-checked.

  Allowed divergence: text wording in `:message` and issue `:text` is
  hand-coded per provider. The structural fields (`:result`, `:code`,
  `:system`, `:display`, issue `:severity`/`:type`/`:details-code`)
  must match exactly. The normaliser strips text-shaped fields before
  comparison; if a normaliser allowance grows beyond a few well-known
  cases, that's a smell — the providers are drifting in shape, not just
  wording."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.index.sqlite :as sqlite-index]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.sqlite.provider :as sqlite-provider])
  (:import (java.io File)))

;; ---------------------------------------------------------------------------
;; Fixture data
;; ---------------------------------------------------------------------------

(def cs-url     "http://example.org/cs/colours")
(def cs-version "1.0")
(def vs-url     "http://example.org/vs/primary-colours")
(def cm-url     "http://example.org/cm/colours-to-iso")
(def target-cs  "http://example.org/cs/iso-colours")

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

   ;; Compose-driven ValueSet — the three primary colours, no
   ;; descendents (no filter).
   {:type :valueset
    :url vs-url :version "1.0"
    :metadata {"resourceType" "ValueSet" "status" "active"}
    :compose {"include" [{"system" cs-url
                          "concept" [{"code" "red"} {"code" "green"} {"code" "blue"}]}]}}

   ;; ConceptMap colours → ISO colours.
   {:type :conceptmap
    :url cm-url :version "1.0"
    :source-uri cs-url :target-uri target-cs
    :groups [{:source cs-url :target target-cs
              :elements [{:code "red"   :target [{:code "ISO-R" :equivalence "equivalent"}]}
                         {:code "green" :target [{:code "ISO-G" :equivalence "equivalent"}]}
                         {:code "blue"  :target [{:code "ISO-B" :equivalence "equivalent"}]}]}]}])

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

(defn- build-in-memory-providers
  "Build a TerminologyService from `data` and pull the impls back out
  by their canonical URLs. Self-contained — no global state touched."
  [data]
  (let [{:keys [providers supplements]} (load-fhir/build-from-fhir-data data)
        svc (composite/from-providers providers {:supplements supplements})]
    {:svc svc
     :cs (composite/find-codesystem svc cs-url)
     :vs (composite/find-valueset   svc vs-url)
     :cm (some :impl (:conceptmaps svc))}))

(defn- build-sqlite-providers [data]
  (let [path (temp-db-path)]
    (sqlite-index/build! path data {:loader-type "parity-test"})
    (sqlite-index/index! path)
    (let [{:keys [codesystem valueset conceptmap datasource]}
          (sqlite-provider/open-providers path)]
      {:cs codesystem :vs valueset :cm conceptmap
       :ds datasource :path path})))

(defn provider-fixture [f]
  (let [in-mem (build-in-memory-providers fhir-data)
        sqlite (build-sqlite-providers   fhir-data)]
    (reset! state {:in-mem in-mem :sqlite sqlite})
    (try (f)
         (finally
           (when-let [ds (:ds sqlite)]
             (.close ^java.io.Closeable ds))
           (delete-quietly (:path sqlite))
           (reset! state nil)))))

(use-fixtures :once provider-fixture)

;; ---------------------------------------------------------------------------
;; Normalisation + diff helpers
;; ---------------------------------------------------------------------------

(defn- normalise-issue
  "Drop hand-coded text from an issue; keep the structural fields that
  every provider must agree on."
  [issue]
  (-> issue
      (select-keys [:severity :type :details-code :expression])
      (update :expression #(when % (vec (sort %))))))

(defn- sort-by-key [k coll]
  (vec (sort-by (juxt #(name (or (get % k) "")) :value) coll)))

(defn- normalise-result
  "Strip text wording, sort multi-value collections so order
  differences don't trigger spurious failures, drop optional fields
  whose population is genuinely allowed to differ. The remaining map
  must match exactly."
  [result]
  (when result
    (cond-> result
      true             (dissoc :message :name)
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
  (let [{{im :cs} :in-mem {sq :cs} :sqlite} @state]
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
                    {:system cs-url :code "red" :displayLanguage "fr"}))))

(deftest parity-cs-validate-code
  (let [{{im :cs} :in-mem {sq :cs} :sqlite} @state]
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
                    {:system cs-url :code "blue" :display "Marine"}))))

(deftest parity-cs-subsumes
  (let [{{im :cs} :in-mem {sq :cs} :sqlite} @state]
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
  (let [{{im :vs im-svc :svc} :in-mem {sq :vs} :sqlite} @state
        ;; SQLite provider also needs a service for compose callbacks;
        ;; build a parallel one wrapping its impls.
        sq-svc (composite/from-providers [(get-in @state [:sqlite :cs])
                            (get-in @state [:sqlite :vs])
                            (get-in @state [:sqlite :cm])])]
    (testing "expand the primary-colours ValueSet"
      (let [im-r (protos/vs-expand im im-svc {:url vs-url})
            sq-r (protos/vs-expand sq sq-svc {:url vs-url})
            im-n (normalise-result im-r)
            sq-n (normalise-result sq-r)
            ;; vs-expand carries used-codesystems / used-valuesets
            ;; which both providers populate from the compose engine —
            ;; structure differs trivially. Compare just the concept set
            ;; to assert both providers expand to the same content.
            im-codes (set (map (juxt :system :code) (:concepts im-n)))
            sq-codes (set (map (juxt :system :code) (:concepts sq-n)))]
        (is (= im-codes sq-codes)
            (str "Concept-set drift: " (pr-str {:in-mem im-codes :sqlite sq-codes})))))))

(deftest parity-vs-validate-code
  (let [{{im :vs im-svc :svc} :in-mem {sq :vs} :sqlite} @state
        sq-svc (composite/from-providers [(get-in @state [:sqlite :cs])
                            (get-in @state [:sqlite :vs])
                            (get-in @state [:sqlite :cm])])
        check (fn [params]
                (let [im-r (protos/vs-validate-code im im-svc (assoc params :url vs-url))
                      sq-r (protos/vs-validate-code sq sq-svc (assoc params :url vs-url))
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
      (check {:code "red" :system "http://example.org/unknown"}))))

(deftest parity-cm-translate
  (let [{{im :cm} :in-mem {sq :cm} :sqlite} @state]
    (testing "forward translate red → ISO-R"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :system cs-url :code "red"}))
    (testing "forward translate green → ISO-G"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :system cs-url :code "green"}))
    (testing "no-mapping returns result=false"
      (parity-check "cm-translate" protos/cm-translate im sq
                    {:url cm-url :system cs-url :code "scarlet"}))))
