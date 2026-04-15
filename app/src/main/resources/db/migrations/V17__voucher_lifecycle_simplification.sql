-- V17: Simplify voucher lifecycle
--
-- Remove the DRAFT→BOOKED distinction; every non-cancelled, non-correction
-- voucher is now ACTIVE.  Drop the hash-chain integrity mechanism.

-- 1. Convert DRAFT and BOOKED vouchers to ACTIVE
update voucher set status = 'ACTIVE' where status = 'DRAFT';
update voucher set status = 'ACTIVE' where status = 'BOOKED';

-- 2. Assign running numbers to former drafts that lack them.
--    For each voucher_series, allocate numbers sequentially starting after
--    the current max running_number in that series.
--    Also set voucher_number = series_code || '-' || running_number.
update voucher v
set v.running_number = (
      select coalesce(max(v2.running_number), 0)
      from voucher v2
      where v2.voucher_series_id = v.voucher_series_id
        and v2.fiscal_year_id = v.fiscal_year_id
        and v2.running_number is not null
    ) + (
      select count(*)
      from voucher v3
      where v3.voucher_series_id = v.voucher_series_id
        and v3.fiscal_year_id = v.fiscal_year_id
        and v3.running_number is null
        and v3.id <= v.id
    ),
    v.voucher_number = (
      select vs.series_code
      from voucher_series vs
      where vs.id = v.voucher_series_id
    ) || '-' || (
      select coalesce(max(v2.running_number), 0)
      from voucher v2
      where v2.voucher_series_id = v.voucher_series_id
        and v2.fiscal_year_id = v.fiscal_year_id
        and v2.running_number is not null
    ) + (
      select count(*)
      from voucher v3
      where v3.voucher_series_id = v.voucher_series_id
        and v3.fiscal_year_id = v.fiscal_year_id
        and v3.running_number is null
        and v3.id <= v.id
    )
where v.running_number is null;

-- 3. Update next_running_number in voucher_series to reflect allocated numbers
update voucher_series vs
set vs.next_running_number = coalesce((
    select max(v.running_number) + 1
    from voucher v
    where v.voucher_series_id = vs.id
      and v.fiscal_year_id = vs.fiscal_year_id
), vs.next_running_number);

-- 4. Drop hash-related columns
alter table voucher drop column if exists previous_hash;
alter table voucher drop column if exists content_hash;
alter table voucher drop column if exists booked_at;

-- 5. Drop the voucher_chain_head table
drop table if exists voucher_chain_head;

-- 6. Replace the status CHECK constraint.
--    H2 auto-names CHECK constraints; drop the named one from V4.
alter table voucher drop constraint if exists ck_voucher_status;

alter table voucher add constraint ck_voucher_status
    check (status in ('ACTIVE', 'CANCELLED', 'CORRECTION'));
