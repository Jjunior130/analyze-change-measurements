(ns analyze-change-measurements.routes.home
  (:require [analyze-change-measurements.layout :as layout]
            [clojure.java.io :as io]
            [analyze-change-measurements.middleware :as middleware]
            [ring.util.http-response :as response]
            [clojure.tools.logging :refer [debug info]]
            [analyze-change-measurements.server.event-handler :as event-handler]
            [analyze-change-measurements.server.db :as db]))

(defn server<> [client]
 (doto client
  db/server>
  event-handler/client>))

(defn home-page [{client :ws-channel}]
 (when client
  (server<> client)
  (layout/render "home.html"))
 (when-not client
  (debug "Client websocket connecting.")
  (layout/render "home.html")))

(defn home-routes []
  ["" {:middleware [middleware/wrap-csrf]}
   ["/" {:get home-page}]
   ["/docs" {:get (fn [_]
                    (-> (response/ok (-> "docs/docs.md" io/resource slurp))
                        (response/header "Content-Type" "text/plain; charset=utf-8")))}]])

