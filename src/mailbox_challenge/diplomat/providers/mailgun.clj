(ns mailbox-challenge.diplomat.providers.mailgun
  (:require [common-core.protocols.http-client :as protocols.http-client]
            [mailbox-challenge.diplomat.providers.protocol :as provider.protocol]))

(defrecord MailgunProvider []
  provider.protocol/EmailProvider

  (provider-name [_] :mailgun)

  (send-email! [_ email http]
    (let [response (protocols.http-client/req! http
                                               {:method  :post
                                                :url     :mailgun-send
                                                :payload {:to      (:to email)
                                                          :from    (:from email)
                                                          :subject (:subject email)
                                                          :body    (:body email)}})]
      (= 200 (:status response)))))

(defn new-mailgun [] (->MailgunProvider))
