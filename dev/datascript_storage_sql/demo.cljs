(ns datascript-storage-sql.demo
  (:require
   [datascript.core :as d]
   [datascript.storage.sql.core :as storage-sql]
   [goog.object :as gobj]))

(defn log [msg]
  (let [logs (.getElementById js/document "logs")]
    (when logs
      (set! (.-innerHTML logs) (str (.-innerHTML logs) msg "\n")))
    (js/console.log msg)))

;; Helper to access SqlStorage implementation
(defn call-store [storage addr+data]
  (storage-sql/store-impl (:datasource storage) (:opts storage) addr+data))

(defn call-restore [storage addr]
  (storage-sql/restore-impl (:datasource storage) (:opts storage) addr))

(defn call-list [storage]
  (storage-sql/list-impl (:datasource storage) (:opts storage)))

;; Custom storage handler
(defn my-storage-handler [storage tx-report]
  (let [tx-data (:tx-data tx-report)]
    (when (seq tx-data)
      (log (str "Persisting " (count tx-data) " datoms..."))
      (let [addr (js/Date.now)]
        (try
          (call-store storage [[addr tx-data]])
          (log "Persisted successfully.")
          (catch :default e
            (log (str "Error persisting: " e))))))))

(defn restore-from-storage [storage schema]
  (log "Restoring from storage...")
  (let [addrs (call-list storage)
        all-datoms (atom [])]
    (log (str "Found " (count addrs) " segments."))
    (doseq [addr addrs]
      (let [segment (call-restore storage addr)]
        (when segment
          (swap! all-datoms concat segment))))
    (log (str "Total restored datoms: " (count @all-datoms)))
    (d/init-db @all-datoms schema)))

(defn run-demo [sqlite3]
  (log "SQLite3 loaded.")

  (try
    (let [oo1 (gobj/get sqlite3 "oo1")]
      (if oo1
        (log "oo1 found.")
        (log "oo1 NOT found."))

      (let [DB (gobj/get oo1 "DB")
            db (new DB "demo.db" "ct")]
        (log "DB instance created.")

        (let [storage (storage-sql/make db {:dbtype :sqlite})]
          (log "Storage created.")

          (let [schema {:name {:db/unique :db.unique/identity}}]

            ;; 1. Restore/Initialize DB
            (let [stored-db (restore-from-storage storage schema)
                  conn (d/conn-from-db stored-db)]

              (log "DataScript connection created and restored.")
              (log (str "Initial entities: " (count (d/datoms @conn :eavt))))

              ;; 2. Attach custom listener
              (d/listen! conn :persistence (partial my-storage-handler storage))
              (log "Custom storage listener attached.")

              ;; 3. Transact
              (d/transact! conn [{:name "Ivan" :age 30}
                                 {:name "Petr" :age 25}])
              (log "Transacted data.")

              (let [db @conn
                    ivan (d/entity db [:name "Ivan"])
                    petr (d/entity db [:name "Petr"])]
                (log (str "Ivan: " (:age ivan)))
                (log (str "Petr: " (:age petr)))

                ;; 4. Verify persistence by restoring into a new DB
                (log "Verifying persistence...")
                (let [conn2 (d/conn-from-db (restore-from-storage storage schema))]
                   (log (str "Restored DB entities: " (count (d/datoms @conn2 :eavt))))

                   (let [ivan-ent (first (d/q '[:find ?e :where [?e :name "Ivan"]] @conn2))]
                     (if ivan-ent
                       (let [ivan2 (d/entity @conn2 (first ivan-ent))]
                          (log (str "Restored Ivan age: " (:age ivan2)))
                          (if (= 30 (:age ivan2))
                            (log "SUCCESS: Data persisted and restored correctly.")
                            (log "FAILURE: Ivan found but age mismatch.")))
                       (log "FAILURE: Ivan not found in restored DB."))))))))))
    (catch :default e
      (log (str "Error in run-demo: " e))
      (js/console.error e))))

(defn start-sqlite []
  (if-let [init-fn js/window.sqlite3InitModule]
    (-> (init-fn)
        (.then (fn [sqlite3]
                 (run-demo sqlite3)))
        (.catch (fn [err]
                  (log (str "Error initializing sqlite3: " err)))))
    (log "Error: window.sqlite3InitModule not found even after ready event.")))

(defn init []
  (log "Starting demo...")
  (if js/window.sqlite3InitModule
    (start-sqlite)
    (do
      (log "Waiting for sqlite3-ready event...")
      (js/window.addEventListener "sqlite3-ready" start-sqlite))))
