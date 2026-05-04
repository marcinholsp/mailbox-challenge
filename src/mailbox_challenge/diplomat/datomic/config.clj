(ns mailbox-challenge.diplomat.datomic.config
  (:require [common-datomic.extensions.transformations :refer [std-xforms]]
            [common-datomic.functions :as functions]
            [common-datomic.utils :as utils]
            [mailbox-challenge.config :as config]
            [mailbox-challenge.diplomat.datomic.email :as datomic.email]))

(def schemata
  (let [skeletons []
        enums     []
        functions []]
    (concat [(map functions/function-datom functions)]
            [(utils/read-edn-schemata ["extensions/db.transform.edn"])] ;; obtained magically from common-db's resources folder
            (map utils/gen-datomic-enum-seq enums)
            (map utils/skeleton->datomic-schema skeletons)
            [datomic.email/schema])))

(def settings
  {:seed     []
   :schemata schemata
   :xforms   std-xforms})

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def db-name config/service-name)

