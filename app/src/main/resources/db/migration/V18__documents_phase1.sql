-- V18: Documents Phase 1 (cutover) — folder/type/template catalogs, status history,
-- and document state fields. Brings the documents module to mortgage-app parity,
-- adapted multi-tenant + staff-only. All new tables are org-scoped + FORCE RLS + grants
-- (the V13 pattern). Seeds 17 folder_templates + 16 document_types per existing org.
-- Migrations are an append-only ordered sequence: V17 was the last; V18 is next.

-- ── ALTER document: add Phase-1 state fields ─────────────────────────────────────────
alter table document add column folder_id        uuid;
alter table document add column document_type_id uuid;
alter table document add column document_status  varchar(30) not null default 'PENDING_UPLOAD';
alter table document add column party_role       varchar(20);
alter table document add column reviewed_by      varchar(120);
alter table document add column reviewer_notes   varchar(2000);
alter table document add column reviewed_at      timestamptz;
alter table document add column file_hash        varchar(64);
alter table document add column description       varchar(1000);
alter table document add column deleted_at        timestamptz;

create index idx_document_org_loan_status on document(org_id, loan_id, document_status);

-- ── folder ───────────────────────────────────────────────────────────────────────────
create table folder (
    id                  uuid primary key,
    org_id              uuid not null references organization(id),
    loan_id             uuid not null,
    parent_id           uuid,
    display_name        varchar(200) not null,
    name_normalized     varchar(200) not null,
    sort_key            varchar(64),
    is_system           boolean not null default false,
    is_old_loan_archive boolean not null default false,
    is_delete_folder    boolean not null default false,
    folder_template_id  uuid,
    created_by          varchar(120),
    deleted_at          timestamptz,
    version             bigint not null default 0,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    updated_by          varchar(120)
);
create index idx_folder_org_loan on folder(org_id, loan_id);
-- one live root folder per loan
create unique index uq_folder_root
    on folder(org_id, loan_id)
    where parent_id is null and deleted_at is null;
-- case-insensitive sibling uniqueness within a parent
create unique index uq_folder_sibling_name
    on folder(org_id, loan_id, parent_id, name_normalized)
    where deleted_at is null;

-- ── document_type (org-scoped catalog) ────────────────────────────────────────────────
create table document_type (
    id                     uuid primary key,
    org_id                 uuid not null references organization(id),
    name                   varchar(200) not null,
    slug                   varchar(120) not null,
    default_folder_name    varchar(200),
    required_for_milestones varchar(500),
    allowed_mime_types     varchar(500),
    max_file_size_bytes    bigint,
    is_active              boolean not null default true,
    sort_order             int not null default 0,
    version                bigint not null default 0,
    created_at             timestamptz not null default now(),
    created_by             varchar(120),
    updated_at             timestamptz not null default now(),
    updated_by             varchar(120),
    constraint uq_document_type_slug unique (org_id, slug)
);
create index idx_document_type_org on document_type(org_id);

-- ── folder_template (org-scoped) ──────────────────────────────────────────────────────
create table folder_template (
    id                  uuid primary key,
    org_id              uuid not null references organization(id),
    display_name        varchar(200) not null,
    sort_key            varchar(64),
    is_old_loan_archive boolean not null default false,
    is_delete_folder    boolean not null default false,
    is_active           boolean not null default true,
    sort_order          int not null default 0,
    eval_prompt         text,
    version             bigint not null default 0,
    created_at          timestamptz not null default now(),
    created_by          varchar(120),
    updated_at          timestamptz not null default now(),
    updated_by          varchar(120),
    constraint uq_folder_template_name unique (org_id, display_name)
);
create index idx_folder_template_org on folder_template(org_id);

-- ── document_status_history (append-only) ─────────────────────────────────────────────
create table document_status_history (
    id              uuid primary key,
    org_id          uuid not null references organization(id),
    document_id     uuid not null references document(id) on delete cascade,
    status          varchar(30) not null,
    transitioned_at timestamptz not null default now(),
    transitioned_by varchar(120),
    note            varchar(1000),
    version         bigint not null default 0,
    created_at      timestamptz not null default now(),
    created_by      varchar(120),
    updated_at      timestamptz not null default now(),
    updated_by      varchar(120)
);
create index idx_doc_status_history on document_status_history(org_id, document_id, transitioned_at);

