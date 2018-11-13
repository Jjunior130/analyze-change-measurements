(ns analyze-change-measurements.datsync
 (:require [datascript.core :as d]))

;; Datsync let's us transparently mirror and syncronize a client side DataScript database with a server side
;; Datascript database.


;; ## Transaction normalization
;; ----------------------------

;; Maybe we should scrap this and assume we always have datoms? XXX

;; We need some ability to normalize our transactions so they make sense in DataScript and are fully queryable.
;; We can do this recursively as below.

(declare normalized-tx-list-form)

(defn normalized-tx-map-form
 "Takes a transaction map form and translates it into a collection of list forms"
 [m]
 ;; Ensure there is a :db/id key in the map
 ;{:pre [(:db/id m)]}
 (if-let [m-id (:db/id m)]
  (mapcat
   (fn [[k v]]
    (normalized-tx-list-form :db/add m-id k v))
   (dissoc m :db/id))
  (do
   (println "Tx map form does not have a :db/id:" m)
   (throw
    (js/Error. "Tx map form doesn't not have a :db/id")))))

(defn normalized-tx-list-form
 "Takes a transaction list form and normalizes it's values, such that any maps or collections are expanded
 into list forms and concat'd onto the argument list form."
 ([op e a v]
  (cond (map? v) (conj (normalized-tx-map-form v)
                       [op e a (:db/id v)])
        (coll? v) (mapcat (partial normalized-tx-list-form op e a) v)
        :else [[op e a v]]))
 ([[op e a v]] (normalized-tx-list-form op e a v)))

(defn normalized-tx-form
 [tx-form]
 (cond (map? tx-form) (normalized-tx-map-form tx-form)
       (coll? tx-form) (normalized-tx-list-form tx-form)))

;; This will be the main entry point for this functionality, and can be used as a safety measure on all the
;; functions below that operate on transactions.

(defn normalize-tx
 "Normalizes a transaction specification such that any map or :db.cardinality/many collection forms are expanded into list form"
 [tx]
 (let [result (mapcat normalized-tx-form tx)]
  result))

;; This is terribly broken... It makes all sorts of bad assumptions about things that are chainging in the P2P/identifiers
;; based approach. Will need to seriously rewrite and rethink this whole section. I think it's going to put a lot more of
;; the onux on tracking the

