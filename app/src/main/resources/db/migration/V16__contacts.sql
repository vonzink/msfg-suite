-- V16: contacts module — loan-scoped contact roster (listing agent, escrow officer, ...)

CREATE TABLE contact (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL REFERENCES organization(id),
    loan_id     uuid NOT NULL,
    role        varchar(40) NOT NULL,
    name        varchar(200) NOT NULL,
    company     varchar(200),
    phone       varchar(40),
    email       varchar(200),
    ordinal     int NOT NULL DEFAULT 0,
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  varchar(120),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  varchar(120)
);
CREATE INDEX idx_contact_org_loan ON contact (org_id, loan_id);

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V15.
alter table contact enable row level security;
alter table contact force row level security;
create policy tenant_isolation on contact
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on contact to app_user;
