(defproject mailbox-challenge "0.1.0-SNAPSHOT"
  :description "mailbox-challenge"
  :url "https://github.com/nubank/mailbox-challenge"
  :license {:name "Proprietary"}

  :plugins [[dev.nu/plug-n-play "2.10.0"]
            [dev.nu/managed-dependencies "0.126.0"]]

  :managed-dependencies/automatic-management? true

  :repositories ^:replace [["nu-codeartifact" {:url "https://maven.cicd.nubank.world"}]]
  :plugin-repositories ^:replace [["nu-codeartifact" {:url "https://maven.cicd.nubank.world"}]]

  :exclusions [log4j]

  ;; IMPORTANT: Order of dependencies matters!
  ;;
  ;; Leiningen uses Maven to manage dependencies. Maven creates a tree that includes our dependencies
  ;; and their dependencies. When determining which version of a dependency to use, Maven traverses
  ;; this tree. Then, it selects the first version it encounters.
  ;;
  ;; See also https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html
  ;;
  ;; Ideally, the order of dependencies definied here is:
  ;;   1. Clojure: Because we want to define ourselves which version of Clojure our services use
  ;;   2. common-core: Because it contains the most basic dependencies of a service (including schema)
  ;;   3. common-io: Because it contains the networking/server dependencies of a service (including pedestal)
  ;;   4. secrets.nu.service: Because it contains cryptographic stack that requires specific/newer versions of libraries
  ;;   5. Other Nubank libraries like common-datomic, common-http-client etc.: Because we prefer that these define dependencies that we test with
  ;;   6. External libraries
  :dependencies [[org.clojure/clojure]

                 [common-core/common-core]
                 [common-io/common-io]
                 [dev.nu/secrets.nu.service]
                 [dev.nu/authorization.nu.service]
                 [dev.nu/clockwise]
                 [common-datomic/common-datomic]
                 [common-db/common-db]
                 [common-finagle/common-finagle]
                 [common-http-client/common-http-client]
                 [common-kafka/common-kafka]
                 [common-metrics/common-metrics]
                 [common-repl/common-repl]
                 [common-tracing/common-tracing]]

  :profiles {
             :uberjar {:aot :all}

             :integration {:test-paths ^:replace ["test/integration/"]
                           :dependencies [[state-flow-helpers/state-flow-helpers]
                                          [nubank/matcher-combinators]]}

             :unit {:test-paths ^:replace ["test/unit/"]}


             :dev {:resource-paths ["test/resources/"]
                   :source-paths   ["dev"]
                   :dependencies   [[common-test/common-test]
                                    [codestyle/codestyle]
                                    [state-flow-helpers/state-flow-helpers]
                                    [nubank/matcher-combinators]
                                    [nubank/mockfn]
                                    [org.clojure/tools.namespace]
                                    [dev.nu/authorization.test]]
                   :repl-options   {:init-ns user}}


             :repl-start {:injections   [                                         (require '[mailbox-challenge.server :as s])
                                         (s/run-dev)]
                          :repl-options {:prompt  #(str "[mailbox-challenge] " % "=> ")
                                         :timeout 300000
                                         :init-ns user}
                          :test-paths   ^:replace []}}

  :min-lein-version "2.10.0"

  :test-paths ["test/unit" "test/integration"]
  :resource-paths ["resources"]
  :main ^{:skip-aot false} mailbox-challenge.server

  :aliases {"run-dev"         ["with-profile" "+repl-start" "trampoline" "repl" ":headless"]
            "run-dev-notramp" ["with-profile" "+repl-start" "repl" ":headless"]
})
