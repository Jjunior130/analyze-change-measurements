(ns analyze-change-measurements.client.db
 (:require [datascript.core :as d]
           [re-posh.core :as rp]))

(def conn (d/create-conn))
(rp/connect! conn)
