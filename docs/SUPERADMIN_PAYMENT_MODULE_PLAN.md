# IsleVendor Pay: Superadmin module plan

**No new dashboard.** The Superadmin already has an **IsleVendor Pay** section inside the existing admin console (`frontend/src/pages/admin/pay/IsleVendorPay.jsx`), with its own menu. The plan is to keep those screens and change only where their data comes from, page by page, as each phase moves to Java.

## Pages and where their data comes from

| Page | Today (Node) | Java endpoint (planned) | Moves in |
|---|---|---|---|
| Payment overview | `GET /api/admin/pay/overview` | `GET /api/pay/admin/overview` | Phase 2 |
| Payment gateways | `GET`/`PATCH /api/admin/pay/gateways` | `GET /api/pay/admin/gateways` (**built**, read-only); `PATCH` Phase 2 | Phase 1–2 |
| WiPay configuration | `GET`/`PATCH /api/admin/pay/wipay`, `POST .../test` | `/api/pay/admin/gateways/WIPAY` | Phase 2 |
| Gateway links | `GET /api/admin/pay/links` | `GET /api/pay/admin/gateways/WIPAY/links` | Phase 2 |
| Transactions | `GET /api/admin/pay/transactions` | `GET /api/pay/admin/transactions` | Phase 2 |
| Marketplace commissions | `GET`/`PUT /api/admin/pay/commissions` | `/api/pay/admin/commissions` | Phase 3 |
| Vendor earnings | `GET /api/admin/pay/earnings` | `GET /api/pay/admin/earnings` | Phase 3 |
| Vendor withdrawals | `/api/admin/payouts/*` | `/api/pay/admin/payouts/*` | Phase 4 |
| Refunds and chargebacks | `/api/admin/pay/refunds` | `/api/pay/admin/refunds` | Phase 3 |
| Financial reports (CSV) | `/api/admin/pay/reports/:type` | `/api/pay/admin/reports/:type` | Phase 3 |
| Audit logs (finance) | `/api/admin/audit-events?category=finance` | `/api/pay/admin/audit-events`, merged with Node's for display | Phase 2 |

Each move is a change to one React component's fetch path, behind the same switch that moves the data. The page layout, wording and controls stay as they are.

## Routing

- The React app keeps calling Node: `/api/admin/pay/...`.
- When a page's data has moved, Node forwards the call to Java's `/api/pay/admin/...` with:
  - the admin's own login token: Java checks it is a **current** admin in the database;
  - the service token: Java checks the call came through Node.
- Response shapes stay identical, so the React code changes only if a field changes.

## Rules every admin action follows

1. **Admin only, checked now.** The role is read from the database on every request, never trusted from the token.
2. **Password re-confirmation** for changes that affect money: turning a gateway on or off, sandbox ↔ live, fee structure, commission rate, approving and sending payouts, recording refunds.
   - Node checks the password (only Node has password hashes).
   - Node then calls Java's internal API with the admin's id and a one-time confirmation reference.
   - Java refuses money-affecting changes that lack that reference.
3. **Audited.** Every change writes `payments.audit_events`: who, what, before and after (never secret values or full account numbers). The table refuses updates and deletes.
4. **Secrets are write-only and out of band.**
   - The dashboard shows "configured / not configured" and the last 4 characters at most.
   - API keys and webhook secrets are set in the secret store, not typed into the dashboard. Phase 2 may add write-only entry that goes straight to the secret store.
5. **What the dashboard cannot do:**
   - add gateway domains or change webhook URLs (fixed in code, see the WiPay plan);
   - switch to live without the deployment's `ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED=true`;
   - mark a gateway payment paid without the gateway's confirmation;
   - edit or delete ledger entries. Corrections are new, opposite entries.

## Visibility for other roles

These pages already exist in the dashboards. Their data source moves with Phases 3–4:
- Vendors (warehouse, reseller, store) and drivers see their own balances, ledger and payouts on the **Payouts** tab.
- Customers see their payment status on the order and payment pages.

Java scopes every such query to the signed-in user's id from the verified token.
