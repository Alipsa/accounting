create table if not exists account (
    account_number varchar(10) primary key,
    account_name varchar(255) not null,
    account_class varchar(32),
    normal_balance_side varchar(16),
    vat_code varchar(32),
    active boolean not null default true,
    manual_review_required boolean not null default false,
    classification_note varchar(255),
    created_at timestamp not null,
    updated_at timestamp not null
);

create table if not exists opening_balance (
    fiscal_year_id bigint not null,
    account_number varchar(10) not null,
    amount decimal(18, 2) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint pk_opening_balance primary key (fiscal_year_id, account_number),
    constraint fk_opening_balance_fiscal_year
        foreign key (fiscal_year_id) references fiscal_year(id) on delete cascade,
    constraint fk_opening_balance_account
        foreign key (account_number) references account(account_number) on delete cascade
);
