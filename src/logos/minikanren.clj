(ns logos.minikanren
  (:refer-clojure :exclude [reify inc == take])
  (:use [clojure.pprint :only [pprint]]))

;; =============================================================================
;; Logic Variables

(deftype lvarT [name s]
  Object
  (toString [this] (str "<lvar:" name ">")))

(defn ^lvarT lvar
  ([] (lvarT. (gensym) nil))
  ([name] (lvarT. name nil))
  ([name s] (lvarT. name s)))

(defmethod print-method lvarT [x writer]
  (.write writer (str "<lvar:" (.name ^lvarT x) ">")))

(deftype rest-lvarT [name s]
  Object
  (toString [this] (str "<lvar:" name ">")))

(defn ^rest-lvarT rest-lvar
  ([] (rest-lvarT. (gensym) nil))
  ([name] (rest-lvarT. name nil))
  ([name s] (rest-lvarT. name s)))

(defmethod print-method rest-lvarT [x writer]
  (.write writer (str "<lvar:" (.name ^rest-lvarT x) ">")))

(defn lvar? [x]
  (or (instance? lvarT x) (instance? rest-lvarT x)))

(defn rest-lvar? [x]
  (instance? rest-lvarT x))

(defn rest-lvar-sym? [x]
  (= (first (str x)) \&))

;; =============================================================================
;; Pairs

(defprotocol IPair
  (lhs [this])
  (rhs [this]))

(deftype pairT [lhs rhs]
  IPair
  (lhs [this] lhs)
  (rhs [this] rhs)
  clojure.lang.ISeq
  (first [this] lhs)
  (more [this] rhs)
  Object
  (toString [this] (str "(" lhs " . " rhs ")")))

(defn ^pairT pair [lhs rhs]
  (pairT. lhs rhs))

(defn pair? [x]
  (instance? pairT x))

(defmethod print-method pairT [x w]
  (.write w (str "(" (lhs x)  " . " (rhs x)  ")")))

;; =============================================================================
;; LCons

(defprotocol LConsSeq
  (lfirst [this])
  (lnext [this]))

;; TODO: clean up the printing code

(defprotocol LConsPrint
  (toShortString [this]))

(deftype LCons [a d]
  LConsSeq
  (lfirst [_] a)
  (lnext [_] d)
  LConsPrint
  (toShortString [this]
                 (cond
                  (lvar? d) (str a " . " d )
                  (instance? LCons d) (str a " " (toShortString d))))
  Object
  (toString [this] (cond
                    (lvar? d) (str "(" a " . " d ")")
                    (instance? LCons d) (str "(" a " " (toShortString d) ")")))
  ;; equiv
  ;; equals
  ;; hashCode
  )

(defmethod print-method LCons [x writer]
  (.write writer (str x)))

;; TODO: define lconj
;; reverse the arguments and we can avoid the conditional here
;; just dispatch on type

(defn lcons [a d]
  (if (seq? d)
    (cons a d)
    (LCons. a d)))

(defn lcons? [x]
  (instance? LCons x))

(extend-type clojure.lang.PersistentList
  LConsSeq
  (lfirst [this] (first this))
  (lnext [this] (next this))
  (lseq [this] (seq this)))

(extend-type clojure.lang.PersistentVector
  LConsSeq
  (lfirst [this] (first this))
  (lnext [this] (next this))
  (lseq [this] (seq this)))

(extend-type clojure.lang.PersistentArrayMap
  LConsSeq
  (lfirst [this] (first this))
  (lnext [this] (next this))
  (lseq [this] (seq this)))

(extend-type clojure.lang.PersistentHashMap
  LConsSeq
  (lfirst [this] (first this))
  (lnext [this] (next this))
  (lseq [this] (seq this)))

(extend-type clojure.lang.PersistentHashSet
  LConsSeq
  (lfirst [this] (first this))
  (lnext [this] (next this))
  (lseq [this] (seq this)))

;; =============================================================================
;; Unification

(declare lookup)
(declare lookup*)
(declare ext-no-check)
(declare ext)
(declare unify)
(declare length)
(declare empty-s)

(defn print-identity [v]
  (println v) v)

;; TODO: convert to macro

(defn lcoll? [x]
  (or (lcons? x)
      (and (coll? x) (seq x))))

(defn unify*
  [s u v]
  (let [u (lookup s u)
        v (lookup s v)]
    (cond
     (identical? u v) s
     (lvar? u) (if (lvar? v)
                 (ext-no-check s u v)
                 (ext s u v))
     (lvar? v) (ext s v u)
     (and (lcoll? u) (lcoll? v)) (let [uf (lfirst u)
                                       ur (lnext u)
                                       vf (lfirst v)
                                       vr (lnext v)]
                                   (cond
                                    (rest-lvar? uf) (unify* s uf v)
                                    (rest-lvar? vf) (unify* s vf u)
                                    :else (let [s (unify* s uf vf)]
                                            (and s (unify* s ur vr)))))
     (= u v) s
     :else false)))

;; =============================================================================
;; Reification

;; OPTIMIZE: add interfaces to dispatch on the type of v ?
;; means we would need to reverse the arguments

