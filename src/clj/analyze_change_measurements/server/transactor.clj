(ns analyze-change-measurements.server.transactor
 (:require [analyze-change-measurements.config :refer [env]]
           [me.raynes.fs :as fs]
           [clojure.tools.logging :refer [debug info]]))

(defn download-datomic
 "When no 'datomic' folder found in project root directory:
 1. download datomic.zip using credentials and datomic-version
 2. extract to root dir and delete the zip file
 3. rename the extracted folder from datomic-pro-<DATOMIC_VERSION> to datomic"
 [& {:keys [datomic-version datomic-license-user datomic-license-password]
     :or {datomic-version (:datomic-version env)
          datomic-license-user (:datomic-license-user env)
          datomic-license-password (:datomic-license-password env)}}]
 (when-not (fs/directory? "datomic")
  (info "-----> Installing Datomic " datomic-version)
  (let [credential (str datomic-license-user ":" datomic-license-password)
        download-url (str "https://my.datomic.com/repo/com/datomic/datomic-pro/"
                          datomic-version
                          "/datomic-pro-"
                          datomic-version ".zip")]
   (when-not (fs/file? "datomic.zip")
    (fs/exec (str "curl -L -u " credential " " download-url " > datomic.zip"))))
  (fs/exec "jar xf datomic.zip")
  (fs/delete (fs/file "datomic.zip"))
  (fs/exec "mv datomic-pro-* datomic")))

(defn datomic-postgres-table-exist?
 "Creates postgres table if it doesn't exist."
 [& {:keys [database-url jdbc-database-username]
     :or {database-url (:database-url env)
          jdbc-database-username (:jdbc-database-username env)}}]
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

(defn configure-properties
 "Creates datomic/config/transactor.properties
 with variables pid-file, sql-url, sql-user, sql-password, sql-driver-params, license-key, and host
 using datomic/config/samples/sql-transactor-template.properties as template"
 [& {:keys [jdbc-database-url jdbc-database-username jdbc-database-password
            sql-driver-params datomic-transactor-key]
     :or {jdbc-database-url (:jdbc-database-url env)
          jdbc-database-username (:jdbc-database-username env)
          jdbc-database-password (:jdbc-database-password env)
          sql-driver-params (:sql-driver-params env)
          datomic-transactor-key (:datomic-transactor-key env)}}]
 (let [template-properties-url "datomic/config/samples/sql-transactor-template.properties"
       transactor-properties-url "datomic/config/transactor.properties"]
  (when-not (fs/file? transactor-properties-url)
   (info "-----> Configuring Datomic to connect to HEROKU POSTGRES")
   (doseq [line (map (fn [line]
                      (condp #(clojure.string/includes? %2 %1) line
                       "# pid-file=transactor.pid" "pid-file=transactor.pid"
                       "sql-url=" (str "sql-url=" jdbc-database-url)
                       "sql-user=" (str "sql-user=" jdbc-database-username)
                       "sql-password=" (str "sql-password=" jdbc-database-password)
                       "sql-driver-params=" (str "sql-driver-params=" sql-driver-params)
                       "license-key=" (str "license-key=" datomic-transactor-key)
                       "host=" (str "host=" (.getHostAddress (java.net.InetAddress/getLocalHost)))
                       line))
                     (-> template-properties-url
                         slurp
                         (clojure.string/split #"\n")))]
    (spit transactor-properties-url (str line "\n") :append true)))
  (when (fs/file? transactor-properties-url)
   (info "Skipping configuration. " (fs/file transactor-properties-url) " File already exist."))))

(defn start
 "If file datomic/transactor.pid is not found then start the datomic transactor and returns its pid for taskkill
 else return pid"
 []
 (if-not (fs/file? "datomic/transactor.pid")
  (with-open [stdout (-> (Runtime/getRuntime)
                         (.exec "bin/transactor config/transactor.properties" nil (fs/file "datomic"))
                         .getInputStream)]
   (.read stdout) ; Blocks thread until first character is read.
   (slurp "datomic/transactor.pid"))
  (slurp "datomic/transactor.pid")))

(defn stop
 "pid is used to taskkill the datomic transactor.
 datomic/transactor.pid file is deleted."
 ([] (when (fs/file? "datomic/transactor.pid")
      (stop (slurp "datomic/transactor.pid"))))
 ([pid]
  (fs/exec (str "kill -9 " pid))
  (fs/delete "datomic/transactor.pid")))

