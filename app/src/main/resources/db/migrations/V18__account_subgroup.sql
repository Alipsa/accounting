alter table account
    add column account_subgroup varchar(32);

-- Balance accounts (10-29)
update account set account_subgroup = 'INTANGIBLE_ASSETS'
 where cast(substring(account_number, 1, 2) as int) = 10;

update account set account_subgroup = 'BUILDINGS_AND_LAND'
 where cast(substring(account_number, 1, 2) as int) = 11;

update account set account_subgroup = 'MACHINERY'
 where cast(substring(account_number, 1, 2) as int) = 12;

update account set account_subgroup = 'FINANCIAL_FIXED_ASSETS'
 where cast(substring(account_number, 1, 2) as int) = 13;

update account set account_subgroup = 'INVENTORY'
 where cast(substring(account_number, 1, 2) as int) = 14;

update account set account_subgroup = 'RECEIVABLES'
 where cast(substring(account_number, 1, 2) as int) = 15;

update account set account_subgroup = 'OTHER_CURRENT_RECEIVABLES'
 where cast(substring(account_number, 1, 2) as int) = 16;

update account set account_subgroup = 'PREPAID_EXPENSES'
 where cast(substring(account_number, 1, 2) as int) = 17;

update account set account_subgroup = 'SHORT_TERM_INVESTMENTS'
 where cast(substring(account_number, 1, 2) as int) = 18;

update account set account_subgroup = 'CASH_AND_BANK'
 where cast(substring(account_number, 1, 2) as int) = 19;

update account set account_subgroup = 'EQUITY'
 where cast(substring(account_number, 1, 2) as int) = 20;

update account set account_subgroup = 'UNTAXED_RESERVES'
 where cast(substring(account_number, 1, 2) as int) = 21;

update account set account_subgroup = 'PROVISIONS'
 where cast(substring(account_number, 1, 2) as int) = 22;

update account set account_subgroup = 'LONG_TERM_LIABILITIES'
 where cast(substring(account_number, 1, 2) as int) = 23;

update account set account_subgroup = 'SHORT_TERM_LIABILITIES_CREDIT'
 where cast(substring(account_number, 1, 2) as int) = 24;

update account set account_subgroup = 'TAX_LIABILITIES'
 where cast(substring(account_number, 1, 2) as int) = 25;

update account set account_subgroup = 'VAT_AND_EXCISE'
 where cast(substring(account_number, 1, 2) as int) = 26;

update account set account_subgroup = 'PAYROLL_TAXES'
 where cast(substring(account_number, 1, 2) as int) = 27;

update account set account_subgroup = 'OTHER_CURRENT_LIABILITIES'
 where cast(substring(account_number, 1, 2) as int) = 28;

update account set account_subgroup = 'ACCRUED_EXPENSES'
 where cast(substring(account_number, 1, 2) as int) = 29;

-- Result accounts (30-89)
update account set account_subgroup = 'NET_REVENUE'
 where cast(substring(account_number, 1, 2) as int) between 30 and 34;

update account set account_subgroup = 'INVOICED_COSTS'
 where cast(substring(account_number, 1, 2) as int) = 35;

update account set account_subgroup = 'SECONDARY_INCOME'
 where cast(substring(account_number, 1, 2) as int) = 36;

update account set account_subgroup = 'REVENUE_ADJUSTMENTS'
 where cast(substring(account_number, 1, 2) as int) = 37;

update account set account_subgroup = 'CAPITALIZED_WORK'
 where cast(substring(account_number, 1, 2) as int) = 38;

update account set account_subgroup = 'OTHER_OPERATING_INCOME'
 where cast(substring(account_number, 1, 2) as int) = 39;

update account set account_subgroup = 'RAW_MATERIALS'
 where cast(substring(account_number, 1, 2) as int) between 40 and 49;

update account set account_subgroup = 'OTHER_EXTERNAL_COSTS'
 where cast(substring(account_number, 1, 2) as int) between 50 and 69;

update account set account_subgroup = 'PERSONNEL_COSTS'
 where cast(substring(account_number, 1, 2) as int) between 70 and 76;

update account set account_subgroup = 'DEPRECIATION'
 where cast(substring(account_number, 1, 2) as int) between 77 and 78;

update account set account_subgroup = 'OTHER_OPERATING_COSTS'
 where cast(substring(account_number, 1, 2) as int) = 79;

update account set account_subgroup = 'FINANCIAL_INCOME'
 where cast(substring(account_number, 1, 2) as int) between 80 and 83;

update account set account_subgroup = 'FINANCIAL_COSTS'
 where cast(substring(account_number, 1, 2) as int) = 84;

update account set account_subgroup = 'APPROPRIATIONS'
 where cast(substring(account_number, 1, 2) as int) = 88;

update account set account_subgroup = 'TAX_AND_RESULT'
 where cast(substring(account_number, 1, 2) as int) = 89;
