alter table loan
    add column documentation_type varchar(40),
    add column interest_rate numeric(7,4),
    add column loan_term_months int,
    add column base_loan_amount numeric(15,2),
    add column financed_fees_amount numeric(15,2),
    add column second_loan_amount numeric(15,2),
    add column down_payment_amount numeric(15,2),
    add column qualifying_credit_score int,
    add column proposed_taxes_monthly numeric(15,2),
    add column proposed_hazard_insurance_monthly numeric(15,2),
    add column proposed_hoa_dues_monthly numeric(15,2),
    add column proposed_mortgage_insurance_monthly numeric(15,2),
    add column sales_price numeric(15,2),
    add column appraised_value numeric(15,2),
    add column property_type varchar(40),
    add column occupancy_type varchar(40),
    add column number_of_units int;

create table reo (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    owner_borrower_id uuid,
    ordinal int not null default 0,
    is_subject_property boolean not null default false,
    address_line1 varchar(255), address_line2 varchar(255),
    city varchar(120), state varchar(2), postal_code varchar(10),
    property_type varchar(40),
    intended_occupancy varchar(40),
    property_status varchar(20),
    market_value numeric(15,2),
    gross_monthly_rental_income numeric(15,2),
    monthly_taxes numeric(15,2), monthly_insurance numeric(15,2),
    monthly_hoa_dues numeric(15,2), monthly_maintenance numeric(15,2),
    mortgage_unpaid_balance numeric(15,2), mortgage_monthly_payment numeric(15,2),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_reo_org_loan on reo(org_id, loan_id);

alter table reo enable row level security;
alter table reo force row level security;
create policy tenant_isolation on reo
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
grant select, insert, update, delete on reo to app_user;
