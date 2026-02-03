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

(ns clj-xmlrpc.server
  "Embedded XML-RPC server backed by Apache's `WebServer`.

  Quick start:

    (require '[clj-xmlrpc.server :as server])

    (def srv
      (server/start!
        {:port 8080
         :handlers {\"math.add\" (fn [a b] (+ a b))
                    \"math.sub\" (fn [a b] (- a b))
                    \"echo\"     identity}}))

    (server/stop! srv)

  Features:
    • Plain Clojure functions as handlers — no Java classes needed.
    • Automatic type coercion on arguments and return values.
    • Built-in `system.*` introspection (listMethods, methodHelp).
    • Hot-reload handlers at runtime via `update-handlers!`.
    • Composable middleware for cross-cutting concerns.
    • Namespace-based bulk handler registration."
  (:require [clj-xmlrpc.types :as t])
  (:import [org.apache.xmlrpc XmlRpcException XmlRpcHandler]
           [org.apache.xmlrpc.server
            XmlRpcHandlerMapping
            XmlRpcServerConfigImpl]
           [org.apache.xmlrpc.webserver WebServer]
           [java.net InetAddress]))

;; ---------------------------------------------------------------------------
;; Handler wrapping
;; ---------------------------------------------------------------------------

(defn- wrap-handler
  "Wrap a Clojure function as an `XmlRpcHandler`.

  Pulls parameters from the `XmlRpcRequest`, coerces them to Clojure types,
  applies `f`, then coerces the result back to XML-RPC types.

  Any non-`XmlRpcException` thrown by `f` is wrapped in one so that the
  XML-RPC protocol can report the fault properly."
  ^XmlRpcHandler [f {:keys [coerce-args? coerce-result?]
                      :or   {coerce-args? true coerce-result? true}}]
  (reify XmlRpcHandler
    (execute [_ request]
      (try
        (let [n    (.getParameterCount request)
              args (loop [i 0, acc (transient [])]
                     (if (< i n)
                       (let [raw (.getParameter request (int i))]
                         (recur (inc i)
                                (conj! acc (if coerce-args?
                                             (t/->clj raw)
                                             raw))))
                       (persistent! acc)))
              result (apply f args)]
          (if coerce-result?
            (t/->xmlrpc result)
            result))
        (catch XmlRpcException e (throw e))
        (catch Exception e
          (throw (XmlRpcException. 0 (.getMessage e) e)))))))

;; ---------------------------------------------------------------------------
;; Dynamic (atom-backed) handler mapping
;; ---------------------------------------------------------------------------

(defn- make-handler-mapping
  "Build an `XmlRpcHandlerMapping` backed by an atom of
  `{method-name → clojure-fn}`.

  Because the mapping reads from the atom on every `getHandler` call,
  handlers can be swapped at runtime without restarting the server."
  ^XmlRpcHandlerMapping [handlers-atom opts]
  (reify XmlRpcHandlerMapping
    (getHandler [_ method-name]
      (if-let [f (get @handlers-atom method-name)]
        (wrap-handler f opts)
        (throw (XmlRpcException.
                 (str "No handler registered for method: " method-name)))))))

;; ---------------------------------------------------------------------------
;; system.* introspection handlers
;; ---------------------------------------------------------------------------

(defn- system-list-methods
  "Handler for `system.listMethods`."
  [handlers-atom]
  (fn []
    (vec (sort (keys @handlers-atom)))))

(defn- system-method-help
  "Handler for `system.methodHelp`."
  [handlers-atom]
  (fn [method-name]
    (if-let [f (get @handlers-atom method-name)]
      (or (:doc (meta f))
          (:xmlrpc/doc (meta f))
          "")
      (throw (XmlRpcException.
               (str "Unknown method: " method-name))))))

(defn- system-method-signature
  "Handler for `system.methodSignature`.
  Returns signature from metadata `:xmlrpc/sig` if present, else \"undef\"."
  [handlers-atom]
  (fn [method-name]
    (if-let [f (get @handlers-atom method-name)]
      (if-let [sig (:xmlrpc/sig (meta f))]
        sig
        "undef")
      (throw (XmlRpcException.
               (str "Unknown method: " method-name))))))

(defn- add-system-handlers
  "Merge `system.*` introspection handlers into the handler map."
  [handlers handlers-atom]
  (merge handlers
         {"system.listMethods"    (system-list-methods handlers-atom)
          "system.methodHelp"     (system-method-help handlers-atom)
          "system.methodSignature" (system-method-signature handlers-atom)
          "system.multicall"
          (fn [calls]
            ;; calls is a vector of maps {:methodName "x" :params [...]}
            ;; Note: params are already coerced to Clojure types by wrap-handler.
            (mapv (fn [call-spec]
                    (try
                      (let [method (:methodName call-spec)
                            params (or (:params call-spec) [])
                            f      (or (get @handlers-atom method)
                                       (throw (XmlRpcException.
                                                (str "Unknown: " method))))
                            result (apply f params)]
                        [result])
                      (catch Exception e
                        {"faultCode"   (if (instance? XmlRpcException e)
                                         (.code ^XmlRpcException e)
                                         0)
                         "faultString" (.getMessage e)})))
                  calls))}))

;; ---------------------------------------------------------------------------
;; Middleware
;; ---------------------------------------------------------------------------

(defn wrap-logging
  "Middleware: log every method call and its result to *out*.

    (start! {:handlers (apply-middleware handlers [wrap-logging]) ...})"
  [handler-fn]
  (fn [& args]
    (let [result (apply handler-fn args)]
      (println "  →" result)
      result)))

(defn wrap-timing
  "Middleware: print elapsed time for each call."
  [handler-fn]
  (fn [& args]
    (let [start  (System/nanoTime)
          result (apply handler-fn args)
          ms     (/ (- (System/nanoTime) start) 1e6)]
      (println (format "  ⏱ %.2fms" ms))
      result)))

(defn wrap-exception-logging
  "Middleware: log exceptions to *err* before re-throwing."
  [handler-fn]
  (fn [& args]
    (try
      (apply handler-fn args)
      (catch Exception e
        (binding [*out* *err*]
          (println "XML-RPC handler error:" (.getMessage e)))
        (throw e)))))

(defn apply-middleware
  "Apply a sequence of middleware functions to every handler in a handler map.

  Middleware are composed right-to-left (outermost first), matching Ring
  convention:

    (apply-middleware
      {\"add\" +, \"sub\" -}
      [wrap-logging wrap-timing])

  produces handlers wrapped as: (wrap-logging (wrap-timing f))"
  [handlers middlewares]
  (let [composed (apply comp middlewares)]
    (into {}
          (map (fn [[method-name f]]
                 [method-name (composed f)]))
          handlers)))

;; ---------------------------------------------------------------------------
;; Namespace-based handler registration
;; ---------------------------------------------------------------------------

(defn ns-handlers
  "Build a handler map from the public functions in a namespace.

  Each public `defn` becomes a handler named `\"prefix.fn-name\"`.
  Preserves var metadata (`:doc`, `:xmlrpc/sig`, etc.) on the function.

    (ns-handlers 'my.math \"math\")
    ;=> {\"math.add\" #<fn>, \"math.sub\" #<fn>, ...}"
  [ns-sym prefix]
  (require ns-sym)
  (into {}
        (for [[sym v] (ns-publics (the-ns ns-sym))
              :when (and (var? v) (fn? (deref v)))]
          [(str prefix "." (name sym))
           (with-meta (deref v) (meta v))])))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Start an embedded XML-RPC server.

  `opts` — map with keys:
    :port             (required) TCP port to listen on
    :bind             bind address — string or InetAddress (default all interfaces)
    :handlers         (required) {\"method.name\" (fn [& args] ...)}
    :system-handlers? add system.listMethods / system.methodHelp / system.multicall
                      (default true)
    :extensions?      enable Apache XML-RPC extensions — nil, i8, etc.
                      (default false)
    :coerce-args?     auto-coerce XML-RPC → Clojure on args (default true)
    :coerce-result?   auto-coerce Clojure → XML-RPC on results (default true)

  Returns a server map.  Pass it to `stop!` to shut down."
  [{:keys [port bind handlers system-handlers? extensions?
           coerce-args? coerce-result?]
    :or   {system-handlers? true
           extensions?      false
           coerce-args?     true
           coerce-result?   true}
    :as   opts}]
  {:pre [(pos-int? port) (map? handlers) (seq handlers)]}
  (let [handlers-atom (atom handlers)
        _            (when system-handlers?
                       (swap! handlers-atom
                              add-system-handlers handlers-atom))
        handler-opts {:coerce-args?   coerce-args?
                      :coerce-result? coerce-result?}
        mapping      (make-handler-mapping handlers-atom handler-opts)
        ^WebServer ws (if bind
                        (WebServer. (int port)
                                    (if (instance? InetAddress bind)
                                      ^InetAddress bind
                                      (InetAddress/getByName (str bind))))
                        (WebServer. (int port)))
        xml-server   (.getXmlRpcServer ws)]
    (.setHandlerMapping xml-server mapping)
    (let [^XmlRpcServerConfigImpl scfg
          (.getConfig xml-server)]
      (.setEnabledForExtensions scfg (boolean extensions?)))
    (.start ws)
    {::web-server    ws
     ::xmlrpc-server xml-server
     ::handlers      handlers-atom
     ::opts          opts}))

(defn stop!
  "Stop a running XML-RPC server."
  [server]
  (.shutdown ^WebServer (::web-server server))
  nil)

(defn running?
  "True if the server's `WebServer` thread is alive."
  [server]
  (some? (::web-server server)))

;; ---------------------------------------------------------------------------
;; Hot-reload
;; ---------------------------------------------------------------------------

(defn update-handlers!
  "Replace or merge handlers on a running server.

  `mode` — `:replace` (default) to set handlers exactly,
           `:merge` to add/overwrite without removing existing ones.

  The server does *not* need to be restarted; the next incoming request
  will see the new handlers."
  ([server new-handlers]
   (update-handlers! server new-handlers :replace))
  ([server new-handlers mode]
   (let [a (::handlers server)]
     (case mode
       :replace (reset! a new-handlers)
       :merge   (swap! a merge new-handlers))
     ;; Re-add system handlers if they were originally enabled
     (when (:system-handlers? (::opts server) true)
       (swap! a add-system-handlers a))
     @a)))

(defn remove-handler!
  "Remove a single handler by method name from a running server."
  [server method-name]
  (swap! (::handlers server) dissoc method-name))

(defn current-handlers
  "Return the current handler map (snapshot) for a running server."
  [server]
  @(::handlers server))
