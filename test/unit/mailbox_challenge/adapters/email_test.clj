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
    (let [model     {:id              #uuid "00000000-0000-0000-0000-000000000002"
                     :idempotency-key "idem-k"
                     :to              "a@b.com"
                     :from            "x@y.com"
                     :subject         "sub"
                     :body            "bod"
                     :status          :sent
                     :provider        :sendgrid
                     :sent-at         now
                     :created-at      now}
          m         (adapters.email/model->datomic model)
          restored  (adapters.email/datomic->model m)]
      (is (= model restored))
      (is (= (:id model) (:email/id m)))
      (is (= (:status model) (:email/status m))))))
