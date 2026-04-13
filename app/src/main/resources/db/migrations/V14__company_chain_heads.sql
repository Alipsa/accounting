-- voucher_chain_head: replace singleton id with company_id
alter table voucher_chain_head
    drop constraint if exists ck_voucher_chain_head_singleton;

alter table voucher_chain_head
    add column if not exists company_id bigint;

insert into voucher_chain_head (company_id, last_content_hash, updated_at)
select 1, null, current_timestamp
where not exists (select 1 from voucher_chain_head);

update voucher_chain_head
   set company_id = 1
 where company_id is null;

alter table voucher_chain_head
    alter column company_id set not null;

alter table voucher_chain_head
    drop primary key;

alter table voucher_chain_head
    drop column if exists id;

alter table voucher_chain_head
    add constraint pk_voucher_chain_head primary key (company_id);

alter table voucher_chain_head
    add constraint fk_voucher_chain_head_company
        foreign key (company_id) references company(id) on delete restrict;

-- audit_log_chain_head: replace singleton id with company_id
alter table audit_log_chain_head
    drop constraint if exists ck_audit_log_chain_head_singleton;

alter table audit_log_chain_head
    add column if not exists company_id bigint;

insert into audit_log_chain_head (company_id, last_entry_hash, updated_at)
select 1, null, current_timestamp
where not exists (select 1 from audit_log_chain_head);

update audit_log_chain_head
   set company_id = 1
 where company_id is null;

alter table audit_log_chain_head
    alter column company_id set not null;

alter table audit_log_chain_head
    drop primary key;

alter table audit_log_chain_head
    drop column if exists id;

alter table audit_log_chain_head
    add constraint pk_audit_log_chain_head primary key (company_id);

alter table audit_log_chain_head
    add constraint fk_audit_log_chain_head_company
        foreign key (company_id) references company(id) on delete restrict;
