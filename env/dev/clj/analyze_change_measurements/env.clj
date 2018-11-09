(ns analyze-change-measurements.env
  (:require [selmer.parser :as parser]
            [clojure.tools.logging :as log]
            [analyze-change-measurements.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (parser/cache-off!)
     (log/info "\n-=[analyze-change-measurements started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[analyze-change-measurements has shut down successfully]=-"))
   :middleware wrap-dev})
