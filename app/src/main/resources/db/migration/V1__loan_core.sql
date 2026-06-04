create sequence loan_number_seq start 1 increment 1;

create table loan (
    id uuid primary key,
    version bigint not null default 0,
    loan_number varchar(20) not null unique,
    loan_officer_id uuid not null,
    status varchar(40) not null,
    loan_purpose varchar(40),
    mortgage_type varchar(40),
    lien_priority varchar(40),
    amortization_type varchar(40),
    note_amount numeric(15,2),
    address_line1 varchar(255),
    address_line2 varchar(255),
    city varchar(120),
    state varchar(2),
    postal_code varchar(10),
    estimated_value numeric(15,2),
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_loan_officer on loan(loan_officer_id);
create index idx_loan_status on loan(status);

create table loan_status_history (
    id uuid primary key,
    version bigint not null default 0,
    loan_id uuid not null references loan(id),
    from_status varchar(40),
    to_status varchar(40) not null,
    reason varchar(1000),
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_history_loan on loan_status_history(loan_id);
