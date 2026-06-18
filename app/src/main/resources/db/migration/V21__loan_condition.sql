-- V21: conditions module — loan-scoped underwriting conditions (PriorToDocs, PriorToFunding, ...)

CREATE TABLE loan_condition (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL REFERENCES organization(id),
    loan_id         uuid NOT NULL,
    condition_text  varchar(2000) NOT NULL,
    condition_type  varchar(50),
    status          varchar(20) NOT NULL DEFAULT 'Outstanding',
    assigned_to     varchar(120),
    due_date        date,
    cleared_at      timestamptz,
    cleared_by      varchar(120),
    notes           varchar(2000),
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      varchar(120),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      varchar(120)
);
CREATE INDEX idx_loan_condition_org_loan_status ON loan_condition (org_id, loan_id, status);

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V13/V16.
alter table loan_condition enable row level security;
alter table loan_condition force row level security;
create policy tenant_isolation on loan_condition
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on loan_condition to app_user;
