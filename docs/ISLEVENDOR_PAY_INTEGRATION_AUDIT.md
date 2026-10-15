# IsleVendor Pay: integration audit

A read-only audit of the existing IsleVendor application against the IsleVendor Pay requirements, made before any Java payment code was written. Date: 2026-10-15.

## 1. What the application is today

| Part | Technology | Where |
|---|---|---|
| Marketplace API | Node.js, Express 5, TypeScript, Prisma 6 | `backend-node` (Railway) |
| Web app (customers, vendors, drivers, Superadmin) | React (Vite, Tailwind) | `frontend` |
| Mobile apps | React Native | `isledash` (drivers), `islevendor-app` |
| Database | PostgreSQL 16 + PostGIS, one database, `public` schema | Railway |
| Cache, sockets | Redis, Socket.IO | Railway |
| Payment gateway | WiPay hosted checkout (Payments API) and WAPI withdrawals | Called from Node |
| **IsleVendor Pay service** | Java 17, Spring Boot 4.1 | `islevendor-pay` (new, Phase 1 in this change) |

## 2. Authentication (reused, not duplicated)

- Logins are HS256 JWTs signed by Node (`signAuthToken`) with `JWT_SECRET`, carrying `sub` (user UUID), `role` and `ver`.
- `verifyAuthToken` checks the signature and expiry. It also checks that `ver` equals `users.token_version`, so "sign out everywhere" takes effect at once.
- Admin routes additionally re-read the role from the database (`requireCurrentAdmin`).
- Roles: `WAREHOUSE`, `RESELLER`, `STORE`, `DRIVER`, `CUSTOMER`, `ADMIN`. There is one `users` table for everyone.

**Consequence for IsleVendor Pay:**
- The Java service verifies the same tokens the same way, and reads the role from `public.users`.
- It does not have its own accounts, passwords or login.
- Password checks (for example, re-confirming a password before changing money settings) stay in Node, the only place password hashes are kept.

## 3. Payment functionality that already exists in Node

| Area | What exists | Key files |
|---|---|---|
| Checkout | One WiPay hosted-checkout payment per cart. It covers one order per seller, with shipping split per seller. Orders wait in `AWAITING_PAYMENT`. | `orders.routes.ts`, `payments.service.ts` |
| Payment confirmation | Customer return (`GET /api/webhooks/wipay`) verified by WiPay's MD5 hash against the stored total. Payments webhook de-duplicated in `payment_events`. A duplicate charge becomes `DUPLICATE`, for refund. | `payments.service.ts` |
| Payment records | `checkout_payments` (`PENDING`/`PAID`/`FAILED`/`SUPERSEDED`/`DUPLICATE`/`REFUNDED`) and `payment_events` | `schema.prisma` |
| Ledger | `ledger_accounts` (pending/available, CHECK ≥ 0) and `ledger_transactions`: one leg per (order, recipient), held in escrow until delivery. Covers commission, driver fee, house-store margin and seller delivery fee. | `ledger.service.ts` |
| Withdrawals | The owner withdraws available funds and gets an estimated arrival date. An admin approves, then pays by hand or through WiPay WAPI `sub_banked`. WAPI payouts are off by default, and live use needs a separate authorisation flag. | `ledger.service.ts`, `wipayPayouts.ts` |
| Payout details | Account and ID numbers encrypted with AES-256-GCM (`PAYOUT_DATA_KEY`). Changing them needs the password and is audited. | `payoutAccounts.ts`, `crypto.ts` |
| Refunds | Seller-initiated order refunds unwind ledger legs. Card refunds are made in WiPay's dashboard and recorded here. | `refundOrder`, `pay.routes.ts` |
| Superadmin | The **IsleVendor Pay** section of the existing admin console. Its 11 pages: overview, gateways, WiPay configuration, gateway links, transactions, commissions, vendor earnings, withdrawals, refunds, reports (CSV), finance audit log. | `frontend/src/pages/admin/pay/*`, `admin/pay.routes.ts` |
| Settings | Commission rate, payments on/off and fee structure in `platform_settings`, with a 30-second cache. Secrets come only from environment variables. | `platformSettings.ts` |
| Audit | `audit_events`: every money action, with actor, target and metadata | `audit.ts` |
| Feature flags | `ENABLE_WIPAY_WEBHOOKS` (also gates card payments), `ENABLE_WITHDRAWALS`, `ENABLE_INTERNAL_REFUNDS`, `ENABLE_ESCROW_RELEASE`, `ENABLE_AUTOMATED_PAYOUTS`, `WIPAY_PAYOUTS_LIVE_AUTHORIZED` | `env.ts` |
| Tests | 183 automated tests, including fake WiPay servers | `backend-node/test` |

