(ns com.eldrix.hades.impl.mcp
  "MCP (Model Context Protocol) tool definitions for Hades. Pure data
  and handler functions with no transport dependency — suitable for
  in-process use by any MCP host or LLM integration. The stdio JSON-RPC
  transport lives in `com.eldrix.hades.impl.mcp.server`."
  (:require [clojure.string :as str]
            [com.eldrix.hades.core :as hades]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Shared schema fragments
;; ---------------------------------------------------------------------------

(def ^:private system-url-schema
  {:type        "string"
   :description "Canonical URL of a CodeSystem (e.g. http://snomed.info/sct, http://loinc.org)."})

(def ^:private value-set-url-schema
  {:type        "string"
   :description "Canonical URL of a ValueSet."})

(def ^:private code-schema
  {:type        "string"
   :description "Code as defined by the CodeSystem."})

(def ^:private version-schema
  {:type        "string"
   :description "Optional CodeSystem version. Required when multiple versions are loaded for the same canonical URL."})

(def ^:private value-set-version-schema
  {:type        "string"
   :description "Pin a ValueSet version (used with `url`)."})

(def ^:private display-language-schema
  {:type        "string"
   :description "BCP 47 / RFC 3066 Accept-Language value (e.g. 'en-GB'). Defaults to the server locale."})

(def ^:private string-mode-schema
  {:type        "string"
   :enum        ["starts-with" "exact" "contains"]
   :description "How to match the corresponding string filter. Defaults to 'starts-with' on the FHIR REST surface."})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ->string-mode
  "Translate the JSON string form (`starts-with` / `exact` / `contains`)
  to the keyword the search params spec accepts."
  [s]
  (when s (keyword s)))

;; ---------------------------------------------------------------------------
;; Tool handlers
;;
;; Each handler accepts a Hades service plus a keyword-keyed args map
;; (the transport layer keyword-ises top-level JSON string keys once at
;; the boundary), translates snake_case JSON conventions into the
;; camelCase / kebab-case params that `com.eldrix.hades.core` expects,
;; calls the operation, and returns the raw result map.
;; ---------------------------------------------------------------------------

(defn- tool-lookup
  [svc {:keys [system code version display_language properties]}]
  (hades/lookup svc (cond-> {:system system :code code}
                      version          (assoc :version version)
                      display_language (assoc :displayLanguage display_language)
                      (seq properties) (assoc :properties properties))))

(defn- tool-validate-code
  [svc {:keys [system code url version display display_language value_set_version]}]
  (when-not (or system url)
    (throw (ex-info "validate_code requires either `system` or `url`" {})))
  (hades/validate-code svc (cond-> {:code code}
                             system            (assoc :system system)
                             url               (assoc :url url)
                             display           (assoc :display display)
                             version           (assoc :version version)
                             display_language  (assoc :displayLanguage display_language)
                             value_set_version (assoc :valueSetVersion value_set_version))))

(defn- tool-subsumes
  [svc {:keys [system code_a code_b]}]
  (hades/subsumes svc {:systemA system :codeA code_a
                       :systemB system :codeB code_b}))

(defn- tool-expand
  [svc {:keys [url filter active_only display_language count offset
               properties value_set_version]}]
  (hades/expand svc (cond-> {:url url}
                      filter            (assoc :filter filter)
                      (some? active_only) (assoc :activeOnly active_only)
                      display_language  (assoc :displayLanguage display_language)
                      count             (assoc :count count)
                      offset            (assoc :offset offset)
                      (seq properties)  (assoc :properties properties)
                      value_set_version (assoc :valueSetVersion value_set_version))))

(defn- tool-translate
  [svc {:keys [code url system target version display_language]}]
  (when-not (or url (and system target))
    (throw (ex-info "translate requires either `url` or both `system` and `target`" {})))
  (hades/translate svc (cond-> {:code code}
                         url              (assoc :url url)
                         system           (assoc :system system)
                         target           (assoc :target target)
                         version          (assoc :version version)
                         display_language (assoc :displayLanguage display_language))))

(defn- tool-validate-codeable-concept
  [svc {:keys [url codings display_language value_set_version]}]
  (when (empty? codings)
    (throw (ex-info "validate_codeable_concept requires at least one coding" {})))
  (let [codings*    (mapv #(update-keys % keyword) codings)
        base-params (cond-> {:url url}
                      display_language  (assoc :displayLanguage display_language)
                      value_set_version (assoc :valueSetVersion value_set_version))]
    (hades/validate-codeable-concept svc codings* base-params)))

(defn- tool-find-matches
  [svc {:keys [system query version display_language properties max_hits active_only]}]
  (hades/find-matches svc (cond-> {:system system}
                            version             (assoc :version version)
                            query               (assoc :text query)
                            display_language    (assoc :displayLanguage display_language)
                            (seq properties)    (assoc :properties properties)
                            max_hits            (assoc :max-hits max_hits)
                            (some? active_only) (assoc :active-only active_only))))

(defn- search-params
  [{:keys [url version status name title description
           name_mode title_mode description_mode
           count offset summary]}]
  (cond-> {}
    url              (assoc :url url)
    version          (assoc :version version)
    status           (assoc :status status)
    name             (assoc :name name)
    title            (assoc :title title)
    description      (assoc :description description)
    name_mode        (assoc :name-mode (->string-mode name_mode))
    title_mode       (assoc :title-mode (->string-mode title_mode))
    description_mode (assoc :description-mode (->string-mode description_mode))
    count            (assoc :_count count)
    offset           (assoc :_offset offset)
    summary          (assoc :_summary summary)))

(defn- tool-search-code-systems [svc args]
  (hades/search-code-systems svc (search-params args)))

(defn- tool-search-value-sets [svc args]
  (hades/search-value-sets svc (search-params args)))

(defn- tool-service-info [svc _]
  (hades/metadata svc))

;; ---------------------------------------------------------------------------
;; Tool catalogue
;; ---------------------------------------------------------------------------

(def ^:private search-properties
  {:url              {:type "string" :description "Filter by canonical URL."}
   :version          {:type "string" :description "Filter by version string."}
   :status           {:type "string" :description "Filter by publication status (e.g. 'active')."}
   :name             {:type "string" :description "Filter by computer-friendly name (e.g. 'SCT')."}
   :title            {:type "string" :description "Filter by human-friendly title."}
   :description      {:type "string" :description "Filter by description text."}
   :name_mode        string-mode-schema
   :title_mode       string-mode-schema
   :description_mode string-mode-schema
   :count            {:type "integer" :description "Maximum resources to return."}
   :offset           {:type "integer" :description "Offset into the merged result set."}
   :summary          {:type "string"  :description "FHIR _summary mode (e.g. 'true', 'data', 'text')."}})

(def ^:private tools*
  [{:name        "lookup"
    :description (str "FHIR CodeSystem $lookup. Given a CodeSystem URL and a code, "
                      "return the concept's display name and any requested properties "
                      "or designations. Use this when you have a code and need to know "
                      "what it means.")
    :inputSchema {:type       "object"
                  :properties {:system           system-url-schema
                               :code             code-schema
                               :version          version-schema
                               :display_language display-language-schema
                               :properties       {:type        "array"
                                                  :items       {:type "string"}
                                                  :description "Optional property codes to include (e.g. ['parent','definition']). Omit for provider defaults."}}
                  :required   ["system" "code"]}
    :fn          tool-lookup}

   {:name        "validate_code"
    :description (str "FHIR $validate-code. Provide `system` for CodeSystem validation, "
                      "or `url` for ValueSet validation. Confirms the code exists and, "
                      "if `display` is supplied, that the supplied display matches. "
                      "Returns `{result: bool, message?, display?, ...}`.")
    :inputSchema {:type       "object"
                  :properties {:system            system-url-schema
                               :url               value-set-url-schema
                               :code              code-schema
                               :display           {:type        "string"
                                                   :description "Optional display string to validate against the canonical display."}
                               :version           version-schema
                               :display_language  display-language-schema
                               :value_set_version value-set-version-schema}
                  :required   ["code"]}
    :fn          tool-validate-code}

   {:name        "subsumes"
    :description (str "FHIR CodeSystem $subsumes. Test whether one code subsumes "
                      "(is an ancestor of) another within a single CodeSystem. "
                      "Returns the relationship: equivalent / subsumes / subsumed-by / "
                      "not-subsumed.")
    :inputSchema {:type       "object"
                  :properties {:system system-url-schema
                               :code_a (assoc code-schema :description "First code in the comparison.")
                               :code_b (assoc code-schema :description "Second code in the comparison.")}
                  :required   ["system" "code_a" "code_b"]}
    :fn          tool-subsumes}

   {:name        "expand"
    :description (str "FHIR ValueSet $expand. Given a ValueSet URL, return the "
                      "concrete list of concepts that satisfy its definition. "
                      "Optional `filter` narrows by display text. Use `count` and "
                      "`offset` for pagination on large expansions.")
    :inputSchema {:type       "object"
                  :properties {:url               value-set-url-schema
                               :filter            {:type "string"  :description "Free-text filter applied to concept displays/designations."}
                               :active_only       {:type "boolean" :description "Restrict to active concepts."}
                               :display_language  display-language-schema
                               :count             {:type "integer" :description "Maximum concepts to return in this page."}
                               :offset            {:type "integer" :description "Offset into the expanded result set."}
                               :properties        {:type "array" :items {:type "string"}
                                                   :description "Optional property codes to surface on each concept."}
                               :value_set_version value-set-version-schema}
                  :required   ["url"]}
    :fn          tool-expand}

   {:name        "translate"
    :description (str "FHIR ConceptMap $translate. Map a code from one CodeSystem to "
                      "matching code(s) in another. Provide either `url` (canonical of "
                      "a specific ConceptMap) OR both `system` and `target` (lookup a "
                      "ConceptMap by its source/target system pair).")
    :inputSchema {:type       "object"
                  :properties {:code             code-schema
                               :url              {:type        "string"
                                                  :description "Canonical URL of a ConceptMap."}
                               :system           (assoc system-url-schema :description "Source CodeSystem URL (with `target`).")
                               :target           {:type        "string"
                                                  :description "Target CodeSystem URL (with `system`)."}
                               :version          version-schema
                               :display_language display-language-schema}
                  :required   ["code"]}
    :fn          tool-translate}

   {:name        "validate_codeable_concept"
    :description (str "FHIR $validate-code against a CodeableConcept (multiple codings). "
                      "Returns true if any coding validates against the ValueSet. "
                      "`codings` is an array of `{system, code, display?, version?}` "
                      "objects.")
    :inputSchema {:type       "object"
                  :properties {:url               value-set-url-schema
                               :codings           {:type        "array"
                                                   :items       {:type       "object"
                                                                 :properties {:system  system-url-schema
                                                                              :code    code-schema
                                                                              :display {:type "string"}
                                                                              :version {:type "string"}}
                                                                 :required   ["system" "code"]}
                                                   :description "Codings comprising the CodeableConcept."}
                               :display_language  display-language-schema
                               :value_set_version value-set-version-schema}
                  :required   ["url" "codings"]}
    :fn          tool-validate-codeable-concept}

   {:name        "find_matches"
    :description (str "CodeSystem $find-matches. Search a CodeSystem by free-text query "
                      "and/or filters; ideal for autocomplete and concept discovery. "
                      "Returns matching concepts with displays.")
    :inputSchema {:type       "object"
                  :properties {:system           system-url-schema
                               :query            {:type "string"  :description "Free-text query to match against displays/designations."}
                               :version          version-schema
                               :display_language display-language-schema
                               :properties       {:type "array" :items {:type "string"}
                                                  :description "Optional property codes to surface on each match."}
                               :max_hits         {:type "integer" :description "Maximum results to return."}
                               :active_only      {:type "boolean" :description "Restrict to active concepts."}}
                  :required   ["system"]}
    :fn          tool-find-matches}

   {:name        "search_code_systems"
    :description (str "FHIR REST search across registered CodeSystems. Filter by URL, "
                      "version, status, name, title, or description (with mode = "
                      "'starts-with' / 'exact' / 'contains'). Returns "
                      "`{total, resources}`.")
    :inputSchema {:type       "object"
                  :properties search-properties
                  :required   []}
    :fn          tool-search-code-systems}

   {:name        "search_value_sets"
    :description (str "FHIR REST search across registered ValueSets. Same shape as "
                      "`search_code_systems`. Implicit ValueSets (e.g. the SNOMED "
                      "'all of SNOMED' VS) are excluded.")
    :inputSchema {:type       "object"
                  :properties search-properties
                  :required   []}
    :fn          tool-search-value-sets}

   {:name        "service_info"
    :description (str "Describe what the server knows about: every CodeSystem, "
                      "ValueSet, and ConceptMap registered (with their canonical "
                      "URLs and versions) plus totals. Use this to discover what "
                      "terminology is loaded before guessing a system URL.")
    :inputSchema {:type       "object"
                  :properties {}
                  :required   []}
    :fn          tool-service-info}])

(defn tools
  "Public tool catalogue for the MCP `tools/list` response. The `:fn`
  handler is stripped before going on the wire."
  []
  (mapv #(dissoc % :fn) tools*))

(def ^:private tools-by-name
  (into {} (map (juxt :name identity)) tools*))

(defn call-tool
  "Dispatch an MCP `tools/call` to its handler. `args` is keyword-keyed
  (the transport keyword-ises JSON string keys once). Throws if
  `tool-name` is not registered."
  [svc tool-name args]
  (if-let [{f :fn} (get tools-by-name tool-name)]
    (f svc args)
    (throw (ex-info (str "Unknown tool: " tool-name) {:tool tool-name}))))

;; ---------------------------------------------------------------------------
;; Resources
;;
;; Two static markdown guides plus two dynamic catalogue resources backed
;; by `core/metadata`. Each entry carries a `:content` fn `(svc) -> text`;
;; static guides ignore svc.
;; ---------------------------------------------------------------------------

(def ^:private operations-guide
  "# FHIR terminology operations — Hades

The Hades MCP exposes ten operation tools. Use this to pick the right one
and to understand what each returns.

## Tool selection

| You want to... | Use |
|---|---|
| Resolve a code to its display + properties | `lookup` |
| Confirm a code exists (and that the display matches) | `validate_code` |
| Confirm a CodeableConcept (multiple codings) is valid | `validate_codeable_concept` |
| List concepts in a ValueSet, optionally text-filtered | `expand` |
| Map a code to one in another CodeSystem | `translate` |
| Test whether one code is an ancestor of another | `subsumes` |
| Search a CodeSystem by free text (autocomplete) | `find_matches` |
| Find a CodeSystem or ValueSet by name/title/description | `search_code_systems` / `search_value_sets` |
| Discover what terminology is installed | `service_info` |

## $lookup vs $validate-code

Both confirm a (system, code) pair is valid. They differ in what they return:
- `lookup` returns the display, parents, properties, designations.
- `validate_code` returns `{result, message?, display?}` — lighter.

## $validate-code: CodeSystem vs ValueSet

Pass `system` for CodeSystem $validate-code (does this code exist anywhere
in this CodeSystem?). Pass `url` for ValueSet $validate-code (does this
coding satisfy this ValueSet's compose definition?). Pass both to validate
a specific coding against a ValueSet.

## $expand

`expand` against a ValueSet `url` materialises all included concepts.
- For SNOMED, the implicit `http://snomed.info/sct?fhir_vs=isa/<id>`
  expands to all descendants of `<id>`.
- Published ValueSets (`http://hl7.org/fhir/ValueSet/...`) are resolved
  by walking their `compose` block recursively.
- Use `filter` to text-search the expansion. `count` / `offset` paginate.

## $translate

Translate a code from a source CodeSystem to one or more target codes.
- Provide `url` (a specific ConceptMap canonical) OR `system` + `target`
  (look up a ConceptMap by source/target system pair).
- Returns `{result: bool, matches: [{equivalence, system, code, display?}]}`.
- `equivalence` values: equivalent, equal, wider, narrower, relatedto,
  inexact, unmatched, disjoint.

## $subsumes

Tests whether one code is an ancestor of another within a single CodeSystem.

## $find-matches

Search-as-you-type within a CodeSystem. Backed by Lucene for SNOMED, by
SQLite FTS for FHIR-tx providers. Use this rather than `expand` when
you're looking for \"concepts mentioning X\" rather than \"concepts in
this ValueSet\".

## Workflows

- **Code a clinical term**: `find_matches` for candidates → `lookup` to
  inspect the best one → `validate_code` against the target ValueSet.
- **Build a draft ValueSet**: `find_matches` to seed → `expand` against
  draft compose → iterate.
- **Cross-terminology mapping**: `service_info` to find ConceptMaps with
  the right target → `translate` per code.
")

(def ^:private value-sets-guide
  "# FHIR ValueSets — what they are and how Hades expands them

A ValueSet says \"here are some codes you may use\". It carries a
`compose` block declaring which CodeSystems contribute and how to filter
them.

## Two flavours

**Extensional** — an explicit list:
```
compose:
  include:
    - system: http://hl7.org/fhir/administrative-gender
      concept: [{code: male}, {code: female}, {code: other}]
```

**Intensional** — a definition by reference:
```
compose:
  include:
    - system: http://snomed.info/sct
      filter:
        - {property: concept, op: is-a, value: 404684003}
```

Hades' `expand` resolves both shapes uniformly. For intensional VSs it
queries the underlying CodeSystem provider — e.g. SNOMED via Hermes for
`is-a` filters.

## Common filter operations

| op | Meaning |
|---|---|
| `is-a` | concept and all descendants |
| `descendant-of` | descendants only (excludes the concept itself) |
| `regex` | property value matches regex |
| `=` | property has exact value |
| `in` | property in a comma-separated value list |

## SNOMED ECL filters

For SNOMED, the special property `constraint` accepts an ECL expression:
```
filter:
  - {property: constraint, op: =, value: \"<<73211009 |Diabetes mellitus|\"}
```
ECL is more expressive than `is-a` — refinements, conjunctions, exclusions.

## Multi-include and exclude

A `compose` may have multiple includes from different systems. Hades unions
them, dedupes by (system, code), and applies any `compose.exclude` last.

## Versioning

- Pin a CodeSystem version with `version` on the include.
- When validating or expanding, pass `value_set_version` to pin the VS
  itself.
- When two providers serve the same canonical URL at different versions,
  the server uses `--default URL=VERSION` from boot.

## Pitfalls

- The implicit `http://snomed.info/sct?fhir_vs` expands to *all of SNOMED* —
  too costly. Use `?fhir_vs=isa/<id>` or `?fhir_vs=ecl/<expr>`.
- A VS with no concrete `compose` won't expand — it must reference real
  codes somewhere down the chain.
- `validate_code` against a VS that requires display matching will fail if
  you supply the wrong language; pass `display_language` if needed.
")

(defn- format-catalogue-section
  "Render `entries` as a small markdown table. Each entry uses the keys
  `:url`, `:version`, plus a per-section extras fn for any final column."
  [title entries extras]
  (let [rows (->> entries
                  (mapv (fn [{:keys [url version] :as e}]
                          (str "| " url " | " (or version "-") " | " (extras e) " |"))))]
    (str "## " title " (" (count entries) ")\n\n"
         "| URL | Version | Notes |\n"
         "|-----|---------|-------|\n"
         (str/join "\n" rows)
         "\n")))

(defn- catalogue-code-systems [svc]
  (let [{:keys [codesystems]} (hades/metadata svc)]
    (str "# CodeSystems registered on this server\n\n"
         "Live from each provider's `cs-metadata`. Counts include the\n"
         "implicit and supplement entries that providers expose for routing.\n\n"
         (format-catalogue-section
          "CodeSystems" codesystems
          (fn [{:keys [implicit? content supplements]}]
            (str/join " " (cond-> []
                            implicit?       (conj "implicit")
                            content         (conj (str "content=" content))
                            (seq supplements) (conj (str "supplements=" (count supplements))))))))))

(defn- catalogue-value-sets [svc]
  (let [{:keys [valuesets]} (hades/metadata svc)]
    (str "# ValueSets registered on this server\n\n"
         "Live from each provider's `vs-metadata`. Implicit entries (e.g.\n"
         "Hermes' \"all of SNOMED\" VS) are listed but are not extensionally\n"
         "expandable.\n\n"
         (format-catalogue-section
          "ValueSets" valuesets
          (fn [{:keys [implicit?]}] (if implicit? "implicit" ""))))))

(def ^:private resources*
  [{:uri         "hades://guides/operations"
    :name        "FHIR terminology operations reference"
    :description "When to use lookup vs validate-code vs expand vs translate, what each returns, and common workflows."
    :mimeType    "text/markdown"
    :content     (constantly operations-guide)}
   {:uri         "hades://guides/value-sets"
    :name        "FHIR ValueSet guide"
    :description "How ValueSets are defined (compose, include/exclude, filters), how Hades expands them, and SNOMED ECL filter syntax."
    :mimeType    "text/markdown"
    :content     (constantly value-sets-guide)}
   {:uri         "hades://catalog/code-systems"
    :name        "Live CodeSystem catalogue"
    :description "Every CodeSystem the server can answer for, with canonical URL and version. Computed on demand from the live providers."
    :mimeType    "text/markdown"
    :content     catalogue-code-systems}
   {:uri         "hades://catalog/value-sets"
    :name        "Live ValueSet catalogue"
    :description "Every ValueSet the server can answer for. Computed on demand from the live providers."
    :mimeType    "text/markdown"
    :content     catalogue-value-sets}])

(defn resources
  "Public resource catalogue for the MCP `resources/list` response. The
  `:content` fn is stripped before going on the wire."
  []
  (mapv #(dissoc % :content) resources*))

(def ^:private resources-by-uri
  (into {} (map (juxt :uri identity)) resources*))

(defn resource-content
  "Return the text body for `uri`. Static guides ignore `svc`; catalogue
  resources read from the live service. Throws on unknown URI."
  [svc uri]
  (if-let [{f :content mime :mimeType} (get resources-by-uri uri)]
    {:mime mime :text (f svc)}
    (throw (ex-info (str "Unknown resource: " uri) {:uri uri}))))

;; ---------------------------------------------------------------------------
;; Prompts
;;
;; Each prompt is a workflow recipe. `messages-fn` takes the user-supplied
;; arguments (string-keyed, as they arrive from JSON) and produces a
;; `{:description :messages}` map. Messages always carry one user-role
;; entry — the workflow instruction the LLM should execute.
;; ---------------------------------------------------------------------------

(defn- user-message [text]
  {:role    "user"
   :content {:type "text" :text text}})

(defn- require-arg! [args k label]
  (let [v (get args k)]
    (when (or (nil? v) (and (string? v) (str/blank? v)))
      (throw (ex-info (str label " is required") {:arg k})))
    v))

(defn- prompt-code-a-term [args]
  (let [term   (require-arg! args "clinical_term" "clinical_term")
        system (get args "system")
        target (get args "target_value_set")]
    {:description (str "Code the clinical term '" term "'.")
     :messages    [(user-message
                    (str "I need a code for the clinical term: " term ".\n\n"
                         "Please:\n"
                         (if system
                           (str "1. Use `find_matches` against `" system "` for candidate concepts matching the term.\n")
                           "1. Use `search_code_systems` to identify the most appropriate CodeSystem for the term, then `find_matches` against it.\n")
                         "2. For the most plausible candidate(s), use `lookup` to inspect parents and properties to confirm semantic fit.\n"
                         (when target (str "3. Use `validate_code` against `" target "` to confirm the code is bindable in that ValueSet.\n"))
                         "Report the chosen `(system, code, display)` plus your confidence and any ambiguity."))]}))

(defn- prompt-build-value-set [args]
  (let [domain (require-arg! args "clinical_domain" "clinical_domain")
        system (get args "system")]
    {:description (str "Draft a ValueSet for '" domain "'.")
     :messages    [(user-message
                    (str "I want to construct a ValueSet for the clinical domain: " domain ".\n\n"
                         "Please:\n"
                         (if system
                           (str "1. Use `find_matches` against `" system "` to seed candidate concepts.\n")
                           "1. Use `search_code_systems` to identify the appropriate CodeSystem(s). Use `find_matches` to seed candidates.\n")
                         "2. For SNOMED candidates, look at parents (via `lookup`) and propose an `is-a` filter or ECL expression that captures the domain cleanly.\n"
                         "3. Use `expand` with a draft compose block to preview the result. Report the count and a sample.\n"
                         "4. Iterate until the expansion matches intent.\n"
                         "5. Output a JSON `ValueSet.compose` block I can paste into a Bundle."))]}))

(defn- prompt-translate-codes [args]
  (let [codes  (require-arg! args "source_codes" "source_codes")
        target (require-arg! args "target_system" "target_system")]
    {:description (str "Translate codes to " target ".")
     :messages    [(user-message
                    (str "Translate the following codes to the target CodeSystem `" target "`:\n\n"
                         codes "\n\n"
                         "Please:\n"
                         "1. Use `service_info` to identify ConceptMaps that target `" target "`.\n"
                         "2. For each input code, call `translate` (with `url=` the chosen ConceptMap, or `system`+`target`) to get matches.\n"
                         "3. Note the `equivalence` of each match (equivalent / equal / wider / narrower / relatedto / inexact / unmatched / disjoint) so I can judge fidelity.\n"
                         "4. Report a table: source → target → equivalence → display."))]}))

(defn- prompt-explore-concept [args]
  (let [system (require-arg! args "system" "system")
        code   (require-arg! args "code"   "code")]
    {:description (str "Explore concept " system "|" code ".")
     :messages    [(user-message
                    (str "Explore the concept `" system "|" code "`.\n\n"
                         "Please:\n"
                         "1. Use `lookup` with `properties=['parent','child','definition']` for full context.\n"
                         "2. Use `validate_code` (no `url`) to confirm the concept is currently active and not retired.\n"
                         "3. Use `find_matches` with a query derived from the display to surface sibling concepts in the same area.\n"
                         "4. Summarise what this concept represents, where it sits in the hierarchy, and any notable peers."))]}))

(def ^:private prompts*
  [{:name        "code_a_term"
    :description "Walk through finding the right code for a free-text clinical term across SNOMED / LOINC / FHIR code systems."
    :arguments   [{:name "clinical_term"     :description "The free-text clinical phrase to code (e.g. 'type 1 diabetes')." :required true}
                  {:name "system"            :description "Optional CodeSystem URL to restrict the search."                 :required false}
                  {:name "target_value_set"  :description "Optional ValueSet URL to validate the chosen code against."      :required false}]
    :messages-fn prompt-code-a-term}

   {:name        "build_value_set"
    :description "Draft a FHIR ValueSet.compose block for a clinical domain by seeding from search and refining via expand."
    :arguments   [{:name "clinical_domain" :description "The clinical domain the ValueSet should cover (e.g. 'asthma severity')." :required true}
                  {:name "system"          :description "Optional CodeSystem URL to restrict the seed search."                    :required false}]
    :messages-fn prompt-build-value-set}

   {:name        "translate_codes"
    :description "Map codes from one CodeSystem to another via ConceptMap, reporting equivalence per match."
    :arguments   [{:name "source_codes"  :description "Codes to translate, formatted one per line as `system|code` or just `code` (with `system` implied by the source ConceptMap)." :required true}
                  {:name "target_system" :description "Canonical URL of the target CodeSystem (e.g. http://hl7.org/fhir/sid/icd-10)."                                              :required true}]
    :messages-fn prompt-translate-codes}

   {:name        "explore_concept"
    :description "Inspect a concept and its hierarchy/siblings to understand what it means and where it sits."
    :arguments   [{:name "system" :description "CodeSystem canonical URL." :required true}
                  {:name "code"   :description "Code within that CodeSystem." :required true}]
    :messages-fn prompt-explore-concept}])

(defn prompts
  "Public prompt catalogue for the MCP `prompts/list` response. The
  `:messages-fn` is stripped before going on the wire."
  []
  (mapv #(dissoc % :messages-fn) prompts*))

(def ^:private prompts-by-name
  (into {} (map (juxt :name identity)) prompts*))

(defn get-prompt
  "Build the messages for prompt `name` from string-keyed `args` (as they
  arrive from JSON). Returns `{:description :messages}`. Throws on unknown
  prompt or missing required argument."
  [name args]
  (if-let [{f :messages-fn} (get prompts-by-name name)]
    (f (or args {}))
    (throw (ex-info (str "Unknown prompt: " name) {:prompt name}))))
