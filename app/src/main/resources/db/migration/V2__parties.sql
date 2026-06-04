create table borrower_party (
    id uuid primary key,
    version bigint not null default 0,
    loan_id uuid not null references loan(id),
    is_primary boolean not null default false,
    ordinal int not null default 0,
    first_name varchar(120),
    last_name varchar(120),
    created_at timestamp(6) with time zone,
    created_by varchar(120),
    updated_at timestamp(6) with time zone,
    updated_by varchar(120)
);
create index idx_borrower_loan on borrower_party(loan_id);
