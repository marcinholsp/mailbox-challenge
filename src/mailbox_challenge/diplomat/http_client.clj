(ns mailbox-challenge.diplomat.http-client
  (:require [schema.core :as s]))

(def bookmarks-settings
  (let [mailgun
        {:url     "zk://mailbox-challenge/mock/mailgun/send"
         :service :mailbox-challenge
         :methods {:post {:schema-req   {:to s/Str :from s/Str :subject s/Str :body s/Str}
                          :schema-resps {200 {:success s/Bool}
                                         400 {:error   s/Str}
                                         500 {:error   s/Str}}}}}
        sendgrid
        {:url     "zk://mailbox-challenge/mock/sendgrid/send"
         :service :mailbox-challenge
         :methods {:post {:schema-req   {:to s/Str :from s/Str :subject s/Str :body s/Str}
                          :schema-resps {200 {:success s/Bool}
                                         400 {:error   s/Str}
                                         500 {:error   s/Str}}}}}]
    {:mailgun-send  mailgun
     :sendgrid-send sendgrid}))
