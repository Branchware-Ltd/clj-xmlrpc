# clj-xmlrpc

Idiomatic Clojure bindings for [Apache XML-RPC](https://ws.apache.org/xmlrpc/) — the `org.apache.xmlrpc` library.

Plain Clojure functions as handlers, automatic type coercion, async calls, hot-reload, composable middleware. No Java boilerplate.

## Quick Start

```clojure
;; deps.edn
{:deps {clj-xmlrpc/clj-xmlrpc {:local/root "."}}}
```

### Server

```clojure
(require '[clj-xmlrpc.server :as server])

(def srv
  (server/start!
    {:port 8080
     :handlers {"math.add" (fn [a b] (+ a b))
                "math.sub" (fn [a b] (- a b))
                "greet"    (fn [name] (str "Hello, " name "!"))}}))

;; Later...
(server/stop! srv)
```

### Client

```clojure
(require '[clj-xmlrpc.client :as rpc])

(def c (rpc/client "http://localhost:8080/xmlrpc"))

(rpc/call c "math.add" 2 3)        ;=> 5
(rpc/call c "greet" "Clojure")     ;=> "Hello, Clojure!"
(rpc/list-methods c)                ;=> ["greet" "math.add" "math.sub" ...]
```

### Unified Namespace

For simple programs, use the unified namespace:

```clojure
(require '[clj-xmlrpc.core :as xmlrpc])

(xmlrpc/call client "method" arg1 arg2)
(xmlrpc/start! {:port 8080 :handlers {...}})
```

## Type Coercion

Types are coerced automatically on both sides of the wire.

| XML-RPC Type       | Java Type       | Clojure Type          |
|--------------------|----------------|-----------------------|
| `int` / `i4`       | `Integer`      | `long`                |
| `i8` (extension)   | `Long`         | `long`                |
| `boolean`          | `Boolean`      | `boolean`             |
| `string`           | `String`       | `string` / `keyword`  |
| `double`           | `Double`       | `double`              |
| `dateTime.iso8601` | `Date`         | `Date` / `Instant`    |
| `base64`           | `byte[]`       | `byte[]`              |
| `struct`           | `HashMap`      | map (keyword keys)    |
| `array`            | `Object[]`     | vector                |
| `nil` (extension)  | `null`         | `nil`                 |

### Outbound (Clojure → XML-RPC)

```clojure
(require '[clj-xmlrpc.types :as t])

(t/->xmlrpc 42)                   ;=> (int 42)
(t/->xmlrpc :status)              ;=> "status"
(t/->xmlrpc :ns/key)              ;=> "ns/key"
(t/->xmlrpc {:a 1 :b [2 3]})     ;=> HashMap{"a"=1, "b"=Object[]{2,3}}
(t/->xmlrpc (java.time.Instant/now))  ;=> java.util.Date
```

### Inbound (XML-RPC → Clojure)

```clojure
(t/->clj some-hashmap)            ;=> {:key "val", ...}
(t/->clj some-object-array)       ;=> [1 2 3]
(t/->clj (Integer. 42))           ;=> 42  (long)
```

## Client API

### Creating a Client

```clojure
(def c (rpc/client "http://host:port/xmlrpc"
         {:basic-auth   {:user "admin" :password "secret"}
          :timeout      5000          ; connection timeout (ms)
          :reply-timeout 10000        ; reply timeout (ms)
          :extensions?  true          ; enable i8, nil, etc.
          :gzip-compression? true     ; gzip outgoing
          :gzip-requesting?  true     ; request gzip responses
          :encoding     "UTF-8"
          :user-agent   "my-app/1.0"}))
```

### Synchronous Calls

```clojure
(rpc/call c "method")              ; no args
(rpc/call c "method" 1 "two" 3.0) ; multiple args
(rpc/call c "method" {:key "val"}) ; struct arg
(rpc/call c "method" [1 2 3])      ; array arg
```

### Per-Request Config Overrides

```clojure
(rpc/call-with c {:reply-timeout 500} "fast.method" arg)
(rpc/call-with c {:basic-auth {:user "other" :password "pw"}} "secure.method")
```

### Async Calls

Returns a `CompletableFuture` — deref with `@` or chain with Java interop:

```clojure
;; Block for result
@(rpc/call-async c "slow.method" 42)

;; With timeout
(deref (rpc/call-async c "slow.method" 42) 5000 :timeout)

;; Chain transformations
(-> (rpc/call-async c "compute" x)
    (.thenApply inc)
    (.exceptionally (fn [e] :fallback)))
```

### Batch / Multicall

Execute multiple calls in a single HTTP round-trip:

```clojure
(rpc/multicall c
  ["math.add" 1 2]
  ["math.sub" 10 3]
  ["greet" "World"])
;=> [3 7 "Hello, World!"]
```

### Introspection

```clojure
(rpc/list-methods c)                ;=> ["math.add" "math.sub" ...]
(rpc/method-help c "math.add")      ;=> "Add two numbers"
(rpc/method-signature c "math.add") ;=> [["int" "int" "int"]]
```

### Error Handling

XML-RPC faults throw `ExceptionInfo` with structured data:

```clojure
(try
  (rpc/call c "nonexistent")
  (catch clojure.lang.ExceptionInfo e
    (ex-data e)))
;=> {:type   :xmlrpc/fault
;    :code   0
;    :method "nonexistent"
;    :args   []}
```

## Server API

### Starting a Server

```clojure
(def srv
  (server/start!
    {:port             8080
     :bind             "127.0.0.1"   ; default: all interfaces
     :handlers         {"add" + "sub" -}
     :system-handlers? true          ; default: true (system.listMethods etc.)
     :extensions?      true          ; default: false
     :coerce-args?     true          ; default: true
     :coerce-result?   true}))       ; default: true

(server/stop! srv)
```

### Handler Metadata

Attach metadata for `system.methodHelp`:

```clojure
(def handlers
  {"math.add" (with-meta
                (fn [a b] (+ a b))
                {:doc "Add two integers"
                 :xmlrpc/sig [["int" "int" "int"]]})})
```

### Namespace Registration

Register all public functions from a namespace:

```clojure
(ns my.math)
(defn add "Add two numbers" [a b] (+ a b))
(defn sub "Subtract b from a" [a b] (- a b))

;; Elsewhere:
(server/ns-handlers 'my.math "math")
;=> {"math.add" #<fn add>, "math.sub" #<fn sub>}
```

### Hot-Reload

Update handlers on a running server — no restart needed:

```clojure
;; Merge new handlers (keep existing ones)
(server/update-handlers! srv
  {"math.pow" (fn [b e] (long (Math/pow b e)))}
  :merge)

;; Replace all handlers
(server/update-handlers! srv new-handler-map :replace)

;; Remove a single handler
(server/remove-handler! srv "math.pow")

;; Inspect current state
(server/current-handlers srv)
```

### Middleware

Middleware wraps handler functions (like Ring middleware):

```clojure
(require '[clj-xmlrpc.server :as server])

;; Built-in middleware
server/wrap-logging            ; prints args + result
server/wrap-timing             ; prints elapsed time
server/wrap-exception-logging  ; logs errors to *err*

;; Compose middleware onto all handlers
(def handlers
  (server/apply-middleware
    {"add" + "sub" -}
    [server/wrap-logging server/wrap-timing]))

;; Custom middleware
(defn wrap-auth [handler-fn]
  (fn [& args]
    ;; Your auth logic here
    (apply handler-fn args)))

(server/start! {:port 8080
                :handlers (server/apply-middleware
                            base-handlers
                            [wrap-auth server/wrap-logging])})
```

## Architecture

```
clj-xmlrpc.types   — Bidirectional type coercion (protocol + multimethod)
clj-xmlrpc.client  — XmlRpcClient wrapper (call, call-async, multicall)
clj-xmlrpc.server  — WebServer wrapper (start!, stop!, hot-reload, middleware)
clj-xmlrpc.core    — Unified re-exports for convenience
```

### Design Principles

1. **Data over objects** — Clients and servers are plain maps, handlers are plain functions.
2. **Automatic coercion** — No manual type juggling; Clojure types map naturally to XML-RPC.
3. **Composable** — Middleware, namespace registration, and hot-reload work together.
4. **Transparent errors** — XML-RPC faults become `ex-info` with full context.
5. **Zero ceremony** — A server is one function call away. No XML config, no annotations.

## Running Tests

```bash
clj -X:test
```

## License

Apache License 2.0
