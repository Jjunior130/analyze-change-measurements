(ns analyze-change-measurements.core
 (:require [analyze-change-measurements.handler :as handler]
           [analyze-change-measurements.nrepl :as nrepl]
           [luminus.http-server :as http]
           [analyze-change-measurements.config :refer [env]]
           [clojure.tools.cli :refer [parse-opts]]
           [clojure.tools.logging :as log]
           [mount.core :as mount]
           [analyze-change-measurements.server.transactor :as transactor])
 (:gen-class))

(def cli-options
 [["-p" "--port PORT" "Port number"
   :parse-fn #(Integer/parseInt %)]])

(mount/defstate ^{:on-reload :noop} http-server
                :start
                (http/start
                 (-> env
                     (assoc  :handler #'handler/app)
                     (update :io-threads #(or % (* 2 (.availableProcessors (Runtime/getRuntime)))))
                     (update :port #(or (-> env :options :port) %))))
                :stop
                (http/stop http-server))

(mount/defstate ^{:on-reload :noop} repl-server
                :start
                (when (env :nrepl-port)
                 (nrepl/start {:bind (env :nrepl-bind)
                               :port (env :nrepl-port)}))
                :stop
                (when repl-server
                 (nrepl/stop repl-server)))


(defn stop-app []
 (doseq [component (:stopped (mount/stop))]
  (log/info component "stopped"))
 (shutdown-agents))

(defn start-app [args]
 (doseq [component (-> args
                       (parse-opts cli-options)
                       mount/start-with-args
                       :started)]
  (log/info component "started"))
 (.addShutdownHook (Runtime/getRuntime) (Thread. stop-app)))

(defn -main [& args]
 (transactor/download-datomic)
 (transactor/configure-properties)
 (start-app args))
