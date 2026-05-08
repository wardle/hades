(ns com.eldrix.hades.impl.search-test
  "Unit tests for FHIR REST search on CodeSystem and ValueSet.

  Covers three layers:

    - Composite orchestration — `search-code-systems` /
      `search-value-sets` filtering, sort, paging, _summary,
      implicit-VS exclusion via vs-resource→nil.
    - Wire shape — `search-bundle` produces a string-keyed FHIR
      Bundle of type `searchset`.
    - HTTP integration — GET `/CodeSystem`, POST `/CodeSystem/_search`
      (form-urlencoded), and modifier-rejection paths."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.core :as hades]
            [com.eldrix.hades.fixtures :as fixtures]
            [com.eldrix.hades.impl.composite :as composite]
            [com.eldrix.hades.impl.load :as load-fhir]
            [com.eldrix.hades.impl.loaders.fhir :as loaders]
            [com.eldrix.hades.impl.protocols :as protos]
            [com.eldrix.hades.impl.protocols.result :as result]
            [com.eldrix.hades.impl.wire :as wire])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------------------
;; Fixtures — three CodeSystems, three ValueSets, varied attributes.
;; ---------------------------------------------------------------------------

(def ^:private cs-alpha-v1
  {"resourceType" "CodeSystem"
   "url" "http://example.com/cs/alpha" "version" "1.0" "status" "active"
   "name" "Alpha" "title" "Alpha System"
   "description" "The Alpha CodeSystem"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha"}]})

(def ^:private cs-alpha-v2
  {"resourceType" "CodeSystem"
   "url" "http://example.com/cs/alpha" "version" "2.0" "status" "draft"
   "name" "AlphaV2" "title" "Alpha System v2"
   "description" "Newer Alpha"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "A" "display" "Alpha v2"}]})

(def ^:private cs-beta
  {"resourceType" "CodeSystem"
   "url" "http://example.com/cs/beta" "version" "1.0" "status" "active"
   "name" "Beta" "title" "Beta System"
   "description" "The Beta CodeSystem"
   "content" "complete" "caseSensitive" true
   "concept" [{"code" "B" "display" "Bravo"}]})

(def ^:private vs-alpha
  {"resourceType" "ValueSet"
   "url" "http://example.com/vs/alpha" "version" "1.0" "status" "active"
   "name" "AlphaVS" "title" "Alpha ValueSet"
   "description" "An Alpha ValueSet"
   "compose" {"include" [{"system" "http://example.com/cs/alpha"}]}})

(def ^:private vs-beta
  {"resourceType" "ValueSet"
   "url" "http://example.com/vs/beta" "version" "1.0" "status" "retired"
   "name" "BetaVS" "title" "Beta ValueSet"
   "description" "A Beta ValueSet"
   "compose" {"include" [{"system" "http://example.com/cs/beta"}]}})

