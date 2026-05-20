(ns com.eldrix.hades.providers.common.issues
  "Shared issue / message helpers used by both the composite layer and
  the validate-code engines. Kept narrow on purpose: each helper here
  is invoked from at least two places and is purely structural — no
  protocol calls, no service lookups."
  (:require [clojure.string :as str]))

(defn unknown-code-lookup
  "Build a `::result/lookup` not-found map for `:unknown-code` — the
  CodeSystem is registered but the requested code isn't in it."
  [system code]
  (let [msg (str "Unknown code '" code "' in code system '" system "'")]
    {:not-found        true
     :not-found-reason :unknown-code
     :system           system
     :code             (when code (keyword code))
     :message          msg
     :issues           [{:severity "error" :type "not-found" :text msg}]}))

(defn unknown-system-lookup
  "Build a `::result/lookup` not-found map for `:unknown-system` — no
  registered CodeSystem serves the requested URL/version."
  [system code]
  (let [msg (str "Unknown code system: " system)]
    {:not-found        true
     :not-found-reason :unknown-system
     :system           system
     :code             (when code (keyword code))
     :message          msg
     :issues           [{:severity "error" :type "not-found" :text msg}]}))

(defn unknown-system-validate
  "Build a `::result/validate` map for an unknown CodeSystem. Mirrors the
  shape composite uses when no provider serves the URL."
  [system code]
  (let [msg (str "A definition for CodeSystem '" system
                 "' could not be found, so the code cannot be validated")]
    {:result           false
     :code             (when code (keyword code))
     :system           system
     :message          msg
     :x-unknown-system system
     :issues           [{:severity     "error"
                         :type         "not-found"
                         :details-code "not-found"
                         :text         msg
                         :expression   ["system"]}]}))

(defn unknown-system-subsumes
  "Build a subsumes result for an unknown CodeSystem — no `:outcome`;
  the presence of `:not-found` + issues signals the error."
  [system]
  (let [msg (str "A definition for the code system '" system
                 "' could not be found, so subsumption cannot be tested")]
    {:not-found        true
     :x-unknown-system system
     :issues           [{:severity     "error"
                         :type         "not-found"
                         :details-code "not-found"
                         :text         msg
                         :expression   ["system"]}]}))

(defn unknown-code-subsumes
  "Build a subsumes result for a code that isn't in the CodeSystem.
  `expression` is the FHIRPath token identifying which input was bad
  (`\"codeA\"` or `\"codeB\"`)."
  [system code expression]
  (let [msg (str "Unknown code '" code "' in code system '" system "'")]
    {:not-found true
     :issues    [{:severity     "error"
                  :type         "not-found"
                  :details-code "not-found"
                  :text         msg
                  :expression   [expression]}]}))

(defn inactive-warning-issue
  "Build an OperationOutcome issue for an inactive concept."
  [code status]
  {:severity     "warning"
   :type         "business-rule"
   :details-code "code-comment"
   :text         (str "The concept '" code "' has a status of " status
                      " and its use should be reviewed")
   :expression   ["Coding"]})

(defn add-inactive-warning
  "If `result` describes an inactive concept, append the inactive
  warning issue(s) and message text. Surfaces the `:inactive` /
  `:inactive-status` flags that protocol impls already populated."
  [result]
  (if (:inactive result)
    (let [code      (name (:code result))
          specific  (:inactive-status result)
          specific? (and specific (not= specific "inactive"))
          issues    (cond-> [(inactive-warning-issue code "inactive")]
                      specific? (conj (inactive-warning-issue code specific)))
          msgs      (cond-> [(:text (first issues))]
                      specific? (conj (:text (second issues))))
          combined  (str/join "; " msgs)]
      (-> result
          (update :issues (fnil into []) issues)
          (update :message (fn [existing]
                             (if existing (str existing "; " combined) combined)))))
    result))

(defn format-case-mismatch
  "Format the `:text` for an `invalid-display` `code-rule` issue raised
  when a case-insensitive CodeSystem matched the request but only by
  folding case. Reused by every CodeSystem provider so the wording
  (including the `|version` suffix only when versioned) stays
  consistent."
  [given-code actual-code system version]
  (str "The code '" given-code "' differs from the correct code '"
       actual-code "' by case. Although the code system '"
       system (when version (str "|" version))
       "' is case insensitive, implementers are strongly "
       "encouraged to use the correct case anyway"))

(defn format-display-mismatch
  "Format the `:message` text for an invalid-display issue.

  Inputs are taken from the matched concept the validator already has:
    given-display    — the display the caller passed
    system / code    — the matched coding
    primary-display  — the concept's primary display
    designations     — the concept's designations (each `{:value :language}`)
    display-language — the caller's `displayLanguage` (nil if absent)
    cs-language      — the CodeSystem-level language (nil if absent)"
  [given-display system code primary-display designations display-language cs-language]
  (let [prefix       (str "Wrong Display Name '" given-display "' for " system "#" code ". ")
        lang         (or display-language "--")
        primary-lang (or (some (fn [d] (when (= (:value d) primary-display)
                                         (when-let [l (:language d)] (name l))))
                                designations)
                         cs-language)
        all-choices  (cond-> []
                       primary-display
                       (conj {:display primary-display :lang primary-lang})
                       (seq designations)
                       (into (keep (fn [d]
                                     (when (and (:value d) (not= (:value d) primary-display))
                                       {:display (:value d)
                                        :lang (when-let [l (:language d)] (name l))})))
                             designations))
        has-lang-info? (some :lang all-choices)
        lang-filtered  (if display-language
                         (filter #(= (:lang %) display-language) all-choices)
                         all-choices)
        unique-choices (distinct lang-filtered)]
    (cond
      (and display-language (empty? unique-choices) has-lang-info?)
      (str prefix "There are no valid display names found for language(s) '" lang
           "'. Default display is '" primary-display "'")

      (> (count unique-choices) 1)
      (let [formatted (map (fn [{:keys [display lang]}]
                             (if lang (str "'" display "' (" lang ")") (str "'" display "'")))
                           unique-choices)]
        (str prefix "Valid display is one of " (count unique-choices) " choices: "
             (str/join " or " formatted) " (for the language(s) '" lang "')"))

      (and (= 1 (count unique-choices)) (:lang (first unique-choices)))
      (let [{display :display, choice-lang :lang} (first unique-choices)]
        (str prefix "Valid display is '" display "' (" choice-lang ") (for the language(s) '" lang "')"))

      :else
      (str prefix "Valid display is '" primary-display "' (for the language(s) '" lang "')"))))
