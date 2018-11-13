(ns analyze-change-measurements.core
 (:require [kee-frame.core :as kf]
           [re-frame.core :as rf]
           [analyze-change-measurements.client.event-handler :as event-handler]
           [analyze-change-measurements.client.db]
           [analyze-change-measurements.ajax :as ajax]
           [analyze-change-measurements.routing :as routing]
           [analyze-change-measurements.view :as view]))

(kf/reg-controller
 ::about-controller
 {:params (constantly true)
  :start  [::event-handler/load-about-page]})

(rf/reg-sub
 :docs
 (fn [db _]
  (:docs db)))

(kf/reg-controller
 ::home-controller
 {:params (constantly true)
  :start  [::event-handler/load-home-page]})

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
 (rf/dispatch [::event-handler/start-socket])
 (mount-components))