(defn reify-lookup [s v]
  (let [v' (lookup s v)]
    (cond
     (lvar? v') v'
     (lcoll? v') (let [vseq (if (map? v') (reduce concat v') v')
                       vf (reify-lookup s (lfirst vseq))
                       vn (reify-lookup s (lnext vseq))
                       r (lcons vf vn)]
                   (cond
                    (vector? v') (vec r)
                    (map? v') (apply hash-map r)
                    (set? v') (set r)
                    :else r))
     :else v')))

(defn reify-lvar-name [s]
  (symbol (str "_." (length s))))

(defn -reify [s v]
  (let [v (lookup s v)]
    (cond
     (lvar? v) (ext s v (reify-lvar-name s))
     (coll? v) (-reify (-reify s (first v)) (next v))
     :else s)))

(defn reify [s v]
  (let [v (reify-lookup s v)]
    (reify-lookup (-reify empty-s v) v)))

;; =============================================================================
;; Substitutions

(defn lookup* [s v]
  (loop [v v p (find s v) s s ov v]
    (if (nil? p)
      v
      (let [[v v'] p]
        (if (lvar? v')
          (recur v' (find s v') s ov)
          v')))))

(defprotocol ISubstitutions
  (length [this])
  (ext [this x v])
  (ext-no-check [this x v])
  (lookup [this v])
  (unify [this u v]))

(defrecord Substitutions [s s']
  ISubstitutions
  (length [this] (count s'))
  (ext [this x v]
       (if (= (lookup* s x) ::circular)
         nil
         (ext-no-check this x v)))
  (ext-no-check [this x v]
                (Substitutions. (assoc s x v)
                                (conj s' (pair x v))))
  (lookup [this v]
          (lookup* s v))
  (unify [this u v] (unify* this u v)))

(def empty-s (Substitutions. {} []))

(defn to-s [v]
  (let [s (reduce (fn [m [k v]] (assoc m k v)) {} v)
        s' (vec (map (partial apply pair) v))]
    (Substitutions. s s')))

;; =============================================================================
;; Goals and Goal Constructors

(defprotocol IMPlus
  (mplus [this f]))

(defprotocol IBind
  (bind [this g]))

(defmacro mzero [] false)

(defmacro unit [a] a)

(defmacro choice [a f]
  `(pair ~a ~f))

(defmacro inc [e]
  `(fn [] ~e))

(defn succeed [a]
  (unit a))

(defn fail [a]
  (mzero))

(def s# succeed)

(def u# fail)

(defmacro case-inf [& [e _ e0 f' e1 a' e2 [a f] e3]]
  `(let [a-inf# ~e]
     (cond
      (not a-inf#) ~e0
      (fn? a-inf#) (let [~f' a-inf#] ~e1)
      (and (pair? a-inf#) (fn? (rhs a-inf#))) (let [~a (lhs a-inf#)
                                                    ~f (rhs a-inf#)]
                                                ~e3)
      :else (let [~a' a-inf#] ~e2))))

(defmacro == [u v]
  `(fn [a#]
     (if-let [s# (unify a# ~u ~v)]
       (unit s#)
       (mzero))))

(defmacro mplus*
  ([e] e)
  ([e0 & e-rest] `(mplus ~e0 (fn [] (mplus* ~@e-rest)))))

(defn mplus [a-inf f]
  (case-inf a-inf
            false (f)
            f' (inc (mplus (f) f'))
            a (choice a f)
            [a f'] (choice a (fn [] (mplus (f) f')))))

(defn bind-cond-e-clause [s]
  (fn [[g0 & g-rest]]
    `(bind* (~g0 ~s) ~@g-rest)))

(defn bind-cond-e-clauses [s clauses]
  (map (bind-cond-e-clause s) clauses))

(defmacro cond-e [& clauses]
  (let [a (gensym "a")]
   `(fn [~a]
      (inc
       (mplus* ~@(bind-cond-e-clauses a clauses))))))

(defn lvar-bind [sym]
  ((juxt identity
         (fn [s] (if (rest-lvar-sym? s)
                   `(rest-lvar '~s)
                   `(lvar '~s)))) sym))

(defn lvar-binds [syms]
  (reduce concat (map lvar-bind syms)))

(defmacro exist [[& x-rest] g0 & g-rest]
  `(fn [a#]
     (inc
      (let [~@(lvar-binds x-rest)]
        (bind* (~g0 a#) ~@g-rest)))))

(defmacro bind*
  ([e] e)
  ([e g0 & g-rest] `(bind* (bind ~e ~g0) ~@g-rest)))

(defn bind [a-inf g]
  (case-inf a-inf
            false (mzero)
            f (inc (bind (f) g))
            a (g a)
            [a f] (mplus (g a) (fn [] (bind (f) g)))))

;; TODO: find for what reason are putting the value in a vector?

(defmacro run [& [n [x] g0 & g-rest]]
  `(take ~n
         (fn []
           ((exist [~x] ~g0 ~@g-rest
                   (fn [a#]
                     (conj [] (reify a# ~x))))
            empty-s))))

(defn take
  ([n f] (take n f [])) 
  ([n f v]
     (if (and n (zero? n))
       v
       (case-inf (f)
                 false v
                 f (take n f v)
                 a (conj v (first a))
                 [a f] (take (and n (dec n)) f (conj v (first a)))))))

(defmacro run* [& body]
  `(run false ~@body))

(defn sym->lvar [sym]
  (if (rest-lvar-sym? sym)
    `(rest-lvar '~sym)
    `(lvar '~sym)))

(defn trace-lvar [a lvar]
  `(println (format "%5s = %s" (str '~lvar) (reify ~a ~lvar))))

(defmacro trace-lvars [title & lvars]
  (let [a (gensym "a")]
   `(fn [~a]
      (println ~title)
      ~@(map (partial trace-lvar a) lvars)
      (println)
      (unit ~a))))

(defmacro trace-s []
  (let [a (gensym "a")]
   `(fn [~a]
      (println ~a)
      ~a)))

;; =============================================================================
;; Comments and Testing

(comment
  (deftype MZero
    IMPlus
    (mplus [this f])
    IBind
    (bind [this g]))

  (def mzero (MZero.))

  ;; what is the cost of wrapping values ?
  (deftype Unit [x]
    IFn
    (invoke [this] x)
    IMPlus
    (mplus [this f])
    IBind
    (bind [this g]))
  )
