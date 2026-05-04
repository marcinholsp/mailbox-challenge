(ns integration.service-test
  (:require [integration.aux.init :as init :refer [defflow]]
            [state-flow.api :refer [flow match?]]
            [state-flow.helpers.component.servlet :as servlet]))

(defflow routes-check

  (flow "we can get the service version"
    (match?
     {:status 200
      :body   {:version "0"}}
     (servlet/request {:method :get
                       :uri    "/api/version"})))

  (flow "metrics check"
    [{metrics :body} (servlet/request {:method       :get
                                       :content-type :text
                                       :user-info    {:scope "admin prometheus"}
                                       :uri          "/ops/prometheus/metrics"})]

    (match? #"TYPE services_components_health_check gauge" metrics)))
