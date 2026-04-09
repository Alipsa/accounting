create index if not exists idx_fiscal_year_dates
    on fiscal_year(start_date, end_date);

create index if not exists idx_accounting_period_fiscal_year
    on accounting_period(fiscal_year_id, period_index);

create index if not exists idx_accounting_period_dates
    on accounting_period(start_date, end_date);

create index if not exists idx_accounting_period_locked
    on accounting_period(locked);