-- ── Row-level security (FORCE + WITH CHECK, fail-closed) — the V13 policy shape ────────
alter table folder enable row level security;
alter table folder force row level security;
create policy tenant_isolation on folder
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table document_type enable row level security;
alter table document_type force row level security;
create policy tenant_isolation on document_type
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table folder_template enable row level security;
alter table folder_template force row level security;
create policy tenant_isolation on folder_template
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table document_status_history enable row level security;
alter table document_status_history force row level security;
create policy tenant_isolation on document_status_history
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on folder to app_user;
grant select, insert, update, delete on document_type to app_user;
grant select, insert, update, delete on folder_template to app_user;
grant select, insert, update, delete on document_status_history to app_user;

-- ── SEED: 17 folder_templates per existing organization (sort_key "01".."17") ─────────
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Submission',          '01', false, false, true, 1  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Borrower Documents',  '02', false, false, true, 2  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Income',              '03', false, false, true, 3  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Assets',              '04', false, false, true, 4  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Credit',              '05', false, false, true, 5  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Property',            '06', false, false, true, 6  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Title',               '07', false, false, true, 7  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Insurance',           '08', false, false, true, 8  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Disclosures',         '09', false, false, true, 9  from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Conditions',          '10', false, false, true, 10 from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Underwriting',        '11', false, false, true, 11 from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Closing',             '12', false, false, true, 12 from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Post Closing',        '13', false, false, true, 13 from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Invoices',            '14', false, false, true, 14 from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Correspondence',      '15', false, false, true, 15 from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Old Loan Files',      '16', true,  false, true, 16 from organization o;
insert into folder_template (id, org_id, version, display_name, sort_key, is_old_loan_archive, is_delete_folder, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Delete',              '17', false, true,  true, 17 from organization o;

-- ── SEED: 16 document_types per existing organization ─────────────────────────────────
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'W-2',                   'w-2',                  '03 Income',             'application/pdf,image/jpeg,image/png', 10485760, true, 1  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Pay Stub',              'pay-stub',             '03 Income',             'application/pdf,image/jpeg,image/png', 10485760, true, 2  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Tax Return',            'tax-return',           '03 Income',             'application/pdf', 52428800, true, 3  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Bank Statement',        'bank-statement',       '04 Assets',             'application/pdf,image/jpeg,image/png', 20971520, true, 4  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Investment Statement',  'investment-statement', '04 Assets',             'application/pdf', 20971520, true, 5  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Gift Letter',           'gift-letter',          '04 Assets',             'application/pdf,image/jpeg,image/png', 10485760, true, 6  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'ID / Driver''s License', 'drivers-license',     '02 Borrower Documents', 'application/pdf,image/jpeg,image/png', 10485760, true, 7  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Explanation Letter',    'explanation-letter',   '02 Borrower Documents', 'application/pdf,image/jpeg,image/png', 10485760, true, 8  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Credit Report',         'credit-report',        '05 Credit',             'application/pdf', 20971520, true, 9  from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Purchase Agreement',    'purchase-agreement',   '06 Property',           'application/pdf', 52428800, true, 10 from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Appraisal',             'appraisal',            '06 Property',           'application/pdf', 52428800, true, 11 from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Title Report',          'title-report',         '07 Title',              'application/pdf', 52428800, true, 12 from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Homeowners Insurance',  'homeowners-insurance', '08 Insurance',          'application/pdf,image/jpeg,image/png', 20971520, true, 13 from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Disclosure',            'disclosure',           '09 Disclosures',        'application/pdf', 52428800, true, 14 from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Closing Document',      'closing-document',     '12 Closing',            'application/pdf', 52428800, true, 15 from organization o;
insert into document_type (id, org_id, version, name, slug, default_folder_name, allowed_mime_types, max_file_size_bytes, is_active, sort_order)
    select gen_random_uuid(), o.id, 0, 'Other',                 'other',                null,                    null, 52428800, true, 99 from organization o;
