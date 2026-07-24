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
--
-- Repairing rows already corrupted by the old nulling behavior happens in Groovy
-- (AuditLogService.repairIntegrityForAllCompanies, invoked from DatabaseService right after
-- this migration applies) rather than here: reconstructing the five reference columns needs
-- per-event-type knowledge of which key each was recorded under in the details text (they
-- don't all use the same key names - VAT_PERIOD_LOCKED's voucher_id, for instance, was
-- recorded as transferVoucherId), and not every field is recoverable that way. Whatever
-- can't be recovered is closed out by rebuilding that company's hash chain from its current
-- (already-archived, un-nullable-further) state, which is easier to get right and test as
-- Groovy than as a wall of per-field regexes.
alter table audit_log drop constraint if exists fk_audit_log_voucher;
alter table audit_log drop constraint if exists fk_audit_log_attachment;
alter table audit_log drop constraint if exists fk_audit_log_fiscal_year;
alter table audit_log drop constraint if exists fk_audit_log_period;
alter table audit_log drop constraint if exists fk_audit_log_vat_period;
