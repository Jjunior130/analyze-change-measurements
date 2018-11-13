(ns analyze-change-measurements.datsync
 (:require [clojure.core.async :as a]
           [datomic.api :as d]
           [clojure.tools.logging :refer [debug info]]
           [analyze-change-measurements.server.db :as db :refer [conn]]
           [clojure.core.async.impl.protocols :as protocols]))

(defn reverse-ref-attribute?
  [attr-kw]
  (= \_ (first (name attr-kw))))

(defn tempid-map
  ;; TODO Need to be able to customize partition here...
  ([e]
   (if (and (integer? e) (< e 0)) (d/tempid :db.part/user e) e))
  ([db a e]
   (if (and (keyword? a)
            ;; TODO Note; this doesn't cover reverse reference attributes...
            (or (reverse-ref-attribute? a)
                (d/q '[:find ?a . :in $ ?a-ident :where [?a :db/ident ?a-ident] [?a :db/valueType :db.type/ref]] db a))
            ;; for the attribute...
            (integer? e)
            (< e 0))
     (d/tempid :db.part/user e)
     e)))

;; ## Applying changes from clients

;; Multimethod dispatch on op is perfect for securing what transaction functions are possible.
;; Nothing gets called unless you said it can.

;; We need a function we can call to transact a tx-message from the client

(defmulti translate-tx-form
  (fn [db tempid-map [op]] op))

(defmethod translate-tx-form :db/add
  [db tempid-map [op e a v]]
  [op (tempid-map e) a (tempid-map db a v)])

(defmethod translate-tx-form :db/retract
  [db tempid-map [op e a v]]
  [op (tempid-map e) a (tempid-map db a v)])

(defmethod translate-tx-form :db.fn/retractEntity
  [db tempid-map [op e]]
  [op (tempid-map e)])

;; Custom tx functions can be added by completing associated multimethod definitions.

;;     (defmethod dat.sync/translate-tx-form :your.app.tx/function-name
;;       [tempid-map [op arg1 arg2]
;;       [op (tempid-map arg1) arg2])

;; The first arg will be a map of the foreign ids to the local datomic ids. This can be used to translate eid
;; args in the tx call.

;; Could maybe do something more efficient here for the tempid-map in the future.
;; Like preload all of tempid mappings in a relation that we put into a map, and pass around.
;; For now though, just separate queries to keep things simple.

(defn apply-remote-tx!
  "Takes a client transaction and transacts it"
  [tx]
  ;; This is where we'd want to put security measures in place;
  ;; What other translation things do we need to do here?
  (let [db (d/db conn)
        tx' (mapv (partial translate-tx-form db tempid-map) tx)]
   (d/transact conn tx')))

(def client>server apply-remote-tx!)

;; ## Pushing changes to client

;; Every time we get a transaction, we want to send the results of that transaction to every client.
;; Eventually we can get fancy with installing subscription middleware, so for each client we have a
;; specification of what they have "checked out", but this is just a starting point.

;; Now for our P2P/offline available/local first translation function
;; This will operate by translating eids from the datoms of a tx-report (tx-data) into lookup references, or into tempids
;; where app approapriate, based on the novelty relative to the db argument.

;; Relevant differences between Datomic/DataScript:
;; * Datomic needs a :db/id in transactions, but DataScript does not (both still resolve entities given identities though)
;; * We have to query for attr type differently in the two cases
;; * We can still query by attr-ident passed in as an arg in both cases
;; * Pulling :db/id on a nonexistent db id gives different results in datascript than it does in datomic
;;   * (d/pull [:db/id :db/ident] x) -> {:db/id x}
;;   * (d/pull [:db/ident] x) -> nil
;;   * (ds/pull [:db/id :db/ident] x) -> nil
;; * In DS You can transact a refid that isn't in any other datoms; will pull out, however not in Datomic
;; * You can call first on #datascript/Datoms but not datomic #datoms

;; Have to be careful changing the identifying attr of an entity; I think this necessitates transactionality
;; However, this is something that shouldn't really be frequently happening anyway... ids are ids

(defn schema-attrs-by
  [db attr-or-fn value]
  (->> db
       :schema
       (filter (fn [[_ attr-schema]]
                 (= value (attr-or-fn attr-schema))))
       (map first)
       set))


(defn globalize-datoms
 "Given the state of the database before a set of datoms were asserted (as given by `(:db-before tx-report)`),
  translate the datoms such that local eids are replaced with lookup refs, or for new entities, tempids.
  By default, any `:db.unique/identity` attribute found associated with the eids in question is eligible to be selected.
  However, you can pass a final options hash argument with `:identity-attrs` mapping to a collection of attribute keywords
  (order asserts precedence) corresponding to desired identity attributes.
  Note that (for now) it is assumed that new entities (relative to db-before) assert some identity attribute other than
  the local id. This will soon be either validated, or 'fixed' via submission of a transaction which generates ids where
  needed, and submits a transaction which fixes these issues. Note that it is also assumed that whatever client recieves
  this payload already has identity mappings for all data up to db-before. Otherwise, lookups may fail to resolve remotely."
  [db-before {:keys [identity-attrs]} datoms]
  (let [;; Figure out what our ref attributes are
        ref-attrs (schema-attrs-by db-before :db/valueType :db.type/ref)
        ;; Figure out what identity attributes we'll be looking for
        identity-attrs (or identity-attrs
                           ;; XXX Need to think properly about role of remote.db/id in light of all this...
                           (disj (schema-attrs-by db-before :db/unique :db.unique/identity) :dat.sync.remote.db/id))
        ;; Figure out all the eids needed in our translation
        eids (reduce
               (fn [eids [e a v]]
                 (let [eids (conj eids e)]
                   (if (get ref-attrs a)
                     (conj eids v)
                     eids)))
               []
               datoms)
        ;; Figure out our lookups
        eid-identities (->> eids
                            ;; XXX Could look for remote.db/id here as well, but for now...
                            (d/pull-many db-before (into [:db/id] identity-attrs))
                            (map (fn [pull-data]
                                   [(:db/id pull-data)
                                    ;; return nil if no identity for eid (should either assert or fix downstream)
                                    (when-let [identity-attr
                                               (->> identity-attrs
                                                    (filter (partial get pull-data))
                                                    first)]
                                      [identity-attr (get pull-data identity-attr)])]))
                            (into {}))
        ;; Use our lookup if we have it, and a tempid (- eid) if we don't
        ;; The assumption is that only things that have ids can have been synced meaningfully with the server
        translate-eid (fn [e] (or (get eid-identities e) (- e)))
        translate-val (fn [a v] (if (ref-attrs a) (translate-eid v) v))
        datoms (map
                 (fn [[e a v t b]]
                   [(translate-eid e) a (translate-val a v) t b])
                 datoms)]
    ;; Can optionally go through either a process to fix or throw on missing lookups/identities
    ;; But for now...
    ;; Wait... have to review logic; do we need to do this after all?
    ;new-eids (->> eid-identities
    ;              (remove second)
    ;              (map first)
    ;              set
    datoms))

;; The easiest thing to do here is take the tx-data (datoms) produced by each transaction and convert those to
;; :db/add and :db/retract statements which we can send to clients.

(defn tx-report-deltas
  [{:as tx-report :keys [db-before db-after tx-data]}]
  (->> tx-data
       (globalize-datoms db-before {:identity-attrs nil})
       (d/q '[:find ?e ?aname ?v ?t ?added
              :in $ [[?e ?a ?v ?t ?added]]
              :where [?a :db/ident ?aname]]
           db-after)
       (map (fn [[e a v t b]] [({true :db/add false :db/retract} b) e a v t]))))

(defn- server>clients
 "The LinkedBlockingQueue method .take will block the thread until something is put into the tx-report-queue.
 ((protocols/commit fn1) return-value) is needed for this function to work as <! inside an async go(-loop) block"
 [tx-report-queue fn1]
 (let [tx-report (.take tx-report-queue)]
  (info "Broadcasting server database change event to all connected clients.")
  (->> tx-report
       tx-report-deltas
       (vector :analyze-change-measurements.client.event-handler/server>clients)
       ((protocols/commit fn1)))))

(extend java.util.concurrent.LinkedBlockingQueue
 clojure.core.async.impl.protocols/ReadPort
 {:take! server>clients})

