(ns com.eldrix.hades.impl.mcp-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hades.impl.mcp :as mcp]))

(deftest tools-catalogue
  (let [tools (mcp/tools)]
    (testing "tools is a non-empty vector"
      (is (vector? tools))
      (is (seq tools)))
      (testing "tools includes the full Phase 2 surface"
        (is (= #{"lookup" "validate_code" "subsumes" "expand" "translate"
                 "validate_codeable_concept"
                 "search_code_systems" "search_value_sets" "service_info"}
               (set (map :name tools)))))
    (testing "no :fn handlers leak onto the wire"
      (is (every? #(not (contains? % :fn)) tools)))
    (testing "each tool has a description and inputSchema"
      (doseq [{:keys [name description inputSchema]} tools]
        (is (string? description) (str name " missing description"))
        (is (= "object" (:type inputSchema)) (str name " bad schema type"))
        (is (map? (:properties inputSchema)) (str name " missing properties"))
        (is (vector? (:required inputSchema)) (str name " missing required"))))
    (testing "tools list serialises to JSON without errors"
      (is (string? (json/write-str tools))))))

(deftest required-params-listed-in-properties
  (testing "every required param is described in properties"
    (doseq [{:keys [name inputSchema]} (mcp/tools)
            req                        (:required inputSchema)]
      (is (contains? (:properties inputSchema) (keyword req))
          (str name ": required param '" req "' missing from properties")))))

(deftest call-tool-rejects-unknown
  (testing "call-tool throws on unknown tool name"
    (let [ex (try (mcp/call-tool nil "no_such_tool" {})
                  nil
                  (catch Exception e e))]
      (is ex)
      (is (str/includes? (ex-message ex) "no_such_tool")))))

(deftest validate-code-requires-system-or-url
  (testing "validate_code rejects args missing both system and url"
    (let [ex (try (mcp/call-tool nil "validate_code" {:code "X"})
                  nil
                  (catch Exception e e))]
      (is ex)
      (is (str/includes? (ex-message ex) "system")))))

(deftest translate-requires-url-or-system-target
  (testing "translate rejects args missing both url and (system,target)"
    (let [ex (try (mcp/call-tool nil "translate" {:code "X"})
                  nil
                  (catch Exception e e))]
      (is ex)
      (is (str/includes? (ex-message ex) "url"))
      (is (str/includes? (ex-message ex) "system")))))

(deftest validate-codeable-concept-requires-codings
  (testing "validate_codeable_concept rejects an empty codings array"
    (let [ex (try (mcp/call-tool nil "validate_codeable_concept"
                                 {:url "http://x" :codings []})
                  nil
                  (catch Exception e e))]
      (is ex)
      (is (str/includes? (ex-message ex) "coding")))))

(deftest resources-catalogue
  (let [resources (mcp/resources)]
    (testing "resources is a non-empty vector"
      (is (vector? resources))
      (is (seq resources)))
    (testing "expected URIs are present"
      (is (= #{"hades://guides/operations"
               "hades://guides/value-sets"
               "hades://catalog/code-systems"
               "hades://catalog/value-sets"}
             (set (map :uri resources)))))
    (testing "each resource carries name, description, mimeType"
      (doseq [{:keys [uri name description mimeType]} resources]
        (is (string? name) (str uri " missing name"))
        (is (string? description) (str uri " missing description"))
        (is (= "text/markdown" mimeType) (str uri " bad mimeType"))))
    (testing "no :content fns leak onto the wire"
      (is (every? #(not (contains? % :content)) resources)))))

(deftest resource-content-static
  (testing "static guide URIs return non-empty text"
    (let [{:keys [text mime]} (mcp/resource-content nil "hades://guides/operations")]
      (is (= "text/markdown" mime))
      (is (str/includes? text "lookup"))
      (is (str/includes? text "validate_code")))))

(deftest resource-content-unknown-uri
  (testing "unknown URI throws"
    (let [ex (try (mcp/resource-content nil "hades://nope") nil
                  (catch Exception e e))]
      (is ex)
      (is (str/includes? (ex-message ex) "Unknown resource")))))

(deftest prompts-catalogue
  (let [prompts (mcp/prompts)]
    (testing "all four prompts are listed"
      (is (= #{"code_a_term" "build_value_set" "translate_codes" "explore_concept"}
             (set (map :name prompts)))))
    (testing "each prompt has description and arguments"
      (doseq [{:keys [name description arguments]} prompts]
        (is (string? description) (str name " missing description"))
        (is (vector? arguments) (str name " missing arguments"))))
    (testing "no :messages-fn leaks onto the wire"
      (is (every? #(not (contains? % :messages-fn)) prompts)))))

(deftest get-prompt-builds-messages
  (testing "code_a_term returns a single user message mentioning the term"
    (let [{:keys [description messages]} (mcp/get-prompt "code_a_term"
                                                         {"clinical_term" "type 1 diabetes"})]
      (is (string? description))
      (is (= 1 (count messages)))
      (is (= "user" (-> messages first :role)))
      (is (str/includes? (-> messages first :content :text) "type 1 diabetes")))))

(deftest get-prompt-rejects-missing-required
  (testing "code_a_term without clinical_term throws"
    (let [ex (try (mcp/get-prompt "code_a_term" {}) nil
                  (catch Exception e e))]
      (is ex)
      (is (str/includes? (ex-message ex) "clinical_term")))))

(deftest get-prompt-rejects-unknown
  (testing "get-prompt on unknown name throws"
    (let [ex (try (mcp/get-prompt "no_such_prompt" {}) nil
                  (catch Exception e e))]
      (is ex)
      (is (str/includes? (ex-message ex) "no_such_prompt")))))
