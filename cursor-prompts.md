# Cursor Prompts — mailbox-challenge

> **Contexto para o Cursor:** este é o `mailbox-challenge`, um serviço Clojure no estilo Nubank
> que abstrai provedores de e-mail (Mailgun, SendGrid). Usa Pedestal (HTTP), Datomic (persistência),
> Prismatic Schema (validação) e segue a Diplomat Architecture da Nubank.
>
> Execute cada prompt na ordem abaixo. Cada um é independente e autocontido.

---

## Prompt 1 — Wire schemas de entrada e saída (`wire/in` e `wire/out`)

**Arquivo alvo:** `src/mailbox_challenge/wire/in/email.clj`  
**Arquivo alvo:** `src/mailbox_challenge/wire/out/email.clj`

Os dois arquivos existem mas estão vazios. Implemente-os seguindo o padrão Nubank de `common-core.schema`.

**`wire/in/email.clj`** — schema de entrada HTTP. O header `Idempotency-Key` é obrigatório e vem
separado (não faz parte do body). O body contém apenas os campos de conteúdo do e-mail:

```clojure
(ns mailbox-challenge.wire.in.email
  (:require [common-core.schema :as schema]
            [schema.core :as s]))

(s/defschema SendEmailRequest
  (schema/loose-schema
   {:to      {:schema s/Str :required true}
    :from    {:schema s/Str :required true}
    :subject {:schema s/Str :required true}
    :body    {:schema s/Str :required true}}))
```

**`wire/out/email.clj`** — schema de saída HTTP. `provider` e `sent-at` são `(s/maybe ...)` porque
podem ser `nil` em casos de falha:

```clojure
(ns mailbox-challenge.wire.out.email
  (:require [schema.core :as s]))

(s/defschema EmailResponse
  {:id       s/Uuid
   :status   s/Keyword
   :provider (s/maybe s/Keyword)
   :sent-at  (s/maybe java.util.Date)})
```

Certifique-se de que os namespaces correspondem à estrutura de arquivos.

---

## Prompt 2 — Completar o Datomic diplomat (`diplomat/datomic/email.clj`)

**Arquivo alvo:** `src/mailbox_challenge/diplomat/datomic/email.clj`

O arquivo já tem o `schema` e `save-email!`. Adicione as duas funções que faltam:

**`find-by-idempotency-key`** — busca um e-mail pelo idempotency key. Use scalar binding
(`:find ?e .`) e retorne o entity map via `d/pull`, ou `nil` se não encontrado:

```clojure
(s/defn find-by-idempotency-key
  [idempotency-key :- s/Str
   datomic]
  (when-let [eid (d/q '[:find ?e .
                         :in $ ?key
                         :where [?e :email/idempotency-key ?key]]
                      (d/db datomic)
                      idempotency-key)]
    (d/pull (d/db datomic)
            [:email/id :email/idempotency-key :email/to :email/from
             :email/subject :email/body :email/status :email/provider
             :email/sent-at :email/created-at]
            eid)))
```

**`update-status!`** — atualiza status, provider e sent-at de um e-mail existente. Use `cond->` para
incluir `:email/provider` e `:email/sent-at` apenas quando não forem `nil`:

```clojure
(s/defn update-status!
  [id       :- s/Uuid
   status   :- s/Keyword
   provider :- (s/maybe s/Keyword)
   sent-at  :- (s/maybe java.util.Date)
   datomic]
  (let [tx (cond-> {:email/id     id
                    :email/status status}
             provider (assoc :email/provider provider)
             sent-at  (assoc :email/sent-at sent-at))]
    @(d/transact datomic [tx])))
```

Adicione `[schema.core :as s]` aos requires se ainda não estiver lá.

---

## Prompt 3 — Registrar schema Datomic em `config.clj`

**Arquivo alvo:** `src/mailbox_challenge/diplomat/datomic/config.clj`

O schema de email foi definido em `diplomat/datomic/email.clj` mas ainda não está registrado.
Adicione o require e inclua o schema na concatenação de `schemata`:

```clojure
(ns mailbox-challenge.diplomat.datomic.config
  (:require [common-datomic.extensions.transformations :refer [std-xforms]]
            [common-datomic.functions :as functions]
            [common-datomic.utils :as utils]
            [mailbox-challenge.config :as config]
            [mailbox-challenge.diplomat.datomic.email :as datomic.email])) ;; <-- adicionar

(def schemata
  (let [skeletons []
        enums     []
        functions []]
    (concat [(map functions/function-datom functions)]
            [(utils/read-edn-schemata ["extensions/db.transform.edn"])]
            (map utils/gen-datomic-enum-seq enums)
            (map utils/skeleton->datomic-schema skeletons)
            [datomic.email/schema]))) ;; <-- adicionar
```

