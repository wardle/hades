(ns com.eldrix.hades.registry
  "Dynamic registration of HL7 FHIR terminology services.

  Each codesystem and valueset is registered using id or uri.
  Each conceptmap is registered by tuples of id/uri and uri representing source
  and target respectively.

  In general, identifiers are represented as uris, but there is additional
  support for server-specific well-known identifiers. This means a given
  operation could use a URL encoded identifier or a property encoding the uri
  of the codesystem or valueset. For example, the following are equivalent:
  - [base]/ConceptMap/$translate
  - [base]/ConceptMap/[id]/$translate

  Registration by local (logical) id:
  CodeSystem.id: The logical id on the system that holds the CodeSystem resource
  instance - this typically is expected to change as the resource moves from
  server to server. The location URI is constructed by appending the logical id
  to the server base address where the instance is found and the resource type.
  This URI should be a resolvable URL by which the resource instance may be
  retrieved, usually from a FHIR server, and it may be a relative reference
  typically to the server base URL.

  Registration by URI:
  CodeSystem.url: The canonical URL that never changes for this code system - it
  is the same in every copy. The element is named url rather than uri for legacy
  reasons and to strongly encourage providing a resolvable URL as the identifier
  whenever possible. This canonical URL is used to refer to all instances of
  this particular code system across all servers and systems. Ideally, this URI
  should be a URL which resolves to the location of the master version of the
  code system, though this is not always possible."
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.hades.protocols :as protos]
            [lambdaisland.uri :as uri])
  (:import (com.eldrix.hades.protocols CodeSystem ConceptMap ValueSet)
           (java.net URI URISyntaxException)))


;; registered codesystems, valuesets and concept map providers
;; we don't use multimethods or other approaches for dynamic polymorphism
;; because we need to be able to report on registered providers at runtime
;; TODO: switch to simply using immutable data on startup?
(def codesystems (atom {}))
(def valuesets (atom {}))
(def conceptmaps (atom {}))

(s/def ::uri string?)
(s/def ::url ::uri)
(s/def ::system ::uri)
(s/def ::value string?)
(s/def ::identifier (s/keys :req-un [::system ::value]))
(s/def ::identifiers (s/coll-of ::identifier))
(s/def ::name string?)
(s/def ::title string?)
(s/def ::description string?)
(s/def ::codesystem (s/keys :req-un [::url ::identifiers]
                            :opt-un [::name ::title ::description]))

(defn uri-without-query
  [s]
  (str (assoc (uri/uri s) :query nil)))

(defn register-codesystem
  "Register a codesystem implementation by virtue of URI and identifiers within
  the definition itself, and optionally, via a local, logical identifier."
  [uri-or-logical-id ^CodeSystem impl]
  (swap! codesystems assoc uri-or-logical-id impl))

(defn codesystem
  "Return a codesystem implementation for the given URI or logical id.
  If a match cannot be found, and the URI has query parameters, the URI without
  those query parameters will be used to find an implementation as a fallback."
  ^CodeSystem [uri-or-logical-id]
  (if-let [cs (get @codesystems uri-or-logical-id)]
    cs
    (when-let [uri (uri-or-logical-id uri-or-logical-id)]
      (when (not= uri-or-logical-id uri)
        (get @codesystems uri-or-logical-id)))))

(defn register-valueset
  "Register a valueset implementation"
  [uri-or-logical-id ^ValueSet impl]
  (swap! valuesets assoc uri-or-logical-id impl))

(defn valueset
  ^ValueSet [uri-or-logical-id]
  (if-let [cs (get @valuesets uri-or-logical-id)]
    cs
    (when-let [uri (uri-without-query uri-or-logical-id)]
      (when-not (= uri-or-logical-id uri)
        (get @valuesets uri)))))

(defn register-concept-map
  [source-uri target-uri ^ConceptMap impl]
  (swap! conceptmaps assoc (vector source-uri target-uri) impl))

(defn concept-map
  ^ConceptMap [uri-or-logical-id]
  (if-let [cs (get @conceptmaps uri-or-logical-id)]
    cs
    (when-let [uri (uri-or-logical-id uri-or-logical-id)]
      (when (not= uri-or-logical-id uri)
        (get @conceptmaps uri-or-logical-id)))))




(defn codesystem-resource
  [params])

(s/fdef codesystem-lookup
  :args (s/cat :params ::protos/codesystem-lookup))
(defn codesystem-lookup
  "Given a code/system, get additional details about the concept,
    including definition, status, designations, and properties. One of the
    products of this operation is a full decomposition of a code from a
    structured terminology."
  [{:keys [system] :as params}]
  (when-let [cs (codesystem system)]
    (protos/cs-lookup cs params)))


(defn codesystem-validate-code [params])
(defn codesystem-subsumes
  [{:keys [systemA systemB] :as params}]
  (when-not (= systemA systemB)
    (throw (ex-info "Currently, can only check subsumption within same codesystem" params)))
  (when-let [cs (codesystem systemA)]
    (protos/cs-subsumes cs params)))
(defn codesystem-find-matches [params])

(defn valueset-resource [params])
(defn valueset-expand [{:keys [url] :as params}]
  (when-let [vs (valueset url)]
    (protos/vs-expand vs params)))
(defn valueset-validate-code [params])

(defn conceptmap-resource [params])
(defn conceptmap-translate [params])