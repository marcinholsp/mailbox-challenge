(ns mailbox-challenge.diplomat.adapters.email
  (:require [datomic.api :as d]
            [mailbox-challenge.models.email :as models.email]
            [mailbox-challenge.wire.in.email :as wire.in.email]
            [mailbox-challenge.wire.out.email :as wire.out.email]
            [schema.core :as s])
  (:import [java.util Date]))

(s/defn wire->model :- models.email/Email
  "Constrói o modelo de domínio a partir do body HTTP de entrada.
   Gera :id via squuid (time-ordered UUID) e inicializa status como :pending."
  [wire-in    :- wire.in.email/SendEmailRequest
   idempotency-key :- s/Str
   now        :- Date]
  {:id              (d/squuid)
   :idempotency-key idempotency-key
   :to              (:to wire-in)
   :from            (:from wire-in)
   :subject         (:subject wire-in)
   :body            (:body wire-in)
   :status          :pending
   :provider        nil
   :sent-at         nil
   :created-at      now})

(s/defn model->datomic
  [email :- models.email/Email]
  (cond-> {:email/id              (:id email)
           :email/idempotency-key (:idempotency-key email)
           :email/to              (:to email)
           :email/from            (:from email)
           :email/subject         (:subject email)
           :email/body            (:body email)
           :email/status          (:status email)
           :email/created-at      (:created-at email)}
    (:provider email) (assoc :email/provider (:provider email))
    (:sent-at email)  (assoc :email/sent-at (:sent-at email))))

(s/defn datomic->model :- models.email/Email
  "Converte entity map Datomic para modelo de domínio.
   Campos opcionais (:provider, :sent-at) podem ser nil."
  [email-entity]
  {:id              (:email/id email-entity)
   :idempotency-key (:email/idempotency-key email-entity)
   :to              (:email/to email-entity)
   :from            (:email/from email-entity)
   :subject         (:email/subject email-entity)
   :body            (:email/body email-entity)
   :status          (:email/status email-entity)
   :provider        (:email/provider email-entity)
   :sent-at         (:email/sent-at email-entity)
   :created-at      (:email/created-at email-entity)})

(s/defn model->wire-out :- wire.out.email/EmailResponse
  "Projeta o modelo de domínio para o schema de saída HTTP (apenas campos públicos)."
  [email :- models.email/Email]
  {:id       (:id email)
   :status   (:status email)
   :provider (:provider email)
   :sent-at  (:sent-at email)})
