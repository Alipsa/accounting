alter table audit_log
    add column if not exists archived boolean not null default false;

create index if not exists idx_audit_log_archived
    on audit_log (company_id, archived);
