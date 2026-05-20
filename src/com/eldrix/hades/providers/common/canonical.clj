(ns com.eldrix.hades.providers.common.canonical
  "Pure utilities for FHIR canonical URLs and version handling.

  - parse `url|version` canonicals
  - version pattern matching (`x` wildcards)
  - numeric version compare
  - query-string stripping"
  (:require [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [version-clj.core :as ver]))

(set! *warn-on-reflection* true)

(def ^:const wildcard-version
  "Sentinel `version` value declaring that a provider serves *every*
  version of its CodeSystem URL. Reserved for backends that handle
  arbitrary editions natively (e.g. SNOMED via Hermes resolves any
  `http://snomed.info/sct/<edition>/version/<date>` URI). Providers
  that only know specific versions must enumerate them; never use `*`
  as a fallback for \"version unknown\"."
  "*")

(defn parse-versioned-uri
  "Split a FHIR canonical reference into [url version].
  'http://example.com/cs|1.0' => ['http://example.com/cs' '1.0']
  'http://example.com/cs'     => ['http://example.com/cs' nil]"
  [s]
  (when s
    (let [idx (str/last-index-of s "|")]
      (if (and idx (pos? idx))
        [(subs s 0 idx) (subs s (inc idx))]
        [s nil]))))

(defn versioned-uri
  "Construct a versioned canonical: url|version. Returns url if version is nil."
  [url version]
  (if version (str url "|" version) url))

(defn parse-version-param
  "Parse a seq of 'url|version' canonical strings into {url version} map."
  [params]
  (into {} (keep (fn [s] (let [[url ver] (parse-versioned-uri s)]
                            (when ver [url ver]))))
        params))

(defn version-matches?
  "Check if concrete version matches a pattern. 'x' segments are wildcards.
   '1.0.x' matches '1.0.0', '1.x.x' matches '1.2.0'.
   A shorter pattern is padded with 'x': '1.x' matches '1.0.0'."
  [pattern concrete]
  (when (and pattern concrete)
    (if (= pattern concrete)
      true
      (let [p-parts (str/split pattern #"\.")
            c-parts (str/split concrete #"\.")
            p-padded (if (< (count p-parts) (count c-parts))
                       (into (vec p-parts) (repeat (- (count c-parts) (count p-parts)) "x"))
                       p-parts)]
        (and (= (count p-padded) (count c-parts))
             (every? true? (map (fn [p c] (or (= "x" p) (= p c)))
                                p-padded c-parts)))))))

(defn- recognisable-version?
  "True when `s` looks like a version we can meaningfully order: SemVer
  (`1.2.3`), pre-release (`1.2.3-rc1`) and date stamps (`2025-02-01`)
  all qualify. Opaque labels (`draft`, `released`, `*`) and URIs do
  not — they have no inherent ordering and must tie under
  `semver-compare` so that callers using tie-detection for ambiguity
  (e.g. composite `pick-latest-semver`) refuse to pick a winner."
  [^String s]
  (boolean (re-find #"^\d" s)))

(defn semver-compare
  "Compare two version strings, delegating to `version-clj` so SemVer
  2.0.0 §11 is honoured (`4.0.0-rc1` < `4.0.0`, `4.0.0-alpha` <
  `4.0.0-beta`) alongside ordinary numeric ordering (`1.10.0` >
  `1.9.0`).

  Nil is treated as 'no declared version' and orders below any concrete
  version, so a concrete version always wins 'latest semver' against
  a versionless entry. Two nils are equal.

  Strings that don't start with a digit (URIs, opaque labels like
  'draft' or '*') are treated as unorderable and tie at 0."
  [a b]
  (cond
    (and (nil? a) (nil? b)) 0
    (nil? a) -1
    (nil? b) 1
    (not (and (recognisable-version? a) (recognisable-version? b))) 0
    :else (ver/version-compare a b)))

(defn uri-without-query
  "Return the URI with the query component stripped."
  [s]
  (str (assoc (uri/uri s) :query nil)))
