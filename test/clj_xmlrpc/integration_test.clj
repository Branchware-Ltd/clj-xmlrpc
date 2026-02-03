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

(ns clj-xmlrpc.integration-test
  "End-to-end integration tests for clj-xmlrpc.

  Spins up a real WebServer, connects a client, and exercises the full
  type coercion + call lifecycle."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-xmlrpc.core   :as xmlrpc]
            [clj-xmlrpc.client :as client]
            [clj-xmlrpc.server :as server]
            [clj-xmlrpc.types  :as types])
  (:import [java.util Date HashMap]))

;; ---------------------------------------------------------------------------
;; Type coercion unit tests
;; ---------------------------------------------------------------------------

(deftest test-xmlrpc-roundtrip-primitives
  (testing "Primitives survive Clojure → XML-RPC → Clojure round-trip"
    (is (= 42   (types/->clj (types/->xmlrpc 42))))
    (is (= 3.14 (types/->clj (types/->xmlrpc 3.14))))
    (is (= true (types/->clj (types/->xmlrpc true))))
    (is (= "hi" (types/->clj (types/->xmlrpc "hi"))))
    (is (nil?   (types/->clj (types/->xmlrpc nil))))))

(deftest test-xmlrpc-keyword-coercion
  (testing "Keywords become strings on the way out"
    (is (= "foo"     (types/->xmlrpc :foo)))
    (is (= "ns/name" (types/->xmlrpc :ns/name)))))

(deftest test-xmlrpc-map-coercion
  (testing "Maps become HashMaps with string keys"
    (let [hm (types/->xmlrpc {:a 1 :b "two"})]
      (is (instance? HashMap hm))
      (is (= 1     (.get hm "a")))
      (is (= "two" (.get hm "b")))))

  (testing "HashMaps become Clojure maps with keyword keys"
    (let [hm (doto (HashMap.) (.put "x" 10) (.put "y" 20))]
      (is (= {:x 10 :y 20} (types/->clj hm))))))

(deftest test-xmlrpc-collection-coercion
  (testing "Vectors become Object[] and back"
    (let [arr (types/->xmlrpc [1 2 3])]
      (is (.isArray (class arr)))
      (is (= [1 2 3] (types/->clj arr)))))

  (testing "Nested structures round-trip"
    (let [data {:items [1 2 3] :meta {:key "val"}}
          rt   (types/->clj (types/->xmlrpc data))]
      (is (= {:items [1 2 3] :meta {:key "val"}} rt)))))

(deftest test-xmlrpc-date-coercion
  (testing "java.util.Date passes through"
    (let [d (Date.)]
      (is (= d (types/->xmlrpc d)))
      (is (= d (types/->clj d)))))

  (testing "Instant converts to Date"
    (let [i (java.time.Instant/now)
          d (types/->xmlrpc i)]
      (is (instance? Date d))
      (is (= (.toEpochMilli i) (.getTime d))))))

(deftest test-xmlrpc-integer-widening
  (testing "Integers within 32-bit range stay as int"
    (is (instance? Integer (types/->xmlrpc 42))))
  (testing "Longs outside 32-bit range stay as Long (i8 extension)"
    (is (instance? Long (types/->xmlrpc (long Long/MAX_VALUE))))))

(deftest test-xmlrpc-byte-array-passthrough
  (testing "byte[] passes through for base64"
    (let [bs (.getBytes "hello")]
      (is (identical? bs (types/->xmlrpc bs))))))

;; ---------------------------------------------------------------------------
;; Server integration tests
;; ---------------------------------------------------------------------------

(def ^:private test-port 9876)

(def ^:private test-handlers
  {"math.add"      (fn [a b] (+ a b))
   "math.sub"      (fn [a b] (- a b))
   "math.mul"      (fn [a b] (* a b))
   "math.div"      (fn [a b]
                     (when (zero? b)
                       (throw (ex-info "Division by zero" {})))
                     (double (/ a b)))
   "echo"          identity
   "echo.many"     (fn [& args] (vec args))
   "struct.mirror" (fn [m] m)
   "array.sum"     (fn [xs] (reduce + xs))
   "bool.not"      not
   "greet"         (fn [name] (str "Hello, " name "!"))
   "void"          (fn [] nil)})

(def ^:private test-server (atom nil))
(def ^:private test-client (atom nil))

(use-fixtures :once
  (fn [run-tests]
    (let [srv (server/start! {:port     test-port
                              :bind     "127.0.0.1"
                              :handlers test-handlers
                              :extensions? true})
          ;; Small delay for the server to bind
          _   (Thread/sleep 200)
          cli (client/client (str "http://127.0.0.1:" test-port "/xmlrpc")
                             {:extensions? true})]
      (reset! test-server srv)
      (reset! test-client cli)
      (try
        (run-tests)
        (finally
          (server/stop! srv)
          (reset! test-server nil)
          (reset! test-client nil))))))

