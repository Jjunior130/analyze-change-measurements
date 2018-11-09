(ns analyze-change-measurements.core
  (:require [kee-frame.core :as kf]
            [re-frame.core :as rf]
            [ajax.core :as http]
            [analyze-change-measurements.ajax :as ajax]
            [analyze-change-measurements.routing :as routing]
            [analyze-change-measurements.view :as view]
            [kee-frame.websocket :as websocket]))

(rf/reg-event-fx ::start-socket
                 (fn [{:keys [db]} _]
                  {::websocket/open {:path      "/"
                                     :dispatch  ::on-event-receive}})) ;; The re-frame event receiving server messages.

(kf/reg-event-fx ::on-event-receive
                 (fn [_ [{event :message}]]
                  {:dispatch [::send-event event]}))

(kf/reg-event-fx ::send-event
                 (fn [{:keys [db]} [event]]
                  {:dispatch [::websocket/send "/" event]}))

(rf/reg-event-fx
  ::load-about-page
  (constantly nil))

(kf/reg-controller
  ::about-controller
  {:params (constantly true)
   :start  [::load-about-page]})

(rf/reg-sub
  :docs
  (fn [db _]
    (:docs db)))

(kf/reg-chain
  ::load-home-page
  (fn [_ _]
    {:http-xhrio {:method          :get
                  :uri             "/docs"
                  :response-format (http/raw-response-format)
                  :on-failure      [:common/set-error]}})
  (fn [{:keys [db]} [_ docs]]
    {:db (assoc db :docs docs)}))


(kf/reg-controller
  ::home-controller
  {:params (constantly true)
   :start  [::load-home-page]})

;; -------------------------
;; Initialize app
(defn mount-components []
  (rf/clear-subscription-cache!)
  (kf/start! {:debug?         true
              :routes         routing/routes
              :hash-routing?  true
              :initial-db     {}
              :root-component [view/root-component]}))

(defn init! []
 (ajax/load-interceptors!)
 (rf/dispatch [::start-socket])
 (mount-components))
