create table if not exists audit_log_chain_head (
    id integer primary key,
    last_entry_hash varchar(64),
    updated_at timestamp not null default current_timestamp,
    constraint ck_audit_log_chain_head_singleton check (id = 1)
);

merge into audit_log_chain_head (id, last_entry_hash, updated_at)
key (id)
values (1, null, current_timestamp);

update audit_log_chain_head
   set last_entry_hash = (
       select entry_hash
         from audit_log
        order by id desc
        limit 1
   ),
       updated_at = current_timestamp
 where id = 1
   and exists (
       select 1
         from audit_log
   );