(defn- fhir-data [resource-maps]
  (mapcat #(loaders/resource->fhir-data % "<test>") resource-maps))

(defn- svc-of [resource-maps]
  (let [{:keys [providers supplements]} (load-fhir/build-from-fhir-data
                                          (fhir-data resource-maps))]
    (composite/from-providers providers {:supplements supplements})))

(def ^:private all-resources
  [cs-alpha-v1 cs-alpha-v2 cs-beta vs-alpha vs-beta])

;; ---------------------------------------------------------------------------
;; Composite — token + string filters, modifiers, paging, sort, _summary
;; ---------------------------------------------------------------------------

(deftest cs-search-token-filters-test
  (let [svc (svc-of all-resources)]
    (testing "url filter narrows to one URL (both versions)"
      (let [{:keys [total resources]} (hades/search-code-systems
                                        svc {:url "http://example.com/cs/alpha"})]
        (is (= 2 total))
        (is (= ["1.0" "2.0"] (mapv :version resources)))))
    (testing "url + version pin to one entry"
      (let [{:keys [total resources]} (hades/search-code-systems
                                        svc {:url "http://example.com/cs/alpha"
                                             :version "2.0"})]
        (is (= 1 total))
        (is (= "2.0" (-> resources first :version)))))
    (testing "status filter selects across URLs"
      (let [{:keys [total resources]} (hades/search-code-systems
                                        svc {:status "active"})]
        (is (= 2 total))
        (is (every? #(= "active" (:status %)) resources))))
    (testing "no filters returns all"
      (is (= 3 (:total (hades/search-code-systems svc {})))))))

(deftest cs-search-string-filters-test
  (let [svc (svc-of all-resources)]
    (testing "name default :starts-with case-insensitive"
      (is (= 2 (:total (hades/search-code-systems svc {:name "alpha"})))))
    (testing "name :exact rejects case mismatch"
      (is (= 0 (:total (hades/search-code-systems
                         svc {:name "alpha" :name-mode :exact})))))
    (testing "name :exact accepts exact case"
      (is (= 1 (:total (hades/search-code-systems
                         svc {:name "Alpha" :name-mode :exact})))))
    (testing "title :contains substring"
      (is (= 1 (:total (hades/search-code-systems
                         svc {:title "v2" :title-mode :contains})))))
    (testing "description :starts-with"
      (is (= 2 (:total (hades/search-code-systems
                         svc {:description "the"})))))))

(deftest cs-search-sort-and-paging-test
  (let [svc (svc-of all-resources)]
    (testing "sort: alphabetic url, then semver version"
      (let [{:keys [resources]} (hades/search-code-systems svc {})]
        (is (= [["http://example.com/cs/alpha" "1.0"]
                ["http://example.com/cs/alpha" "2.0"]
                ["http://example.com/cs/beta"  "1.0"]]
               (mapv (juxt :url :version) resources)))))
    (testing "_count caps and _offset skips after sort"
      (let [{:keys [total resources]} (hades/search-code-systems
                                        svc {:_count 1 :_offset 1})]
        (is (= 3 total))
        (is (= 1 (count resources)))
        (is (= ["http://example.com/cs/alpha" "2.0"]
               [(-> resources first :url) (-> resources first :version)]))))
    (testing "_count=0 returns total only"
      (let [{:keys [total resources]} (hades/search-code-systems
                                        svc {:_count 0})]
        (is (= 3 total))
        (is (empty? resources))))))

(deftest cs-search-summary-test
  (let [svc (svc-of all-resources)]
    (testing "_summary=true is a no-op for CodeSystem (no :compose field)"
      (let [{:keys [resources]} (hades/search-code-systems
                                  svc {:_summary "true"})]
        (is (every? :name resources))))
    (testing "_summary=count returns total only"
      (let [{:keys [total resources]} (hades/search-code-systems
                                        svc {:_summary "count"})]
        (is (= 3 total))
        (is (empty? resources))))))

(deftest vs-search-drops-compose-when-summary-true-test
  (let [svc (svc-of [vs-alpha vs-beta])]
    (testing "default response includes :compose"
      (let [{:keys [resources]} (hades/search-value-sets svc {})]
        (is (every? :compose resources))))
    (testing "_summary=true drops :compose"
      (let [{:keys [resources]} (hades/search-value-sets
                                  svc {:_summary "true"})]
        (is (every? #(nil? (:compose %)) resources))))))

(deftest vs-search-excludes-implicit-test
  (testing "tuples flagged :implicit? are dropped from search at the"
    (testing " tuple level (no *-resource call needed)"
      (let [implicit-url "urn:test:implicit"
            implicit-vs (reify
                          protos/CodeSystem
                          (cs-metadata [_ _opts] [])
                          protos/ValueSet
                          (vs-metadata [_ {:keys [include-implicit?]
                                           :or   {include-implicit? true}}]
                            (when include-implicit?
                              [{:url implicit-url :version "x" :implicit? true}]))
                          (vs-resource [_ _] nil))
            base (svc-of [vs-alpha])
            svc  (composite/with-overlays base [implicit-vs])
            result (hades/search-value-sets svc {})]
        (is (= 1 (:total result)))
        (is (= ["http://example.com/vs/alpha"]
               (mapv :url (:resources result))))))))

(deftest result-conforms-to-spec-test
  (let [svc (svc-of all-resources)]
    (is (s/valid? ::result/search-result (hades/search-code-systems svc {})))
    (is (s/valid? ::result/search-result (hades/search-value-sets svc {})))))

;; ---------------------------------------------------------------------------
;; Wire — search-bundle shape
;; ---------------------------------------------------------------------------

(deftest search-bundle-shape-test
  (let [result {:total 2
                :resources [{:url "http://x" :version "1" :name "X"
                             :status "active"}
                            {:url "http://y" :version "1" :name "Y"
                             :status "active"}]}
        bundle (wire/search-bundle result
                                    {:type "CodeSystem"
                                     :self-link "http://localhost/fhir/CodeSystem"
                                     :resource->map wire/cs-resource->map})]
    (is (= "Bundle" (get bundle "resourceType")))
    (is (= "searchset" (get bundle "type")))
    (is (= 2 (get bundle "total")))
    (is (= 2 (count (get bundle "entry"))))
    (let [entry0 (-> bundle (get "entry") first)]
      (is (= "match" (get-in entry0 ["search" "mode"])))
      (is (= "CodeSystem" (get-in entry0 ["resource" "resourceType"])))
      (is (= "http://x" (get-in entry0 ["resource" "url"]))))
    (is (= "http://localhost/fhir/CodeSystem"
           (get-in bundle ["link" 0 "url"])))))

(deftest search-bundle-empty-test
  (let [bundle (wire/search-bundle {:total 0 :resources []}
                                    {:type "CodeSystem"
                                     :self-link "http://localhost/fhir/CodeSystem"
                                     :resource->map wire/cs-resource->map})]
    (is (= 0 (get bundle "total")))
    (is (= [] (get bundle "entry")))))

;; ---------------------------------------------------------------------------
;; HTTP integration — GET + POST _search
;; ---------------------------------------------------------------------------

(defn- http-client ^HttpClient [] (HttpClient/newHttpClient))

(defn- url-encode [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- form-body [pairs]
  (->> pairs
       (map (fn [[k v]] (str (url-encode k) "=" (url-encode v))))
       (clojure.string/join "&")))

(defn- post-form! [base-url path pairs]
  (let [req (-> (HttpRequest/newBuilder (URI. (str base-url path)))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.header "Accept" "application/fhir+json")
                (.POST (HttpRequest$BodyPublishers/ofString (form-body pairs)))
                (.build))
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (try (json/read-str (.body resp))
                  (catch Exception _ (.body resp)))}))

(deftest http-search-test
  (let [svc (svc-of all-resources)
        srv (fixtures/start-server svc)]
    (try
      (let [base (:url srv)]
        (testing "GET /CodeSystem returns a searchset Bundle"
          (let [{:keys [status body]} (fixtures/request!
                                        base {:path "/CodeSystem"})]
            (is (= 200 status))
            (is (= "Bundle" (get body "resourceType")))
            (is (= "searchset" (get body "type")))
            (is (= 3 (get body "total")))))
        (testing "GET /CodeSystem?url=… narrows by URL"
          (let [{:keys [body]} (fixtures/request!
                                 base {:path (str "/CodeSystem?url="
                                                  (url-encode "http://example.com/cs/beta"))})]
            (is (= 1 (get body "total")))
            (is (= "http://example.com/cs/beta"
                   (get-in body ["entry" 0 "resource" "url"])))))
        (testing "POST /CodeSystem/_search form-urlencoded round-trips"
          (let [{:keys [status body]} (post-form!
                                        base "/CodeSystem/_search"
                                        [["url" "http://example.com/cs/alpha"]])]
            (is (= 200 status))
            (is (= "Bundle" (get body "resourceType")))
            (is (= 2 (get body "total")))))
        (testing "GET /ValueSet excludes implicit (vs-resource→nil)"
          ;; Non-explicit VSs would be filtered automatically; with our
          ;; fixture there are only explicit VSs so total = 2.
          (let [{:keys [body]} (fixtures/request! base {:path "/ValueSet"})]
            (is (= 2 (get body "total")))))
        (testing "Unsupported modifier returns 400 OperationOutcome"
          (let [{:keys [status body]} (fixtures/request!
                                        base {:path "/CodeSystem?name:above=x"})]
            (is (= 400 status))
            (is (= "OperationOutcome" (get body "resourceType")))))
        (testing "Unknown param ignored by default"
          (let [{:keys [status body]} (fixtures/request!
                                        base {:path "/CodeSystem?bogus=1"})]
            (is (= 200 status))
            (is (= 3 (get body "total")))))
        (testing "Unknown param + Prefer: handling=strict → 400"
          (let [{:keys [status body]} (fixtures/request!
                                        base {:path "/CodeSystem?bogus=1"
                                              :headers {"Accept" "application/fhir+json"
                                                        "Prefer" "handling=strict"}})]
            (is (= 400 status))
            (is (= "OperationOutcome" (get body "resourceType")))))
        (testing "GET /ValueSet defaults _summary=true (drops :compose)"
          (let [{:keys [body]} (fixtures/request! base {:path "/ValueSet"})]
            (is (every? #(nil? (get-in % ["resource" "compose"]))
                        (get body "entry")))))
        (testing "GET /ValueSet?_summary=false retains :compose"
          (let [{:keys [body]} (fixtures/request!
                                 base {:path "/ValueSet?_summary=false"})]
            (is (some #(some? (get-in % ["resource" "compose"]))
                      (get body "entry"))))))
      (finally
        (fixtures/stop-server srv)
        (hades/close svc)))))
