(ns mailbox-challenge.components
  (:refer-clojure :exclude [test])
  (:require [authorization.nu.service.components :as authorization.components]
            [clockwise.api :as t]
            [com.stuartsierra.component :as component]
            [common-core.components.config :as components.config]
            [common-core.components.log-stacker :as log-stacker]
            [common-core.fault-tolerance.circuit-breaker-strategy-rate :as circuit-breaker]
            [common-core.system :as system]
            [common-core.time]
            [common-core.visibility :as vis]
            [common-datomic.components.datomic :as datomic]
            [common-db.components.mock-s3-store :as mock-s3-store]
            [common-db.components.s3-store :as s3-store]
            [common-finagle.components.announcer :as announcer]
            [common-finagle.components.http-client :as finagle]
            [common-finagle.components.null-announcer :as null-announcer]
            [common-http-client.components.http :as http]
            [common-http-client.components.http.config :as http.config]
            [common-http-client.components.mock-http :as mock-http]
            [common-http-client.components.routes :as routes]
            [common-io.components.container-servlet :as container-servlet]
            [common-io.components.debug-logger :as debug-logger]
            [common-io.components.embedded-servlet :as embedded-servlet]
            [common-io.components.health :as health]
            [common-io.components.mock-servlet :as mock-servlet]
            [common-io.components.mock-token-verifier :as mock-token-verifier]
            [common-io.components.operations-routes :as operations-routes]
            [common-io.components.service :as service]
            [common-io.components.token-verifier :as token-verifier]
            [common-io.components.webapp :as webapp]
            [common-kafka.components.consumer :as consumer]
            [common-kafka.components.impls.null-producer :as kafka.null-producer]
            [common-kafka.components.impls.producer :as kafka.simple-producer]
            [common-kafka.components.mock-consumer :as mock-consumer]
            [common-kafka.components.mock-producer :as mock-producer]
            [common-kafka.components.producer :as kafka.producer]
            [common-metrics.components.filters :as filters]
            [common-metrics.components.metrics :as metrics]
            [common-metrics.components.mock-prometheus :as mock-prometheus]
            [common-metrics.components.prometheus :as prometheus]
            [common-repl.components.repl :as repl]
            [mailbox-challenge.config :as config]
            [mailbox-challenge.diplomat.consumer :as diplomat.consumer]
            [mailbox-challenge.diplomat.datomic.config :as datomic.config]
            [mailbox-challenge.diplomat.http-client :as http-client]
            [mailbox-challenge.diplomat.http-server]
            [mailbox-challenge.diplomat.producer :as diplomat.producer]
            [secrets.nu.service.ops :as secrets.nu.service.ops]))

(def http-defaults (assoc http.config/transit-transit-defaults :user-agent config/user-agent))

(def healthcheckable-components
  [:metrics :consumer :datomic :http])

(def webapp-deps
  [:config :clock :crypto-ops :datomic :http :metrics :routes :token-verifier :producer :prometheus :policy-authorizer :authorization-policies])

