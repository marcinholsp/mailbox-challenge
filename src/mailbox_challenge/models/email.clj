(ns mailbox-challenge.models.email
  (:require [schema.core :as s])
  (:import [java.util Date]))

(s/defschema Email
  {:id              s/Uuid
   :idempotency-key s/Str
   :to              s/Str
   :from            s/Str
   :subject         s/Str
   :body            s/Str
   :status          s/Keyword
   (s/optional-key :provider) (s/maybe s/Keyword)
   (s/optional-key :sent-at)  (s/maybe java.util.Date)
   :created-at      Date})