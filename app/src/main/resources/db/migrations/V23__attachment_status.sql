alter table attachment
    add column if not exists status varchar(20) not null default 'ACTIVE'
        constraint ck_attachment_status
            check (status in ('PENDING', 'ACTIVE', 'PENDING_DELETE', 'DELETED', 'FAILED'));

create index if not exists idx_attachment_status on attachment(status);
