(ns analyze-change-measurements.server.transactor
 (:require [analyze-change-measurements.config :refer [env]]
           [me.raynes.fs :as fs]
           [clojure.tools.logging :refer [debug info]])
 (:import [java.net InetAddress]))

(defn download-datomic
 "When no 'datomic' folder found in project root directory:
 1. download datomic.zip using credentials and datomic-version
 2. extract to root dir and delete the zip file
 3. rename the extracted folder from datomic-pro-<DATOMIC_VERSION> to datomic"
 [{:keys [datomic-version datomic-license-user datomic-license-password]
   :or {datomic-version (-> env :datomic-version)
        datomic-license-user (-> env :datomic-license-user)
        datomic-license-password (-> env :datomic-license-password)}}]
 (when-not (fs/directory? "datomic")
  (info "-----> Installing Datomic " datomic-version)
  (let [credential (str datomic-license-user ":" datomic-license-password)
        download-url (str "https://my.datomic.com/repo/com/datomic/datomic-pro/"
                          datomic-version
                          "/datomic-pro-"
                          datomic-version ".zip")]
   (when-not (fs/file? "datomic.zip")
    (fs/exec "cmd" "/C" (str "curl -L -u " credential " " download-url " > datomic.zip"))))
  (fs/exec "cmd" "/C" "jar xf datomic.zip")
  (fs/delete (fs/file "datomic.zip"))
  (fs/exec "cmd" "/C" "move datomic-pro-* datomic")))

(defn datomic-postgres-table-exist?
 "Creates postgres table if it doesn't exist."
 [& {:keys [database-url jdbc-database-username]
     :or {database-url (-> env :options :database-url)
          jdbc-database-username (-> env :options :jdbc-database-username)}}]
 (info "Checking if postgres table exists yet..")
 (let [table-not-exist (not (clojure.string/includes?
                             (:out (fs/exec "cmd" "/C"
                                            (str "heroku psql -c \""
                                                 "SELECT tablename "
                                                 "FROM pg_catalog.pg_tables "
                                                 "where tablename = 'datomic_kvs';\"")))
                             "(1 row)"))]
  (when (and table-not-exist jdbc-database-username)
   (fs/exec "cmd" "/C"
            (str "heroku psql -c \""
                 "CREATE TABLE datomic_kvs "
                 "(id text NOT NULL,"
                 " rev integer,"
                 " map text,"
                 " val bytea,"
                 " CONSTRAINT pk_id PRIMARY KEY (id)) WITH (OIDS=FALSE); "
                 "ALTER TABLE datomic_kvs OWNER TO " jdbc-database-username ";\""))
   (info "Postgres table created successfully"))
  (when-not table-not-exist
   (info "Postgres table exists, skipping creation"))))

(defprotocol TransactorProtocol
 (configure-properties
  [properties]
  "Create the datomic/config/transactor.properties file from properties."))

