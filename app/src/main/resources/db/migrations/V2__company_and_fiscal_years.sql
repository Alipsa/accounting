create table if not exists company_settings
(
    id
    bigint
    primary
    key,
    company_name
    varchar
(
    200
) not null,
    organization_number varchar
(
    64
) not null,
    default_currency varchar
(
    3
) not null,
    locale_tag varchar
(
    32
) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint ck_company_settings_singleton check
(
    id =
    1
)
    );

create table if not exists fiscal_year
(
    id
    bigint
    generated
    by
    default as
    identity
    primary
    key,
    name
    varchar
(
    120
) not null,
    start_date date not null,
    end_date date not null,
    closed boolean not null default false,
    closed_at timestamp null,
    created_at timestamp not null,
    constraint ck_fiscal_year_date_range check
(
    start_date
    <=
    end_date
)
    );

create table if not exists accounting_period
(
    id
    bigint
    generated
    by
    default as
    identity
    primary
    key,
    fiscal_year_id
    bigint
    not
    null,
    period_index
    integer
    not
    null,
    period_name
    varchar
(
    20
) not null,
    start_date date not null,
    end_date date not null,
    locked boolean not null default false,
    lock_reason varchar
(
    500
),
    locked_at timestamp null,
    created_at timestamp not null,
    constraint fk_accounting_period_fiscal_year
    foreign key
(
    fiscal_year_id
) references fiscal_year
(
    id
) on delete cascade,
    constraint uq_accounting_period_order unique
(
    fiscal_year_id,
    period_index
),
    constraint ck_accounting_period_date_range check
(
    start_date
    <=
    end_date
)
    );
