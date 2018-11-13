(ns analyze-change-measurements.server.event-handler
 (:require [analyze-change-measurements.datsync :as datsync]
           [clojure.core.async :as a]
           [clojure.tools.logging :refer [debug info]]))

(defmulti on-event-receive (fn [client [event-id]] event-id))

(defmethod on-event-receive ::client>server
 [client [_ tx]]
 (debug tx)
 (datsync/client>server tx))

;; (defn get-bootstrap [uid]
;;   (concat
;;     ;; At least one of these should probably be for your schema, and we'll probably give you a helper for that
;;     (d/q ...)
;;     (d/q ...)))

;; (defmethod on-event-receive :dat.sync.client/bootstrap
;;   [client [_ uid]]
;; (a/go (a/>! client [:dat.sync.client/bootstrap (get-bootstrap uid)]))

(defmethod on-event-receive nil
 [client [_ tx]])

(defn client> [client]
 (a/go-loop [event (:message (a/<! client))]
            (on-event-receive client event)
            (recur (:message (a/<! client))))
 (info "Started process to handle events received from the client."))


