# IsleVendor Pay: WiPay gateway configuration plan

## Three kinds of setting

| Kind | Examples | Where it lives | Who changes it |
|---|---|---|---|
| **Fixed in code** | WiPay hosts (`jmsb.wipayfinancial.com` sandbox, `jm.wipayfinancial.com` live); webhook and return paths; the verification method | `GatewayUrlPolicy` and controllers | Developers, through code review |
| **Secrets** | WiPay account number, API key, WAPI key, webhook signing secrets | Secret store (Railway variables today; a managed secret store recommended before live), referenced by name from `payments.gateway_configurations` | Operations, never through the dashboard |
| **Admin settings** | Gateway on/off; sandbox or live (within the deployment's authorisation); fee structure (`merchant_absorb` / `customer_pay` / `split`, as permitted by WiPay); display name | `payments.gateway_configurations`, audited | Superadmin, with password re-confirmation |

## Secret names

| Secret | Purpose |
|---|---|
| `WIPAY_ACCOUNT_NUMBER` | Payments API account (sandbox `1234567890`) |
| `WIPAY_API_KEY` | Payments API key, used for the return hash (sandbox `123`) |
| `WIPAY_PAYMENTS_WEBHOOK_SECRET` | Payments webhook signature |
| `WIPAY_PAYOUT_API_KEY` | WAPI key (`X-WAPI-Key`), Phase 4 |
| `WIPAY_PAYOUT_WEBHOOK_SECRET` | WAPI webhook signature, Phase 4 |

Java reports only `configured: true/false` for each (built: `GET /api/pay/admin/gateways`). No endpoint returns a value, and none is logged.

## Endpoints WiPay calls (server-generated)

| Purpose | URL (production) |
|---|---|
| Customer return after checkout | `https://pay.islevendor.com/gateway/wipay/return` |
| Payments webhook | `https://pay.islevendor.com/gateway/wipay/webhooks/payments` |
| Withdrawals webhook (Phase 4) | `https://pay.islevendor.com/gateway/wipay/webhooks/withdrawals` |

- The dashboard shows these **read-only** for copying into WiPay's developer dashboard. They are built from the service's own configured base URL, never from admin input.
- Until Java owns checkout, Node's existing URLs stay registered with WiPay (`/api/webhooks/wipay`, `/api/ledger/webhooks/wipay-payouts`).

## Payment flow (Phase 2, sandbox first)

1. Node sends Java an intent: orders, customer, the amount Node calculated, and an idempotency key.
2. Java sends WiPay's Payments API request:
   - `POST https://jmsb.wipayfinancial.com/plugins/payments/request`, method `credit_card_co`;
   - with `order_id` = our ≤16-character reference, the server-generated `response_url`, and the total to 2 decimal places.
3. Java accepts WiPay's checkout `url` only if `GatewayUrlPolicy` allows it, then returns it for the redirect.
4. **The return is not proof of payment.**
   - Java checks `hash = md5(transaction_id + total + api_key)` against **its own stored total**, in constant time.
   - The intent becomes `PAID` only if the hash matches and the status is success.
5. Webhooks:
   - verify the signature over the raw body and the timestamp (5-minute window);
   - de-duplicate on `(gateway, event id)` in `payments.webhook_events`;
   - then apply WiPay's own record, not the payload alone.
6. Java tells Node "orders paid" through an idempotent callback. Node moves the orders to `PACKING`.
7. A reconciliation job re-checks `PENDING` intents older than 30 minutes.

## Switching to live

All of these must be true. None can be done from the dashboard alone.

1. WiPay has confirmed in writing that IsleVendor's account is live-enabled. For payouts, it must also confirm that `sub_banked` marketplace withdrawals are authorised.
2. The full sandbox test plan passes:
   - approve card `4111111111111111`;
   - decline card `4111111111113333`;
   - duplicate return, replayed webhook, tampered hash, wrong amount.
3. Live secrets are in the secret store.
4. The deployment sets `WIPAY_ENV=live` and `ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED=true`. Without the second, the service refuses to start (built).
5. A Superadmin switches the gateway's environment to live, confirming with their password (audited).

## Questions for WiPay

1. Confirmation that the account supports marketplace withdrawals (`sub_banked`) in sandbox and live, and a sandbox WAPI key.
2. Whether the Payments API webhook is signed the same way as WAPI webhooks (`X-WiPay-Webhook-Signature`, HMAC-SHA256 over `timestamp.body`), and the event names.
3. A server-to-server endpoint to look up a payment by transaction id (for reconciliation).
4. Whether `customer_pay` is enabled for IsleVendor's account, and the exact fee formula, so the checkout can show it.
5. A refund API, or confirmation that refunds stay dashboard-only.
6. IP ranges for webhooks, if published (optional extra check).
