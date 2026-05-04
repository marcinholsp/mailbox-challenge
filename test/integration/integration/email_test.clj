(ns integration.email-test
  (:require [integration.aux.init :refer [defflow]]
            [matcher-combinators.matchers :as m]
            [state-flow.api :refer [flow match?]]
            [state-flow.helpers.component.servlet :as servlet]
            [state-flow.helpers.http-client :as http-client]))

(def valid-email-body
  {:to      "cliente@example.com"
   :from    "noreply@nubank.com.br"
   :subject "Seu cartão chegou"
   :body    "Olá, seu cartão foi enviado."})

;; Fixed key so two servlet steps in the same flow can share it (state-flow binding RHS must be a monadic step).
(def idempotency-replay-scenario-key "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

(defflow send-email-success-test
  (flow "envia e-mail com sucesso via provedor primário (mailgun)"
    (http-client/with-responses
      {:mailgun-send (constantly {:status 200 :body {:success true}})}
      (match?
       {:status 200
        :body   (m/embeds {:status   :sent
                           :provider :mailgun})}
       (servlet/request {:method  :post
                         :uri     "/email/send"
                         :headers {"idempotency-key" (str (java.util.UUID/randomUUID))
                                   "content-type"   "application/json"}
                         :body    valid-email-body})))))

(defflow send-email-fallback-test
  (flow "usa sendgrid quando mailgun falha"
    (http-client/with-responses
      {:mailgun-send  (constantly {:status 500 :body {:error "timeout"}})
       :sendgrid-send (constantly {:status 200 :body {:success true}})}
      (match?
       {:status 200
        :body   (m/embeds {:status   :sent
                           :provider :sendgrid})}
       (servlet/request {:method  :post
                         :uri     "/email/send"
                         :headers {"idempotency-key" (str (java.util.UUID/randomUUID))
                                   "content-type"   "application/json"}
                         :body    valid-email-body})))))

(defflow send-email-all-providers-fail-test
  (flow "retorna 502 quando todos os provedores falham"
    (http-client/with-responses
      {:mailgun-send  (constantly {:status 500 :body {:error "down"}})
       :sendgrid-send (constantly {:status 500 :body {:error "down"}})}
      (match?
       {:status 502}
       (servlet/request {:method  :post
                         :uri     "/email/send"
                         :headers {"idempotency-key" (str (java.util.UUID/randomUUID))
                                   "content-type"   "application/json"}
                         :body    valid-email-body})))))

(defflow send-email-idempotency-test
  (flow "segunda requisição com mesma Idempotency-Key retorna 409 (corpo em cache) sem reenvio"
    (http-client/with-responses
      {:mailgun-send (constantly {:status 200 :body {:success true}})}
      (flow "duas requisições com a mesma chave"
        (match?
         {:status 200
          :body   (m/embeds {:status :sent})}
         (servlet/request {:method  :post
                           :uri     "/email/send"
                           :headers {"idempotency-key" idempotency-replay-scenario-key
                                     "content-type"   "application/json"}
                           :body    valid-email-body}))
        (match?
         {:status 409
          :body   (m/embeds {:status :sent})}
         (servlet/request {:method  :post
                           :uri     "/email/send"
                           :headers {"idempotency-key" idempotency-replay-scenario-key
                                     "content-type"   "application/json"}
                           :body    valid-email-body}))))))

(defflow send-email-missing-idempotency-key-test
  (flow "retorna 400 quando Idempotency-Key está ausente"
    (match?
     {:status 400}
     (servlet/request {:method :post
                       :uri    "/email/send"
                       :body   valid-email-body}))))
