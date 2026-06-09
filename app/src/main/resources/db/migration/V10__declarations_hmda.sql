create table borrower_declarations (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    occupy_as_primary_residence boolean,
    had_ownership_interest_last3_years boolean,
    family_or_business_affiliation_with_seller boolean,
    borrowing_undisclosed_money boolean,
    applying_for_other_mortgage_on_property boolean,
    applying_for_new_credit_before_closing boolean,
    subject_to_priority_lien_pace boolean,
    co_signer_or_guarantor_on_undisclosed_debt boolean,
    outstanding_judgments boolean,
    delinquent_or_default_on_federal_debt boolean,
    party_to_lawsuit boolean,
    conveyed_title_in_lieu_last7_years boolean,
    completed_pre_foreclosure_short_sale_last7_years boolean,
    property_foreclosed_last7_years boolean,
    declared_bankruptcy_last7_years boolean,
    prior_property_usage varchar(40),
    prior_property_title_type varchar(40),
    bankruptcy_types varchar(60),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120),
    unique (org_id, borrower_id)
);
create index idx_borrower_declarations_org_loan on borrower_declarations(org_id, loan_id);

alter table borrower_declarations enable row level security;
alter table borrower_declarations force row level security;
create policy tenant_isolation on borrower_declarations
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
grant select, insert, update, delete on borrower_declarations to app_user;

create table borrower_demographics (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null references borrower_party(id),
    ethnicity varchar(255),
    race varchar(512),
    sex varchar(30),
    collected_by_visual_observation_or_surname boolean,
    application_taken_method varchar(30),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120),
    unique (org_id, borrower_id)
);
create index idx_borrower_demographics_org_loan on borrower_demographics(org_id, loan_id);

alter table borrower_demographics enable row level security;
alter table borrower_demographics force row level security;
create policy tenant_isolation on borrower_demographics
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
grant select, insert, update, delete on borrower_demographics to app_user;
