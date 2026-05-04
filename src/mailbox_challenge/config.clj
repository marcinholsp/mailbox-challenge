(ns mailbox-challenge.config
  (:require [clojure.string :as string]
            [common-core.protocols.config :as protocols.config]))

(def service-name "mailbox-challenge")

(def user-agent (format "Nubank/%s" service-name))

(defn version [config] (protocols.config/get! config [:version]))

(defn environment [config] (protocols.config/get! config [:environment]))

(defn services [config svc]
  (string/replace (protocols.config/get! config [:services svc]) #"/api\z" ""))
