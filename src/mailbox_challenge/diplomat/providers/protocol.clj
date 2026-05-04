(ns mailbox-challenge.diplomat.providers.protocol)

(defprotocol EmailProvider
  (provider-name [this]
    "Retorna o keyword identificador do provedor (ex: :mailgun).")
  (send-email! [this email http]
    "Tenta enviar o e-mail. Retorna true em sucesso. Lança exception em falha."))
