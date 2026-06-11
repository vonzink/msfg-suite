-- V14: Products & Pricing / Rate Lock — rate_lock (current, 1:1/loan),
-- pricing_adjustment (quote snapshot), lock_event (append-only audit).

create table rate_lock (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null unique,
    locked_rate numeric(7,3) not null,
    commitment_days int not null,
    lock_date timestamp(6) with time zone not null,
    expiration_date date not null,
    extension_days_total int not null default 0,
    compensation_payer_type varchar(20) not null,
    locked_by varchar(120),
    interviewer_email varchar(320),
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_rate_lock_org_loan on rate_lock(org_id, loan_id);

create table pricing_adjustment (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    ordinal int not null,
    name varchar(200) not null,
    row_type varchar(30) not null,
    adjustment_percent numeric(8,3) not null,
    dollar_amount numeric(14,2) not null,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_pricing_adjustment_org_loan on pricing_adjustment(org_id, loan_id);

create table lock_event (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    action varchar(30) not null,
    actor varchar(120),
    occurred_at timestamp(6) with time zone not null,
    rate numeric(7,3) not null,
    commitment_days int not null,
    expiration_date date not null,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_lock_event_org_loan on lock_event(org_id, loan_id);

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V13.
alter table rate_lock enable row level security;
alter table rate_lock force row level security;
create policy tenant_isolation on rate_lock
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table pricing_adjustment enable row level security;
alter table pricing_adjustment force row level security;
create policy tenant_isolation on pricing_adjustment
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table lock_event enable row level security;
alter table lock_event force row level security;
create policy tenant_isolation on lock_event
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on rate_lock to app_user;
grant select, insert, update, delete on pricing_adjustment to app_user;
-- lock_event is an append-only audit: SELECT/INSERT only (pii_access_log precedent)
grant select, insert on lock_event to app_user;
