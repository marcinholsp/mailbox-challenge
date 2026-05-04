(ns mailbox-challenge.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [common-core.components.config :as components.config]
            [mailbox-challenge.config :as config]))

(def system-map
  (component/system-map :config
                        (components.config/new-config "mailbox-challenge")))

(deftest configuration-file

  (let [system (component/start system-map)]

    (testing "reads a version"
      (is (string? (config/version (:config system)))))

    (testing "reads a service uri"
      (is (string? (config/services (:config system) :mailbox-challenge))))))
