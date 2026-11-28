-- Gate 5 review S8, location privacy. While a courier records, their phone sends a precise fix every minute. Before
-- this, every member of the shipment could read every point for ever, and append_gps_points took any number of
-- points with any values.
--
-- - append_gps_points refuses more than 500 points in one call, and any point whose latitude, longitude, accuracy or
--   time a real fix can't have (CC011). The app sends at most 200 per call, from Android's own fixes.
-- - A point can be read by the shipment's sender, and by the person whose phone recorded it. Nobody else: not the
--   receiver, and not another courier on the same shipment.
-- - Retention: 30 days after the trip ends (ACCEPTED or REPLACEMENT_REQUESTED, at its last custody move) nobody can
--   read the points any more. pg_cron is not enabled on the dev project, so hiding them is a read rule, which needs no
--   project setting; private.purge_expired_location() deletes them and can be scheduled by the owner.
--
-- The function keeps its name and arguments, so the app pinned for Gate 3 (05b342e) keeps working.

create function private.location_retention() returns interval
language sql immutable set search_path = '' as $$ select interval '30 days' $$;

-- When the trip ended: the time of its last custody move, once the shipment is finished. Null while it goes on.
create function private.trip_ended_at(p_shipment_id text) returns timestamptz
language sql stable set search_path = '' as $$
  select max(e.server_time)
  from public.custody_events e
  join public.shipments s on s.id = e.shipment_id
  where e.shipment_id = p_shipment_id
    and s.status in ('ACCEPTED', 'REPLACEMENT_REQUESTED')
$$;

create function private.can_see_location(p_shipment_id text, p_device_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select (
      exists (
        select 1 from public.shipments s
        where s.id = p_shipment_id and s.sender_id = (select auth.uid())
      )
      or exists (
        select 1 from public.devices d
        where d.device_id = p_device_id and d.user_id = (select auth.uid())
      )
    )
    and coalesce(private.trip_ended_at(p_shipment_id) > now() - private.location_retention(), true)
$$;

revoke execute on function private.can_see_location(text, uuid) from public;
grant execute on function private.can_see_location(text, uuid) to authenticated;

drop policy gps_points_read on public.gps_points;
create policy gps_points_read on public.gps_points for select to authenticated
  using (private.can_see_location(shipment_id, device_id));

create or replace function public.append_gps_points(p_shipment_id text, p_device_id uuid, p_points jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_from_ms numeric;
  v_to_ms numeric := (extract(epoch from clock_timestamp()) * 1000)::bigint + private.clock_tolerance_ms();
begin
  perform private.require_own_device(p_device_id);

  if private.current_custodian(p_shipment_id) is distinct from (select auth.uid()) then
    raise exception 'Only the current custodian can report location.' using errcode = 'CC002';
  end if;

  if jsonb_typeof(p_points) is distinct from 'array' or jsonb_array_length(p_points) > 500 then
    raise exception 'Location is sent as a list of at most 500 points.' using errcode = 'CC011';
  end if;

  select extract(epoch from s.created_at) * 1000 - private.clock_tolerance_ms() into v_from_ms
  from public.shipments s where s.id = p_shipment_id;

  -- Types first (CASE keeps the order; OR doesn't promise one), then the values as numerics, so a huge or fractional
  -- value is refused rather than overflowing a cast. JSON has no NaN or infinity: a JSON number is finite.
  if exists (
    select 1 from jsonb_array_elements(p_points) p
    where case
      when jsonb_typeof(p) is distinct from 'object'
        or jsonb_typeof(p -> 'id') is distinct from 'string'
        or jsonb_typeof(p -> 'lat') is distinct from 'number'
        or jsonb_typeof(p -> 'lon') is distinct from 'number'
        or jsonb_typeof(p -> 'accuracy_m') is distinct from 'number'
        or jsonb_typeof(p -> 'device_ts') is distinct from 'number' then true
      else not (
        (p ->> 'lat')::numeric between -90 and 90
        and (p ->> 'lon')::numeric between -180 and 180
        and (p ->> 'accuracy_m')::numeric between 0 and 100000
        and (p ->> 'device_ts')::numeric = trunc((p ->> 'device_ts')::numeric)
        and (p ->> 'device_ts')::numeric between v_from_ms and v_to_ms
      )
    end
  ) then
    raise exception 'A location point is out of range.' using errcode = 'CC011';
  end if;

  insert into public.gps_points (id, shipment_id, device_id, lat, lon, accuracy_m, device_ts)
  select (p ->> 'id')::uuid, p_shipment_id, p_device_id,
         (p ->> 'lat')::double precision, (p ->> 'lon')::double precision,
         (p ->> 'accuracy_m')::real, (p ->> 'device_ts')::bigint
  from jsonb_array_elements(p_points) p
  on conflict (id) do nothing;
end
$$;

-- Deletes the points nobody may read any more. Not granted to any client role: the owner runs or schedules it.
create function private.purge_expired_location() returns bigint
language sql volatile set search_path = '' as $$
  with gone as (
    delete from public.gps_points g
    where private.trip_ended_at(g.shipment_id) <= now() - private.location_retention()
    returning 1
  )
  select count(*) from gone
$$;

revoke execute on function private.location_retention() from public;
revoke execute on function private.trip_ended_at(text) from public;
revoke execute on function private.purge_expired_location() from public;
