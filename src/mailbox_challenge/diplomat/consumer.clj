(ns mailbox-challenge.diplomat.consumer
  (:require [common-io.services.auth :as auth]
            [mailbox-challenge.config :as config]))

(def settings
  (merge
   (auth/token-revocation-consumer-settings {:service-name config/service-name :customer-facing? false})))

(comment
  ;; you'll need to add to this namespace
  (require '[clockwise.api :as t]
           '[common-core.schema :as schema]
           '[schema.core :as s])

  (def greeting-schema (schema/loose-schema {:greeting s/Str}))

  (s/defn greeting-handler
    "Demonstrates a typical consumer function."
    [message meta {:keys [clock] :as components}]
    (println "The following greeting has been received: " message " (at " (t/now clock) ")"))

  (def settings
    {:new-greeting {:handler   greeting-handler
                    :schema    greeting-schema
                    :topic-str "NEW-GREETING"}}))
