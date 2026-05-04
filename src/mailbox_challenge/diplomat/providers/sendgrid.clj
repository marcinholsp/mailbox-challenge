(ns mailbox-challenge.diplomat.providers.sendgrid
  (:require [common-core.protocols.http-client :as protocols.http-client]
            [mailbox-challenge.diplomat.providers.protocol :as provider.protocol]))

(defrecord SendgridProvider []
  provider.protocol/EmailProvider

  (provider-name [_] :sendgrid)

  (send-email! [_ email http]
    (let [response (protocols.http-client/req! http
                                               {:method  :post
                                                :url     :sendgrid-send
                                                :payload {:to      (:to email)
                                                          :from    (:from email)
                                                          :subject (:subject email)
                                                          :body    (:body email)}})]
      (= 200 (:status response)))))

(defn new-sendgrid [] (->SendgridProvider))
