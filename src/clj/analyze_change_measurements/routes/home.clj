(ns analyze-change-measurements.routes.home
  (:require [analyze-change-measurements.layout :as layout]
            [clojure.java.io :as io]
            [analyze-change-measurements.middleware :as middleware]
            [chord.http-kit :refer [wrap-websocket-handler with-channel]]
            [ring.util.http-response :as response]
            [clojure.core.async :as a]
            [clojure.tools.logging :refer [debug]]))

(defn home-page [{client :ws-channel}]
 (when client
  (let [event [:analyze-change-measurements.core/send-event [:response "event received"]]]
   (debug "\nSending an event to client:\n" event "\n")
   (a/go
    (a/>! client event)
    (debug "\nResponse from client received:\n"
           (:message (a/<! client)) "\n"))))
 (layout/render "home.html"))

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf]}
   ["/" {:get home-page}]
   ["/docs" {:get (fn [_]
                    (-> (response/ok (-> "docs/docs.md" io/resource slurp))
                        (response/header "Content-Type" "text/plain; charset=utf-8")))}]])

