(ns com.eldrix.hades.providers.common.search-filter
  "FHIR REST-search filter matching, shared by the composite (ConceptMap
  search) and by providers that apply the `::input/metadata-opts` filters
  with an in-memory predicate. Matches a resource-meta-shaped candidate
  map (`{:status :name :title :description}`) against the filter map
  carried in search params / metadata-opts."
  (:require [clojure.string :as str]))

(defn string-filter-match
  "FHIR string search semantics. `f` is a `::input/string-filter`
  (`{:value :modifier}`); `:modifier` defaults to `:starts-with`. nil
  candidate never matches."
  [candidate {:keys [value modifier]}]
  (when candidate
    (case (or modifier :starts-with)
      :exact       (= candidate value)
      :starts-with (str/starts-with? (str/lower-case candidate) (str/lower-case value))
      :contains    (str/includes? (str/lower-case candidate) (str/lower-case value)))))

(defn matches-resource-filters?
  "True when candidate `m` satisfies the `:status :name :title
  :description` filters. The token `:status` matches exactly; the
  `::input/string-filter` fields (`:name :title :description`) match per
  their modifier."
  [m {:keys [status name title description]}]
  (and (or (nil? status)      (= status (:status m)))
       (or (nil? name)        (string-filter-match (:name m) name))
       (or (nil? title)       (string-filter-match (:title m) title))
       (or (nil? description) (string-filter-match (:description m) description))))
