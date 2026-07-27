alter table company add column legal_form varchar(20);
alter table company add column simplified_annual_report boolean not null default false;
