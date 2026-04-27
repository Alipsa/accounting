alter table company
    add column if not exists archived boolean not null default false;

create index if not exists idx_company_archived
    on company (archived);
