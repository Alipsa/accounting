-- Speeds up balance calculations while navigating between vouchers.
create index if not exists idx_voucher_line_account_id
    on voucher_line(account_id, voucher_id);
