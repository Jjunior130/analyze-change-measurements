(ns user
  (:require [analyze-change-measurements.config :refer [env]]
            [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [mount.core :as mount]
            [analyze-change-measurements.figwheel :refer [start-fw stop-fw cljs]]
            [analyze-change-measurements.core :refer [start-app]]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(defn start []
  (mount/start-without #'analyze-change-measurements.core/repl-server
                       #'analyze-change-measurements.handler/init-app))

(defn stop []
  (mount/stop-except #'analyze-change-measurements.core/repl-server
                     #'analyze-change-measurements.handler/init-app))

(defn restart []
  (stop)
  (start))

(comment
 (do
  (start)
  (start-fw))
 (restart)
 (stop-fw)
 (stop))
