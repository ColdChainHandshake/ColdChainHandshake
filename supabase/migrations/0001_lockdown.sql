-- Deny by default (finding 1). After this, anon and authenticated can reach no app table, and anything
-- created later in public starts with no access for them. Access returns only through explicit grants
-- and policies in later migrations. service_role is unchanged.

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
    if to_regclass('public.' || quote_ident(t)) is not null then
      execute format('alter table public.%I enable row level security', t);
      for r in
        select policyname from pg_policies
        where schemaname = 'public' and tablename = t
      loop
        execute format('drop policy %I on public.%I', r.policyname, t);
      end loop;
      execute format('revoke all on table public.%I from anon, authenticated', t);
    end if;
  end loop;
end $$;

-- Supabase auto-grants new public objects to anon and authenticated unless told otherwise.
-- https://supabase.com/docs/guides/api/securing-your-api
alter default privileges for role postgres in schema public
  revoke all on tables from anon, authenticated;

alter default privileges for role postgres in schema public
  revoke all on sequences from anon, authenticated;

alter default privileges for role postgres in schema public
  revoke execute on functions from anon, authenticated;

-- PUBLIC's EXECUTE on new functions is a global default. A rule scoped to one schema can't remove it,
-- so this one has no schema.
alter default privileges for role postgres
  revoke execute on functions from public;
