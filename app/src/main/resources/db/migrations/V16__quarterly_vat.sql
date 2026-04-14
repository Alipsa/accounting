alter table company drop constraint if exists ck_company_vat_periodicity;

alter table company add constraint ck_company_vat_periodicity
    check (vat_periodicity in ('MONTHLY', 'QUARTERLY', 'ANNUAL'));
