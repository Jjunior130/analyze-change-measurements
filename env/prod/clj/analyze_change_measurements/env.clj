(ns analyze-change-measurements.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[analyze-change-measurements started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[analyze-change-measurements has shut down successfully]=-"))
   :middleware identity})
