-- Run ONCE, by a database administrator, against the marketplace database,
-- before IsleVendor Pay first starts there. Not a Flyway migration: creating
-- roles needs privileges the payments service must never have.
--
--   psql "$ADMIN_DATABASE_URL" -v pay_password="'<generated password>'" -f db/setup/001_payments_role.sql
--
-- Generate the password with `openssl rand -base64 32`, store it only in the
-- payments service's secret store (ISLEVENDOR_PAY_DATABASE_PASSWORD), and
-- never reuse the marketplace's own database password.

create role islevendor_pay login password :pay_password;

-- The payments schema belongs to the payments role. Flyway runs as this role.
create schema if not exists payments authorization islevendor_pay;

-- Marketplace logins are verified against the user's current role and
-- session version. Read-only, and only these three columns.
grant usage on schema public to islevendor_pay;
grant select (id, role, token_version) on public.users to islevendor_pay;

-- The marketplace's own role does not get write access to payments tables.
-- (Phase 2 may grant it SELECT on chosen payments views for reporting.)
revoke all on schema payments from public;
