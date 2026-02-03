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

(ns clj-xmlrpc.types
  "Bidirectional type coercion between Clojure and XML-RPC.

  XML-RPC Type      Java Type       Clojure Type
  ─────────────────────────────────────────────────
  int / i4          Integer         long
  i8 (ext)          Long            long
  boolean           Boolean         boolean
  string            String          string / keyword
  double            Double          double
  dateTime.iso8601  java.util.Date  Date / Instant
  base64            byte[]          byte[]
  struct            HashMap         map (keyword keys)
  array             Object[]        vector
  nil (ext)         null            nil

  Two core operations:

    (->xmlrpc val)  — Clojure → XML-RPC (outbound, before sending)
    (->clj    val)  — XML-RPC → Clojure (inbound, after receiving)"
  (:import [java.util Date HashMap List Map Map$Entry]
           [java.time Instant LocalDateTime ZoneOffset]))

;; ---------------------------------------------------------------------------
;; Clojure → XML-RPC
;; ---------------------------------------------------------------------------

(defprotocol XmlRpcWritable
  "Convert a Clojure value into its XML-RPC–compatible Java representation."
  (->xmlrpc [v]))

(extend-protocol XmlRpcWritable
  nil
  (->xmlrpc [_] nil)

  String
  (->xmlrpc [s] s)

  Boolean
  (->xmlrpc [b] b)

  Integer
  (->xmlrpc [n] n)

  Long
  (->xmlrpc [n]
    ;; XML-RPC `int` is 32-bit.  Fit when possible; overflow → Long (i8 ext).
    (if (<= Integer/MIN_VALUE n Integer/MAX_VALUE)
      (int n)
      n))

  Short
  (->xmlrpc [n] (int n))

  Byte
  (->xmlrpc [n] (int n))

  Double
  (->xmlrpc [n] n)

  Float
  (->xmlrpc [n] (double n))

  clojure.lang.BigInt
  (->xmlrpc [n] (->xmlrpc (long n)))

  java.math.BigInteger
  (->xmlrpc [n] (->xmlrpc (.longValueExact n)))

  java.math.BigDecimal
  (->xmlrpc [n] (.doubleValue n))

  clojure.lang.Ratio
  (->xmlrpc [r] (double r))

  Date
  (->xmlrpc [d] d)

  Instant
  (->xmlrpc [i] (Date/from i))

  LocalDateTime
  (->xmlrpc [ldt] (Date/from (.toInstant ldt ZoneOffset/UTC)))

  clojure.lang.Keyword
  (->xmlrpc [k]
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k)))

  clojure.lang.Symbol
  (->xmlrpc [s] (str s))

  clojure.lang.IPersistentMap
  (->xmlrpc [m]
    (let [hm (HashMap. (count m))]
      (doseq [[k v] m]
        (.put hm
              (cond
                (keyword? k) (->xmlrpc k)
                (string? k)  k
                :else         (str k))
              (->xmlrpc v)))
      hm))

  clojure.lang.Sequential
  (->xmlrpc [coll]
    (object-array (map ->xmlrpc coll)))

  clojure.lang.IPersistentSet
  (->xmlrpc [s]
    (object-array (map ->xmlrpc s)))

  Object
  (->xmlrpc [o] o))

;; byte[] passes through unchanged — XML-RPC base64.
(extend (Class/forName "[B")
  XmlRpcWritable
  {:->xmlrpc identity})

;; ---------------------------------------------------------------------------
;; XML-RPC → Clojure
;; ---------------------------------------------------------------------------

(defn ->clj
  "Convert an XML-RPC Java value into an idiomatic Clojure value.

  Coercion rules:
    HashMap / Map  →  persistent map with keyword keys
    Object[]       →  vector
    List           →  vector
    Integer        →  long
    null           →  nil
    everything else passes through."
  [x]
  (cond
    (nil? x)
    nil

    (instance? Map x)
    (persistent!
      (reduce
        (fn [acc ^Map$Entry entry]
          (assoc! acc
                  (keyword (str (.getKey entry)))
                  (->clj (.getValue entry))))
        (transient {})
        (.entrySet ^Map x)))

    ;; Object[] — the common XML-RPC array representation
    (and (some? x) (.isArray (class x)))
    (mapv ->clj x)

    (instance? List x)
    (mapv ->clj x)

    ;; Widen Integer → long for Clojure arithmetic consistency
    (instance? Integer x)
    (long x)

    :else x))

;; ---------------------------------------------------------------------------
;; Convenience helpers
;; ---------------------------------------------------------------------------

(defn ->instant
  "Convert a `java.util.Date` (from an XML-RPC dateTime) to `java.time.Instant`."
  ^Instant [^Date d]
  (when d (.toInstant d)))

(defn coerce-params
  "Coerce a sequence of Clojure values into an Object[] for XML-RPC."
  ^objects [args]
  (object-array (map ->xmlrpc args)))
