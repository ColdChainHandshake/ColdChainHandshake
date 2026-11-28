-- Rules that must keep holding as the schema grows. They look at every object in public rather than a
-- list of names, and at each kind of access, because the checks in 0001_lockdown.test.sql only see anon
-- and PUBLIC through information_schema, which leaves out materialized views.
--
-- Each grant to authenticated is on an allow-list here, so adding one is a deliberate change to this
-- file. Anon has none.

begin;

create extension if not exists pgtap with schema extensions;

select plan(15);

-- Privileges on every table-like object, including views and materialized views.
select is_empty(
  $$ select c.relname, r.role
     from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     cross join (values ('anon'), ('public')) as r(role)
     where n.nspname = 'public'
       and c.relkind in ('r', 'p', 'v', 'm', 'f')
       and has_table_privilege(r.role, c.oid, 'select, insert, update, delete, truncate, references, trigger') $$,
  'anon and PUBLIC hold no table privilege on anything in public'
);

select is_empty(
  $$ select c.relname, r.role
     from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     cross join (values ('anon'), ('public')) as r(role)
     where n.nspname = 'public'
       and c.relkind in ('r', 'p', 'v', 'm', 'f')
       and has_any_column_privilege(r.role, c.oid, 'select, insert, update, references') $$,
  'anon and PUBLIC hold no column grant on anything in public'
);

select is_empty(
  $$ select c.relname
     from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public'
       and c.relkind in ('r', 'p', 'v', 'm', 'f')
       and (has_table_privilege('authenticated', c.oid, 'insert, update, delete, truncate, references, trigger')
            or has_any_column_privilege('authenticated', c.oid, 'insert, update, references')) $$,
  'authenticated can write nothing directly: every write goes through a function'
);

select bag_eq(
  $$ select c.relname::text
     from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public'
       and c.relkind in ('r', 'p', 'v', 'm', 'f')
       and has_any_column_privilege('authenticated', c.oid, 'select') $$,
  $$ values ('profiles'), ('devices'), ('product_profiles'), ('shipments'), ('shipment_members'),
            ('chain_heads'), ('readings'), ('reading_batches'), ('custody_events'),
            ('handover_confirmations'), ('alerts'), ('gps_points') $$,
  'authenticated can read exactly the allow-listed tables'
);

select is_empty(
  $$ select c.relname, r.role
     from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     cross join (values ('anon'), ('authenticated'), ('public')) as r(role)
     where n.nspname = 'public'
       and c.relkind = 'S'
       and has_sequence_privilege(r.role, c.oid, 'usage, select, update') $$,
  'no client role can use a sequence in public'
);

select is_empty(
  $$ select p.oid::regprocedure::text, r.role
     from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     cross join (values ('anon'), ('public')) as r(role)
     where n.nspname in ('public', 'private')
       and has_function_privilege(r.role, p.oid, 'execute') $$,
  'anon and PUBLIC can execute no function in public or private'
);

select bag_eq(
  $$ select p.oid::regprocedure::text
     from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname in ('public', 'private')
       and has_function_privilege('authenticated', p.oid, 'execute') $$,
  $$ values
       ('complete_profile(text,text)'),
       ('register_device(uuid,text)'),
       ('create_shipment(text,text,text,text,text,text,bigint)'),
       ('add_shipment_member(text,text,text)'),
       ('dispatch_shipment(text)'),
       ('start_custody(text,uuid)'),
       ('request_replacement(text)'),
       ('append_readings(text,jsonb,text,uuid,text)'),
       ('confirm_handover(text,integer,uuid,text,uuid,text,text,text,text)'),
       ('record_alert(text,text,bigint,text)'),
       ('escalate_alert(text,text)'),
       ('acknowledge_alert(text)'),
       ('append_gps_points(text,uuid,jsonb)'),
       ('private.is_member(text)'),
       ('private.shares_shipment(uuid)') $$,
  'authenticated can execute exactly the allow-listed functions'
);

select is_empty(
  $$ select r.role
     from (values ('anon'), ('authenticated'), ('public')) as r(role)
     where has_schema_privilege(r.role, 'public', 'create') $$,
  'no client role can create objects in public'
);

select is_empty(
  $$ select tablename from pg_publication_tables
     where pubname = 'supabase_realtime' and schemaname = 'public' $$,
  'no public table is published to realtime'
);

select is_empty(
  $$ select r.rolname, m.rolname
     from pg_auth_members a
     join pg_roles r on r.oid = a.member
     join pg_roles m on m.oid = a.roleid
     where r.rolname in ('anon', 'authenticated') $$,
  'anon and authenticated inherit no other role'
);

-- Default privileges for objects that migrations create from now on.
select is_empty(
  $$ select d.defaclobjtype, e.grantee::regrole
     from pg_default_acl d
     join pg_roles owner_role on owner_role.oid = d.defaclrole
     join pg_namespace n on n.oid = d.defaclnamespace
     cross join lateral aclexplode(d.defaclacl) e
     where owner_role.rolname = 'postgres'
       and n.nspname = 'public'
       and e.grantee in ('anon'::regrole, 'authenticated'::regrole) $$,
  'default privileges give client roles nothing in public'
);

-- New objects, as created by a migration: the role that runs migrations is postgres.
create function public.pgtap_new_function() returns int language sql as 'select 1';
create sequence public.pgtap_new_sequence;
create materialized view public.pgtap_new_matview as select 1 as id;

select ok(
  not has_function_privilege('anon', 'public.pgtap_new_function()', 'execute'),
  'anon cannot execute a newly created function'
);

select ok(
  not has_function_privilege('authenticated', 'public.pgtap_new_function()', 'execute'),
  'authenticated cannot execute a newly created function'
);

select ok(
  not has_sequence_privilege('anon', 'public.pgtap_new_sequence', 'usage'),
  'anon cannot use a newly created sequence'
);

select ok(
  not has_table_privilege('anon', 'public.pgtap_new_matview', 'select'),
  'anon cannot read a newly created materialized view'
);

select * from finish();

rollback;
