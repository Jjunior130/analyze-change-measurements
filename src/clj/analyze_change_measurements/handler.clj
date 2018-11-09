(ns analyze-change-measurements.handler
  (:require [analyze-change-measurements.middleware :as middleware]
            [analyze-change-measurements.layout :refer [error-page]]
            [analyze-change-measurements.routes.home :refer [home-routes]]
            [compojure.core :refer [routes wrap-routes]]
            [ring.util.http-response :as response]
            [compojure.route :as route]
            [reitit.ring :as ring]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.webjars :refer [wrap-webjars]]
            [analyze-change-measurements.env :refer [defaults]]
            [mount.core :as mount]))

(mount/defstate init-app
  :start ((or (:init defaults) identity))
  :stop  ((or (:stop defaults) identity)))


(mount/defstate app
                :start
                (middleware/wrap-base
                 (ring/ring-handler
                  (ring/router
                   (home-routes))
                  (ring/routes
                   (ring/create-resource-handler
                    {:path "/"})
                   (wrap-content-type
                    (wrap-webjars (constantly nil)))
                   (ring/create-default-handler
                    {:not-found
                     (constantly (error-page {:status 404, :title "404 - Page not found"}))
                     :method-not-allowed
                     (constantly (error-page {:status 405, :title "405 - Not allowed"}))
                     :not-acceptable
                     (constantly (error-page {:status 406, :title "406 - Not acceptable"}))})))))
