(ns methodical.util.trace
  (:require [clojure.string :as str]
            [methodical.interface :as i]
            [methodical.util :as u]
            [pretty.core :as pretty]
            #?(:clj [puget.printer :as puget])))

(set! *warn-on-reflection* true)

(def ^:dynamic *color*
  "Whether or not to print the trace in color. True by default, unless the env var `NO_COLOR` is true."
  (if-let [env-var-value (#?(:cljr Environment/GetEnvironmentVariable :default System/getenv) "NO_COLOR")]
    (complement (#?(:cljr Boolean/Parse :default Boolean/parseBoolean) env-var-value))
    true))

(def ^:dynamic *pprinter*
  "Pretty-printer function to use for pretty printing forms in the trace. You can bind this to override the default
  pretty-printing functions (see below)."
  nil)

;; Wrap a String with `(->Literal s)` to print it literally instead of wrapping in double quotes.
(defrecord Literal [s])

(def ^:private default-print-handlers
  {Literal (fn [_printer literal]
             [:text (:s literal)])})

(defn- default-color-printer [x]
  ;; don't print in black. I can't see it
  #?(:cljr (print x)
     :default (puget/cprint x {:color-scheme   {:nil nil}
                   :print-handlers default-print-handlers})))

(defn- default-boring-printer [x]
  #?(:cljr (clojure.pprint/pprint x) 
     :default (puget/pprint x {:print-handlers default-print-handlers})))

(defn- pprint
  "Pretty print a form `x`."
  [x]
  ((or *pprinter*
       (if *color*
         default-color-printer
         default-boring-printer)) x))

(def ^:private ^:dynamic *trace-level*
  "Current depth of the trace."
  0)

(def ^:private ^:dynamic *trace-indent*
  "Number of spaces to indent lines when printing stuff."
  0)

(defn- trace-print-indent []
  (doseq [_ (range *trace-indent*)]
    (print " ")))

(defn- trace-println [& args]
  (let [[first-line & more] (str/split-lines (str/trim (with-out-str (apply println args))))]
    (println first-line)
    (doseq [line more]
      (trace-print-indent)
      (println line))))

(defn- describe-method [a-method]
  (let [{:keys [qualifier dispatch-value]} (meta a-method)]
    (->Literal (if qualifier
                 (format "#aux-method<%s %s>" (pr-str qualifier) (pr-str dispatch-value))
                 (format "#primary-method<%s>" (pr-str dispatch-value))))))

(defn- describe [x]
  (cond
    (::description (meta x))                  (::description (meta x))
    (:dispatch-value (meta x))                (describe-method x)
    (:methodical/combined-method? (meta x))   (->Literal "#combined-method")
    (fn? x)                                   (->Literal (pr-str x))
    (instance? pretty.core.PrettyPrintable x) (pretty/pretty x)
    :else                                     x))

(defn- trace-method [m]
  (fn [& args]
    (trace-print-indent)
    (printf (format "%d: " *trace-level*))
    (binding [*trace-indent* (+ *trace-indent* 3)]
      (trace-println (with-out-str (pprint (map describe (cons m args))))))
    (let [result (binding [*trace-level*  (inc *trace-level*)
                           *trace-indent* (+ *trace-indent* 2)]
                   (apply m args))]
      (trace-print-indent)
      (printf "%d> " *trace-level*)
      (binding [*trace-indent* (+ *trace-indent* 3)]
        (trace-println (with-out-str (pprint (describe result)))))
      result)))

(defn- trace-primary-method [primary-method]
  (-> (trace-method primary-method)
      (with-meta (meta primary-method))))

(defn- trace-primary-methods [primary-methods]
  (map trace-primary-method primary-methods))

(defn- trace-aux-method [aux-method]
  (-> (trace-method aux-method)
      (with-meta (meta aux-method))))

(defn- trace-aux-methods [qualifier->ms]
  (into {} (for [[qualifier aux-methods] qualifier->ms]
             [qualifier (for [aux-method aux-methods]
                          (trace-aux-method (vary-meta aux-method assoc :qualifier qualifier)))])))

(defn trace*
  "Function version of [[trace]] macro. The only difference is this doesn't capture the form of `multifn` passed to
  [[trace]], and thus can't usually generate a pretty description for the top-level form."
  [multifn & args]
  (let [dispatch-value  (apply u/dispatch-value multifn args)
        primary-methods (trace-primary-methods (u/matching-primary-methods multifn dispatch-value))
        aux-methods     (trace-aux-methods (u/matching-aux-methods multifn dispatch-value))
        combined        (-> (i/combine-methods multifn primary-methods aux-methods)
                            (with-meta (meta multifn))
                            trace-method)]
    (apply combined args)))

(defmacro trace
  "Instrument a multimethod `multifn`, then invoke it; calls to its primary and aux methods and their results are
  printed to *out*`. Returns same result as untraced version would have returned. Prints trace in color by default,
  but you can disable this by binding [[*color*]] to `false`.

  Method calls are printed with `n:`, where `n` is the current depth of the trace; the result of each method call is
  printed with a corresponding `n>`:

  ```clj
  (trace/trace my-fn 1 {})
  ;; ->
  0: (my-fn 1 {})
    1: (#primary-method<:default> nil 1 {})
    1> {:x 1}
    1: (#aux-method<:after [java.lang.Object :default]> 1 {:x 1})
    1> {:object? true, :x 1}
  0> {:object? true, :x 1}
  ```"
  [multifn & args]
  `(trace* (vary-meta ~multifn assoc ::description '~multifn)
           ~@args))
