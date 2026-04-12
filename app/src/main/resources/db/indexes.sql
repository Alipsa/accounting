create index if not exists idx_fiscal_year_dates
    on fiscal_year(start_date, end_date);

create index if not exists idx_accounting_period_fiscal_year
    on accounting_period(fiscal_year_id, period_index);

create index if not exists idx_accounting_period_dates
    on accounting_period(start_date, end_date);

create index if not exists idx_accounting_period_locked
    on accounting_period(locked);

create index if not exists idx_vat_period_fiscal_year
    on vat_period(fiscal_year_id, period_index);

create index if not exists idx_vat_period_dates
    on vat_period(start_date, end_date);

create index if not exists idx_vat_period_status
    on vat_period(status, reported_at);

create index if not exists idx_report_archive_fiscal_year
    on report_archive(fiscal_year_id, created_at);

create index if not exists idx_report_archive_type
    on report_archive(report_type, created_at);

create index if not exists idx_import_job_checksum
    on import_job(checksum_sha256);

create index if not exists idx_import_job_status
    on import_job(status, started_at);

create index if not exists idx_import_job_fiscal_year
    on import_job(fiscal_year_id, started_at);

create index if not exists idx_closing_entry_fiscal_year
    on closing_entry(fiscal_year_id, created_at);

create index if not exists idx_closing_entry_next_fiscal_year
    on closing_entry(next_fiscal_year_id, created_at);

create index if not exists idx_closing_entry_voucher
    on closing_entry(voucher_id);

create index if not exists idx_closing_entry_type
    on closing_entry(entry_type, created_at);

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

create index if not exists idx_voucher_line_voucher
    on voucher_line(voucher_id, line_index);

create index if not exists idx_voucher_line_account
    on voucher_line(account_number);

create index if not exists idx_attachment_voucher
    on attachment(voucher_id, created_at);

create index if not exists idx_attachment_checksum
    on attachment(checksum_sha256);

create index if not exists idx_audit_log_voucher
    on audit_log(voucher_id, created_at);

create index if not exists idx_audit_log_attachment
    on audit_log(attachment_id, created_at);

create index if not exists idx_audit_log_fiscal_year
    on audit_log(fiscal_year_id, created_at);

create index if not exists idx_audit_log_period
    on audit_log(accounting_period_id, created_at);

create index if not exists idx_audit_log_vat_period
    on audit_log(vat_period_id, created_at);

create index if not exists idx_audit_log_event_type
    on audit_log(event_type, created_at);