(deftest test-basic-call
  (let [c @test-client]
    (testing "Basic arithmetic"
      (is (= 5   (xmlrpc/call c "math.add" 2 3)))
      (is (= 7   (xmlrpc/call c "math.sub" 10 3)))
      (is (= 12  (xmlrpc/call c "math.mul" 3 4)))
      (is (= 2.5 (xmlrpc/call c "math.div" 5 2))))))

(deftest test-string-calls
  (let [c @test-client]
    (testing "String arguments and return"
      (is (= "Hello, World!" (xmlrpc/call c "greet" "World"))))))

(deftest test-echo
  (let [c @test-client]
    (testing "Echo preserves types"
      (is (= 42      (xmlrpc/call c "echo" 42)))
      (is (= "hello" (xmlrpc/call c "echo" "hello")))
      (is (= true    (xmlrpc/call c "echo" true))))))

(deftest test-struct-roundtrip
  (let [c @test-client]
    (testing "Struct / map round-trip"
      (is (= {:a 1 :b "two" :c true}
             (xmlrpc/call c "struct.mirror" {:a 1 :b "two" :c true}))))))

(deftest test-array-operations
  (let [c @test-client]
    (testing "Array / vector arguments"
      (is (= 15 (xmlrpc/call c "array.sum" [1 2 3 4 5]))))))

(deftest test-boolean-operations
  (let [c @test-client]
    (testing "Boolean arguments and return"
      (is (= false (xmlrpc/call c "bool.not" true)))
      (is (= true  (xmlrpc/call c "bool.not" false))))))

(deftest test-system-list-methods
  (let [c @test-client]
    (testing "system.listMethods returns all registered methods"
      (let [methods (xmlrpc/list-methods c)]
        (is (vector? methods))
        (is (some #{"math.add"} methods))
        (is (some #{"system.listMethods"} methods))))))

(deftest test-error-handling
  (let [c @test-client]
    (testing "XML-RPC fault produces ex-info"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"XML-RPC fault"
                            (xmlrpc/call c "nonexistent.method"))))))

(deftest test-async-call
  (let [c @test-client]
    (testing "Async call returns correct result"
      (let [fut (xmlrpc/call-async c "math.add" 10 20)]
        (is (= 30 (deref fut 5000 :timeout)))))))

(deftest test-call-with-overrides
  (let [c @test-client]
    (testing "call-with works with per-request config"
      ;; Just ensure it doesn't break — the timeout won't matter for localhost
      (is (= 8 (client/call-with c {:reply-timeout 5000} "math.add" 3 5))))))

;; ---------------------------------------------------------------------------
;; Hot-reload tests
;; ---------------------------------------------------------------------------

(deftest test-hot-reload
  (let [srv @test-server
        c   @test-client]
    (testing "Merge new handlers at runtime"
      (server/update-handlers! srv {"math.pow" (fn [base exp]
                                                  (long (Math/pow base exp)))}
                               :merge)
      (is (= 8 (xmlrpc/call c "math.pow" 2 3)))
      ;; Original handlers still work
      (is (= 5 (xmlrpc/call c "math.add" 2 3))))

    (testing "Remove a handler"
      (server/remove-handler! srv "math.pow")
      (is (thrown? Exception (xmlrpc/call c "math.pow" 2 3))))))

;; ---------------------------------------------------------------------------
;; Namespace handler registration tests
;; ---------------------------------------------------------------------------

(deftest test-ns-handlers
  (testing "ns-handlers extracts public fns"
    (let [h (server/ns-handlers 'clojure.string "str")]
      (is (string? (key (first h))))
      (is (contains? h "str.upper-case"))
      (is (fn? (get h "str.upper-case"))))))

;; ---------------------------------------------------------------------------
;; Middleware tests
;; ---------------------------------------------------------------------------

(deftest test-middleware-composition
  (testing "apply-middleware wraps all handlers"
    (let [call-log (atom [])
          logging  (fn [f]
                     (fn [& args]
                       (swap! call-log conj args)
                       (apply f args)))
          handlers (server/apply-middleware
                     {"add" + "sub" -}
                     [logging])]
      ;; Call the wrapped handlers directly
      (is (= 5 ((get handlers "add") 2 3)))
      (is (= 7 ((get handlers "sub") 10 3)))
      (is (= 2 (count @call-log))))))
