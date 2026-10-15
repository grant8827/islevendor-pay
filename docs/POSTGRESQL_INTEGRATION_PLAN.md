# IsleVendor Pay: PostgreSQL integration plan

## Decision

Use a **dedicated `payments` schema in the existing PostgreSQL database**, owned by a **separate role**. Do not add a second database server.
- **Same server:** keeps backups, connection details and costs in one place.
- **Separate schema and role:** keeps the payment service from touching marketplace tables, and keeps the marketplace from writing payment tables.

## Roles and permissions

| Role | Owns | Can read | Can write |
|---|---|---|---|
| `islevendor_pay` (new) | schema `payments` | `public.users (id, role, token_version)` only | `payments.*` only |
| Marketplace (Node) role | schema `public` | `public.*` (plus chosen `payments` reporting views, Phase 3, if needed) | `public.*` only |

- The role is created once by a DBA with `db/setup/001_payments_role.sql`. That script is deliberately not a Flyway migration: creating roles needs privileges the payment service must not have.
- **Prerequisite:** Node connects as user `islevendor`, probably the database owner, which could read `payments`. Check with `\du` and `\dn+`. If it is the owner, before production cut-over:
  - create a non-owner marketplace role with rights on `public` only;
  - switch Node's `DATABASE_URL` to it;
  - rotate the old password, which was exposed.

## Migrations

- **Flyway** for `payments` (`src/main/resources/db/migration`, `V<n>__name.sql`). Its history table lives in `payments.flyway_schema_history`.
- **Prisma** keeps managing `public` only. Never enable Prisma `multiSchema` for `payments`, and never run `prisma migrate reset` against a database holding real payments.
- Hibernate validates the schema only (`ddl-auto: validate`) and never changes it.
- **No cross-schema foreign keys.** Marketplace users and orders are referenced by UUID. Neither side's migrations can break the other's, and Phase 3 can move tables without touching `public`.
- Migrations are additive. A destructive change needs a written migration plan, a backup and an approval, as with the marketplace's production migrations.

## Phase 1 schema (`V1__payments_foundation.sql`)

| Table | Purpose |
|---|---|
| `gateway_configurations` | One row per gateway: environment, on/off, and the **names** of its secrets, never their values. WiPay is seeded **off, sandbox**. |
| `payment_intents`, `payment_intent_orders` | A request to collect money for marketplace orders, with a unique idempotency key. Amounts `numeric(12,2)` > 0, JMD only. |
| `webhook_events` | Gateway notifications, unique per (gateway, event id), for Phase 2 |
| `audit_events` | Append-only (a trigger refuses UPDATE and DELETE) |

## Single source of truth: the migration path

Today the authoritative financial records are Node's tables:
- `checkout_payments`, `payment_events`;
- `ledger_accounts`, `ledger_transactions`;
- `payouts`, `payout_accounts`, `payout_webhook_events`;
- `refunds`.

| Step | What happens |
|---|---|
| Phase 2 | New checkouts may be owned by Java (`payments.payment_intents` and later `payments.payments`). Old ones stay in Node. Every checkout has exactly one owner, recorded on the order (`payment_provider`), so reports know where to look. |
| Phase 3 | In a maintenance window: **back up** (`pg_dump` to `~/islevendor-db-backups/` and Railway's backup), copy Node's financial tables into `payments` with their original ids, run a reconciliation report (counts and sums per account must match to the cent), switch Node to read-only on them, flip ownership. Rollback is the switch back plus the untouched Node tables. |
| Phase 5 | After a retention period: export Node's financial tables to an archive, then drop them through an approved Prisma migration. |

## Environments

| Environment | Database | Notes |
|---|---|---|
| Local development | `islevendor_pay_dev` on local PostgreSQL | Default `ISLEVENDOR_PAY_DATABASE_URL`. A non-local URL is refused unless `ISLEVENDOR_PAY_ALLOW_REMOTE_DATABASE=true`. |
| Automated tests | `islevendor_pay_test` (or `PAY_TEST_DATABASE_URL`) | A stand-in `public.users` table with the same three columns |
| Staging | Railway database copy | Role script, then Flyway on start |
| Production | Railway PostgreSQL (`postgres-postgis.railway.internal`) | **Not touched in Phase 1.** Applying needs the role script, a backup and an explicit go-ahead. |

## Connection settings

| Variable | Value |
|---|---|
| `ISLEVENDOR_PAY_DATABASE_URL` | `jdbc:postgresql://<host>:5432/<db>`. The payments role's own connection; never Node's URL. |
| `ISLEVENDOR_PAY_DATABASE_USERNAME` / `_PASSWORD` | `islevendor_pay` and its generated password, from the secret store |
| `ISLEVENDOR_PAY_ALLOW_REMOTE_DATABASE` | `true` on Railway only |

Pool: Hikari defaults (10). Combined with Node's pool, stay under the server's `max_connections`.
