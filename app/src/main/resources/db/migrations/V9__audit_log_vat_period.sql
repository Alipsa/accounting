alter table audit_log
    add column if not exists vat_period_id bigint;

alter table audit_log
    add constraint if not exists fk_audit_log_vat_period
        foreign key (vat_period_id) references vat_period(id) on delete restrict;
