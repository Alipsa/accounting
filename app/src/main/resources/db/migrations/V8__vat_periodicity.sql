alter table company_settings
    add column if not exists vat_periodicity varchar(20) not null default 'MONTHLY';

alter table vat_period
    alter column period_name varchar(40);

update company_settings
   set vat_periodicity = coalesce(vat_periodicity, 'MONTHLY')
 where id = 1;
