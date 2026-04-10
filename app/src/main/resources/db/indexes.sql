create index if not exists idx_fiscal_year_dates
    on fiscal_year(start_date, end_date);

create index if not exists idx_accounting_period_fiscal_year
    on accounting_period(fiscal_year_id, period_index);

create index if not exists idx_accounting_period_dates
    on accounting_period(start_date, end_date);

create index if not exists idx_accounting_period_locked
    on accounting_period(locked);

create index if not exists idx_account_class
    on account(account_class);

create index if not exists idx_account_active
    on account(active);

create index if not exists idx_account_manual_review
    on account(manual_review_required);

create index if not exists idx_account_name
    on account(account_name);

create index if not exists idx_opening_balance_fiscal_year
    on opening_balance(fiscal_year_id);

create index if not exists idx_voucher_series_fiscal_year
    on voucher_series(fiscal_year_id, series_code);

create index if not exists idx_voucher_fiscal_year
    on voucher(fiscal_year_id, accounting_date);

create index if not exists idx_voucher_status
    on voucher(status);

create index if not exists idx_voucher_original
    on voucher(original_voucher_id);

create index if not exists idx_voucher_hash_chain
    on voucher(booked_at, id);

create index if not exists idx_voucher_line_voucher
    on voucher_line(voucher_id, line_index);

create index if not exists idx_voucher_line_account
    on voucher_line(account_number);
