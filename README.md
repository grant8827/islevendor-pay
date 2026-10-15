# IsleVendor Pay

IsleVendor's payment service (Java 17, Spring Boot 4.1).

**Phase 2: WiPay hosted checkout, sandbox.**
- **Default:** switched off. The marketplace keeps taking payments itself until its `PAYMENTS_PROVIDER` is set to `islevendor-pay`.
- **Live:** refused until WiPay confirms the account and `ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED=true`.

Documents:
- [Integration audit](docs/ISLEVENDOR_PAY_INTEGRATION_AUDIT.md): what exists and what's missing
- [Architecture](docs/ISLEVENDOR_PAY_ARCHITECTURE.md): how it fits with the Node marketplace, and the phases
- [Superadmin module plan](docs/SUPERADMIN_PAYMENT_MODULE_PLAN.md)
- [PostgreSQL integration plan](docs/POSTGRESQL_INTEGRATION_PLAN.md)
- [WiPay gateway configuration plan](docs/WIPAY_GATEWAY_CONFIGURATION_PLAN.md)

## How a payment goes

1. **Checkout starts.** The customer checks out on the marketplace. Node records its payment and calls `POST /internal/v1/payment-intents`, using the payment's id as the idempotency key.
2. **WiPay page opens.** IsleVendor Pay asks WiPay (sandbox) for a hosted checkout page. It accepts the page only on WiPay's own host, and returns it.
3. **The customer pays on WiPay and comes back** to `/gateway/wipay/return`.
   - The payment counts as paid only if WiPay's hash `md5(transaction_id + total + API key)` matches **our** stored total.
   - The customer is then sent to the marketplace's `/payment/<id>` page.
4. **Signed WiPay notifications** on `/gateway/wipay/webhooks/payments` are checked: HMAC-SHA256 signature, 5-minute window, once per event id, and the amount must match.
5. **IsleVendor Pay tells Node the outcome** at `POST /internal/pay/payments/<id>/outcome`.
   - Node credits each order once, or answers DUPLICATE if the orders were already paid (refund needed).
   - IsleVendor Pay retries every minute until Node acknowledges.

## Run locally

```sh
createdb islevendor_pay_dev
createdb islevendor_pay_test
JWT_SECRET=<backend-node's JWT_SECRET> ./mvnw spring-boot:run   # http://localhost:8081, switched off
./mvnw test
```

To try the whole flow locally against WiPay's sandbox, run both services:

```sh
# islevendor-pay
ISLEVENDOR_PAY_ENABLED=true JWT_SECRET=<same> ISLEVENDOR_PAY_SERVICE_TOKEN=<32+ chars> \
ISLEVENDOR_PAY_PUBLIC_URL=http://localhost:8081 PUBLIC_WEB_URL=http://localhost:5173 \
MARKETPLACE_INTERNAL_URL=http://localhost:4000 ./mvnw spring-boot:run
psql islevendor_pay_dev -c "update payments.gateway_configurations set enabled = true where gateway_code = 'WIPAY'"

# backend-node .env
PAYMENTS_PROVIDER=islevendor-pay
ISLEVENDOR_PAY_INTERNAL_URL=http://localhost:8081
ISLEVENDOR_PAY_SERVICE_TOKEN=<same>
ENABLE_WIPAY_WEBHOOKS=true
```

Test cards: `4111111111111111` is approved; `4111111111113333` is declined.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `ISLEVENDOR_PAY_ENABLED` | `false` | Master switch. Off: payment endpoints answer 503. |
| `JWT_SECRET` | — (required) | Node's login secret, to accept marketplace logins |
| `ISLEVENDOR_PAY_SERVICE_TOKEN` | — | Shared with Node, both directions. At least 32 characters when switched on. |
| `ISLEVENDOR_PAY_PUBLIC_URL` | — | This service's public https address. WiPay returns customers to `<it>/gateway/wipay/return`. |
| `PUBLIC_WEB_URL` | — | The marketplace web app (customers land on `/payment/<id>`) |
| `MARKETPLACE_INTERNAL_URL` | — | The Node backend on the private network |
| `ISLEVENDOR_PAY_DATABASE_URL`, `_USERNAME`, `_PASSWORD` | local `islevendor_pay_dev` | The payments role's connection |
| `ISLEVENDOR_PAY_ALLOW_REMOTE_DATABASE` | `false` | Must be `true` to use a non-local database |
| `WIPAY_ENV` | `sandbox` | `live` is refused unless the next variable is `true` |
| `ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED` | `false` | Set only after WiPay confirms the live account in writing |
| `WIPAY_ACCOUNT_NUMBER`, `WIPAY_API_KEY` | WiPay's public sandbox account | Required for live |
| `WIPAY_PAYMENTS_WEBHOOK_SECRET` | — | WiPay Payments webhook signing secret. Unset: the webhook answers 503 and the return alone confirms payments. |
| `WIPAY_ORIGIN` | `IsleVendor` | |
| `PORT` | `8081` | |

The WiPay gateway row (`payments.gateway_configurations`) must also be **enabled**, and its environment must match `WIPAY_ENV`.

## Endpoints

| Endpoint | Who |
|---|---|
| `GET /actuator/health` (+ `/liveness`, `/readiness`) | Public, no details |
| `GET /gateway/wipay/return` | WiPay's customer redirect. Verified by hash. |
| `POST /gateway/wipay/webhooks/payments` | WiPay. Verified by signature. |
| `GET /api/pay/status` | Any marketplace login |
| `GET /api/pay/admin/gateways` | Current admins. Shows whether secrets are configured, never their values. |
| `POST /internal/v1/payment-intents`, `GET /internal/v1/payment-intents/{id}` | Node backend (service token) |

Everything else is refused.

## Not yet

- **Looking up abandoned payments.** A checkout the customer abandons stays `PENDING`. WiPay has no confirmed lookup API to reconcile it with.
- **Ledger and payouts.** Still in Node (Phases 3 and 4).
- **Gateway settings.** The Superadmin views them, but can't edit them here yet.
