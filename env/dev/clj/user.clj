(ns user
 (:require [analyze-change-measurements.config :refer [env]]
           [clojure.spec.alpha :as s]
           [expound.alpha :as expound]
           [mount.core :as mount]
           [analyze-change-measurements.figwheel :refer [start-fw stop-fw cljs]]
           [analyze-change-measurements.core :refer [start-app]]
           [analyze-change-measurements.server.transactor :as transactor])
 (:import [java.lang.management ManagementFactory]))

(defn get-pid []
 "Gets this process' PID."
 (let [pid (.getName (ManagementFactory/getRuntimeMXBean))]
  (first (re-seq #"[0-9]+" pid))))

(defn write-pid-file [pid-file]
 "Writes this process' PID to a supplied file name."
 (spit pid-file (get-pid)))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(defn start []
 (mount/start-without #'analyze-change-measurements.core/repl-server
                      #'analyze-change-measurements.handler/init-app))

(defn stop []
 (mount/stop-except))
;;   #'analyze-change-measurements.core/repl-server
;;   #'analyze-change-measurements.handler/init-app
;;   #'analyze-change-measurements.server.db/conn
;;   #'analyze-change-measurements.server.db/server>))

(defn restart []
 (stop)
 (start)
 (start-fw))

(comment
 (do
  (start)
  (start-fw))
 (restart)
 (stop-fw)
 (stop))

(get-pid)

