-- V22: notes module — loan-scoped free-text notes (author-stamped, newest-first)

CREATE TABLE loan_note (
    id           uuid PRIMARY KEY,
    org_id       uuid NOT NULL REFERENCES organization(id),
    loan_id      uuid NOT NULL,
    author_id    varchar(120),
    author_name  varchar(200),
    content      varchar(2000) NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   varchar(120),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   varchar(120)
);
CREATE INDEX idx_loan_note_org_loan_created ON loan_note (org_id, loan_id, created_at);

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V13/V16/V21.
alter table loan_note enable row level security;
alter table loan_note force row level security;
create policy tenant_isolation on loan_note
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on loan_note to app_user;
