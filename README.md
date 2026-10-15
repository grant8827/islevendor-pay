# IsleVendor Pay

IsleVendor's payment service (Java 17, Spring Boot 4.1). It is **Phase 1: the foundation.** It is switched off by default, uses the WiPay sandbox only, and makes no gateway calls.

- [Integration audit](docs/ISLEVENDOR_PAY_INTEGRATION_AUDIT.md): what exists and what's missing
- [Architecture](docs/ISLEVENDOR_PAY_ARCHITECTURE.md): how it fits with the Node marketplace, and the phases
- [Superadmin module plan](docs/SUPERADMIN_PAYMENT_MODULE_PLAN.md)
- [PostgreSQL integration plan](docs/POSTGRESQL_INTEGRATION_PLAN.md)
- [WiPay gateway configuration plan](docs/WIPAY_GATEWAY_CONFIGURATION_PLAN.md)

## Run locally

```sh
createdb islevendor_pay_dev
createdb islevendor_pay_test
JWT_SECRET=<backend-node's JWT_SECRET> ./mvnw spring-boot:run   # http://localhost:8081
./mvnw test
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `ISLEVENDOR_PAY_ENABLED` | `false` | Master switch. Off: payment endpoints answer 503 and record nothing. |
| `JWT_SECRET` | — (required) | Node's login secret, to accept marketplace logins |
| `ISLEVENDOR_PAY_SERVICE_TOKEN` | — | Shared with Node for `/internal/**`. At least 32 characters when switched on. |
| `ISLEVENDOR_PAY_DATABASE_URL`, `_USERNAME`, `_PASSWORD` | local `islevendor_pay_dev` | The payments role's connection |
| `ISLEVENDOR_PAY_ALLOW_REMOTE_DATABASE` | `false` | Must be `true` to use a non-local database |
| `WIPAY_ENV` | `sandbox` | `live` is refused unless the next variable is `true` |
| `ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED` | `false` | Set only after WiPay confirms the live account in writing |
| `PORT` | `8081` | |

## Endpoints

| Endpoint | Who |
|---|---|
| `GET /actuator/health` (+ `/liveness`, `/readiness`) | Public, no details |
| `GET /api/pay/status` | Any marketplace login |
| `GET /api/pay/admin/gateways` | Current admins. Shows whether secrets are configured, never their values. |
| `POST /internal/v1/payment-intents`, `GET /internal/v1/payment-intents/{id}` | Node backend (service token). Idempotent and audited. No money moves. |

Everything else is refused.
