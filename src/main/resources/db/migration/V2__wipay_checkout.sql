-- Phase 2: WiPay hosted checkout.

alter table payment_intents drop constraint payment_intents_status_check;
alter table payment_intents add constraint payment_intents_status_check
    check (status in ('CREATED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'DUPLICATE'));

alter table payment_intents
    -- WiPay's order_id: letters and digits, at most 16 characters.
    add column reference                 varchar(16) unique check (reference ~ '^[A-Z0-9]{1,16}$'),
    -- The marketplace's own record of this payment (Node's checkout_payments.id):
    -- where the customer is sent back to, and what the marketplace is told about.
    add column marketplace_payment_id    uuid unique,
    add column checkout_url              varchar(2048),
    add column message                   varchar(500),
    add column paid_at                   timestamptz,
    -- The last status the marketplace acknowledged. Differs from status
    -- while the marketplace still has to be told (retried every minute).
    add column marketplace_synced_status varchar(20);

create index payment_intents_unsynced_idx on payment_intents (status)
    where status in ('PAID', 'FAILED');
