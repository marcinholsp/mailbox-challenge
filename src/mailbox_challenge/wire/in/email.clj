(ns mailbox-challenge.wire.in.email
  (:require [common-core.schema :as schema]
            [schema.core :as s]))

(s/defschema SendEmailRequest
  (schema/loose-schema
   {:to      {:schema s/Str :required true}
    :from    {:schema s/Str :required true}
    :subject {:schema s/Str :required true}
    :body    {:schema s/Str :required true}}))