---

## Prompt 4 — Corrigir e completar os adapters (`diplomat/adapters/email.clj`)

**Arquivo alvo:** `src/mailbox_challenge/diplomat/adapters/email.clj`

O arquivo tem `model->datomic` correto, mas `datomic->model` está errado (chama
`models.email/email` que não existe — o schema é um `defschema`, não um record). Corrija e adicione
`wire->model` e `model->wire-out`.

Implemente o arquivo completo:

```clojure
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
```

---

## Prompt 5 — Protocolo e implementações de provedores

**Arquivos novos:**
- `src/mailbox_challenge/diplomat/providers/protocol.clj`
- `src/mailbox_challenge/diplomat/providers/mailgun.clj`
- `src/mailbox_challenge/diplomat/providers/sendgrid.clj`

Crie os três arquivos. O protocolo define a interface; cada record implementa um provedor.

**`protocol.clj`:**
```clojure
(ns mailbox-challenge.diplomat.providers.protocol)

(defprotocol EmailProvider
  (provider-name [this]
    "Retorna o keyword identificador do provedor (ex: :mailgun).")
  (send-email! [this email http]
    "Tenta enviar o e-mail. Retorna true em sucesso. Lança exception em falha."))
```

**`mailgun.clj`:**
```clojure
(ns mailbox-challenge.diplomat.providers.mailgun
  (:require [common-core.protocols.http-client :as protocols.http-client :refer [IHttpClient]]
            [mailbox-challenge.diplomat.providers.protocol :as provider.protocol]
            [mailbox-challenge.models.email :as models.email]
            [schema.core :as s]))

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
```

**`sendgrid.clj`:** estrutura idêntica à do Mailgun, trocando `:mailgun-send` por `:sendgrid-send`
e o `provider-name` para `:sendgrid`. Crie `new-sendgrid` como factory function.

---

## Prompt 6 — HTTP client bookmarks (`diplomat/http_client.clj`)

**Arquivo alvo:** `src/mailbox_challenge/diplomat/http_client.clj`

O mapa `bookmarks-settings` está vazio. Substitua o arquivo completo com os bookmarks para os
dois provedores simulados. As URLs apontam para endpoints mock no próprio serviço (definidos no
`http_server.clj` no próximo prompt):

```clojure
(ns mailbox-challenge.diplomat.http-client
  (:require [common-core.protocols.http-client :as protocols.http-client :refer [IHttpClient]]
            [schema.core :as s]))

(def bookmarks-settings
  {:mailgun-send
   {:url     "zk://mailbox-challenge/mock/mailgun/send"
    :service :mailbox-challenge
    :methods {:post {:schema-req   {:to s/Str :from s/Str :subject s/Str :body s/Str}
                     :schema-resps {200 {:success s/Bool}
                                    400 {:error s/Str}
                                    500 {:error s/Str}}}}}
   :sendgrid-send
   {:url     "zk://mailbox-challenge/mock/sendgrid/send"
    :service :mailbox-challenge
    :methods {:post {:schema-req   {:to s/Str :from s/Str :subject s/Str :body s/Str}
                     :schema-resps {200 {:success s/Bool}
                                    400 {:error s/Str}
                                    500 {:error s/Str}}}}}})
```

> **Nota:** em testes de integração, o `mock-http` intercepta as chamadas a `:mailgun-send` e
> `:sendgrid-send` via `with-responses`. Nenhuma chamada de rede real é feita.

---

## Prompt 7 — Lógica pura de seleção de provedor (`logic/email.clj`)

**Arquivo novo:** `src/mailbox_challenge/logic/email.clj`

Funções puras, sem efeitos colaterais. Crie o arquivo:

```clojure
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
```

---

## Prompt 8 — Controller (`controllers/email.clj`)

**Arquivo novo:** `src/mailbox_challenge/controllers/email.clj`

O controller orquestra o fluxo completo: idempotência → persistência → fallback de provedores.
Atenção: `attempt-send` é separado do `loop/recur` porque a JVM não permite `recur` dentro de
blocos `try`.

