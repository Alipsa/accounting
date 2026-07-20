alter table company
    add column if not exists accounting_method varchar(20) not null default 'CASH';

alter table company
    add constraint ck_company_accounting_method
        check (accounting_method in ('CASH', 'INVOICE'));