This is working checkout functionality and **must keep working** until the Java service has fully replaced it (see the architecture document).

## 4. Requirement-by-requirement status

The status column uses four labels:
- **Exists:** in Node today.
- **Phase 1:** implemented in Java in this change.
- **Modify:** exists but needs changing.
- **Missing:** not built anywhere.

| Requirement | Status | Notes |
|---|---|---|
| Reuse existing authentication, no duplicate accounts | **Phase 1** | Java verifies Node's JWTs and `token_version`, with the role from the DB. |
| No new React frontend or dashboards | Exists | The IsleVendor Pay section stays in the existing admin console. |
| Superadmin payment pages (11 sections) | Exists | They call Node today. They move to Java endpoints one by one (Superadmin plan). |
| Dedicated payment schema with separate permissions | **Phase 1** | `payments` schema, Flyway V1, and role script `db/setup/001_payments_role.sql`. **Not applied to production.** |
| Flyway migrations | **Phase 1** | `src/main/resources/db/migration` |
| Reference marketplace orders, users and vendors by stable id | **Phase 1** | UUIDs only, no cross-schema foreign keys |
| One authoritative source of truth for payments and the ledger | **Modify** | Node's `public` tables are authoritative now. Moving them is Phase 3 (PostgreSQL plan). Until then Java records only intents, never real payments. |
| Feature flag around the Java service | **Phase 1** | `ISLEVENDOR_PAY_ENABLED=false` by default. Off, everything answers 503. |
| Never return API keys to the frontend | Exists, **Phase 1** | Neither service returns secrets. Java reports only whether each one is configured. |
| Secrets in a secrets-management mechanism | **Modify** | Railway environment variables today. A managed secret store (or Railway sealed variables) is recommended before live. |
| No arbitrary redirects or unverified gateway domains | Exists, **Phase 1** | Java's `GatewayUrlPolicy` fixes the WiPay hosts in code, per environment. |
| Server-generated webhook endpoints, separate from user links | Exists | Node shows webhook URLs read-only. Java does the same (WiPay plan). |
| Admin settings cannot bypass verification | Exists, **Phase 1** | Live use needs an environment flag that the dashboard cannot set. |
| No live payments or withdrawals during development | **Phase 1** | Java makes no gateway calls at all in Phase 1, and refuses `WIPAY_ENV=live` without `ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED`. |
| Webhook signature verification and de-duplication in Java | Missing | Table `webhook_events` created. Logic is Phase 2. |
| Escrow ledger in Java | Missing | Phase 3 |
| Payouts in Java | Missing | Phase 4. Real payouts wait for WiPay's written confirmation. |
| Scheduled reconciliation | Missing | Neither service has it. Phase 2. |
| Gateway-initiated refunds (API) | Missing | WiPay's refund API is not yet confirmed. Refunds are made in WiPay's dashboard. |
| Rate limiting on payment endpoints | Missing | Neither service. Recommended at the edge before live. |
| Production database password rotation | **Outstanding** | The marketplace DB password was shared in chat and sits in a local `.env`. Rotate it. |

## 5. Risks found

1. **Two possible sources of truth.**
   - If Node and Java both record payments for the same checkout, the ledger can diverge.
   - Mitigation: for any one checkout, exactly one service owns the payment, chosen by a flag when the checkout starts (architecture document §4).
2. **Shared JWT secret.**
   - Anyone holding `JWT_SECRET` can mint logins for both services. Rotating it signs everyone out of both.
   - Keep it only in the two services' secret stores.
3. **Single database server.**
   - Separate roles limit what each service can touch, but not resource contention.
   - Acceptable at the current scale.
4. **Prisma and the new schema.**
   - Prisma manages `public` only, so `prisma migrate` must never be pointed at `payments`.
   - Do not enable Prisma `multiSchema` for it.
5. **Node's database role (to verify).**
   - Node connects as user `islevendor`, which is likely the database owner on Railway.
   - If it is, it can read any schema. For the payments role isolation to mean anything, Node should get its own non-owner role without access to `payments`.