```clojure
(ns mailbox-challenge.controllers.email
  (:require [clockwise.api :as t]
            [common-core.visibility :as vis]
            [mailbox-challenge.diplomat.adapters.email :as adapters.email]
            [mailbox-challenge.diplomat.datomic.email :as datomic.email]
            [mailbox-challenge.diplomat.providers.protocol :as provider.protocol]
            [mailbox-challenge.logic.email :as logic.email]
            [mailbox-challenge.models.email :as models.email]
            [schema.core :as s])
  (:import [java.util Date]))

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
              {:keys [result]} (attempt-send provider email http)]
          (if (= result :success)
            provider-kw
            (recur (conj failed provider-kw))))))))

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
      (let [now          (Date/from (t/now clock))
            provider-kw  (try-send-with-fallback providers email http)]
        (if provider-kw
          (do
            (datomic.email/update-status! (:id email) :sent provider-kw now datomic)
            (assoc email :status :sent :provider provider-kw :sent-at now))
          (do
            (datomic.email/update-status! (:id email) :failed nil nil datomic)
            (assoc email :status :failed)))))))
```

---

## Prompt 9 — Rotas HTTP e mock de provedores (`diplomat/http_server.clj`)

**Arquivo alvo:** `src/mailbox_challenge/diplomat/http_server.clj`

Adicione:
1. O handler `send-email` e a rota `POST /email/send`
2. Rotas mock `/mock/mailgun/send` e `/mock/sendgrid/send` para dev/testes

O header `Idempotency-Key` é obrigatório — retorne 400 se ausente.

Adicione os requires necessários ao namespace existente:

```clojure
[mailbox-challenge.controllers.email :as controllers.email]
[mailbox-challenge.diplomat.adapters.email :as adapters.email]
[mailbox-challenge.diplomat.providers.mailgun :as providers.mailgun]
[mailbox-challenge.diplomat.providers.sendgrid :as providers.sendgrid]
[mailbox-challenge.wire.in.email :as wire.in.email]
[mailbox-challenge.wire.out.email :as wire.out.email]
```

Adicione os handlers:

```clojure
(defn- providers
  "Retorna a lista ordenada de provedores (mesma ordem de logic/provider-priority)."
  []
  [(providers.mailgun/new-mailgun)
   (providers.sendgrid/new-sendgrid)])

(defn send-email
  [{:keys [data request components]}]
  (let [{:keys [datomic http clock]} components
        idempotency-key (get-in request [:headers "idempotency-key"])]
    (if-not idempotency-key
      {:status 400 :body {:error "Idempotency-Key header is required"}}
      (let [now    (java.util.Date/from (clockwise.api/now clock))
            email  (adapters.email/wire->model data idempotency-key now)
            result (controllers.email/execute! email (providers) datomic http clock)]
        (case (:status result)
          :sent   {:status 200 :body (adapters.email/model->wire-out result)}
          :failed {:status 502 :body {:error "All email providers failed"}}
          {:status 409 :body (adapters.email/model->wire-out result)})))))

;; Endpoints mock para dev/testes (simulam as APIs externas de e-mail)
(defn mock-mailgun-send [_]
  {:status 200 :body {:success true}})

(defn mock-sendgrid-send [_]
  {:status 200 :body {:success true}})
```

Adicione as rotas ao `default-routes`:

```clojure
["/email/send"
 :post (conj common-interceptors
             (doc/desc "Send an email via configured provider with fallback")
             (auth/public)
             (adapt/coerce! wire.in.email/SendEmailRequest)
             (adapt/externalize! {200 wire.out.email/EmailResponse
                                  409 wire.out.email/EmailResponse})
             send-email)
 :route-name :email-send]

["/mock/mailgun/send"
 :post (conj common-interceptors
             (auth/public)
             mock-mailgun-send)
 :route-name :mock-mailgun-send]

["/mock/sendgrid/send"
 :post (conj common-interceptors
             (auth/public)
             mock-sendgrid-send)
 :route-name :mock-sendgrid-send]
```

> **Importante:** `adapt/coerce!` no Nubank valida e coerce o body. `adapt/externalize!` só
> é necessário nas rotas em que o schema de saída precisa ser validado.

---

## Prompt 10 — Testes unitários da lógica (`test/unit/`)

**Arquivos novos:**
- `test/unit/mailbox_challenge/logic/email_test.clj`
- `test/unit/mailbox_challenge/adapters/email_test.clj`

