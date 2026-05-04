# Challenge 5 – Email Sending Service: Implementation Plan

> Reference: [Confluence page](https://nubank.atlassian.net/wiki/spaces/TL/pages/264263894332/Challenge+5+Email+Sending+Service)
> Stack: Clojure · Pedestal · Datomic · common-http-client

---

## 1. What We're Building

A service called `mailbox-challenge` that abstracts email delivery over multiple providers (Mailgun, SendGrid). It receives an email send request, selects the primary provider, falls back to others on failure, persists every attempt to the database, and guarantees idempotency.

---

## 2. Core Requirements Checklist

| # | Requirement | Design Decision |
|---|-------------|-----------------|
| 1 | Receive email data via HTTP | `POST /email/send` endpoint |
| 2 | Choose a provider and call its API | Protocol-based provider abstraction |
| 3 | Support new providers with minimal changes | Add a new record implementing the protocol + register in config |
| 4 | Sync or async dispatch | Synchronous (simplest path; Kafka optional stretch) |
| 5 | Primary provider + fallback chain | Ordered list of providers in config; iterate until success |
| 6 | Record every request in DB (timestamp + provider) | Datomic entity per request |
| 7 | Idempotent sending | Idempotency key deduplication against Datomic |

---

## 3. Project Scaffold

Mirror the structure of `pix-challenge`:

```
mailbox-challenge/
├── project.clj
├── resources/
│   └── mailbox_challenge_config.json.base
└── src/mailbox_challenge/
    ├── server.clj                          # System startup
    ├── config.clj                          # Provider ordering, config keys
    ├── components.clj                      # Stuart Sierra component map
    ├── models/
    │   └── email.clj                       # Schema + domain model
    ├── wire/
    │   ├── in/email.clj                    # Inbound HTTP schema (Pedestal)
    │   └── out/email.clj                   # Outbound response schema
    ├── adapters/
    │   └── email.clj                       # model↔datomic, model↔wire conversions
    ├── diplomat/
    │   ├── http_server.clj                 # Routes + interceptors
    │   ├── datomic/
    │   │   └── email.clj                   # Datomic schema + queries
    │   └── providers/
    │       ├── protocol.clj                # EmailProvider protocol
    │       ├── mailgun.clj                 # Mailgun implementation
    │       └── sendgrid.clj                # SendGrid implementation
    ├── logic/
    │   └── email.clj                       # Provider selection, fallback, idempotency logic
    └── controllers/
        └── email.clj                       # Orchestration: check idempotency → send → persist
```

---

## 4. Data Model

### 4.1 Domain Model (`models/email.clj`)

```clojure
(def Schema
  {:id              s/Uuid         ;; internal ID
   :idempotency-key s/Str          ;; client-supplied dedup key
   :to              s/Str
   :from            s/Str
   :subject         s/Str
   :body            s/Str
   :status          s/Keyword      ;; :pending | :sent | :failed
   (s/optional-key :provider)   (s/maybe s/Keyword)  ;; :mailgun | :sendgrid
   (s/optional-key :sent-at)    (s/maybe java.util.Date)
   :created-at      java.util.Date})
```

### 4.2 Datomic Schema (`diplomat/datomic/email.clj`)

```clojure
[{:db/ident :email/id              :db/valueType :db.type/uuid    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
 {:db/ident :email/idempotency-key :db/valueType :db.type/string  :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
 {:db/ident :email/to              :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
 {:db/ident :email/from            :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
 {:db/ident :email/subject         :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
 {:db/ident :email/body            :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
 {:db/ident :email/status          :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/index true}
 {:db/ident :email/provider        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
 {:db/ident :email/sent-at         :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
 {:db/ident :email/created-at      :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}]
```

---

## 5. HTTP API

### 5.1 Send Email

```
POST /email/send
Content-Type: application/json
Idempotency-Key: <uuid>         ;; required header

{
  "to":      "user@example.com",
  "from":    "noreply@nubank.com.br",
  "subject": "Your card is under review",
  "body":    "Hi there, ..."
}
```

Response `200 OK`:
```json
{
  "id":       "...",
  "status":   "sent",
  "provider": "mailgun",
  "sent_at":  "2026-04-17T12:00:00Z"
}
```

Response `409 Conflict` (already processed — idempotency hit):
```json
{
  "id":       "...",
  "status":   "sent",
  "provider": "mailgun",
  "sent_at":  "..."
}
```

---

## 6. Provider Abstraction

### 6.1 Protocol (`diplomat/providers/protocol.clj`)

```clojure
(defprotocol EmailProvider
  (provider-name [this])         ;; returns keyword, e.g. :mailgun
  (send-email! [this email http])) ;; throws on failure; returns truthy on success
```

### 6.2 Mailgun Implementation (`diplomat/providers/mailgun.clj`)

- Calls `POST /mailgun/send` (simulated endpoint, see §8)
- Payload: `{:to, :from, :subject, :body}`
- Throws exception on non-2xx response

### 6.3 SendGrid Implementation (`diplomat/providers/sendgrid.clj`)

- Calls `POST /sendgrid/send` (simulated endpoint)
- Same contract, different URL/payload shape

### 6.4 Provider Registry (`config.clj`)

```clojure
(defn ordered-providers
  "Returns providers in priority order: primary first, fallbacks after."
  [mailgun sendgrid]
  [mailgun sendgrid])   ;; order drives fallback chain
```

Adding a new provider = create a new implementation + add it to `ordered-providers`. No other code changes.

---

## 7. Business Logic (`logic/email.clj`)

### 7.1 Idempotency Check

```clojure
(defn already-processed?
  "Returns existing email record if idempotency key was seen before."
  [idempotency-key datomic]
  (datomic.email/find-by-idempotency-key idempotency-key datomic))
```

Rule: if a record exists with `:sent` status → return it directly (no re-send). If `:failed` → allow retry (re-attempt the send flow).

### 7.2 Provider Fallback

```clojure
(defn try-providers!
  "Iterates providers in order. Returns {:provider kw :success? bool} or throws."
  [providers email http]
  (loop [[provider & rest] providers]
    (if (nil? provider)
      {:success? false}
      (try
        (send-email! provider email http)
        {:success? true :provider (provider-name provider)}
        (catch Exception ex
          (log/warn :provider-failed {:provider (provider-name provider) :error (ex-message ex)})
          (recur rest))))))
```

---

## 8. Simulated Provider Endpoints

Since the challenge says "simulate endpoints and schemas", add mock routes directly to the Pedestal HTTP server under a `/mock` prefix. This avoids external dependencies during development/testing.

```
POST /mock/mailgun/send   → returns 200 or 500 based on a configurable failure flag
POST /mock/sendgrid/send  → same
```

These routes will use a simple in-memory flag (or config key) to simulate failures, making it easy to test the fallback logic.

---

## 9. Controller (`controllers/email.clj`)

```
receive request
  │
  ├─ parse + validate wire/in schema
  │
  ├─ check idempotency (find-by-idempotency-key)
  │     └─ if :sent exists → return 200/409 with cached result (done)
  │
  ├─ create Datomic record  (status: :pending, created-at: now)
  │
  ├─ try-providers! (iterate ordered list)
  │     ├─ success → update record (status: :sent, provider: X, sent-at: now)
  │     └─ all fail → update record (status: :failed)
  │
  └─ return wire/out response
```

---

## 10. Implementation Order (Step-by-Step)

### Step 1 – Project scaffold
- Copy `pix-challenge` structure, rename namespace to `mailbox-challenge`
- Update `project.clj` (same dependencies — especially common-datomic, common-http-client)
- Add `mailbox_challenge_config.json.base`

### Step 2 – Domain model + Datomic schema
- Define `models/email.clj` with the Schema above
- Define `diplomat/datomic/email.clj`:
  - `schema` vector
  - `save-email!`
  - `update-status!`
  - `find-by-idempotency-key`

### Step 3 – Provider protocol + implementations
- Create `diplomat/providers/protocol.clj` with `EmailProvider` protocol
- Create `diplomat/providers/mailgun.clj` and `sendgrid.clj`
- Wire HTTP client bookmarks for mock provider URLs

### Step 4 – Business logic
- `logic/email.clj`:  `try-providers!`, `already-processed?`, `build-email`

### Step 5 – Controller
- `controllers/email.clj`: tie idempotency check + DB + provider selection together

### Step 6 – HTTP layer
- `wire/in/email.clj`: request schema + coercion interceptor
- `wire/out/email.clj`: response schema
- `diplomat/http_server.clj`: add `POST /email/send` route
- Add mock routes: `POST /mock/mailgun/send`, `POST /mock/sendgrid/send`

### Step 7 – Tests
- Unit tests: `logic/email_test.clj` — pure fallback logic, idempotency checks
- Integration tests (state-flow): full send flow, fallback scenario (mailgun fails → sendgrid used), idempotency replay

### Step 8 – Manual smoke test
- Run dev REPL, hit `POST /email/send` twice with same `Idempotency-Key`
- Verify: second call returns same result without calling providers again
- Verify: if mailgun mock returns 500, sendgrid is used and `provider: sendgrid` appears in response

---

## 11. Key Design Decisions & Tradeoffs

**Protocol vs. multimethods for providers**: Protocol wins here — it's easier to have each provider as a record/map and compose them with `ordered-providers`. Adding a new provider is zero-friction.

**Synchronous dispatch**: Simpler than Kafka, and the challenge explicitly allows it. Async via Kafka is a stretch goal — worth adding as a second route (`POST /email/send-async`) if time permits.

**Idempotency on `:failed`**: A `:failed` email can be retried (new request with same key will attempt re-send). This is the safest UX choice — the caller knows the email didn't go out and can ask again.

**Mock endpoints inside the same service**: Avoids standing up separate fake services. In tests, `mockfn` can override the HTTP client calls entirely, which is even cleaner.

---

## 12. Stretch Goals

- Async variant via Kafka: producer on `POST /email/send-async`, consumer calls `try-providers!`
- Retry with exponential backoff per provider before moving to fallback
- Circuit breaker per provider (track consecutive failures, skip unhealthy providers)
- Metrics: count of sends per provider, failure rate
