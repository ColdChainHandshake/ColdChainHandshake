-- Reproduces the schema the hackathon app shipped with, including its wide-open access.
-- shipment_custody and shipment_locations come from the SQL files at the repository root. The other
-- four tables were only ever created by hand, so their columns are taken from RemoteDtos.kt.
-- It exists so that 0001_lockdown has real grants and policies to remove on a fresh database.
-- `if not exists` makes it harmless on a project that already has these tables.

create table if not exists public.shipments (
  id text primary key,
  qr_code text not null,
  logger_id text not null,
  origin text not null,
  destination text not null,
  worker_id text not null,
  status text not null
);

create table if not exists public.temperature_events (
  id text primary key,
  shipment_id text not null,
  logger_id text not null,
  "timestamp" bigint not null,
  temperature double precision not null,
  previous_hash text not null,
  current_hash text not null,
  sync_status text not null
);

create table if not exists public.alerts (
  id text primary key,
  shipment_id text not null,
  type text not null,
  message text not null,
  "timestamp" bigint not null,
  escalation_level text not null,
  acknowledged boolean not null
);

create table if not exists public.handovers (
  id text primary key,
  shipment_id text not null,
  worker_signed boolean not null,
  pharmacist_signed boolean not null,
  integrity_verified boolean not null,
  temperature_passed boolean not null,
  verdict text not null,
  "timestamp" bigint not null
);

create table if not exists public.shipment_custody (
  shipment_id text primary key,
  active_device_id text not null,
  custody_state text not null,
  updated_at bigint not null
);

create table if not exists public.shipment_locations (
  id text primary key,
  shipment_id text not null,
  latitude double precision not null,
  longitude double precision not null,
  accuracy real not null,
  "timestamp" bigint not null
);

do $$
declare
  t text;
begin
  foreach t in array array[
    'shipments',
    'temperature_events',
    'alerts',
    'handovers',
    'shipment_custody',
    'shipment_locations'
  ] loop
    execute format('grant all on table public.%I to anon, authenticated, service_role', t);
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists legacy_open_access on public.%I', t);
    execute format(
      'create policy legacy_open_access on public.%I for all to anon, authenticated, service_role using (true) with check (true)',
      t
    );
  end loop;
end $$;
