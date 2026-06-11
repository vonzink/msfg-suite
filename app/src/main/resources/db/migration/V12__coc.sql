create table coc_draft (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    date_of_discovery date,
    reason varchar(40),
    structure_changes jsonb not null default '[]'::jsonb,
    fee_changes jsonb not null default '[]'::jsonb,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120),
    constraint uq_coc_draft unique (org_id, loan_id)
);
create index idx_coc_draft_org_loan on coc_draft(org_id, loan_id);

create table coc_history_entry (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    date_of_discovery date,
    reason varchar(40),
    structure_changes jsonb not null default '[]'::jsonb,
    fee_changes jsonb not null default '[]'::jsonb,
    status varchar(20) not null,
    submitted_at timestamp(6) with time zone,
    submitted_by varchar(120),
    decision_by varchar(120),
    decision_date timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_coc_history_entry_org_loan on coc_history_entry(org_id, loan_id);

alter table coc_draft enable row level security;
alter table coc_draft force row level security;
create policy tenant_isolation on coc_draft
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table coc_history_entry enable row level security;
alter table coc_history_entry force row level security;
create policy tenant_isolation on coc_history_entry
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on coc_draft to app_user;
grant select, insert, update, delete on coc_history_entry to app_user;
