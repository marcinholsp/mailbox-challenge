(ns mailbox-challenge.diplomat.datomic.email
  (:require [datomic.api :as d]
            [mailbox-challenge.diplomat.adapters.email :as adapters.email]
            [mailbox-challenge.models.email :as models.email]
            [schema.core :as s]))

(def schema
  [{:db/ident    :email/id
    :db/valueType    :db.type/uuid
    :db/cardinality    :db.cardinality/one
    :db/unique    :db.unique/identity}
   {:db/ident    :email/idempotency-key
    :db/valueType    :db.type/string
    :db/cardinality    :db.cardinality/one
    :db/unique    :db.unique/identity}
   {:db/ident    :email/to
    :db/valueType    :db.type/string
    :db/cardinality    :db.cardinality/one}
   {:db/ident    :email/from
    :db/valueType    :db.type/string
    :db/cardinality    :db.cardinality/one}
   {:db/ident    :email/subject
    :db/valueType    :db.type/string
    :db/cardinality    :db.cardinality/one}
   {:db/ident    :email/body
    :db/valueType    :db.type/string
    :db/cardinality    :db.cardinality/one}
   {:db/ident    :email/status
    :db/valueType    :db.type/keyword
    :db/cardinality    :db.cardinality/one
    :db/index    true}
   {:db/ident    :email/provider
    :db/valueType    :db.type/keyword
    :db/cardinality    :db.cardinality/one}
   {:db/ident    :email/sent-at
    :db/valueType    :db.type/instant
    :db/cardinality    :db.cardinality/one}
   {:db/ident    :email/created-at
    :db/valueType    :db.type/instant
    :db/cardinality    :db.cardinality/one}])

(s/defn save-email!
  [datomic email-model :- models.email/Email]
  (let [email-datomic (adapters.email/model->datomic email-model)]
    (d/transact datomic [email-datomic])))

(s/defn find-by-idempotency-key
  [idempotency-key :- s/Str
   datomic]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?key
                        :where [?e :email/idempotency-key ?key]]
                      (d/db datomic)
                      idempotency-key)]
    (d/pull (d/db datomic)
            [:email/id :email/idempotency-key :email/to :email/from
             :email/subject :email/body :email/status :email/provider
             :email/sent-at :email/created-at]
            eid)))

(s/defn update-status!
  [id       :- s/Uuid
   status   :- s/Keyword
   provider :- (s/maybe s/Keyword)
   sent-at  :- (s/maybe java.util.Date)
   datomic]
  (let [tx (cond-> {:email/id     id
                    :email/status status}
             provider (assoc :email/provider provider)
             sent-at  (assoc :email/sent-at sent-at))]
    @(d/transact datomic [tx])))

(defn count-total-sent
  [datomic]
  (or (d/q '[:find (count ?e) .
             :where [?e :email/status :sent]]
           (d/db datomic))
      0))

(defn count-sent-by-provider
  [datomic]
  (d/q '[:find ?provider (count ?e)
         :where [?e :email/status :sent]
                [?e :email/provider ?provider]]
       (d/db datomic)))
