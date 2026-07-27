alter table report_archive add column if not exists status varchar(20) not null default 'ACTIVE';

update report_archive set status = 'ACTIVE' where status is null;

alter table report_archive add constraint ck_report_archive_status check (
    status in ('ACTIVE', 'PENDING_DELETE', 'DELETED')
);
