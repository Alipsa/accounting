alter table report_archive
    drop constraint ck_report_archive_format;

alter table report_archive
    add constraint ck_report_archive_format
    check (report_format in ('PDF', 'CSV', 'XLSX'));
