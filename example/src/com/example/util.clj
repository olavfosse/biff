(ns com.example.util
  (:require [cloroutine.core :refer [cr]]))

;; Generators from cloroutine docs
(do
  (def ^:dynamic *gen-tail*)

  (defn gen-seq [gen]
    (lazy-seq (binding [*gen-tail* (gen-seq gen)] (gen))))

  ;; The var name defines the api symbol
  (defn yield [x]
    (cons x *gen-tail*))

  (defn gen-no-op [])

  (defmacro generator [& body]
    `(gen-seq (cr {yield gen-no-op} ~@body nil))))

(comment
  (generator
    (yield 1))

  (generator (doseq [n (range 50)]
               (yield (* n 3))))
  )
