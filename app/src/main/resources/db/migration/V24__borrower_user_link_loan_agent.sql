-- V24: Phase F identity/access —
--   (1) link a borrower_party row to a cross-service identity (Cognito sub) via user_id.
--   (2) loan_agent — loan-scoped roster of buyer's/listing agents (a cross-service identity per row).
-- user_id is a Cognito sub (a cross-service identity): NULLABLE on borrower_party (co-borrowers
-- without an account stay valid), NOT NULL on loan_agent. NO FK in either case — identity lives in
-- another service, so we only carry the sub.

-- ── (1) borrower_party.user_id ────────────────────────────────────────────────
-- Additive nullable column; borrower_party already has RLS + grants (V3/V6) — do NOT re-grant.
alter table borrower_party add column user_id uuid null;
create index idx_borrower_party_org_user on borrower_party (org_id, user_id);

-- ── (2) loan_agent ────────────────────────────────────────────────────────────
-- Loan-scoped; mirrors the V16 contact table convention (column types, audit cols, RLS, grants).
create table loan_agent (
    id          uuid primary key,
    org_id      uuid not null references organization(id),
    loan_id     uuid not null,
    user_id     uuid not null,
    agent_role  varchar(40) not null default 'BUYERS_AGENT',
    ordinal     int not null default 0,
    version     bigint not null default 0,
    created_at  timestamptz not null default now(),
    created_by  varchar(120),
    updated_at  timestamptz not null default now(),
    updated_by  varchar(120),
    constraint uq_loan_agent_org_loan_user unique (org_id, loan_id, user_id)
);
create index idx_loan_agent_org_loan on loan_agent (org_id, loan_id);
create index idx_loan_agent_org_user on loan_agent (org_id, user_id);

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V16.
alter table loan_agent enable row level security;
alter table loan_agent force row level security;
create policy tenant_isolation on loan_agent
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on loan_agent to app_user;
