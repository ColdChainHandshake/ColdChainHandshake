-- Rules that must keep holding as the schema grows. They look at every object in public rather than a
-- list of names, and at each kind of access, because the checks in 0001_lockdown.test.sql only see anon
-- and PUBLIC through information_schema, which leaves out materialized views.
--
-- When Phase 3 grants authenticated its access, each grant goes on the allow-list in the test it
-- affects. Anon stays empty.

begin;

create extension if not exists pgtap with schema extensions;

select plan(12);

-- Privileges on every table-like object, including views and materialized views.
select is_empty(
  $$ select c.relname, r.role
     from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     cross join (values ('anon'), ('authenticated'), ('public')) as r(role)
     where n.nspname = 'public'
       and c.relkind in ('r', 'p', 'v', 'm', 'f')
       and has_table_privilege(r.role, c.oid, 'select, insert, update, delete, truncate, references, trigger') $$,
  'no client role holds any table privilege on anything in public'
);

select is_empty(
  $$ select c.relname, r.role
     from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     cross join (values ('anon'), ('authenticated'), ('public')) as r(role)
     where n.nspname = 'public'
       and c.relkind in ('r', 'p', 'v', 'm', 'f')
       and has_any_column_privilege(r.role, c.oid, 'select, insert, update, references') $$,
  'no client role holds a column grant on anything in public'
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
  $$ select p.proname, r.role
     from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     cross join (values ('anon'), ('authenticated'), ('public')) as r(role)
     where n.nspname = 'public'
       and has_function_privilege(r.role, p.oid, 'execute') $$,
  'no client role can execute a function in public'
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
