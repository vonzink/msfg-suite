-- V15: AUS + credit vendor module — vendor_credential, aus_profile, credit_order, aus_run

CREATE TABLE vendor_credential (
    id                      uuid PRIMARY KEY,
    org_id                  uuid NOT NULL,
    loan_id                 uuid NULL,            -- NULL = org default; non-null = per-loan override
    vendor                  varchar(20) NOT NULL, -- DU | LPA | CREDIT
    institution_id          varchar(80),
    seller_servicer_number  varchar(80),
    tpo_number              varchar(80),
    branch_number           varchar(80),
    credit_provider_code    varchar(40),
    credit_affiliate_code   varchar(40),
    username                varchar(1024),        -- encrypted (AES-GCM ciphertext)
    password                varchar(1024),        -- encrypted
    credit_username         varchar(1024),        -- encrypted
    credit_password         varchar(1024),        -- encrypted
    version                 bigint NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_vendor_credential_org ON vendor_credential (org_id, vendor) WHERE loan_id IS NULL;
CREATE UNIQUE INDEX uq_vendor_credential_loan ON vendor_credential (org_id, vendor, loan_id) WHERE loan_id IS NOT NULL;
CREATE INDEX idx_vendor_credential_org_loan ON vendor_credential (org_id, loan_id);

CREATE TABLE aus_profile (
    id           uuid PRIMARY KEY,
    org_id       uuid NOT NULL,
    loan_id      uuid NOT NULL,
    du_settings  jsonb NOT NULL DEFAULT '{}',
    lpa_settings jsonb NOT NULL DEFAULT '{}',
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_aus_profile UNIQUE (org_id, loan_id)
);

CREATE TABLE credit_order (
    id                        uuid PRIMARY KEY,
    org_id                    uuid NOT NULL,
    loan_id                   uuid NOT NULL,
    provider_code             varchar(40),
    action                    varchar(20) NOT NULL,  -- SUBMIT|FORCE_NEW|REISSUE|UPGRADE
    request_type              varchar(20) NOT NULL,  -- INDIVIDUAL|JOINT
    equifax                   boolean NOT NULL DEFAULT true,
    experian                  boolean NOT NULL DEFAULT true,
    trans_union               boolean NOT NULL DEFAULT true,
    borrower_ids              jsonb NOT NULL DEFAULT '[]',
    status                    varchar(20) NOT NULL,  -- PENDING|COMPLETE|ERROR
    credit_report_identifier  varchar(120),
    scores                    jsonb NOT NULL DEFAULT '[]',
    report_document_id        uuid,
    requested_by              varchar(120),
    requested_at              timestamptz NOT NULL DEFAULT now(),
    error_message             varchar(1000),
    version                   bigint NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_credit_order_org_loan ON credit_order (org_id, loan_id, requested_at);

CREATE TABLE aus_run (
    id                        uuid PRIMARY KEY,
    org_id                    uuid NOT NULL,
    loan_id                   uuid NOT NULL,
    vendor                    varchar(10) NOT NULL,  -- DU|LPA
    status                    varchar(20) NOT NULL,
    vendor_case_id            varchar(120),
    vendor_transaction_id     varchar(120),
    recommendation            varchar(40),
    raw_recommendation        varchar(120),
    raw_eligibility           varchar(120),
    credit_report_identifier  varchar(120),
    findings_html_document_id uuid,
    findings_xml_document_id  uuid,
    messages                  jsonb NOT NULL DEFAULT '[]',
    requested_by              varchar(120),
    requested_at              timestamptz NOT NULL DEFAULT now(),
    error_message             varchar(1000),
    version                   bigint NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_aus_run_org_loan ON aus_run (org_id, loan_id, requested_at);

-- Row-level security (FORCE + WITH CHECK, fail-closed) — same policy shape as V14.
alter table vendor_credential enable row level security;
alter table vendor_credential force row level security;
create policy tenant_isolation on vendor_credential
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table aus_profile enable row level security;
alter table aus_profile force row level security;
create policy tenant_isolation on aus_profile
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table credit_order enable row level security;
alter table credit_order force row level security;
create policy tenant_isolation on credit_order
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

alter table aus_run enable row level security;
alter table aus_run force row level security;
create policy tenant_isolation on aus_run
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on vendor_credential to app_user;
grant select, insert, update, delete on aus_profile to app_user;
grant select, insert, update, delete on credit_order to app_user;
grant select, insert, update, delete on aus_run to app_user;