**`logic/email_test.clj`** — teste das funções puras:

```clojure
(ns mailbox-challenge.logic.email-test
  (:require [clojure.test :refer [deftest is testing]]
            [mailbox-challenge.logic.email :as logic.email]))

(deftest next-provider-test
  (testing "retorna o provedor primário quando nenhum falhou"
    (is (= :mailgun (logic.email/next-provider #{}))))

  (testing "pula o primário quando ele falhou"
    (is (= :sendgrid (logic.email/next-provider #{:mailgun}))))

  (testing "retorna nil quando todos os provedores falharam"
    (is (nil? (logic.email/next-provider #{:mailgun :sendgrid})))))

(deftest all-providers-exhausted-test
  (testing "false quando ainda há provedores disponíveis"
    (is (false? (logic.email/all-providers-exhausted? #{})))
    (is (false? (logic.email/all-providers-exhausted? #{:mailgun}))))

  (testing "true quando todos falharam"
    (is (true? (logic.email/all-providers-exhausted? #{:mailgun :sendgrid})))))
```

**`adapters/email_test.clj`** — teste dos adapters:

```clojure
(ns mailbox-challenge.adapters.email-test
  (:require [clojure.test :refer [deftest is testing]]
            [mailbox-challenge.diplomat.adapters.email :as adapters.email])
  (:import [java.util Date]))

(def now (Date.))

(def wire-in
  {:to      "dest@example.com"
   :from    "noreply@nubank.com.br"
   :subject "Seu cartão aprovado"
   :body    "Olá, seu cartão foi aprovado."})

(deftest wire->model-test
  (testing "gera UUID e inicializa status :pending"
    (let [model (adapters.email/wire->model wire-in "key-123" now)]
      (is (uuid? (:id model)))
      (is (= :pending (:status model)))
      (is (= "key-123" (:idempotency-key model)))
      (is (nil? (:provider model)))
      (is (nil? (:sent-at model)))
      (is (= now (:created-at model))))))

(deftest model->wire-out-test
  (testing "projeta apenas campos públicos"
    (let [model {:id              #uuid "00000000-0000-0000-0000-000000000001"
                 :idempotency-key "k"
                 :to              "a@b.com"
                 :from            "x@y.com"
                 :subject         "s"
                 :body            "b"
                 :status          :sent
                 :provider        :mailgun
                 :sent-at         now
                 :created-at      now}
          out   (adapters.email/model->wire-out model)]
      (is (= #{:id :status :provider :sent-at} (set (keys out))))
      (is (= :sent (:status out)))
      (is (= :mailgun (:provider out))))))

(deftest datomic-roundtrip-test
  (testing "model->datomic->model preserva os dados"
    (let [model {:id              #uuid "00000000-0000-0000-0000-000000000002"
                 :idempotency-key "idem-k"
                 :to              "a@b.com"
                 :from            "x@y.com"
                 :subject         "sub"
                 :body            "bod"
                 :status          :sent
                 :provider        :sendgrid
                 :sent-at         now
                 :created-at      now}
          datomic-map (adapters.email/model->datomic model)
          ;; Simula o pull do Datomic (namespace :email/)
          datomic-entity (clojure.set/rename-keys datomic-map
                                                  {:email/id              :email/id
                                                   :email/idempotency-key :email/idempotency-key})
          restored (adapters.email/datomic->model datomic-map)]
      (is (= (:id model) (:email/id datomic-map)))
      (is (= (:status model) (:email/status datomic-map))))))
```

Execute com: `lein with-profile +unit test`

---

## Prompt 11 — Testes de integração (`test/integration/`)

**Arquivo novo:** `test/integration/integration/email_test.clj`

Use `state-flow` com `defflow` (já configurado em `integration.aux.init`). O `mock-http` do
sistema de teste intercepta chamadas a `:mailgun-send` e `:sendgrid-send`.

