(ns com.eldrix.hades.impl.mcp.server
  "Stdio JSON-RPC 2.0 transport for the Hades MCP server. Reads
  newline-delimited messages from stdin, dispatches to handlers in
  `com.eldrix.hades.impl.mcp`, writes responses to stdout. Logs to
  stderr; `System/out` is redirected to stderr for the lifetime of the
  loop so stray prints from any downstream library can't corrupt the
  wire."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hades.impl.mcp :as mcp])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter PrintStream)))

(set! *warn-on-reflection* true)

(def ^:private hades-version
  (or (some-> (io/resource "com/eldrix/hades/version.edn")
              slurp edn/read-string :version)
      "dev"))

(def ^:private server-info
  {"protocolVersion" "2024-11-05"
   "capabilities"    {"tools"     {}
                      "resources" {}
                      "prompts"   {}}
   "serverInfo"      {"name"    "hades"
                      "version" hades-version}})

;; ---------------------------------------------------------------------------
;; JSON-RPC envelope helpers
;; ---------------------------------------------------------------------------

(defn- json-rpc-response [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn- json-rpc-error [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn- write-message! [msg]
  (let [s (json/write-str msg)]
    (.write *out* ^String s)
    (.write *out* "\n")
    (.flush *out*)))

;; ---------------------------------------------------------------------------
;; Method handlers
;; ---------------------------------------------------------------------------

(defn- handle-initialize [id _params]
  (json-rpc-response id server-info))

(defn- handle-ping [id _params]
  (json-rpc-response id {}))

(defn- handle-tools-list [id _params]
  (json-rpc-response id {"tools" (mcp/tools)}))

(defn- handle-tools-call [svc id {:strs [name arguments]}]
  (try
    (let [result (mcp/call-tool svc name (update-keys (or arguments {}) keyword))]
      (json-rpc-response id
                         {"content" [{"type" "text"
                                      "text" (json/write-str result)}]}))
    (catch Exception e
      (log/warn e "tool call failed" {:tool name})
      (json-rpc-response id
                         {"content" [{"type" "text"
                                      "text" (str "Error: " (ex-message e))}]
                          "isError" true}))))

(defn- handle-resources-list [id _params]
  (json-rpc-response id {"resources" (mcp/resources)}))

(defn- handle-resources-read [svc id {:strs [uri]}]
  (try
    (let [{:keys [mime text]} (mcp/resource-content svc uri)]
      (json-rpc-response id
                         {"contents" [{"uri"      uri
                                       "mimeType" mime
                                       "text"     text}]}))
    (catch Exception e
      (json-rpc-error id -32602 (ex-message e)))))

(defn- handle-prompts-list [id _params]
  (json-rpc-response id {"prompts" (mcp/prompts)}))

(defn- handle-prompts-get [id {:strs [name arguments]}]
  (try
    (let [{:keys [description messages]} (mcp/get-prompt name (or arguments {}))]
      (json-rpc-response id {"description" description "messages" messages}))
    (catch Exception e
      (json-rpc-error id -32602 (ex-message e)))))

(defn- dispatch [svc {:strs [method id params]}]
  (case method
    "initialize"                (handle-initialize id params)
    "ping"                      (handle-ping id params)
    "tools/list"                (handle-tools-list id params)
    "tools/call"                (handle-tools-call svc id params)
    "resources/list"            (handle-resources-list id params)
    "resources/read"            (handle-resources-read svc id params)
    "prompts/list"              (handle-prompts-list id params)
    "prompts/get"               (handle-prompts-get id params)
    "notifications/initialized" nil
    "notifications/cancelled"   nil
    (when id
      (json-rpc-error id -32601 (str "Method not found: " method)))))

;; ---------------------------------------------------------------------------
;; Read loop
;; ---------------------------------------------------------------------------

(defn start!
  "Read JSON-RPC messages from stdin, dispatch via `svc`, write
  responses to stdout. Logs to stderr. Blocks until stdin closes."
  [svc]
  (log/info "starting Hades MCP server" {:version hades-version})
  (let [out-stream System/out
        rdr        (BufferedReader. (InputStreamReader. System/in))]
    (System/setOut (PrintStream. System/err true))
    (try
      (binding [*out* (OutputStreamWriter. out-stream)]
        (loop []
          (when-let [line (.readLine rdr)]
            (when-not (str/blank? line)
              (try
                (when-let [response (dispatch svc (json/read-str line))]
                  (write-message! response))
                (catch Exception e
                  (log/error e "error processing MCP message")
                  (try
                    (write-message! (json-rpc-error nil -32700 "Parse error"))
                    (catch Exception _)))))
            (recur))))
      (finally
        (System/setOut (PrintStream. out-stream true))))))
