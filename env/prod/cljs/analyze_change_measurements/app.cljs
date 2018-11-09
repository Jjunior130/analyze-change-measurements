(ns analyze-change-measurements.app
  (:require [analyze-change-measurements.core :as core]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(core/init!)
