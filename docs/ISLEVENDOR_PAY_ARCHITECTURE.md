# IsleVendor Pay: architecture

## 1. Shape

```
 Browser / mobile apps
   │  (marketplace login JWT)
   ▼
 React web app ──► Node marketplace API (api.islevendor.com)
                     │  orders, users, vendors, carts, delivery
                     │
                     │  private network, service token
                     ▼
                   IsleVendor Pay (Java, Spring Boot)
                     │  payments, ledger, payouts, gateway settings
                     │
          ┌──────────┴───────────┐
          ▼                      ▼
   PostgreSQL               WiPay (Payments API, WAPI)
   ├─ public   (Node)          ▲
   └─ payments (Java)          │ signed webhooks → Java only
```

- **The browser never talks to WiPay's API and never sees a secret.** The browser is only redirected to WiPay's hosted checkout page. Its URL is checked against a fixed allowlist.
- **Node stays the front door.** The React app keeps calling `api.islevendor.com`. Payment calls are forwarded to Java with the customer's login token **and** the service token, and Java checks both. One origin means no CORS changes, and Java needs no public API except webhooks.
- **WiPay talks to Java directly** for webhooks and the customer return, on server-generated URLs (WiPay plan).

## 2. Responsibilities

| Concern | Owner |
|---|---|
| Users, logins, passwords, roles, session revocation | Node |
| Products, carts, orders, delivery, drivers | Node |
| Order totals and the per-seller split (what to charge) | Node calculates; Java records and verifies |
| Payment intents, gateway calls, payment status | Java |
| Webhooks and gateway verification | Java |
| Ledger (escrow, available balances, commissions) | Java (after Phase 3) |
| Withdrawals and payouts | Java (after Phase 4) |
| Gateway settings and payment reports | Java data, shown in the existing React admin |
| Audit of money actions | Java `payments.audit_events` (Node's `audit_events` keeps marketplace actions) |

## 3. How the two services talk

| Direction | How | Authentication |
|---|---|---|
| Node → Java | `POST/GET /internal/v1/...` over Railway's private network | Header `X-IsleVendor-Service-Token` (≥ 32 random characters, compared in constant time). Upgrade path: HMAC-signed requests with a timestamp, to prevent replay. |
| Node → Java on behalf of a user | `/api/pay/...` | The user's own `Authorization: Bearer`. Java verifies it independently, including `token_version` and the database role. |
| Java → Node | Order status callbacks, from Phase 2: "order paid", "order refunded" | Same service token, on a Node `/internal` route. Idempotent by payment id. |
| WiPay → Java | Webhooks and the customer return | WiPay's signature or hash, then a server-to-server confirmation |

Every Node → Java call that creates something carries an **idempotency key**. A retry returns the original result; the same key with different details is refused (409).

## 4. Moving the source of truth without breaking checkout

The rule: **for any one checkout, exactly one service owns the payment.**

| Phase | Node | Java | Switch |
|---|---|---|---|
| **1 (this change)** | Unchanged; owns all payments | Foundation: auth, schema, intents (recorded, never charged), gateway settings view, audit | `ISLEVENDOR_PAY_ENABLED=false` in production |
| **2: payments** | New checkouts call Java when switched on; old checkouts finish in Node | WiPay hosted checkout in **sandbox**, return and webhook verification, reconciliation job, "order paid" callback to Node | Node `PAYMENTS_PROVIDER=node\|islevendor-pay`, read when the checkout starts |
| **3: ledger** | Stops writing ledger rows | Escrow ledger, delivery release, commissions. One-time migration of `ledger_*`, `checkout_payments`, `payment_events` and `refunds` into `payments`, with a reconciliation report that must balance to the cent. | Cut over in a maintenance window |
| **4: payouts** | Payouts tab calls Java | Manual payouts, then WAPI `sub_banked` behind `ENABLE_AUTOMATED_PAYOUTS` and live authorisation | Only after WiPay confirms in writing |
| **5: retire** | Removes its payment code and tables (after an archive export) | Sole owner | — |

At every step, switching off returns to the previous owner for **new** checkouts. In-flight payments always finish where they started.

## 5. Phase 1 contents

| Item | File |
|---|---|
| Settings and the feature switch | `config/PayProperties.java`, `application.yml` |
| Start-up refusal for an unsafe configuration (no JWT secret; weak service token; live WiPay without authorisation; non-local DB without opt-in). Runs before Flyway. | `config/StartupSafetyChecks.java`, `config/PayConfiguration.java` |
| Marketplace login verification (HS256 only, `exp` required, `token_version`, role from the DB) | `security/MarketplaceJwtVerifier.java` |
| Stateless security. Deny by default: health is public, `/internal/**` needs the service token, `/api/pay/admin/**` needs ADMIN, `/api/pay/**` needs a login. | `security/SecurityConfig.java`, `security/AuthenticationFilters.java` |
| `payments` schema: gateway settings (secret names only), payment intents and their orders, webhook events, append-only audit | `db/migration/V1__payments_foundation.sql` |
| Database role and grants (run by a DBA) | `db/setup/001_payments_role.sql` |
| Fixed WiPay host allowlist | `gateway/GatewayUrlPolicy.java` |
| `GET /api/pay/status` (any login), `GET /api/pay/admin/gateways` (admin) | `web/PayStatusController.java`, `gateway/AdminGatewayController.java` |
| `POST /internal/v1/payment-intents` (idempotent, audited, no gateway call), `GET /internal/v1/payment-intents/{id}` | `payment/*` |
| 16 tests against a real local PostgreSQL | `src/test/java` |

## 6. Running locally

```sh
createdb islevendor_pay_dev      # once
createdb islevendor_pay_test     # once, for ./mvnw test
cd islevendor-pay
JWT_SECRET=<same value as backend-node/.env> ./mvnw spring-boot:run   # http://localhost:8081
./mvnw test
```

Defaults are safe: switched off, WiPay sandbox, local database only.
