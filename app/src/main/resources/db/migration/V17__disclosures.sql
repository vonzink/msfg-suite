-- V17: Disclosures (TRID LE/CD) — disclosure_issuance (current/versioned per kind),
-- disclosure_event (append-only audit). + fee_line_item / loan TRID columns.
-- NOTE: `version` (bigint) is the JPA @Version optimistic-lock column (V14/V15 family).
-- The TRID disclosure version (a domain int) is therefore named `disclosure_version`
-- to avoid collision — Task 3 maps the entity field to this column.

create table disclosure_issuance (
    id                          uuid primary key,
    org_id                      uuid not null references organization(id),
    loan_id                     uuid not null,
    kind                        varchar(20) not null,                 -- LE | CD
    disclosure_version          int not null default 1,               -- TRID disclosure version (domain), NOT optimistic lock
    status                      varchar(20) not null,
    apr                         numeric(9,5),
    finance_charge              numeric(15,2),
    amount_financed             numeric(15,2),
    total_of_payments           numeric(15,2),
    tip                         numeric(9,5),
    apr_irregular_basis         boolean not null default false,
    prepayment_penalty          boolean not null default false,
    product_description         varchar(120),
    delivery_method             varchar(20),
    delivered_at                timestamptz,
    received_at                 timestamptz,
    received_basis              varchar(24),
    computed_received_date      date,
    earliest_consummation_date  date,
    document_id                 uuid,
    vendor_reference            varchar(120),
    snapshot                    jsonb not null default '{}',
    trigger_coc_id              uuid,
    reset_triggered             boolean not null default false,
    reset_reasons               jsonb not null default '[]',
    requested_by                varchar(120),
    requested_at                timestamptz,
    error_message               varchar(1000),
    version                     bigint not null default 0,            -- JPA @Version optimistic lock
    created_at                  timestamptz not null default now(),
    created_by                  varchar(120),
    updated_at                  timestamptz not null default now(),
    updated_by                  varchar(120)
);
create index idx_disclosure_issuance_org_loan_kind_ver
    on disclosure_issuance (org_id, loan_id, kind, disclosure_version);

create table disclosure_event (
    id             uuid primary key,
    org_id         uuid not null references organization(id),
    loan_id        uuid not null,
    disclosure_id  uuid,
    event_type     varchar(40) not null,
    detail         jsonb not null default '{}',
    actor          varchar(120),
    occurred_at    timestamptz not null default now(),
    version        bigint not null default 0,                         -- JPA @Version optimistic lock
    created_at     timestamptz not null default now(),
    created_by     varchar(120),
    updated_at     timestamptz not null default now(),
    updated_by     varchar(120)
);
create index idx_disclosure_event_org_loan
    on disclosure_event (org_id, loan_id, occurred_at);

-- TRID-supporting columns on existing tables (their RLS/policies are unchanged).
alter table fee_line_item add column paid_to varchar(40);
alter table fee_line_item add column consumer_can_shop boolean;
alter table fee_line_item add column on_written_list boolean;
alter table loan add column consummation_date date;

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V15.
alter table disclosure_issuance enable row level security;
alter table disclosure_issuance force row level security;
create policy tenant_isolation on disclosure_issuance
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table disclosure_event enable row level security;
alter table disclosure_event force row level security;
create policy tenant_isolation on disclosure_event
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on disclosure_issuance to app_user;
-- disclosure_event is an append-only audit: SELECT/INSERT only (lock_event precedent)
grant select, insert on disclosure_event to app_user;
