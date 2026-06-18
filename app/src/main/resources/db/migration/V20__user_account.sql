-- Cutover Phase 2/3 T2 — tenant-scoped users table, materialized on first /me call.
-- id is the Cognito sub (assigned, NOT generated). loan.loan_officer_id == user_account.id
-- (a logical join; no FK so loans can predate the user row materializing).
create table user_account (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    email varchar(320) not null,
    name varchar(255),
    initials varchar(10),
    role varchar(40),
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120),
    constraint uq_user_account_org_email unique (org_id, email)
);
create index idx_user_account_org on user_account(org_id);

alter table user_account enable row level security;
alter table user_account force row level security;
create policy tenant_isolation on user_account
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on user_account to app_user;
