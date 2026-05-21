(ns mailbox-challenge.wire.out.email
  (:require [common-core.schema :as schema]
            [schema.core :as s]))

(s/defschema EmailResponse
  (schema/strict-schema
   {:id       s/Uuid
    :status   s/Keyword
    :provider (s/maybe s/Keyword)
    :sent-at  (s/maybe java.util.Date)}))

(s/defschema ProviderStat
  (schema/strict-schema
   {:provider s/Keyword
    :count    s/Int}))

(s/defschema EmailStats
  (schema/strict-schema
   {:total-sent  s/Int
    :by-provider [ProviderStat]}))
