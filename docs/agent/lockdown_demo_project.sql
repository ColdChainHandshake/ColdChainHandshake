-- Cold Chain Handshake: deny-by-default lockdown for the existing demo Supabase project.
-- Run this yourself in the Supabase dashboard: SQL Editor > New query > paste > Run.
-- Effect: the anon and authenticated roles lose all access to the app's tables, every existing
-- policy on them is dropped, and RLS is switched on. The old APK will stop syncing; that is intended.
-- Data is not deleted. The service role (dashboard, server code) keeps full access.

do $$
declare
  t text;
  r record;
begin
  foreach t in array array[
    'shipments',
    'temperature_events',
    'alerts',
    'handovers',
    'shipment_custody',
    'shipment_locations'
  ] loop
    if to_regclass('public.' || t) is not null then
      execute format('alter table public.%I enable row level security', t);
      for r in
        select policyname from pg_policies
        where schemaname = 'public' and tablename = t
      loop
        execute format('drop policy %I on public.%I', r.policyname, t);
      end loop;
      execute format('revoke all on table public.%I from anon, authenticated', t);
      raise notice 'locked down public.%', t;
    else
      raise notice 'skipped public.% (table not found)', t;
    end if;
  end loop;
end $$;

-- Check: every row should show rls_enabled = true and policies = 0.
select c.relname as table_name,
       c.relrowsecurity as rls_enabled,
       (select count(*) from pg_policies p
         where p.schemaname = 'public' and p.tablename = c.relname) as policies
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relname in ('shipments','temperature_events','alerts','handovers','shipment_custody','shipment_locations');
