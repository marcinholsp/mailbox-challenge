(ns mailbox-challenge.controllers.email
  (:require [clockwise.api :as t]
            [common-core.visibility :as vis]
            [mailbox-challenge.diplomat.adapters.email :as adapters.email]
            [mailbox-challenge.diplomat.datomic.email :as datomic.email]
            [mailbox-challenge.diplomat.providers.protocol :as provider.protocol]
            [mailbox-challenge.logic.email :as logic.email]
            [mailbox-challenge.models.email :as models.email]
            [schema.core :as s]))

(defn- provider-by-name
  "Encontra o record de provedor pelo keyword de nome."
  [providers provider-kw]
  (first (filter #(= provider-kw (provider.protocol/provider-name %)) providers)))

(defn- attempt-send
  "Tenta enviar o e-mail via um único provedor.
   Retorna {:result :success} ou {:result :failure :reason msg}.
   Isolado do loop principal para contornar a restrição da JVM com recur+try."
  [provider email http]
  (try
    (let [success? (provider.protocol/send-email! provider email http)]
      (if success?
        {:result :success}
        {:result :failure :reason "provider returned false"}))
    (catch Exception e
      (vis/warn :email/provider-failed
                {:provider (provider.protocol/provider-name provider)
                 :reason   (.getMessage e)})
      {:result :failure :reason (.getMessage e)})))

(defn- try-send-with-fallback
  "Itera os provedores em ordem de prioridade até um ter sucesso ou todos falharem.
   Retorna o keyword do provedor que teve sucesso, ou nil se todos falharam."
  [providers email http]
  (loop [failed #{}]
    (let [provider-kw (logic.email/next-provider failed)]
      (if (nil? provider-kw)
        nil
        (let [provider (provider-by-name providers provider-kw)
              {:keys [result]} (if provider
                                 (attempt-send provider email http)
                                 {:result :failure})]
          (if (= result :success)
            provider-kw
            (recur (conj failed provider-kw))))))))

(defn get-stats
  [datomic]
  (let [total   (datomic.email/count-total-sent datomic)
        by-prov (datomic.email/count-sent-by-provider datomic)]
    {:total-sent  total
     :by-provider (mapv (fn [[provider cnt]] {:provider provider :count cnt}) by-prov)}))

(s/defn execute! :- models.email/Email
  "Orquestra o envio de e-mail com idempotência e fallback de provedores.

   Fluxo:
   1. Checa Datomic pela idempotency-key → retorna existente se status=:sent
   2. Salva o registro com status=:pending
   3. Itera provedores em ordem; no sucesso atualiza status=:sent
   4. Se todos falharem, atualiza status=:failed e retorna o modelo"
  [email     :- models.email/Email
   providers :- [s/Any]
   datomic
   http
   clock]
  (if-let [existing (datomic.email/find-by-idempotency-key (:idempotency-key email) datomic)]
    (let [existing-model (adapters.email/datomic->model existing)]
      (vis/info :email/idempotent-response {:idempotency-key (:idempotency-key email)})
      existing-model)
    (do
      (datomic.email/save-email! datomic email)
      (let [now         (let [n (t/now clock)]
                          (java.util.Date/from
                           (if (instance? java.time.Instant n) n (.toInstant n))))
            provider-kw (try-send-with-fallback providers email http)]
        (if provider-kw
          (do
            (datomic.email/update-status! (:id email) :sent provider-kw now datomic)
            (assoc email :status :sent :provider provider-kw :sent-at now))
          (do
            (datomic.email/update-status! (:id email) :failed nil nil datomic)
            (assoc email :status :failed)))))))
