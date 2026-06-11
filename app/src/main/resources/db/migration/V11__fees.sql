create table fee_line_item (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    ordinal int not null default 0,
    section varchar(40) not null,
    label varchar(255) not null,
    amount numeric(15,2),
    seller_concession numeric(15,2),
    percent numeric(9,4),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120),
    constraint uq_fee_line_item unique (org_id, loan_id, section, label)
);
create index idx_fee_line_item_org_loan on fee_line_item(org_id, loan_id);

create table invoice_entry (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    fee_label varchar(255) not null,
    amount_disclosed numeric(15,2),
    invoice_amount numeric(15,2),
    borrower_poc numeric(15,2),
    finalized boolean not null default false,
    comment varchar(1000),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120),
    constraint uq_invoice_entry unique (org_id, loan_id, fee_label)
);
create index idx_invoice_entry_org_loan on invoice_entry(org_id, loan_id);

alter table fee_line_item enable row level security;
alter table fee_line_item force row level security;
create policy tenant_isolation on fee_line_item
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table invoice_entry enable row level security;
alter table invoice_entry force row level security;
create policy tenant_isolation on invoice_entry
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on fee_line_item to app_user;
grant select, insert, update, delete on invoice_entry to app_user;
