-- V26: staff-initiated borrower verification (unified-passwordless security spec §6.2/§6.3).
-- One row per "send a verification code to this borrower" action. The row IS the audit record AND
-- the OTP store: it holds ONLY a salted one-way hash of the 6-digit code (never the code, never
-- reversible ciphertext), a TTL, an attempts counter, and a single-use consumed marker.
--
-- Tenant isolation mirrors pii_access_log (V6): FORCE row-level security + WITH CHECK, fail-closed via
-- nullif(current_setting('app.current_org', true), '')::uuid. Unlike the append-only pii_access_log,
-- app_user needs UPDATE here (the verify path increments attempts and stamps consumed_at) — but NOT
-- DELETE (rows are immutable audit once written).
create table verification_request (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    borrower_id uuid not null,
    channel varchar(20) not null,
    code_hash varchar(120) not null,
    code_salt varchar(40) not null,
    expires_at timestamp(6) with time zone not null,
    attempts int not null default 0,
    consumed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
-- Send-throttle + verify lookups query by (org, borrower) and (org, created_by) over a recent window,
-- and the verify path looks up the latest live row per (org, loan, borrower).
create index idx_verification_org_borrower on verification_request(org_id, borrower_id, created_at);
create index idx_verification_org_creator on verification_request(org_id, created_by, created_at);
create index idx_verification_loan_borrower on verification_request(org_id, loan_id, borrower_id, created_at);

alter table verification_request enable row level security;
alter table verification_request force row level security;
create policy tenant_isolation on verification_request
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

-- SELECT/INSERT/UPDATE only: UPDATE is required for the attempts counter + consumed_at marker on the
-- verify path. No DELETE — verification rows are an immutable audit trail once written.
grant select, insert, update on verification_request to app_user;
