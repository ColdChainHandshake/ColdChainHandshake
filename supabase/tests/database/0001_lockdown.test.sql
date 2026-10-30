begin;

create extension if not exists pgtap with schema extensions;

select plan(35);

-- Every app table exists, so nothing below can pass by checking an absent table.
select has_table('public', 'shipments', 'shipments exists');
select has_table('public', 'temperature_events', 'temperature_events exists');
select has_table('public', 'alerts', 'alerts exists');
select has_table('public', 'handovers', 'handovers exists');
select has_table('public', 'shipment_custody', 'shipment_custody exists');
select has_table('public', 'shipment_locations', 'shipment_locations exists');

-- Rules that must hold for every table in public, including ones added later.
select is_empty(
  $$ select c.relname from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind in ('r', 'p') and not c.relrowsecurity $$,
  'every public table has row level security on'
);

select is_empty(
  $$ select table_name, grantee, privilege_type from information_schema.role_table_grants
     where table_schema = 'public' and grantee in ('anon', 'PUBLIC') $$,
  'anon and PUBLIC hold no table privilege in public'
);

select is_empty(
  $$ select tablename, policyname from pg_policies
     where schemaname = 'public'
       and ('anon' = any(roles) or 'public' = any(roles)) $$,
  'no policy in public applies to anon or to everyone'
);

select is_empty(
  $$ select p.proname from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and (has_function_privilege('anon', p.oid, 'execute')
            or has_function_privilege('public', p.oid, 'execute')) $$,
  'anon and PUBLIC can execute no function in public'
);

-- A table created now inherits no access, which proves the default privileges changed.
create table public.pgtap_future_table (id int primary key);

select is_empty(
  $$ select privilege_type from information_schema.role_table_grants
     where table_name = 'pgtap_future_table' and grantee in ('anon', 'authenticated') $$,
  'a newly created table starts with no access for anon or authenticated'
);

-- service_role must keep working for the dashboard and server code.
select ok(
  has_table_privilege('service_role', 'public.shipments', 'select'),
  'service_role can still read shipments'
);

-- The same denial, observed through the role the API uses for anonymous requests.
set local role anon;

select throws_ok('select * from public.shipments', '42501', null, 'anon cannot read shipments');
select throws_ok('select * from public.temperature_events', '42501', null, 'anon cannot read temperature_events');
select throws_ok('select * from public.alerts', '42501', null, 'anon cannot read alerts');
select throws_ok('select * from public.handovers', '42501', null, 'anon cannot read handovers');
select throws_ok('select * from public.shipment_custody', '42501', null, 'anon cannot read shipment_custody');
select throws_ok('select * from public.shipment_locations', '42501', null, 'anon cannot read shipment_locations');

select throws_ok(
  $$ insert into public.shipments values ('s', 'q', 'l', 'o', 'd', 'w', 'CREATED') $$,
  '42501', null, 'anon cannot insert shipments'
);
select throws_ok(
  $$ insert into public.temperature_events values ('e', 's', 'l', 1, 5.0, 'p', 'c', 'SYNCED') $$,
  '42501', null, 'anon cannot insert temperature_events'
);
select throws_ok(
  $$ insert into public.alerts values ('a', 's', 'TEMPERATURE_BREACH', 'm', 1, 'WORKER', false) $$,
  '42501', null, 'anon cannot insert alerts'
);
select throws_ok(
  $$ insert into public.handovers values ('h', 's', true, true, true, true, 'PASS', 1) $$,
  '42501', null, 'anon cannot insert handovers'
);
select throws_ok(
  $$ insert into public.shipment_custody values ('s', 'd', 'TRANSFERRED', 1) $$,
  '42501', null, 'anon cannot insert shipment_custody'
);
select throws_ok(
  $$ insert into public.shipment_locations values ('g', 's', 1.0, 2.0, 3.0, 1) $$,
  '42501', null, 'anon cannot insert shipment_locations'
);

select throws_ok($$ update public.shipments set status = 'X' $$, '42501', null, 'anon cannot update shipments');
select throws_ok($$ update public.temperature_events set temperature = 5 $$, '42501', null, 'anon cannot update temperature_events');
select throws_ok($$ update public.shipment_custody set active_device_id = 'x' $$, '42501', null, 'anon cannot update shipment_custody');

select throws_ok('delete from public.shipments', '42501', null, 'anon cannot delete shipments');
select throws_ok('delete from public.temperature_events', '42501', null, 'anon cannot delete temperature_events');
select throws_ok('delete from public.shipment_custody', '42501', null, 'anon cannot delete shipment_custody');

-- authenticated has no access yet. Phase 3 grants it, per shipment, through policies.
reset role;
set local role authenticated;

select throws_ok('select * from public.shipments', '42501', null, 'authenticated cannot read shipments yet');
select throws_ok('select * from public.temperature_events', '42501', null, 'authenticated cannot read temperature_events yet');
select throws_ok('select * from public.shipment_custody', '42501', null, 'authenticated cannot read shipment_custody yet');
select throws_ok(
  $$ insert into public.shipment_custody values ('s', 'd', 'TRANSFERRED', 1) $$,
  '42501', null, 'authenticated cannot write shipment_custody yet'
);
select throws_ok('delete from public.temperature_events', '42501', null, 'authenticated cannot delete temperature_events yet');

reset role;

select * from finish();

rollback;
