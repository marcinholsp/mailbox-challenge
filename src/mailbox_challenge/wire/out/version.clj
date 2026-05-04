(ns mailbox-challenge.wire.out.version
  (:require [schema.core :as s]))

;; Please see https://nubank.atlassian.net/wiki/x/QaaNTj0
;; for an explanation of the wire.out / wire.in distinction.

(s/defschema Version {:version s/Str})
