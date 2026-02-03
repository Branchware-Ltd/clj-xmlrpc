;; Copyright 2026 Branchware Ltd
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns clj-xmlrpc.core
  "Unified entry point for clj-xmlrpc.

  Re-exports the most commonly used vars from `client`, `server`, and
  `types` so that simple programs only need a single require:

    (require '[clj-xmlrpc.core :as xmlrpc])

    ;; Client
    (def c (xmlrpc/client \"http://localhost:8080/xmlrpc\"))
    (xmlrpc/call c \"add\" 1 2)

    ;; Server
    (def s (xmlrpc/start! {:port 8080
                           :handlers {\"add\" +}}))
    (xmlrpc/stop! s)

  For advanced usage (middleware, per-call config, type coercion
  customisation) require the sub-namespaces directly."
  (:require [clj-xmlrpc.client :as client]
            [clj-xmlrpc.server :as server]
            [clj-xmlrpc.types  :as types]))

;; ---------------------------------------------------------------------------
;; Client API
;; ---------------------------------------------------------------------------

(defn client
  "Create an XML-RPC client. See `clj-xmlrpc.client/client`."
  ([url]       (client/client url))
  ([url opts]  (client/client url opts)))

(defn call
  "Synchronous XML-RPC call. See `clj-xmlrpc.client/call`."
  [client method & args]
  (apply client/call client method args))

(defn call-async
  "Asynchronous XML-RPC call → CompletableFuture. See `clj-xmlrpc.client/call-async`."
  [client method & args]
  (apply client/call-async client method args))

(defn call-with
  "Synchronous call with per-request config. See `clj-xmlrpc.client/call-with`."
  [client opts method & args]
  (apply client/call-with client opts method args))

(defn multicall
  "Batch multiple calls in one round-trip. See `clj-xmlrpc.client/multicall`."
  [client & calls]
  (apply client/multicall client calls))

(defn list-methods
  "Introspection: list remote methods. See `clj-xmlrpc.client/list-methods`."
  [client]
  (client/list-methods client))

(defn method-help
  "Introspection: get help for a method. See `clj-xmlrpc.client/method-help`."
  [client method]
  (client/method-help client method))

;; ---------------------------------------------------------------------------
;; Server API
;; ---------------------------------------------------------------------------

(defn start!
  "Start an embedded XML-RPC server. See `clj-xmlrpc.server/start!`."
  [opts]
  (server/start! opts))

(defn stop!
  "Stop a running server. See `clj-xmlrpc.server/stop!`."
  [server]
  (server/stop! server))

(defn update-handlers!
  "Hot-reload handlers on a running server. See `clj-xmlrpc.server/update-handlers!`."
  ([server new-handlers]       (server/update-handlers! server new-handlers))
  ([server new-handlers mode]  (server/update-handlers! server new-handlers mode)))

;; ---------------------------------------------------------------------------
;; Type coercion
;; ---------------------------------------------------------------------------

(def ->xmlrpc
  "Coerce a Clojure value to XML-RPC. See `clj-xmlrpc.types/->xmlrpc`."
  types/->xmlrpc)

(def ->clj
  "Coerce an XML-RPC value to Clojure. See `clj-xmlrpc.types/->clj`."
  types/->clj)