(defn base [{circuit-breaker-channel :circuit-breaker-channel :as initial-state}]
  (component/system-map
    :config                  (components.config/new-config config/service-name)
    :crypto-ops              (component/using (secrets.nu.service.ops/new-crypto-ops-all) [:config])
    :clock                   (t/default-zone-clock)
    :circuit-breaker-factory (component/using (circuit-breaker/new-channel-circuit-breaker circuit-breaker-channel) [:metrics])
    :filters                 (filters/new-filters)
    :datomic                 (component/using (datomic/new-datomic datomic.config/settings) [:config :circuit-breaker-factory :metrics])
    :announcer               (component/using (announcer/new-announcer) [:config :servlet])
    :health                  (component/using (health/new-health) healthcheckable-components)
    :http-impl               (component/using (finagle/new-http-client) [:metrics :config :crypto-ops])
    :http                    (component/using (http/new-http http-defaults) [:config :routes :http-impl :metrics :crypto-ops])
    :producer-impl           (component/using (kafka.simple-producer/new-producer) [:config :metrics])
    :producer                (component/using (kafka.producer/new-producer diplomat.producer/settings) [:config :producer-impl :metrics :crypto-ops])
    :consumer                (component/using (consumer/new-consumer diplomat.consumer/settings) [:config :producer :webapp :metrics :circuit-breaker-factory :crypto-ops])
    :metrics                 (component/using (metrics/new-metrics) [:prometheus])
    :operations-routes       (component/using (operations-routes/new-operations-routes "ops") [:config :datomic :health :consumer :token-verifier :repl :routes :prometheus])
    :prometheus              (component/using (prometheus/new-prometheus) [:config])
    :repl                    (component/using (repl/new-repl) [:config])
    :policy-authorizer       (component/using (authorization.components/new-authorizer) [:config])
    :authorization-policies  (component/using (authorization.components/new-policies) [:config])
    :routes                  (component/using (routes/new-routes #'mailbox-challenge.diplomat.http-server/routes {:remote-routes http-client/bookmarks-settings}) [:config])
    :service                 (component/using (service/new-service) [:config :routes :operations-routes :webapp :metrics])
    :servlet                 (component/using (container-servlet/new-servlet initial-state) [:service])
    :s3-auth                 (component/using (s3-store/new-s3 :auth-bucket) [:config])
    :token-verifier          (component/using (token-verifier/new-token-verifier) [:config :s3-auth :crypto-ops])
    :webapp                  (component/using (webapp/new-webapp) webapp-deps)))

(defn local [initial-state]
  (merge (base initial-state)
         (component/system-map :servlet (component/using (embedded-servlet/new-servlet) [:service]))))

(defn test [initial-state]
  (merge (dissoc (base initial-state) :http-impl)
         (component/system-map
           :clock             (t/swappable-clock (t/default-zone-clock))
           :crypto-ops        (component/using (secrets.nu.service.ops/new-crypto-ops-all-test) [:config])
           :datomic           (component/using (datomic/new-datomic datomic.config/settings) [:config :circuit-breaker-factory :metrics :log-stacker])
           :token-verifier    (component/using (mock-token-verifier/new-mock-token-verifier) [:config :s3-auth :crypto-ops])
           :s3-auth           (component/using (mock-s3-store/new-mock-s3 {} :auth-bucket) [:config])
           :producer-impl     (component/using (kafka.null-producer/null-producer) [:config])
           :producer          (component/using (mock-producer/new-mock-producer diplomat.producer/settings) [:config :log-stacker :metrics :producer-impl])
           :consumer          (component/using (mock-consumer/new-mock-consumer diplomat.consumer/settings) [:config :log-stacker :metrics :producer :webapp])
           :servlet           (component/using (mock-servlet/new-servlet) [:service :log-stacker])
           :debug-logger      (debug-logger/new-debug-logger)
           :http              (component/using (mock-http/new-mock-http http-defaults http/raise-400+!) [:config :log-stacker :routes])
           :announcer         (null-announcer/null-announcer)
           :policy-authorizer (component/using (authorization.components/new-authorizer {:loader :resource}) [:config])
           :webapp            (component/using (webapp/new-webapp) (conj webapp-deps :debug-logger))
           :log-stacker       (log-stacker/new-log-stacker))))

(defn staging [initial-state]
  (base initial-state))

(defn sachem
  "This system contains all the components that are needed to run sachem.
  Segregating this avoids starting unused dependencies, just like DynamoDB."
  [_initial-state]
  (component/system-map
    :config              (components.config/new-config config/service-name)
    :announcer           (null-announcer/null-announcer)
    :producer-impl       (component/using (kafka.null-producer/null-producer) [:config])
    :producer            (component/using (mock-producer/new-mock-producer diplomat.producer/settings) [:config :producer-impl])
    :consumer            (component/using (mock-consumer/new-mock-consumer diplomat.consumer/settings) [:config :producer])
    :http                (component/using (mock-http/new-mock-http http-defaults) [:config :routes])
    :routes              (component/using (routes/new-routes #'mailbox-challenge.diplomat.http-server/routes {:remote-routes http-client/bookmarks-settings}) [:config])
    :operations-routes   (component/using (operations-routes/new-operations-routes "ops") [:config])
    :prometheus          (component/using (mock-prometheus/new-mock {}) [])
    :metrics             (component/using (metrics/new-metrics) [:prometheus])
    :service             (component/using (service/new-service) [:config :routes :operations-routes :webapp :announcer :metrics])
    :webapp              (component/using (webapp/new-webapp) [])))

(def systems-map {:local   local
                  :dev     local
                  :test    test
                  :staging staging
                  :sachem  sachem
                  :base    base})

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn start-sachem-system! []
  (system/bootstrap! config/service-name {:test (:sachem systems-map)} {}))

(defn stop-system! []
  (vis/info :log :service-shutdown)
  (system/stop-components!))

(defn register-shutdown-hook! []
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. stop-system!)))

(defn- deployed? [system]
  (-> system
      :config
      config/environment
      #{"staging" "prod"}))

(defn create-and-start-system!
  ([] (create-and-start-system! {}))
  ([initial-state]
   (let [system (system/bootstrap! config/service-name systems-map initial-state)]
     (when (deployed? system) (register-shutdown-hook!))
     system)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ensure-system-up! []
  (or (deref system/system)
      (create-and-start-system!)))
