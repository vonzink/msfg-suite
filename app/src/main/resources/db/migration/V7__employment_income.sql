create table employment (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    ordinal int not null default 0,
    employer_name varchar(255),
    employer_phone varchar(30),
    employer_address_line1 varchar(255),
    employer_address_line2 varchar(255),
    employer_city varchar(120),
    employer_state varchar(2),
    employer_postal_code varchar(10),
    position_title varchar(150),
    employment_status varchar(20),
    classification varchar(20),
    self_employed boolean,
    ownership_share varchar(30),
    employed_by_party_to_transaction boolean,
    start_date date,
    end_date date,
    months_in_line_of_work int,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_employment_org_borrower on employment(org_id, borrower_id);
create index idx_employment_org_loan on employment(org_id, loan_id);

create table income_item (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    employment_id uuid references employment(id) on delete cascade,
    income_type varchar(40) not null,
    monthly_amount numeric(15,2),
    description varchar(255),
    ordinal int not null default 0,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_income_item_org_borrower on income_item(org_id, borrower_id);
create index idx_income_item_org_loan on income_item(org_id, loan_id);
create index idx_income_item_org_employment on income_item(org_id, employment_id);

create table income_verification (
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
create index idx_income_verification_org_loan on income_verification(org_id, loan_id);

alter table employment enable row level security;
alter table employment force row level security;
create policy tenant_isolation on employment
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table income_item enable row level security;
alter table income_item force row level security;
create policy tenant_isolation on income_item
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table income_verification enable row level security;
alter table income_verification force row level security;
create policy tenant_isolation on income_verification
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on employment to app_user;
grant select, insert, update, delete on income_item to app_user;
grant select, insert, update, delete on income_verification to app_user;
