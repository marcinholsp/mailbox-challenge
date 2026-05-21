(ns mailbox-challenge.diplomat.http-server
  (:require [clockwise.api :as t]
            [clojure.set :as set]
            [clojure.string :as str]
            [common-datomic.protocols.datomic :as d-pro]
            [common-finagle.interceptors.context :as finagle]
            [common-io.interceptors.adapt :as adapt]
            [common-io.interceptors.auth :as auth]
            [common-io.interceptors.doc :as doc]
            [common-io.interceptors.errors :as errors]
            [common-io.interceptors.identity :as identity]
            [common-io.interceptors.instrument :as instrument]
            [common-io.interceptors.logging :as logging]
            [common-io.interceptors.pedestal-exporter :as pedestal-exporter]
            [common-io.interceptors.visibility :as visibility]
            [common-io.interceptors.wire :as wire]
            [common-tracing.interceptors.http :as i.trace]
            [io.pedestal.http.route :refer [expand-routes]]
            [mailbox-challenge.config :as config]
            [mailbox-challenge.controllers.email :as controllers.email]
            [mailbox-challenge.diplomat.adapters.email :as adapters.email]
            [mailbox-challenge.diplomat.datomic.email :as datomic.email]
            [mailbox-challenge.diplomat.providers.mailgun :as providers.mailgun]
            [mailbox-challenge.diplomat.providers.sendgrid :as providers.sendgrid]
            [mailbox-challenge.wire.in.email :as wire.in.email]
            [mailbox-challenge.wire.out.email :as wire.out.email]
            [mailbox-challenge.wire.out.version :as wire.out.version]))

(defn- providers
  "Retorna a lista ordenada de provedores (mesma ordem de logic/provider-priority)."
  []
  [(providers.mailgun/new-mailgun)
   (providers.sendgrid/new-sendgrid)])

(defn- idempotency-key
  [request]
  (some (fn [[k v]]
          (let [k' (if (string? k) (str/lower-case k) (str/lower-case (name k)))]
            (when (= "idempotency-key" k') v)))
        (:headers request)))

(defn current-version
  [{{config :config} :components}]
  {:status 200 :body {:version (config/version config)}})

(defn send-email
  [{:keys [data components] :as request}]
  (let [{:keys [datomic http clock]} components
        conn   (d-pro/conn datomic)
        id-key (idempotency-key request)]
    (if-not id-key
      {:status 400 :body {:error "Idempotency-Key header is required"}}
      (if-let [existing (datomic.email/find-by-idempotency-key id-key conn)]
        {:status 409
         :body (adapters.email/model->wire-out (adapters.email/datomic->model existing))}
        (let [now   (let [n (t/now clock)]
                      (java.util.Date/from
                       (if (instance? java.time.Instant n) n (.toInstant n))))
              email  (adapters.email/wire->model data id-key now)
              result (controllers.email/execute! email (providers) conn http clock)]
          (case (:status result)
            :sent   {:status 200 :body (adapters.email/model->wire-out result)}
            :failed {:status 502 :body {:error "All email providers failed"}}
            {:status 409 :body (adapters.email/model->wire-out result)}))))))

(defn get-email-stats
  [{:keys [components]}]
  (let [conn (d-pro/conn (:datomic components))]
    {:status 200 :body (controllers.email/get-stats conn)}))

;; Endpoints mock para dev/testes (simulam as APIs externas de e-mail)
(defn mock-mailgun-send [_]
  {:status 200 :body {:success true}})

(defn mock-sendgrid-send [_]
  {:status 200 :body {:success true}})

(def common-interceptors
  [(i.trace/tracer-interceptor)
   (instrument/instrument)
   (visibility/cid)
   (errors/catch-externalize)
   wire/to-wire
   (errors/catch)
   (pedestal-exporter/pedestal-exporter)
   wire/from-wire
   (identity/add-identity)
   (finagle/context)
   (logging/log)])

(def default-routes
  #{["/api/version"
     :get (conj common-interceptors
                (doc/desc "Current version")
                (auth/public)
                (adapt/externalize! {200 wire.out.version/Version})
                current-version)
     :route-name :version]

    ["/email/send"
     :post (conj common-interceptors
                 (doc/desc "Send an email via configured provider with fallback")
                 (auth/public)
                 (adapt/coerce! wire.in.email/SendEmailRequest)
                 (adapt/externalize! {200 wire.out.email/EmailResponse
                                      409 wire.out.email/EmailResponse})
                 send-email)
     :route-name :email-send]

    ["/email/stats"
     :get (conj common-interceptors
                (doc/desc "Email delivery statistics")
                (auth/public)
                (adapt/externalize! {200 wire.out.email/EmailStats})
                get-email-stats)
     :route-name :email-stats]

    ["/mock/mailgun/send"
     :post (conj common-interceptors
                 (auth/public)
                 mock-mailgun-send)
     :route-name :mock-mailgun-send]

    ["/mock/sendgrid/send"
     :post (conj common-interceptors
                 (auth/public)
                 mock-sendgrid-send)
     :route-name :mock-sendgrid-send]})

(def routes
  (expand-routes
   (set/union
     default-routes)))
