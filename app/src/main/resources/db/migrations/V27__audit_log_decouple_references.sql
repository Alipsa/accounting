-- The audit log is an append-only trail: once a row is written, its entry_hash must
-- never change, since later rows chain off it via previous_hash. Fiscal-year replacement
-- (SIE reimport) archives audit rows tied to the purged year and used to null out their
-- voucher_id/attachment_id/fiscal_year_id/accounting_period_id/vat_period_id columns so the
-- subsequent hard deletes of voucher/attachment/fiscal_year rows wouldn't violate the
-- "on delete restrict" foreign keys below. But entry_hash was computed from those columns
-- at insert time and was never recalculated, so every archived row permanently failed
-- validateIntegrity() afterwards. Dropping the restrictive foreign keys lets archived rows
-- keep pointing at (now possibly deleted) records instead, so they no longer need to be
-- nulled out.
alter table audit_log drop constraint if exists fk_audit_log_voucher;
alter table audit_log drop constraint if exists fk_audit_log_attachment;
alter table audit_log drop constraint if exists fk_audit_log_fiscal_year;
alter table audit_log drop constraint if exists fk_audit_log_period;
alter table audit_log drop constraint if exists fk_audit_log_vat_period;

-- Self-heal rows already corrupted by the old nulling behavior: the original voucher_id
-- and fiscal_year_id are still recoverable from the human-readable details text that was
-- captured at the same time and was never touched, so entry_hash matches again once they
-- are restored.
update audit_log
   set voucher_id = cast(regexp_replace(details, '(?s).*(?:^|\n)voucherId=(\d+)(?:\n.*|$)', '$1') as bigint)
 where archived = true
   and voucher_id is null
   and details is not null
   and regexp_like(details, '(?s).*(?:^|\n)voucherId=(\d+)(?:\n.*|$)');

update audit_log
   set fiscal_year_id = cast(regexp_replace(details, '(?s).*(?:^|\n)fiscalYearId=(\d+)(?:\n.*|$)', '$1') as bigint)
 where archived = true
   and fiscal_year_id is null
   and details is not null
   and regexp_like(details, '(?s).*(?:^|\n)fiscalYearId=(\d+)(?:\n.*|$)');
