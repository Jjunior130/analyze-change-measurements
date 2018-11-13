(ns analyze-change-measurements.client.event-handler
 (:require [re-frame.core :as rf]
           [kee-frame.core :as kf]
           [kee-frame.websocket :as websocket]
           [re-posh.core :as rp]
           [ajax.core :as http]
           [analyze-change-measurements.datsync :as datsync]))

(rf/reg-event-fx
 ::load-about-page
 (constantly nil))

(kf/reg-chain
 ::load-home-page
 (fn [_ _]
  {:http-xhrio {:method          :get
                :uri             "/docs"
                :response-format (http/raw-response-format)
                :on-failure      [:common/set-error]}})
 (fn [{:keys [db]} [_ docs]]
  {:db (assoc db :docs docs)}))

(rp/reg-event-fx ::start-socket
                 (fn [_ _]
                  {::websocket/open {:path      "/"
                                     :dispatch  ::on-event-receive}}))

(kf/reg-event-fx ::on-event-receive
                 (fn [_ [{event :message}]]
                  {:dispatch event}))

(rp/reg-event-ds ::server>clients
                 (fn [ds [_ tx-datoms]]
                  (datsync/server>client ds tx-datoms)))

(kf/reg-event-fx ::send-event
                 (fn [_ [event]]
                  {:dispatch [::websocket/send "/" event]}))

(kf/reg-event-fx ::send-tx!
                 [(rf/inject-cofx :ds)]
                 (fn [{:keys [ds]} [tx]]
                  {:dispatch [::send-event (datsync/client>server ds tx)]}))
