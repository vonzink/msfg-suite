alter table borrower_party
    add column middle_name varchar(120),
    add column suffix varchar(20),
    add column ssn varchar(512),
    add column date_of_birth date,
    add column marital_status varchar(20),
    add column dependents_count int,
    add column dependent_ages varchar(200),
    add column citizenship_type varchar(40),
    add column veteran boolean,
    add column unmarried_addendum_spousal_rights boolean,
    add column joined_to_borrower_id uuid,
    add column home_phone varchar(30),
    add column cell_phone varchar(30),
    add column work_phone varchar(30),
    add column work_phone_ext varchar(10),
    add column email varchar(255),
    add column no_email boolean;

create table borrower_address (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    borrower_id uuid not null references borrower_party(id),
    address_type varchar(30) not null,
    ordinal int not null default 0,
    address_line1 varchar(255),
    address_line2 varchar(255),
    city varchar(120),
    state varchar(2),
    postal_code varchar(10),
    country varchar(2) default 'US',
    ownership_type varchar(20),
    residency_duration_years int,
    residency_duration_months int,
    rent_amount numeric(15,2),
    rent_verified boolean,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_borrower_address_borrower on borrower_address(borrower_id);
create index idx_borrower_address_org on borrower_address(org_id);

create table pii_access_log (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    subject_type varchar(40) not null,
    subject_id uuid not null,
    field varchar(40) not null,
    reason varchar(500) not null,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_pii_access_org on pii_access_log(org_id);
create index idx_pii_access_subject on pii_access_log(subject_id);

alter table borrower_address enable row level security;
alter table borrower_address force row level security;
create policy tenant_isolation on borrower_address
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table pii_access_log enable row level security;
alter table pii_access_log force row level security;
create policy tenant_isolation on pii_access_log
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on borrower_address to app_user;
-- pii_access_log is an append-only audit: grant SELECT/INSERT only (no update/delete) so the
-- runtime role cannot alter or erase NPI-access records.
grant select, insert on pii_access_log to app_user;
