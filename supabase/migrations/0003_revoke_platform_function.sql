-- Hosted Supabase projects come with public.rls_auto_enable(), the security definer function behind the
-- `ensure_rls` event trigger that turns on RLS for new tables in public. It is created before 0001_lockdown
-- changes the default privileges, so PUBLIC, anon and authenticated can execute it, which breaks the rule
-- that no client role reaches anything in public it wasn't granted on purpose (0002_deny_by_default.test.sql).
--
-- It returns event_trigger, so Postgres refuses a direct call anyway; this closes the advisor warning and
-- keeps that rule true on hosted projects. The event trigger keeps working: the function's owner keeps
-- EXECUTE, and an event trigger doesn't check the caller's EXECUTE privilege when it fires.
--
-- The local stack doesn't create the function, so this does nothing there.

do $$
begin
  if to_regprocedure('public.rls_auto_enable()') is not null then
    revoke execute on function public.rls_auto_enable() from public, anon, authenticated;
  end if;
end $$;
