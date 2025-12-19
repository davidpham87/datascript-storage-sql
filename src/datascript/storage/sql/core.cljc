(ns datascript.storage.sql.core
  #?(:cljs (:require-macros [datascript.storage.sql.core :refer [with-conn with-tx]]))
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [datascript.core :as d]
    [datascript.storage :as storage]
    #?(:cljs [goog.object :as gobj]))
  #?(:clj
     (:import
       [java.lang AutoCloseable]
       [java.lang.reflect InvocationHandler Method Proxy]
       [java.sql Connection DriverManager ResultSet SQLException Statement]
       [javax.sql DataSource]
       [java.util.concurrent.locks Condition Lock ReentrantLock])))

(defmacro with-conn [[conn datasource] & body]
  (if (:ns &env)
    ;; CLJS expansion
    `(let [~conn ~datasource]
       ~@body)
    ;; CLJ expansion
    (let [conn (vary-meta conn assoc :tag 'java.sql.Connection)]
      `(let [^javax.sql.DataSource datasource# ~datasource]
         (with-open [~conn (.getConnection datasource#)]
           (locking ~conn
             ~@body))))))

(defmacro with-tx [conn & body]
  (if (:ns &env)
    ;; CLJS expansion
    `(let [conn# ~conn]
       (try
         (.exec conn# "BEGIN TRANSACTION")
         ~@body
         (.exec conn# "COMMIT")
         (catch :default e#
           (try
             (.exec conn# "ROLLBACK")
             (catch :default ee#
               nil))
           (throw e#))))
    ;; CLJ expansion
    `(let [conn# ~conn]
       (try
         (.setAutoCommit conn# false)
         ~@body
         (.commit conn#)
         (catch Exception e#
           (try
             (.rollback conn#)
             (catch Exception ee#
               (.addSuppressed e# ee#)))
           (throw e#))
         (finally
           (.setAutoCommit conn# true))))))

(defn execute!
  #?(:clj [^Connection conn sql]
     :cljs [conn sql])
  #?(:clj
     (with-open [stmt (.createStatement conn)]
       (.execute stmt sql))
     :cljs
     (.exec conn sql)))

(defmulti upsert-dml :dbtype)

(defmethod upsert-dml :h2 [opts]
  (str "merge into " (:table opts) " key (addr) values (?, ?)"))

(defmethod upsert-dml :mysql [opts]
  (str
    "insert into " (:table opts) " (addr, content) "
    "values (?, ?) "
    "ON DUPLICATE KEY UPDATE content = ?"))

(defmethod upsert-dml :default [opts]
  (str
    "insert into " (:table opts) " (addr, content) "
    "values (?, ?) "
    "on conflict(addr) do update set content = ?"))

(defn store-impl
  #?(:clj [^Connection conn opts addr+data-seq]
     :cljs [conn opts addr+data-seq])
  (let [{:keys [table binary? freeze-str freeze-bytes batch-size]} opts
        sql (upsert-dml opts)
        cnt (count (re-seq #"\?" sql))]
    (with-tx conn
      #?(:clj
         (with-open [stmt (.prepareStatement conn sql)]
           (doseq [part (partition-all batch-size addr+data-seq)]
             (doseq [[addr data] part]
               (.setLong stmt 1 addr)
               (if binary?
                 (let [content ^bytes (freeze-bytes data)]
                   (.setBytes stmt 2 content)
                   (when (= 3 cnt)
                     (.setBytes stmt 3 content)))
                 (let [content ^String (freeze-str data)]
                   (.setString stmt 2 content)
                   (when (= 3 cnt)
                     (.setString stmt 3 content))))
               (.addBatch stmt))
             (.executeBatch stmt)))
         :cljs
         ;; JS (@sqlite.org/sqlite-wasm) implementation
         (doseq [part (partition-all batch-size addr+data-seq)]
           (doseq [[addr data] part]
             (let [params (if binary?
                            (let [content (freeze-bytes data)]
                              (if (= 3 cnt)
                                [addr content content]
                                [addr content]))
                            (let [content (freeze-str data)]
                              (if (= 3 cnt)
                                [addr content content]
                                [addr content])))]
               ;; .exec(sql, {bind: params})
               (.exec conn sql (clj->js {:bind params})))))))))

(defn restore-impl
  #?(:clj [^Connection conn opts addr]
     :cljs [conn opts addr])
  (let [{:keys [table binary? thaw-str thaw-bytes]} opts
        sql (str "select content from " table " where addr = ?")]
    #?(:clj
       (with-open [stmt (.prepareStatement conn sql)]
         (.setLong stmt 1 addr)
         (with-open [rs (.executeQuery stmt)]
           (when (.next rs)
             (if binary?
               (thaw-bytes (.getBytes rs 1))
               (thaw-str (.getString rs 1))))))
       :cljs
       (let [stmt (.prepare conn sql)]
         (try
           (.bind stmt (clj->js [addr]))
           (when (.step stmt)
             ;; get([]) returns array of column values
             (let [val (.get stmt (clj->js []))]
               (if binary?
                 (thaw-bytes (aget val 0))
                 (thaw-str (aget val 0)))))
           (finally
             (.finalize stmt)))))))

(defn list-impl
  #?(:clj [^Connection conn opts]
     :cljs [conn opts])
  (let [sql (str "select addr from " (:table opts))]
    #?(:clj
       (with-open [stmt (.prepareStatement conn sql)
                   rs   (.executeQuery stmt)]
         (loop [res (transient [])]
           (if (.next rs)
             (recur (conj! res (.getLong rs 1)))
             (persistent! res))))
       :cljs
       (let [stmt (.prepare conn sql)]
         (try
           (loop [res (transient [])]
             (if (.step stmt)
               (recur (conj! res (aget (.get stmt (clj->js [])) 0)))
               (persistent! res)))
           (finally
             (.finalize stmt)))))))

(defn delete-impl
  #?(:clj [^Connection conn opts addr-seq]
     :cljs [conn opts addr-seq])
  (let [sql (str "delete from " (:table opts) " where addr = ?")]
    (with-tx conn
      #?(:clj
         (with-open [stmt (.prepareStatement conn sql)]
           (doseq [part (partition-all (:batch-size opts) addr-seq)]
             (doseq [addr part]
               (.setLong stmt 1 addr)
               (.addBatch stmt))
             (.executeBatch stmt)))
         :cljs
         (doseq [part (partition-all (:batch-size opts) addr-seq)]
           (doseq [addr part]
             ;; .exec(sql, {bind: [addr]})
             (.exec conn sql (clj->js {:bind [addr]}))))))))

(defmulti ddl
  (fn [opts]
    (:dbtype opts)))

(defmethod ddl :sqlite [{:keys [table binary?]}]
  (str
    "create table if not exists " table
    " (addr INTEGER primary key, "
    "  content " (if binary? "BLOB" "TEXT") ")"))

(defmethod ddl :h2 [{:keys [table binary?]}]
  (str
    "create table if not exists " table
    " (addr BIGINT primary key, "
    "  content " (if binary? "BINARY VARYING" "CHARACTER VARYING") ")"))

(defmethod ddl :mysql [{:keys [table binary?]}]
  (str
    "create table if not exists " table
    " (addr BIGINT primary key, "
    "  content " (if binary? "LONGBLOB" "LONGTEXT") ")"))

(defmethod ddl :postgresql [{:keys [table binary?]}]
  (str
    "create table if not exists " table
    " (addr BIGINT primary key, "
    "  content " (if binary? "BYTEA" "TEXT") ")"))

(defmethod ddl :default [{:keys [dbtype]}]
  (throw (IllegalArgumentException. (str "Unsupported :dbtype " (pr-str dbtype)))))

(defn merge-opts [opts]
  (let [opts (merge
               {:freeze-str pr-str
                :thaw-str   edn/read-string
                :batch-size 1000
                :table      "datascript"}
               opts)
        opts (assoc opts
               :binary? (boolean (and (:freeze-bytes opts) (:thaw-bytes opts))))]
    (merge {:ddl (ddl opts)} opts)))

(defn make
  "Create new DataScript storage from javax.sql.DataSource (JVM) or sqlite-wasm oo1.DB (CLJS).

   Mandatory opts:

     :dbtype       :: keyword, one of :h2, :mysql, :postgresql or :sqlite

   Optional opts:

     :batch-size   :: int, default 1000
     :table        :: string, default \"datascript\"
     :ddl          :: custom DDL to create :table. Must have `addr, int` and `content, text` columns
     :freeze-str   :: (fn [any]) -> str, serialize DataScript segments, default pr-str
     :thaw-str     :: (fn [str]) -> any, deserialize DataScript segments, default clojure.edn/read-string
     :freeze-bytes :: (fn [any]) -> bytes, same idea as freeze-str, but for binary serialization
     :thaw-bytes   :: (fn [bytes]) -> any

   :freeze-str and :thaw-str, :freeze-bytes and :thaw-bytes should come in pairs, and are mutually exclusive
   (it’s either binary or string serialization)"
  ([datasource]
   #?(:clj {:pre [(instance? DataSource datasource)]})
   (make datasource {}))
  ([datasource opts]
   (let [opts (merge-opts opts)]
     (with-conn [conn datasource]
       (execute! conn (:ddl opts)))
     (with-meta
       {:datasource datasource}
       {'datascript.storage/-store
        (fn [_ addr+data-seq]
          (with-conn [conn datasource]
            (store-impl conn opts addr+data-seq)))

        'datascript.storage/-restore
        (fn [_ addr]
          (with-conn [conn datasource]
            (restore-impl conn opts addr)))

        'datascript.storage/-list-addresses
        (fn [_]
          (with-conn [conn datasource]
            (list-impl conn opts)))

        'datascript.storage/-delete
        (fn [_ addr-seq]
          (with-conn [conn datasource]
            (delete-impl conn opts addr-seq)))}))))

(defn close
  "If storage was created with DataSource that also implements AutoCloseable,
   it will close that DataSource"
  [storage]
  (let [datasource (:datasource storage)]
    #?(:clj
       (when (instance? AutoCloseable datasource)
         (.close ^AutoCloseable datasource))
       :cljs
       (.close datasource))))

#?(:clj
   (defmacro with-lock [lock & body]
     `(let [^Lock lock# ~lock]
        (try
          (.lock lock#)
          ~@body
          (finally
            (.unlock lock#))))))

#?(:clj
   (defrecord Pool [*atom ^Lock lock ^Condition condition ^DataSource datasource opts]
     AutoCloseable
     (close [_]
       (let [[{:keys [taken free]} _] (swap-vals! *atom #(-> % (update :taken empty) (update :idle empty)))]
         (doseq [conn (concat free taken)]
           (try
             (.close ^Connection conn)
             (catch Exception e
               (.printStackTrace e))))))

     DataSource
     (getConnection [this]
       (let [^Connection conn (with-lock lock
                                (loop []
                                  (let [atom @*atom]
                                    (cond
                                      ;; idle connections available
                                      (> (count (:idle atom)) 0)
                                      (let [conn (peek (:idle atom))]
                                        (swap! *atom #(-> %
                                                        (update :taken conj conn)
                                                        (update :idle pop)))
                                        conn)

                                      ;; has space for new connection
                                      (< (count (:taken atom)) (:max-conn opts))
                                      (let [conn (.getConnection datasource)]
                                        (swap! *atom update :taken conj conn)
                                        conn)

                                      ;; already at limit
                                      :else
                                      (do
                                        (.await condition)
                                        (recur))))))
             *closed? (volatile! false)]
         (Proxy/newProxyInstance
           (.getClassLoader Connection)
           (into-array Class [Connection])
           (reify InvocationHandler
             (invoke [this proxy method args]
               (let [method ^Method method]
                 (case (.getName method)
                   "close"
                   (do
                     (when-not (.getAutoCommit conn)
                       (.rollback conn)
                       (.setAutoCommit conn true))
                     (vreset! *closed? true)
                     (with-lock lock
                       (if (< (count (:idle @*atom)) (:max-idle-conn opts))
                         ;; normal return to pool
                         (do
                           (swap! *atom #(-> %
                                           (update :taken disj conn)
                                           (update :idle conj conn)))
                           (.signal condition))
                         ;; excessive idle conn
                         (do
                           (swap! *atom update :taken disj conn)
                           (.close conn))))
                     nil)

                   "isClosed"
                   (or @*closed? (.invoke method conn args))

                   ;; else
                   (.invoke method conn args))))))))))

#?(:clj
   (defn pool
     "Simple connection pool.

      Accepts javax.sql.DataSource, returns javax.sql.DataSource implementation
      that creates java.sql.Connection on demand, up to :max-conn, and keeps up
      to :max-idle-conn when no demand.

      Implements AutoCloseable, which closes all pooled connections."
     (^DataSource [datasource]
       (pool datasource {}))
     (^DataSource [datasource opts]
       {:pre [(instance? DataSource datasource)]}
       (let [lock (ReentrantLock.)]
         (Pool.
           (atom {:taken #{}
                  :idle  []})
           lock
           (.newCondition lock)
           datasource
           (merge
             {:max-idle-conn 4
              :max-conn      10}
             opts))))))
