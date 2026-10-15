-- IsleVendor Pay foundation. Everything lives in the `payments` schema;
-- marketplace users and orders are referenced by their UUIDs only (no
-- cross-schema foreign keys, so neither side's migrations can break the other).

-- One row per payment gateway. Secrets are never stored here: the *_ref
-- columns name the environment variable / secret that holds the value.
create table gateway_configurations (
    gateway_code       varchar(32)  primary key check (gateway_code ~ '^[A-Z][A-Z_]{1,31}$'),
    display_name       varchar(100) not null,
    environment        varchar(10)  not null check (environment in ('SANDBOX', 'LIVE')),
    enabled            boolean      not null default false,
    account_number_ref varchar(64)  check (account_number_ref ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    api_key_ref        varchar(64)  check (api_key_ref ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    updated_at         timestamptz  not null default now(),
    updated_by         uuid
);

insert into gateway_configurations (gateway_code, display_name, environment, enabled, account_number_ref, api_key_ref)
values ('WIPAY', 'WiPay', 'SANDBOX', false, 'WIPAY_ACCOUNT_NUMBER', 'WIPAY_API_KEY');

-- A request to collect money for one or more marketplace orders.
create table payment_intents (
    id                     uuid          primary key default gen_random_uuid(),
    idempotency_key        varchar(100)  not null unique,
    gateway_code           varchar(32)   not null references gateway_configurations (gateway_code),
    customer_user_id       uuid          not null,
    amount                 numeric(12, 2) not null check (amount > 0),
    currency               varchar(3)    not null check (currency = 'JMD'),
    status                 varchar(20)   not null default 'CREATED'
        check (status in ('CREATED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED')),
    gateway_transaction_id varchar(100)  unique,
    created_at             timestamptz   not null default now(),
    updated_at             timestamptz   not null default now()
);

create index payment_intents_customer_idx on payment_intents (customer_user_id, created_at);

create table payment_intent_orders (
    payment_intent_id    uuid not null references payment_intents (id) on delete cascade,
    marketplace_order_id uuid not null,
    primary key (payment_intent_id, marketplace_order_id)
);

create index payment_intent_orders_order_idx on payment_intent_orders (marketplace_order_id);

-- Gateway notifications, de-duplicated by the gateway's event id. Used from
-- Phase 2 (signed WiPay webhooks); created now so the schema is complete.
create table webhook_events (
    id              uuid         primary key default gen_random_uuid(),
    gateway_code    varchar(32)  not null references gateway_configurations (gateway_code),
    event_id        varchar(200) not null,
    signature_valid boolean      not null,
    outcome         varchar(30)  not null,
    payload         jsonb        not null,
    received_at     timestamptz  not null default now(),
    unique (gateway_code, event_id)
);

-- Append-only record of every payment action.
create table audit_events (
    id            uuid         primary key default gen_random_uuid(),
    actor_type    varchar(10)  not null check (actor_type in ('USER', 'SERVICE', 'SYSTEM')),
    actor_user_id uuid,
    action        varchar(100) not null,
    target_type   varchar(50)  not null,
    target_id     varchar(100) not null,
    metadata      jsonb        not null default '{}'::jsonb,
    created_at    timestamptz  not null default now()
);

create index audit_events_target_idx on audit_events (target_type, target_id);

-- Audit entries are never edited or deleted, whoever connects.
create function audit_events_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'audit_events is append-only';
end;
$$;

create trigger audit_events_no_change
    before update or delete on audit_events
    for each row execute function audit_events_append_only();
