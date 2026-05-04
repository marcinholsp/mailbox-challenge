(ns integration.aux.init
  (:require [com.stuartsierra.component :as component]
            [mailbox-challenge.server :as server]
            [schema.core :as s]
            [state-flow.api]
            [state-flow.helpers.runners :as runners]))

(defn init-with-state!
  [_initial-state]
  (s/with-fn-validation
    {:system (server/run-dev)}))

(defn cleanup-system [state]
  (component/stop (:system state)))

(defmacro defflow
  [name & forms]
  (let [default-parameters   {:init       #(init-with-state! {})
                              :cleanup    cleanup-system
                              :runner     runners/run-with-fn-validation}
        [parameters & flows] (if (map? (first forms))
                               (let [[override & rem] forms]
                                 (cons (merge default-parameters override) rem))
                               (cons default-parameters forms))]
    `(state-flow.api/defflow ~name ~parameters ~@flows)))