```clojure
(ns integration.email-test
  (:require [integration.aux.init :as init :refer [defflow]]
            [matcher-combinators.matchers :as m]
            [state-flow.api :refer [flow match?]]
            [state-flow.helpers.component.servlet :as servlet]
            [state-flow.helpers.component.http-client :as http-client]))

(def valid-email-body
  {:to      "cliente@example.com"
   :from    "noreply@nubank.com.br"
   :subject "Seu cartão chegou"
   :body    "Olá, seu cartão foi enviado."})

(defflow send-email-success-test

  (flow "envia e-mail com sucesso via provedor primário (mailgun)"
    (http-client/with-responses
      {:mailgun-send  (constantly {:status 200 :body {:success true}})}

      (match?
       {:status 200
        :body   (m/embeds {:status   "sent"
                           :provider "mailgun"})}
       (servlet/request {:method  :post
                         :uri     "/email/send"
                         :headers {"idempotency-key" (str (random-uuid))
                                   "content-type"   "application/json"}
                         :body    valid-email-body})))))

(defflow send-email-fallback-test

  (flow "usa sendgrid quando mailgun falha"
    (http-client/with-responses
      {:mailgun-send  (constantly {:status 500 :body {:error "timeout"}})
       :sendgrid-send (constantly {:status 200 :body {:success true}})}

      (match?
       {:status 200
        :body   (m/embeds {:status   "sent"
                           :provider "sendgrid"})}
       (servlet/request {:method  :post
                         :uri     "/email/send"
                         :headers {"idempotency-key" (str (random-uuid))
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
                         :headers {"idempotency-key" (str (random-uuid))
                                   "content-type"   "application/json"}
                         :body    valid-email-body})))))

(defflow send-email-idempotency-test

  (flow "segunda requisição com mesma Idempotency-Key retorna resultado cacheado sem reenviar"
    (let [idempotency-key (str (random-uuid))]
      (http-client/with-responses
        {:mailgun-send (constantly {:status 200 :body {:success true}})}

        (flow "primeira requisição — sucesso"
          (match?
           {:status 200
            :body   (m/embeds {:status "sent"})}
           (servlet/request {:method  :post
                             :uri     "/email/send"
                             :headers {"idempotency-key" idempotency-key}
                             :body    valid-email-body})))

        (flow "segunda requisição com mesma chave — retorna 409 sem chamar provedores"
          (match?
           {:status 409
            :body   (m/embeds {:status "sent"})}
           (servlet/request {:method  :post
                             :uri     "/email/send"
                             :headers {"idempotency-key" idempotency-key}
                             :body    valid-email-body})))))))

(defflow send-email-missing-idempotency-key-test

  (flow "retorna 400 quando Idempotency-Key está ausente"
    (match?
     {:status 400}
     (servlet/request {:method :post
                       :uri    "/email/send"
                       :body   valid-email-body}))))
```

Execute com: `lein with-profile +integration test`

---

## Checklist de Verificação Final

Depois de implementar todos os prompts, valide:

```bash
# 1. Testes unitários
lein with-profile +unit test

# 2. Testes de integração
lein with-profile +integration test

# 3. Lint (padrão Nubank)
lein lint

# 4. Smoke test manual (REPL rodando)
curl -X POST http://localhost:8080/email/send \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"to":"cliente@example.com","from":"noreply@nubank.com.br","subject":"Oi","body":"Teste"}'

# 5. Replay com a mesma chave → deve retornar 409
KEY=$(uuidgen)
curl -X POST http://localhost:8080/email/send -H "Idempotency-Key: $KEY" -d '{...}'
curl -X POST http://localhost:8080/email/send -H "Idempotency-Key: $KEY" -d '{...}'
```

### Arquivos criados/modificados neste desafio

| Arquivo | Status |
|---------|--------|
| `wire/in/email.clj` | Criar (Prompt 1) |
| `wire/out/email.clj` | Criar (Prompt 1) |
| `diplomat/datomic/email.clj` | Modificar — adicionar funções (Prompt 2) |
| `diplomat/datomic/config.clj` | Modificar — registrar schema (Prompt 3) |
| `diplomat/adapters/email.clj` | Modificar — corrigir + completar (Prompt 4) |
| `diplomat/providers/protocol.clj` | Criar (Prompt 5) |
| `diplomat/providers/mailgun.clj` | Criar (Prompt 5) |
| `diplomat/providers/sendgrid.clj` | Criar (Prompt 5) |
| `diplomat/http_client.clj` | Modificar — bookmarks (Prompt 6) |
| `logic/email.clj` | Criar (Prompt 7) |
| `controllers/email.clj` | Criar (Prompt 8) |
| `diplomat/http_server.clj` | Modificar — rotas + handlers (Prompt 9) |
| `test/unit/.../logic/email_test.clj` | Criar (Prompt 10) |
| `test/unit/.../adapters/email_test.clj` | Criar (Prompt 10) |
| `test/integration/.../email_test.clj` | Criar (Prompt 11) |
