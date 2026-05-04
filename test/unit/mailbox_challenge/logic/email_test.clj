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
