-- V25: external idempotency key for the borrower funnel hand-off (msfg.us → mortgage-app → suite).
-- Nullable (app-created loans have none); unique PER ORG so a retried intake is idempotent within a
-- tenant without colliding across tenants. loan already has RLS + grants (V3) — do NOT re-grant.
alter table loan add column source_lead_id varchar(100) null;
create unique index uq_loan_org_source_lead on loan (org_id, source_lead_id)
    where source_lead_id is not null;
