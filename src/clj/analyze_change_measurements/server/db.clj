(ns analyze-change-measurements.server.db
 (:require [datomic.api :as d]
           [mount.core :refer [defstate]]
           [clojure.core.async :as a]
           [clojure.tools.logging :refer [debug info]]
           [analyze-change-measurements.config :refer [env]]))

(defstate conn
  :start (-> env :options :database-url d/connect)
  :stop (-> conn .release))

(defn start-server>clients-broadcaster []
 (let [tx-report-mult (-> conn d/tx-report-queue a/mult)]
  (info "Started process to push server database change events to all connected clients.")
  (fn connect [client]
   (a/tap tx-report-mult client)
   (info "Client connected."))))

(defstate server>
  :start (start-server>clients-broadcaster)
  :stop (-> conn d/remove-tx-report-queue))

(defn create-schema []
 (let [schema [{:db/ident              :user/id
                :db/valueType          :db.type/string
                :db/cardinality        :db.cardinality/one
                :db.install/_attribute :db.part/db}
               {:db/ident              :user/first-name
                :db/valueType          :db.type/string
                :db/cardinality        :db.cardinality/one
                :db.install/_attribute :db.part/db}
               {:db/ident              :user/last-name
                :db/valueType          :db.type/string
                :db/cardinality        :db.cardinality/one
                :db.install/_attribute :db.part/db}
               {:db/ident              :user/email
                :db/valueType          :db.type/string
                :db/cardinality        :db.cardinality/one
                :db.install/_attribute :db.part/db}]]
  @(d/transact conn schema)))

(defn entity [conn id]
 (d/entity (d/db conn) id))

(defn touch [conn results]
 "takes 'entity ids' results from a query
 e.g. '#{[272678883689461] [272678883689462] [272678883689459] [272678883689457]}'"
 (let [e (partial entity conn)]
  (map #(-> % first e d/touch) results)))

(defn add-user [conn {:keys [id first-name last-name email]}]
 @(d/transact conn [{:db/id           id
                     :user/first-name first-name
                     :user/last-name  last-name
                     :user/email      email}]))

(defn find-user [conn id]
 (let [user (d/q '[:find ?e :in $ ?id
                   :where [?e :user/id ?id]]
                 (d/db conn) id)]
  (touch conn user)))
