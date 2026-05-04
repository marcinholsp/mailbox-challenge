(ns mailbox-challenge.logic.email
  (:require [schema.core :as s]))

(def provider-priority
  "Ordem de prioridade dos provedores. Primeiro = primário.
   Para adicionar um novo provedor, basta appendar ao vetor."
  [:mailgun :sendgrid])

(s/defn next-provider :- (s/maybe s/Keyword)
  "Retorna o próximo provedor disponível, excluindo os que já falharam.
   Retorna nil se todos os provedores estiverem esgotados."
  [failed-providers :- #{s/Keyword}]
  (first (remove failed-providers provider-priority)))

(s/defn all-providers-exhausted? :- s/Bool
  "Verdadeiro quando não há mais provedores para tentar."
  [failed-providers :- #{s/Keyword}]
  (nil? (next-provider failed-providers)))