(defrecord Sql [jdbc-database-url jdbc-database-username jdbc-database-password
                sql-driver-params datomic-transactor-key]
 TransactorProtocol
 (configure-properties
  [{:keys [jdbc-database-url jdbc-database-username jdbc-database-password
           sql-driver-params datomic-transactor-key]
    :or {jdbc-database-url (-> env :options :jdbc-database-url)
         jdbc-database-username (-> env :options :jdbc-database-username)
         jdbc-database-password (-> env :options :jdbc-database-password)
         sql-driver-params (-> env :options :sql-driver-params)
         datomic-transactor-key (-> env :options :datomic-transactor-key)}}]
  (let [template-properties-url "datomic/config/samples/sql-transactor-template.properties"
        transactor-properties-url "datomic/config/transactor.properties"]
   (fs/delete "datomic/config/transactor.properties")
   (info "-----> Configuring Datomic to connect to HEROKU POSTGRES")
   (doseq [line (map (fn [line]
                      (condp #(clojure.string/includes? %2 %1) line
                       "# pid-file=transactor.pid" "pid-file=transactor.pid"
                       "sql-url=" (str "sql-url=" jdbc-database-url)
                       "sql-user=" (str "sql-user=" jdbc-database-username)
                       "sql-password=" (str "sql-password=" jdbc-database-password)
                       "sql-driver-params=" (str "sql-driver-params=" sql-driver-params)
                       "license-key=" (str "license-key=" datomic-transactor-key)
                       "host=" (str "host="
                                    (or #_(some-> "http://checkip.amazonaws.com" slurp clojure.string/trim)
                                        (.getHostAddress (java.net.InetAddress/getLocalHost))))
                       line))
                     (-> template-properties-url
                         slurp
                         (clojure.string/split #"\n")))]
    (spit transactor-properties-url (str line "\n") :append true)))))

(defrecord Dev [storage-admin-password old-storage-admin-password storage-datomic-password
                old-storage-datomic-password transactor-storage-access datomic-transactor-key]
 TransactorProtocol
 (configure-properties
  [{:keys [storage-admin-password old-storage-admin-password storage-datomic-password
           old-storage-datomic-password transactor-storage-access datomic-transactor-key]
    :or {storage-admin-password (-> env :storage-admin-password)
         old-storage-admin-password (-> env :old-storage-admin-password)
         storage-datomic-password (-> env :storage-datomic-password)
         old-storage-datomic-password (-> env :old-storage-datomic-password)
         transactor-storage-access (-> env :transactor-storage-access)
         datomic-transactor-key (-> env :datomic-transactor-key)}}]
  (let [template-properties-url "datomic/config/samples/dev-transactor-template.properties"
        transactor-properties-url "datomic/config/transactor.properties"]
   (fs/delete "datomic/config/transactor.properties")
   (info "-----> Configuring Datomic to connect to DEV")
   (doseq [line (map (fn [line]
                      (condp #(clojure.string/includes? %2 %1) line
                       "# pid-file=transactor.pid" "pid-file=transactor.pid"
                       "# storage-admin-password=" (str "storage-admin-password=" storage-admin-password)
;;                                              "# old-storage-admin-password=" (str "old-storage-admin-password=" old-storage-admin-password)
                       "# storage-datomic-password=" (str "storage-datomic-password=" storage-datomic-password)
;;                                              "# old-storage-datomic-password=" (str "old-storage-datomic-password=" old-storage-datomic-password)
                       "# storage-access=local" (str "storage-access=" transactor-storage-access)
                       "# h2-port=4335" "h2-port=4335"
                       "license-key=" (str "license-key=" datomic-transactor-key)
                       "host=" (str "host="
                                    (or #_(some-> "http://checkip.amazonaws.com" slurp clojure.string/trim)
                                        (.getHostAddress (java.net.InetAddress/getLocalHost))))
                       line))
                     (-> template-properties-url
                         slurp
                         (clojure.string/split #"\n")))]
    (spit transactor-properties-url (str line "\n") :append true)))))

(defn start
 "If file datomic/config/transactor.properties is found then start the datomic transactor and returns its pid for taskkill
 else create the transactor.properties file from the template file that correspond to transactor-protocol"
 ([]
  (if (fs/file? "datomic/transactor.pid")
   (slurp "datomic/transactor.pid")
   (let [stdout (-> (Runtime/getRuntime)
                    (.exec "cmd /C call bin/transactor config/transactor.properties" nil (fs/file "datomic"))
                    .getInputStream)]
    (loop [c (-> stdout .read)
           nl 0]
     (cond
      (and (= c 10) (< nl 1)) (do (-> c char print) (some-> stdout .read (recur (inc nl))))
      (and (not= c 10) (< nl 2)) (do (-> c char print) (some-> stdout .read (recur nl)))
      :else (-> c char print)))
    (.close stdout)
    (slurp "datomic/transactor.pid"))))
 ([properties]
  (if (fs/file? "datomic/transactor.pid")
   (slurp "datomic/transactor.pid")
   (do
    (configure-properties properties)
    (start)))))

(defn stop
 "pid is used to taskkill the datomic transactor.
 datomic/config/transactor.properties file is deleted."
 []
 (when (fs/file? "datomic/transactor.pid")
  (fs/exec "cmd" "/C" (str "taskkill -f -pid " (slurp "datomic/transactor.pid")))
  (fs/delete "datomic/transactor.pid"))
 (fs/delete "datomic/config/transactor.properties"))

