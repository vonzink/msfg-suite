create table asset (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    ordinal int not null default 0,
    asset_type varchar(50) not null,
    financial_institution varchar(255),
    account_number varchar(80),
    cash_or_market_value numeric(15,2),
    verified boolean,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_asset_org_borrower on asset(org_id, borrower_id);
create index idx_asset_org_loan on asset(org_id, loan_id);

create table liability (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    ordinal int not null default 0,
    liability_type varchar(40) not null,
    creditor_name varchar(255),
    account_number varchar(80),
    unpaid_balance numeric(15,2),
    monthly_payment numeric(15,2),
    include_in_dti boolean not null default true,
    exclusion_reason varchar(40),
    months_remaining int,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_liability_org_borrower on liability(org_id, borrower_id);
create index idx_liability_org_loan on liability(org_id, loan_id);

create table asset_verification (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid,
    verification_type varchar(20) not null,
    status varchar(20) not null,
    provider varchar(120),
    reference_number varchar(120),
    ordered_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_asset_verification_org_loan on asset_verification(org_id, loan_id);

alter table asset enable row level security;
alter table asset force row level security;
create policy tenant_isolation on asset
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table liability enable row level security;
alter table liability force row level security;
create policy tenant_isolation on liability
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table asset_verification enable row level security;
alter table asset_verification force row level security;
create policy tenant_isolation on asset_verification
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on asset to app_user;
grant select, insert, update, delete on liability to app_user;
grant select, insert, update, delete on asset_verification to app_user;
