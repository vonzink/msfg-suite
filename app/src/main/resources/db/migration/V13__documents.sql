create table document (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    document_type varchar(40) not null,
    category varchar(120),
    file_name varchar(500),
    content_type varchar(200),
    size_bytes bigint,
    storage_key varchar(120) not null,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_document_org_loan on document(org_id, loan_id);
create index idx_document_org_loan_type on document(org_id, loan_id, document_type);

create table document_content (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    storage_key varchar(120) not null,
    content bytea,
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120),
    constraint uq_document_content unique (org_id, storage_key)
);

alter table document enable row level security;
alter table document force row level security;
create policy tenant_isolation on document
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table document_content enable row level security;
alter table document_content force row level security;
create policy tenant_isolation on document_content
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on document to app_user;
grant select, insert, update, delete on document_content to app_user;
