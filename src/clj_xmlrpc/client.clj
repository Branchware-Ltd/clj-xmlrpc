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

(ns clj-xmlrpc.client
  "Idiomatic Clojure XML-RPC client.

  Quick start:

    (require '[clj-xmlrpc.client :as rpc])

    (def c (rpc/client \"http://localhost:8080/xmlrpc\"))

    (rpc/call c \"math.add\" 2 3)        ;=> 5
    (rpc/call c \"system.listMethods\")   ;=> [\"math.add\" ...]

    ;; async — returns a CompletableFuture, deref-able with @
    (def fut (rpc/call-async c \"longOp\" 42))
    @fut  ;=> result (blocks)

    ;; batch
    (rpc/multicall c
      [\"math.add\" 1 2]
      [\"math.sub\" 10 3])  ;=> [3 7]

    ;; per-call config overrides
    (rpc/call-with c {:reply-timeout 500} \"fast.op\")

  All arguments and return values are automatically coerced between
  Clojure and XML-RPC types.  See `clj-xmlrpc.types` for the mapping."
  (:require [clj-xmlrpc.types :as t])
  (:import [org.apache.xmlrpc XmlRpcException]
           [org.apache.xmlrpc.client
            XmlRpcClient
            XmlRpcClientConfigImpl
            AsyncCallback]
           [org.apache.xmlrpc.common TypeFactoryImpl]
           [org.apache.xmlrpc.parser
            NullParser I1Parser I2Parser I8Parser
            FloatParser BigDecimalParser]
           [java.net URL]
           [java.util.concurrent CompletableFuture]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(defn- apply-config!
  "Mutate a `XmlRpcClientConfigImpl` from an options map.  Returns the config."
  ^XmlRpcClientConfigImpl [^XmlRpcClientConfigImpl cfg opts]
  (when-let [url (:url opts)]
    (.setServerURL cfg (if (instance? URL url) url (URL. (str url)))))
  (when-let [{:keys [user password]} (:basic-auth opts)]
    (when user     (.setBasicUserName cfg user))
    (when password (.setBasicPassword cfg password)))
  (when-let [t (:timeout opts)]
    (.setConnectionTimeout cfg (int t)))
  (when-let [t (:reply-timeout opts)]
    (.setReplyTimeout cfg (int t)))
  (when (contains? opts :content-length-optional?)
    (.setContentLengthOptional cfg (boolean (:content-length-optional? opts))))
  (when (contains? opts :gzip-compression?)
    (.setGzipCompressing cfg (boolean (:gzip-compression? opts))))
  (when (contains? opts :gzip-requesting?)
    (.setGzipRequesting cfg (boolean (:gzip-requesting? opts))))
  (when (contains? opts :extensions?)
    (.setEnabledForExtensions cfg (boolean (:extensions? opts))))
  (when-let [enc (:encoding opts)]
    (.setEncoding cfg enc))
  (when-let [ua (:user-agent opts)]
    (.setUserAgent cfg ua))
  cfg)

(defn- make-config
  "Build a fresh `XmlRpcClientConfigImpl` from an options map."
  ^XmlRpcClientConfigImpl [opts]
  (apply-config! (XmlRpcClientConfigImpl.) opts))

;; ---------------------------------------------------------------------------
;; Type factory for Python/non-namespaced extension compatibility
;; ---------------------------------------------------------------------------

(defn- python-compat-type-factory
  "Creates a TypeFactory that handles non-namespaced extension types.

   Apache XML-RPC only handles extension types (nil, i1, i2, i8, float,
   bigdecimal) when they're in the extensions namespace (ex:nil, etc.).
   Many implementations (Python's xmlrpc, PHP, etc.) send these
   without a namespace. This factory handles both cases."
  [controller]
  (proxy [TypeFactoryImpl] [controller]
    (getParser [pConfig pContext pURI pLocalName]
      (if (= "" pURI)
        (case pLocalName
          "nil"        (NullParser.)
          "i1"         (I1Parser.)
          "i2"         (I2Parser.)
          "i8"         (I8Parser.)
          "float"      (FloatParser.)
          "bigdecimal" (BigDecimalParser.)
          (proxy-super getParser pConfig pContext pURI pLocalName))
        (proxy-super getParser pConfig pContext pURI pLocalName)))))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defn client
  "Create an XML-RPC client.

  `url` — the XML-RPC endpoint URL (string or java.net.URL).

  `opts` — optional map:
    :basic-auth               {:user \"u\" :password \"p\"}
    :timeout                  connection timeout in ms
    :reply-timeout            reply timeout in ms
    :content-length-optional? allow chunked transfer (default false)
    :gzip-compression?        gzip outgoing (default false)
    :gzip-requesting?         request gzip from server (default false)
    :extensions?              enable Apache XML-RPC extensions — i8,
                              nil, serializable, etc. (default false)
    :python-compat?           handle non-namespaced extension types
                              (nil, i1, i2, i8, float, bigdecimal) for
                              compatibility with Python, PHP, etc.
                              Implies :extensions? true. (default false)
    :encoding                 character encoding (default UTF-8)
    :user-agent               custom User-Agent header

  Returns a client map suitable for `call`, `call-async`, etc."
  ([url]
   (client url {}))
  ([url opts]
   (let [;; python-compat? implies extensions?
         opts      (cond-> opts
                     (:python-compat? opts) (assoc :extensions? true))
         full-opts (assoc opts :url url)
         config    (make-config full-opts)
         raw       (XmlRpcClient.)]
     (.setConfig raw config)
     (when (:python-compat? opts)
       (.setTypeFactory raw (python-compat-type-factory raw)))
     {::raw-client raw
      ::config     config
      ::opts       full-opts})))

;; ---------------------------------------------------------------------------
;; Synchronous call
;; ---------------------------------------------------------------------------

(defn call
  "Execute a synchronous XML-RPC method call.

  Returns the result, automatically coerced to Clojure types.
  Throws `ExceptionInfo` on XML-RPC fault with keys:
    :type   — :xmlrpc/fault
    :code   — integer fault code
    :method — the method name
    :args   — the original arguments"
  [client method & args]
  (let [^XmlRpcClient c (::raw-client client)
        config (::config client)]
    (try
      (t/->clj (.execute c config ^String method ^objects (t/coerce-params args)))
      (catch XmlRpcException e
        (throw (ex-info (str "XML-RPC fault: " (.getMessage e))
                        {:type   :xmlrpc/fault
                         :code   (.code e)
                         :method method
                         :args   (vec args)}
                        e))))))

;; ---------------------------------------------------------------------------
;; Call with per-request config overrides
;; ---------------------------------------------------------------------------

(defn call-with
  "Like `call`, but merges `override-opts` into the client config for
  this single request.  Useful for per-call timeouts, auth changes, etc.

    (call-with client {:reply-timeout 500} \"fast.method\" arg1 arg2)"
  [client override-opts method & args]
  (let [^XmlRpcClient c (::raw-client client)
        merged (merge (::opts client) override-opts)
        config (make-config merged)]
    (try
      (t/->clj (.execute c config ^String method ^objects (t/coerce-params args)))
      (catch XmlRpcException e
        (throw (ex-info (str "XML-RPC fault: " (.getMessage e))
                        {:type   :xmlrpc/fault
                         :code   (.code e)
                         :method method
                         :args   (vec args)}
                        e))))))

;; ---------------------------------------------------------------------------
;; Asynchronous call
;; ---------------------------------------------------------------------------

(defn call-async
  "Execute an asynchronous XML-RPC method call.

  Returns a `CompletableFuture` that:
    • completes normally with the coerced result, or
    • completes exceptionally with the original `Throwable`.

  Deref with `@` or `.get`:

    @(call-async client \"slow.op\" 42)

  Chain with CompletableFuture combinators:

    (-> (call-async client \"slow.op\" 42)
        (.thenApply inc)
        (.exceptionally (fn [e] :fallback)))"
  [client method & args]
  (let [^XmlRpcClient c (::raw-client client)
        config (::config client)
        cf (CompletableFuture.)]
    (.executeAsync c
      config
      ^String method
      ^objects (t/coerce-params args)
      (reify AsyncCallback
        (handleResult [_ _request result]
          (.complete cf (t/->clj result)))
        (handleError [_ _request error]
          (.completeExceptionally cf error))))
    cf))

;; ---------------------------------------------------------------------------
;; Batch / multicall
;; ---------------------------------------------------------------------------

(defn multicall
  "Execute multiple XML-RPC calls in a single HTTP round-trip via
  `system.multicall`.

  Each call is a vector of `[method-name & args]`:

    (multicall client
      [\"math.add\" 1 2]
      [\"math.sub\" 10 3])
    ;=> [3 7]

  Returns a vector of results (one per call).
  Throws on the *first* fault encountered in the batch."
  [client & calls]
  (let [^XmlRpcClient c (::raw-client client)
        ;; system.multicall expects an array of structs:
        ;;   [{:methodName \"x\" :params [a b]} ...]
        call-structs (mapv (fn [[method & args]]
                             (t/->xmlrpc {"methodName" method
                                          "params"     (vec args)}))
                           calls)
        raw-result   (.execute c
                       "system.multicall"
                       ^objects (object-array [(object-array call-structs)]))]
    (mapv (fn [r idx]
            (let [v (t/->clj r)]
              (cond
                ;; Fault struct → throw
                (and (map? v) (contains? v :faultCode))
                (throw (ex-info (str "XML-RPC multicall fault on call #" idx
                                     ": " (:faultString v))
                                {:type         :xmlrpc/fault
                                 :code         (:faultCode v)
                                 :fault-string (:faultString v)
                                 :call-index   idx
                                 :call         (nth calls idx)}))

                ;; Success: single-element array → unwrap
                (vector? v) (first v)

                :else v)))
          (t/->clj raw-result)
          (range))))

;; ---------------------------------------------------------------------------
;; Introspection helpers (system.* methods)
;; ---------------------------------------------------------------------------

(defn list-methods
  "Return a vector of method names supported by the server.
  Requires server-side `system.listMethods` support."
  [client]
  (vec (call client "system.listMethods")))

(defn method-help
  "Return a help string for `method-name`.
  Requires server-side `system.methodHelp` support."
  [client method-name]
  (call client "system.methodHelp" method-name))

(defn method-signature
  "Return the type signature(s) for `method-name`.
  Requires server-side `system.methodSignature` support."
  [client method-name]
  (call client "system.methodSignature" method-name))