(defn get-ref-attrs-for-mapping
 "Returns all reference attribute idents that exist in database ..."
 [ds normalized-tx]
 ;; The valueType is a ref type; so it's pointing to an entity with :db/ident :db.type/ref
 (let [db-ref-type-remote-eid (or ;; XXX Uhh... shouldn't there be something else in this or?
                               ;; Specifically we should have here something that gets the...
                               ;; Wait... maybe we don't need this in the identities version because we'll only have
                               ;; eids on first payload (bootstrap). Everything else will resolve with lookups...
                               ;(???)
                               ;; If we don't already have something in the db, look for an (:db/ident
                               ;; :db.type/ref) pair in tx-forms. (note: this means we have to note that
                               ;; refs need to be references as value types based on an attribute in
                               ;; schema transaction application, till fixed XXX)
                               (some (fn [[eid tx-forms]]
                                      (when (some (fn [[op e a v :as tx-form]]
                                                   (and (= a :db/ident)
                                                        (= v :db.type/ref)))
                                                  tx-forms)
                                       eid))
                                     (group-by second normalized-tx)))
       tx-attr-idents (set (map #(nth % 2) normalized-tx))]
  (set
   ;; There is an assumption here that references attributes must be specified as complete transactions,
   ;; and can't be build up in separate transactions... So no changing to/from ref types XXX
   (concat
    ;; Anything that looks like a reference attribute in the existing db
    ;; TODO Should get this from the (:schema db) instead, since this is more general, and doesn't force persisting of schema;
    ;; Though maybe this is necessary elsewhere? Need to at least consider doing this...
    (d/q '[:find [?attr-ident ...]
           :in $ [?attr-ident ...] ?ref-type-remote-eid
           :where [?attr :db/ident ?attr-ident]
           [?attr :db/valueType ?ref-type]
           [?ref-type :dat.sync.remote.db/id ?ref-type-remote-eid]]
         ds
         tx-attr-idents
         db-ref-type-remote-eid)
    ;; Anything that looks like a reference attribute in the new tx
    (->> normalized-tx
         (group-by second)
         (keep
          (fn [[eid tx-forms]]
           ;; some transaction form for an entity that looks like it's defining a reference attribute
           (when (some
                  (fn [[op e a v :as tx-form]]
                   (and (= a :db/valueType)
                        (= v db-ref-type-remote-eid)))
                  tx-forms)
            ;; given we think this is a reference attribute, lets get the ident;
            ;; Regarding above comment about reference attributes; this is where we need
            ;; :db/ident in part of the tx... unless we want to fix it... XXX
            (some
             (fn [[op e a v :as tx-form]]
              ;; returns the :db/ident, which passes up through the somes and keeps
              ;; above
              (when (= a :db/ident) v))
             tx-forms)))))))))

;; XXX Mark the incoming bootstrap transaction as :in-sync

;; One way to speed things up would be to not try and keep ids the same
;; Would make a lot easier.
;; In short though, we're doing a ton of querying here.
;; Should see if there's a nice elegant way we can do all this stuff at once with just a query (or two)

(defn get-or-assign-local-eid
  [ds current-mapping eid]
  ;; If we already have a mapping in current-mappings use that
  (if-let [local-eid (current-mapping eid)]
    local-eid
    ;; If we don't, find out if we already have a mapping in the db
    (if-let [local-eid (d/q '[:find ?e . :where [?e :dat.sync.remote.db/id ?id] :in $ ?id] ds eid)]
      ;; Then map that eid in the transaction with the local eid
      local-eid
      ;; Used to: If not, then make a new id, attempting to preserve ids wherever possible
      ;; Now; Always generate new (tempid), since otherwise, our identity assignments won't work out properly
      (d/tempid nil))))

(defn make-eid-mapping
 "Creates a map of remote eids to local eids for any id in the tx, providing consistent tempids where needed"
 [ds normalized-tx]
 ;; First translate the main eids of the transactions
 (let [m (reduce
          (fn [m [eid eid-tx]]
           (assoc m eid (get-or-assign-local-eid ds m eid)))
          ;; Group positionally by eids in tx forms
          {}
          (group-by second normalized-tx))
       ;; Then get this for translating reference values below
       ref-attribute-idents (get-ref-attrs-for-mapping ds normalized-tx)]
  ;; Now translate reference values
  (->> normalized-tx
       ;; First, filter to just the tx-forms for attributes which are in ref-attribute-idents
       (filter (fn [[op e a v :as tx-form]]
                (ref-attribute-idents a)))
       ;; Next we reduce over those forms, making local eid assignments for those values
       (reduce (fn [m' [op e a v :as tx-form]]
                (assoc m' v (get-or-assign-local-eid ds m' v)))
               m))))

;; Translate eids in the system
(defn translate-eids
 "Takes a tx in canonical form and changes any incoming eids (including reference ids (WIP XXX)) to equivalents consistent with the local db. It does this
 by looking for a match to an existing :dat.sync.remote.db/id. If it doesn't find one, it matches it with a negative one
 and adds an [:db/add eid :dat.sync.remote.db/id _] statement to the tx."
 [ds normalized-tx]
 (let [eid-mapping (make-eid-mapping ds normalized-tx)
       ref-attribute-idents (get-ref-attrs-for-mapping ds normalized-tx)
       result (vec (concat
                    ;; First map over all the tx-forms and modify any eids
                    (mapv
                     (fn [[op e a v :as tx-form]]
                      [op (eid-mapping e) a (if (ref-attribute-idents a) (eid-mapping v) v)])
                     normalized-tx)
                    ;; Then go in and add :dat.sync.remote.ds/id attributes for new eids
                    ;; First get all local eids involved in the transaction, and see which ones don't have the
                    ;; :dat.sync.remote.db/id attr
                    (let [local-tx-eids (vals eid-mapping)
                          local-tx-eids-w-remote (set (d/q '[:find [?e ...] :in $ [?e ...] :where [?e :dat.sync.remote.db/id _]] ds local-tx-eids))
                          local-tx-eids-wo-remote (filter (comp not local-tx-eids-w-remote) local-tx-eids)
                          reverse-mapping (into {} (map (fn [[k v]] [v k]) eid-mapping))]
                     (println "n local eids w remote" (count local-tx-eids-w-remote))
                     (println "n local eids w/o remote" (count local-tx-eids-wo-remote))
                     (mapv
                      (fn [eid]
                       [:db/add eid :dat.sync.remote.db/id (reverse-mapping eid)])
                      local-tx-eids-wo-remote))))]
  result))

(defn server>client [ds tx-datoms]
 (->> tx-datoms
      normalize-tx
      (translate-eids ds)))

;; ## Sending data back
;; --------------------

;; Note that this doesn't currently deal well with (for example) having a tx entry where the eid is an ident,
;; because of what seems to be a bug in DataScript get-else. So you have to do that translation yourself to
;; get the local eid if you want to use this function.

;; This should be reworked in terms of multimethods, so you can create translations for your own tx functions,
;; just like you can for the client XXX
;; Needs to be reworked overall as well

;; TODO This should probably probably either be deprecated or rewritten to use our globalize fn

(defn remote-tx
 [ds tx]
 (let [normalized-tx (->> (normalize-tx tx)
                          (remove
                           (fn [[_ _ a]]
                           ;; This is something that should never exist on the server
                            (#{:dat.sync.remote.db/id :db/id} a))))
       translated-tx (vec (d/q '[:find ?op ?dat-e ?a ?dat-v
                                 :in % $ [[?op ?e ?a ?v]]
                                 :where [(get-else $ ?e :dat.sync.remote.db/id ?e) ?dat-e]
                                 (remote-value-trans ?v ?a ?dat-v)]
                               ;; Really want to be able to do an or clause here to get either the value back
                               ;; unchanged, or the reference :dat.sync.remote.db/id if a ref attribute
                               ;; Instead I'll use rules...
                               '[[(attr-type-ident ?attr-ident ?type-ident)
                                  [?attr :db/ident ?attr-ident]
                                  [?attr :db/valueType ?vt]
                                  [?vt :db/ident ?type-ident]]
                                 [(is-ref ?attr-ident)
                                  (attr-type-ident ?attr-ident :db.type/ref)]
                                 [(remote-value-trans ?ds-v ?attr-ident ?remote-v)
                                  (is-ref ?attr-ident)
                                  (> ?ds-v 0)
                                  [?ds-v :dat.sync.remote.db/id ?remote-v]]
                                 [(remote-value-trans ?ds-v ?attr-ident ?remote-v)
                                  (is-ref ?attr-ident)
                                  (< ?ds-v 0)
                                  [(ground ?ds-v) ?remote-v]]
                                 ;; Shit... really want to be able to use (not ...) here...
                                 [(remote-value-trans ?ds-v ?atrr-ident ?remote-v)
                                  (attr-type-ident ?attr-ident ?vt-ident)
                                  (not= ?vt-ident :db.type/ref)
                                  [(ground ?ds-v) ?remote-v]]]
                               ds normalized-tx))]
  translated-tx))

(def client>server (comp (partial vector :analyze-change-measurements.server.event-handler/client>server) remote-tx))
