create table organization (
    id uuid primary key,
    version bigint not null default 0,
    name varchar(200) not null,
    slug varchar(100) not null unique,
    status varchar(20) not null default 'ACTIVE',
    settings jsonb not null default '{}'::jsonb,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);

insert into organization (id, version, name, slug, status, settings)
values ('00000000-0000-0000-0000-0000000000aa', 0, 'MSFG', 'msfg', 'ACTIVE', '{}'::jsonb);

alter table loan add column org_id uuid;
update loan set org_id = '00000000-0000-0000-0000-0000000000aa' where org_id is null;
alter table loan alter column org_id set not null;
alter table loan add constraint fk_loan_org foreign key (org_id) references organization(id);
create index idx_loan_org on loan(org_id);

alter table borrower_party add column org_id uuid;
update borrower_party set org_id = '00000000-0000-0000-0000-0000000000aa' where org_id is null;
alter table borrower_party alter column org_id set not null;
alter table borrower_party add constraint fk_borrower_org foreign key (org_id) references organization(id);
create index idx_borrower_org on borrower_party(org_id);

alter table loan_status_history add column org_id uuid;
update loan_status_history set org_id = '00000000-0000-0000-0000-0000000000aa' where org_id is null;
alter table loan_status_history alter column org_id set not null;
alter table loan_status_history add constraint fk_lsh_org foreign key (org_id) references organization(id);
create index idx_lsh_org on loan_status_history(org_id);

-- RLS (defense-in-depth). FORCE so it applies to the table owner / Testcontainers superuser too.
-- Unset GUC -> current_setting(...,true) NULL -> policy false -> deny-all (fail-closed).
alter table loan enable row level security;
alter table loan force row level security;
create policy tenant_isolation on loan using (org_id = current_setting('app.current_org', true)::uuid);

alter table borrower_party enable row level security;
alter table borrower_party force row level security;
create policy tenant_isolation on borrower_party using (org_id = current_setting('app.current_org', true)::uuid);

alter table loan_status_history enable row level security;
alter table loan_status_history force row level security;
create policy tenant_isolation on loan_status_history using (org_id = current_setting('app.current_org', true)::uuid);
