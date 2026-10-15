#!/usr/bin/env bash
# Runs IsleVendor Pay locally, switched on, against WiPay's sandbox and the
# same local database as backend-node's `npm run dev:local`
# (backend-node/.env.development.local supplies the shared secrets).
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=../backend-node/.env.development.local
[ -f "$ENV_FILE" ] || { echo "Missing $ENV_FILE (see backend-node's dev:local setup)"; exit 1; }
value() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

DB_NAME=islevendor_dev
# Enable the WiPay sandbox gateway locally (the table exists after the first start).
psql -h localhost -d "$DB_NAME" -qc \
  "update payments.gateway_configurations set enabled = true, environment = 'SANDBOX' where gateway_code = 'WIPAY'" \
  2>/dev/null || echo "First run: start once, then re-run this script to enable the WiPay sandbox gateway."

export ISLEVENDOR_PAY_ENABLED=true
export ISLEVENDOR_PAY_DATABASE_URL="jdbc:postgresql://localhost:5432/$DB_NAME"
export ISLEVENDOR_PAY_DATABASE_USERNAME="${ISLEVENDOR_PAY_DATABASE_USERNAME:-$(whoami)}"
export JWT_SECRET="$(value JWT_SECRET)"
export ISLEVENDOR_PAY_SERVICE_TOKEN="$(value ISLEVENDOR_PAY_SERVICE_TOKEN)"
export ISLEVENDOR_PAY_PUBLIC_URL=http://localhost:8090
export PUBLIC_WEB_URL=http://localhost:5173
export MARKETPLACE_INTERNAL_URL=http://localhost:4000
export WIPAY_ENV=sandbox
export PORT=8090

exec ./mvnw -q spring-boot:run
