alter table opening_balance
    add column if not exists origin_type varchar(24) default 'MANUAL';

alter table opening_balance
    add column if not exists source_fiscal_year_id bigint;

update opening_balance
   set origin_type = 'MANUAL'
 where origin_type is null;

update opening_balance ob
   set origin_type = 'YEAR_END_CLOSE',
       source_fiscal_year_id = (
           select ce.fiscal_year_id
             from closing_entry ce
            where ce.entry_type = 'OPENING_BALANCE'
              and ce.next_fiscal_year_id = ob.fiscal_year_id
              and ce.account_id = ob.account_id
            fetch first 1 row only
       )
 where exists (
           select 1
             from closing_entry ce
            where ce.entry_type = 'OPENING_BALANCE'
              and ce.next_fiscal_year_id = ob.fiscal_year_id
              and ce.account_id = ob.account_id
       );

alter table opening_balance
    alter column origin_type set not null;

alter table opening_balance
    add constraint ck_opening_balance_origin_type
        check (origin_type in ('MANUAL', 'TRANSFERRED', 'YEAR_END_CLOSE'));

alter table opening_balance
    add constraint fk_opening_balance_source_fiscal_year
        foreign key (source_fiscal_year_id) references fiscal_year(id) on delete restrict;
