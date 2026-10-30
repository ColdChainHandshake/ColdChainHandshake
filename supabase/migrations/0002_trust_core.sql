-- The trust model. The courier is the party with a reason to hide a breach, so the server keeps the
-- record: receive times, the chain head and count, who holds custody and who confirmed what.
--
-- Clients only read. Every write goes through a function below, which checks who is calling and what
-- they may do. Encoding: docs/protocol/CANONICAL_ENCODING.md.
--
-- Error codes: CC001 stale head, CC002 not allowed, CC003 sequence, CC004 hash or link,
-- CC005 device, CC006 bad input, CC007 wrong state, CC008 already confirmed, CC009 clock,
-- CC010 immutable field.

-- The hackathon tables stay, locked and unused, so nothing on an existing project is lost.
alter table if exists public.shipments rename to legacy_shipments;
alter table if exists public.temperature_events rename to legacy_temperature_events;
alter table if exists public.alerts rename to legacy_alerts;
alter table if exists public.handovers rename to legacy_handovers;
alter table if exists public.shipment_custody rename to legacy_shipment_custody;
alter table if exists public.shipment_locations rename to legacy_shipment_locations;

-- Tables -------------------------------------------------------------------------------------------

-- The role here only decides who may create a shipment. What a person may do with a shipment comes
-- from shipment_members, which only that shipment's sender can change.
create table public.profiles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 80),
  role text not null check (role in ('sender', 'courier', 'receiver'))
);

create table public.devices (
  device_id uuid primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  public_key text not null,
  registered_at timestamptz not null default now()
);

create index devices_user_id_idx on public.devices (user_id);

create table public.product_profiles (
  id text primary key,
  name text not null,
  min_c numeric(6, 2) not null,
  max_c numeric(6, 2) not null,
  max_excursion_minutes_heat integer not null check (max_excursion_minutes_heat >= 0),
  max_excursion_minutes_freeze integer not null check (max_excursion_minutes_freeze >= 0),
  logging_interval_s integer not null check (logging_interval_s > 0),
  gap_tolerance_factor integer not null check (gap_tolerance_factor > 0),
  check (min_c < max_c)
);

insert into public.product_profiles values
  ('vaccine-2-8-freeze-sensitive', '2–8 °C vaccine (freeze-sensitive)', 2, 8, 5, 0, 60, 3),
  ('test-2-8-freeze-tolerant', '2–8 °C test profile (freeze-tolerant)', 2, 8, 5, 60, 60, 3);

create table public.shipments (
  id text primary key check (char_length(id) between 1 and 64),
  sender_id uuid not null references auth.users (id),
  product_profile_id text not null references public.product_profiles (id),
  logger_id text not null check (char_length(logger_id) between 1 and 64),
  origin text not null,
  destination text not null,
  status text not null default 'CREATED' check (status in (
    'CREATED', 'DISPATCHED', 'IN_TRANSIT', 'ARRIVED', 'INSPECTED',
    'ACCEPTED', 'QUARANTINED', 'REPLACEMENT_REQUESTED'
  )),
  created_at timestamptz not null default date_trunc('milliseconds', now())
);

create index shipments_sender_id_idx on public.shipments (sender_id);
create index shipments_product_profile_id_idx on public.shipments (product_profile_id);

create table public.shipment_members (
  shipment_id text not null references public.shipments (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null check (role in ('sender', 'courier', 'receiver')),
  primary key (shipment_id, user_id)
);

create index shipment_members_user_id_idx on public.shipment_members (user_id);

create table public.chain_heads (
  shipment_id text primary key references public.shipments (id) on delete cascade,
  head_hash text not null,
  count bigint not null default 0,
  last_seq bigint not null default 0,
  updated_at timestamptz not null default now()
);

create table public.readings (
  shipment_id text not null references public.shipments (id),
  seq bigint not null check (seq >= 1),
  logger_id text not null,
  logger_ts bigint not null,
  temp_centi_c integer not null,
  prev_hash text not null,
  hash text not null,
  chain_version smallint not null default 2,
  uploaded_by_device uuid not null references public.devices (device_id),
  received_at timestamptz not null default now(),
  clock_ahead boolean not null default false,
  primary key (shipment_id, seq)
);

create index readings_uploaded_by_device_idx on public.readings (uploaded_by_device);

-- One row per upload. The signature proves which phone uploaded, not that the readings are true.
create table public.reading_batches (
  shipment_id text not null references public.shipments (id),
  first_seq bigint not null,
  last_seq bigint not null,
  head_hash text not null,
  device_id uuid not null references public.devices (device_id),
  signature text,
  received_at timestamptz not null default now(),
  primary key (shipment_id, last_seq)
);

create index reading_batches_device_id_idx on public.reading_batches (device_id);

create table public.custody_events (
  id uuid primary key default gen_random_uuid(),
  shipment_id text not null references public.shipments (id),
  leg integer not null check (leg >= 1),
  from_user uuid references auth.users (id),
  to_user uuid not null references auth.users (id),
  kind text not null check (kind in ('START', 'TRANSFER')),
  server_time timestamptz not null default now(),
  device_id uuid references public.devices (device_id),
  unique (shipment_id, leg)
);

create index custody_events_device_id_idx on public.custody_events (device_id);
create index custody_events_from_user_idx on public.custody_events (from_user);
create index custody_events_to_user_idx on public.custody_events (to_user);

create table public.handover_confirmations (
  shipment_id text not null references public.shipments (id),
  leg integer not null,
  user_id uuid not null references auth.users (id),
  role text not null check (role in ('sender', 'courier', 'receiver')),
  device_id uuid not null references public.devices (device_id),
  head_hash text not null,
  to_user uuid references auth.users (id),
  verdict text check (verdict in ('PASS', 'UNKNOWN', 'FAIL')),
  reason text,
  vvm_stage text not null default 'NOT_RECORDED'
    check (vvm_stage in ('NOT_RECORDED', 'STAGE_1', 'STAGE_2', 'STAGE_3', 'STAGE_4')),
  shake_test text not null default 'NOT_DONE' check (shake_test in ('NOT_DONE', 'PASSED', 'FAILED')),
  server_time timestamptz not null default now(),
  primary key (shipment_id, leg, user_id, head_hash)
);

create index handover_confirmations_user_id_idx on public.handover_confirmations (user_id);
create index handover_confirmations_device_id_idx on public.handover_confirmations (device_id);
create index handover_confirmations_to_user_idx on public.handover_confirmations (to_user);

create table public.alerts (
  id text primary key,
  shipment_id text not null references public.shipments (id),
  type text not null check (type in (
    'TEMPERATURE_BREACH', 'FREEZE_BREACH', 'DATA_GAP', 'LOGGER_DISCONNECT', 'OPERATIONAL_WARNING'
  )),
  excursion_start bigint,
  level text not null default 'WORKER' check (level in ('WORKER', 'SUPERVISOR', 'PHARMACIST')),
  message text not null,
  acknowledged_by uuid references auth.users (id),
  acknowledged_at timestamptz,
  created_at timestamptz not null default now(),
  reported_by uuid references auth.users (id)
);

create index alerts_shipment_id_idx on public.alerts (shipment_id);
create index alerts_acknowledged_by_idx on public.alerts (acknowledged_by);
create index alerts_reported_by_idx on public.alerts (reported_by);

create table public.gps_points (
  id uuid primary key,
  shipment_id text not null references public.shipments (id),
  device_id uuid not null references public.devices (device_id),
  lat double precision not null,
  lon double precision not null,
  accuracy_m real not null,
  device_ts bigint not null,
  received_at timestamptz not null default now()
);

create index gps_points_shipment_id_device_ts_idx on public.gps_points (shipment_id, device_ts desc);
create index gps_points_device_id_idx on public.gps_points (device_id);

-- Internal helpers ---------------------------------------------------------------------------------

create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

create function private.lp(p_value text) returns bytea
language sql immutable set search_path = '' as $$
  select int4send(octet_length(convert_to(p_value, 'UTF8'))) || convert_to(p_value, 'UTF8')
$$;

create function private.chain_genesis(
  p_shipment_id text,
  p_sender_id uuid,
  p_product_profile_id text,
  p_logger_id text,
  p_created_at_ms bigint
) returns text
language sql immutable set search_path = '' as $$
  select encode(sha256(
    '\x02'::bytea
    || private.lp(p_shipment_id)
    || private.lp(p_sender_id::text)
    || private.lp(p_product_profile_id)
    || private.lp(p_logger_id)
    || int8send(p_created_at_ms)
  ), 'hex')
$$;

create function private.reading_hash(
  p_shipment_id text,
  p_logger_id text,
  p_seq bigint,
  p_logger_ts bigint,
  p_temp_centi_c integer,
  p_prev_hash text
) returns text
language sql immutable set search_path = '' as $$
  select encode(sha256(
    '\x02'::bytea
    || private.lp(p_shipment_id)
    || private.lp(p_logger_id)
    || int8send(p_seq)
    || int8send(p_logger_ts)
    || int4send(p_temp_centi_c)
    || decode(p_prev_hash, 'hex')
  ), 'hex')
$$;

create function private.clock_tolerance_ms() returns bigint
language sql immutable set search_path = '' as 'select 300000::bigint';

create function private.is_member(p_shipment_id text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.shipment_members m
    where m.shipment_id = p_shipment_id and m.user_id = (select auth.uid())
  )
$$;

create function private.shares_shipment(p_user_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.shipment_members mine
    join public.shipment_members theirs on theirs.shipment_id = mine.shipment_id
    where mine.user_id = (select auth.uid()) and theirs.user_id = p_user_id
  )
$$;

grant execute on function private.is_member(text) to authenticated;
grant execute on function private.shares_shipment(uuid) to authenticated;

create function private.current_custodian(p_shipment_id text) returns uuid
language sql stable set search_path = '' as $$
  select e.to_user from public.custody_events e
  where e.shipment_id = p_shipment_id
  order by e.leg desc
  limit 1
$$;

-- The courier is the person a breach reflects on, so they can't clear alerts or end a shipment.
create function private.can_oversee(p_shipment_id text) returns boolean
language sql stable set search_path = '' as $$
  select exists (
    select 1 from public.shipment_members m
    where m.shipment_id = p_shipment_id
      and m.user_id = (select auth.uid())
      and m.role in ('sender', 'receiver')
  )
$$;

create function private.require_own_device(p_device_id uuid) returns void
language plpgsql stable set search_path = '' as $$
begin
  if not exists (
    select 1 from public.devices d
    where d.device_id = p_device_id and d.user_id = (select auth.uid())
  ) then
    raise exception 'This device is not registered to you.' using errcode = 'CC005';
  end if;
end
$$;

create function private.transfer_custody(
  p_shipment_id text,
  p_leg integer,
  p_to_user uuid,
  p_device_id uuid
) returns void
language sql set search_path = '' as $$
  insert into public.custody_events (shipment_id, leg, from_user, to_user, kind, device_id)
  values (p_shipment_id, p_leg + 1, private.current_custodian(p_shipment_id), p_to_user, 'TRANSFER', p_device_id)
$$;

-- The genesis hash commits to these columns, so changing one would give the chain a new start.
create function private.protect_shipment_manifest() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (new.id, new.sender_id, new.product_profile_id, new.logger_id, new.created_at)
     is distinct from
     (old.id, old.sender_id, old.product_profile_id, old.logger_id, old.created_at) then
    raise exception 'A shipment''s manifest cannot change.' using errcode = 'CC010';
  end if;
  return new;
end
$$;

create trigger shipments_manifest_is_immutable
  before update on public.shipments
  for each row execute function private.protect_shipment_manifest();

-- Client functions: accounts -----------------------------------------------------------------------

create function public.complete_profile(p_display_name text, p_role text) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_existing text;
begin
  select p.role into v_existing from public.profiles p where p.user_id = (select auth.uid());

  if v_existing is not null and v_existing <> p_role then
    raise exception 'A role cannot be changed once chosen.' using errcode = 'CC006';
  end if;

  insert into public.profiles (user_id, display_name, role)
  values ((select auth.uid()), p_display_name, p_role)
  on conflict (user_id) do update set display_name = excluded.display_name;
end
$$;

-- A device keeps its key for life, so a stolen session can't swap the key behind past signatures.
create function public.register_device(p_device_id uuid, p_public_key text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.devices (device_id, user_id, public_key)
  values (p_device_id, (select auth.uid()), p_public_key)
  on conflict (device_id) do nothing;

  if not exists (
    select 1 from public.devices d
    where d.device_id = p_device_id
      and d.user_id = (select auth.uid())
      and d.public_key = p_public_key
  ) then
    raise exception 'This device is already registered with a different owner or key.' using errcode = 'CC005';
  end if;
end
$$;

-- Client functions: shipments ----------------------------------------------------------------------

create function public.create_shipment(
  p_id text,
  p_product_profile_id text,
  p_logger_id text,
  p_origin text,
  p_destination text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_shipment public.shipments;
  v_created_ms bigint;
  v_genesis text;
begin
  if not exists (select 1 from public.profiles p where p.user_id = v_uid and p.role = 'sender') then
    raise exception 'Only a sender can create a shipment.' using errcode = 'CC002';
  end if;

  insert into public.shipments (id, sender_id, product_profile_id, logger_id, origin, destination)
  values (p_id, v_uid, p_product_profile_id, p_logger_id, p_origin, p_destination)
  returning * into v_shipment;

  insert into public.shipment_members (shipment_id, user_id, role) values (p_id, v_uid, 'sender');

  v_created_ms := (extract(epoch from v_shipment.created_at) * 1000)::bigint;
  v_genesis := private.chain_genesis(p_id, v_uid, p_product_profile_id, p_logger_id, v_created_ms);

  insert into public.chain_heads (shipment_id, head_hash) values (p_id, v_genesis);

  return jsonb_build_object('genesis_hash', v_genesis, 'created_at_ms', v_created_ms, 'sender_id', v_uid);
end
$$;

create function public.add_shipment_member(p_shipment_id text, p_email text, p_role text) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid;
begin
  if not exists (
    select 1 from public.shipments s
    where s.id = p_shipment_id and s.sender_id = (select auth.uid())
  ) then
    raise exception 'Only the shipment''s sender can add members.' using errcode = 'CC002';
  end if;

  if p_role not in ('courier', 'receiver') then
    raise exception 'A member is a courier or a receiver.' using errcode = 'CC006';
  end if;

  select u.id into v_user from auth.users u where lower(u.email) = lower(p_email);
  if v_user is null then
    raise exception 'No account has that email address.' using errcode = 'CC006';
  end if;

  -- The person dispatching can't also sign for delivery, or one account could play both sides.
  if v_user = (select auth.uid()) then
    raise exception 'A sender cannot be a courier or receiver on their own shipment.' using errcode = 'CC006';
  end if;

  insert into public.shipment_members (shipment_id, user_id, role)
  values (p_shipment_id, v_user, p_role)
  on conflict (shipment_id, user_id) do update set role = excluded.role;
end
$$;

create function public.dispatch_shipment(p_shipment_id text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.shipments s set status = 'DISPATCHED'
  where s.id = p_shipment_id and s.sender_id = (select auth.uid()) and s.status = 'CREATED';

  if not found then
    raise exception 'Only the sender can dispatch a new shipment.' using errcode = 'CC007';
  end if;
end
$$;

create function public.start_custody(p_shipment_id text, p_device_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_status text;
begin
  perform private.require_own_device(p_device_id);

  select s.status into v_status from public.shipments s where s.id = p_shipment_id for update;

  if not exists (
    select 1 from public.shipment_members m
    where m.shipment_id = p_shipment_id and m.user_id = v_uid and m.role = 'courier'
  ) then
    raise exception 'Only a courier on this shipment can take custody.' using errcode = 'CC002';
  end if;

  if v_status <> 'DISPATCHED' then
    raise exception 'This shipment is not waiting for pickup.' using errcode = 'CC007';
  end if;

  insert into public.custody_events (shipment_id, leg, to_user, kind, device_id)
  values (p_shipment_id, 1, v_uid, 'START', p_device_id);

  update public.shipments s set status = 'IN_TRANSIT' where s.id = p_shipment_id;
end
$$;

create function public.request_replacement(p_shipment_id text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not private.can_oversee(p_shipment_id) then
    raise exception 'Only the sender or receiver can ask for a replacement.' using errcode = 'CC002';
  end if;

  update public.shipments s set status = 'REPLACEMENT_REQUESTED'
  where s.id = p_shipment_id and s.status = 'QUARANTINED';

  if not found then
    raise exception 'Only a quarantined shipment can be replaced.' using errcode = 'CC007';
  end if;
end
$$;

-- Client functions: readings -----------------------------------------------------------------------

-- Anything wrong raises, and raising undoes the whole batch, so the chain only ever moves forward.
create function public.append_readings(
  p_shipment_id text,
  p_readings jsonb,
  p_expected_prev_head text,
  p_device_id uuid,
  p_signature text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_head public.chain_heads;
  v_shipment public.shipments;
  v_reading jsonb;
  v_position integer := 0;
  v_seq bigint;
  v_ts bigint;
  v_temp integer;
  v_prev_ts bigint;
  v_hash text;
  v_running_head text;
  v_running_seq bigint;
  v_first_seq bigint;
  v_now_ms bigint := (extract(epoch from now()) * 1000)::bigint;
  v_earliest_ms bigint;
begin
  perform private.require_own_device(p_device_id);

  if coalesce(p_signature, '') = '' then
    raise exception 'A batch must be signed by the device that sends it.' using errcode = 'CC006';
  end if;

  -- The lock makes two uploads for one shipment take turns, so both can't build on the same head.
  select h.* into v_head from public.chain_heads h where h.shipment_id = p_shipment_id for update;

  if not found or private.current_custodian(p_shipment_id) is distinct from v_uid then
    raise exception 'Only the current custodian can add readings.' using errcode = 'CC002';
  end if;

  select s.* into v_shipment from public.shipments s where s.id = p_shipment_id;

  if v_shipment.status not in ('IN_TRANSIT', 'QUARANTINED') then
    raise exception 'This shipment is no longer on the road.' using errcode = 'CC007';
  end if;

  if v_head.head_hash <> p_expected_prev_head then
    raise exception 'The history has moved on since this batch was built.' using errcode = 'CC001';
  end if;

  if jsonb_typeof(p_readings) is distinct from 'array'
     or jsonb_array_length(p_readings) not between 1 and 1000 then
    raise exception 'A batch holds between 1 and 1000 readings.' using errcode = 'CC006';
  end if;

  -- A reading flagged as dated in the future doesn't count here, or one bad clock would make every
  -- honest reading after it look back-dated.
  select r.logger_ts into v_prev_ts
  from public.readings r
  where r.shipment_id = p_shipment_id and not r.clock_ahead
  order by r.seq desc
  limit 1;

  v_earliest_ms := (extract(epoch from v_shipment.created_at) * 1000)::bigint - private.clock_tolerance_ms();

  v_running_head := v_head.head_hash;
  v_running_seq := v_head.last_seq;
  v_first_seq := v_head.last_seq + 1;

  for v_reading in select e from jsonb_array_elements(p_readings) e loop
    v_position := v_position + 1;
    v_seq := (v_reading ->> 'seq')::bigint;
    v_ts := (v_reading ->> 'logger_ts')::bigint;
    v_temp := (v_reading ->> 'temp_centi_c')::integer;

    if v_seq is distinct from v_running_seq + 1 then
      raise exception 'Reading % has sequence %, expected %.', v_position, v_seq, v_running_seq + 1
        using errcode = 'CC003';
    end if;

    if (v_reading ->> 'prev_hash') is distinct from v_running_head then
      raise exception 'Reading % is not linked to the one before it.', v_seq using errcode = 'CC004';
    end if;

    if v_ts <= v_prev_ts or v_ts < v_earliest_ms then
      raise exception 'Reading % is back-dated.', v_seq using errcode = 'CC009';
    end if;

    v_hash := private.reading_hash(p_shipment_id, v_shipment.logger_id, v_seq, v_ts, v_temp, v_running_head);

    if v_hash is distinct from (v_reading ->> 'hash') then
      raise exception 'Reading % does not match its hash.', v_seq using errcode = 'CC004';
    end if;

    -- A reading from the future can't be refused outright, because a logger's clock can drift, but
    -- the server records that it arrived before it says it happened.
    insert into public.readings (
      shipment_id, seq, logger_id, logger_ts, temp_centi_c, prev_hash, hash, uploaded_by_device, clock_ahead
    ) values (
      p_shipment_id, v_seq, v_shipment.logger_id, v_ts, v_temp, v_running_head, v_hash, p_device_id,
      v_ts > v_now_ms + private.clock_tolerance_ms()
    );

    v_running_head := v_hash;
    v_running_seq := v_seq;
    if v_ts <= v_now_ms + private.clock_tolerance_ms() then
      v_prev_ts := v_ts;
    end if;
  end loop;

  update public.chain_heads h
  set head_hash = v_running_head,
      count = v_running_seq,
      last_seq = v_running_seq,
      updated_at = now()
  where h.shipment_id = p_shipment_id;

  insert into public.reading_batches (shipment_id, first_seq, last_seq, head_hash, device_id, signature)
  values (p_shipment_id, v_first_seq, v_running_seq, v_running_head, p_device_id, p_signature);

  return jsonb_build_object('head_hash', v_running_head, 'count', v_running_seq, 'last_seq', v_running_seq);
end
$$;

-- Client functions: handover -----------------------------------------------------------------------

-- Custody moves when the custodian has named who receives, that person has confirmed, and both of
-- them confirmed the history as it stands now. A verdict that isn't a pass quarantines the shipment
-- straight away: it doesn't wait for the custodian, who could otherwise block it by not confirming.
create function public.confirm_handover(
  p_shipment_id text,
  p_leg integer,
  p_device_id uuid,
  p_head_hash text,
  p_to_user uuid default null,
  p_verdict text default null,
  p_reason text default null,
  p_vvm_stage text default 'NOT_RECORDED',
  p_shake_test text default 'NOT_DONE'
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_shipment public.shipments;
  v_head public.chain_heads;
  v_custodian uuid;
  v_role text;
  v_current_leg integer;
  v_is_custodian boolean;
  v_releasing public.handover_confirmations;
  v_receiving public.handover_confirmations;
begin
  perform private.require_own_device(p_device_id);

  -- Taking turns here means two confirmations can't both see the other as missing, and an upload
  -- can't slip in while this one reads the head.
  select s.* into v_shipment from public.shipments s where s.id = p_shipment_id for update;
  select h.* into v_head from public.chain_heads h where h.shipment_id = p_shipment_id for update;

  select m.role into v_role
  from public.shipment_members m
  where m.shipment_id = p_shipment_id and m.user_id = v_uid;

  v_custodian := private.current_custodian(p_shipment_id);
  v_is_custodian := v_custodian is not distinct from v_uid;

  if v_role is null or (v_role = 'sender' and not v_is_custodian) then
    raise exception 'Only the people handing over can confirm.' using errcode = 'CC002';
  end if;

  select max(e.leg) into v_current_leg from public.custody_events e where e.shipment_id = p_shipment_id;

  if v_current_leg is distinct from p_leg or v_shipment.status not in ('IN_TRANSIT', 'QUARANTINED') then
    raise exception 'This handover is not open.' using errcode = 'CC007';
  end if;

  if p_head_hash is distinct from v_head.head_hash then
    raise exception 'The history has moved on. Check the latest readings and confirm again.'
      using errcode = 'CC001';
  end if;

  if v_is_custodian then
    if p_to_user is null or p_to_user = v_uid or not exists (
      select 1 from public.shipment_members m
      where m.shipment_id = p_shipment_id and m.user_id = p_to_user and m.role in ('courier', 'receiver')
    ) then
      raise exception 'The custodian must name the courier or receiver taking over.' using errcode = 'CC006';
    end if;
    p_verdict := null;
    p_reason := null;
  else
    p_to_user := null;
    if p_verdict is null then
      raise exception 'The person receiving must give a verdict.' using errcode = 'CC006';
    elsif p_verdict <> 'PASS' and coalesce(btrim(p_reason), '') = '' then
      raise exception 'A % verdict needs a reason.', p_verdict using errcode = 'CC006';
    end if;
  end if;

  if exists (
    select 1 from public.handover_confirmations c
    where c.shipment_id = p_shipment_id and c.leg = p_leg and c.user_id = v_uid and c.head_hash = p_head_hash
  ) then
    raise exception 'You have already confirmed this history.' using errcode = 'CC008';
  end if;

  -- Each confirmation is a new row and none is ever changed, so an earlier verdict stays on the record
  -- after its owner confirms again on newer history.
  insert into public.handover_confirmations (
    shipment_id, leg, user_id, role, device_id, head_hash, to_user, verdict, reason, vvm_stage, shake_test
  ) values (
    p_shipment_id, p_leg, v_uid, v_role, p_device_id, p_head_hash, p_to_user,
    p_verdict, p_reason, p_vvm_stage, p_shake_test
  );

  select c.* into v_releasing
  from public.handover_confirmations c
  where c.shipment_id = p_shipment_id and c.leg = p_leg
    and c.user_id = v_custodian and c.head_hash = v_head.head_hash;

  -- Checked on every confirmation, against everything said on this leg, so the outcome is the same
  -- whoever confirms first. A later PASS doesn't undo this: nothing moves a shipment out of quarantine
  -- except a replacement request.
  if exists (
    select 1 from public.handover_confirmations c
    where c.shipment_id = p_shipment_id and c.leg = p_leg and c.verdict <> 'PASS'
      and (c.role = 'receiver' or c.user_id = v_releasing.to_user)
  ) then
    update public.shipments s set status = 'QUARANTINED'
    where s.id = p_shipment_id and s.status = 'IN_TRANSIT';
  end if;

  select c.* into v_receiving
  from public.handover_confirmations c
  where c.shipment_id = p_shipment_id and c.leg = p_leg
    and c.user_id = v_releasing.to_user and c.head_hash = v_head.head_hash;

  if v_receiving.user_id is null then
    return jsonb_build_object(
      'state', 'WAITING',
      'status', (select s.status from public.shipments s where s.id = p_shipment_id)
    );
  end if;

  perform private.transfer_custody(p_shipment_id, p_leg, v_receiving.user_id, v_receiving.device_id);

  if v_receiving.role = 'receiver' then
    update public.shipments s set status = 'ACCEPTED'
    where s.id = p_shipment_id and s.status = 'IN_TRANSIT';
  end if;

  return jsonb_build_object(
    'state', 'TRANSFERRED',
    'status', (select s.status from public.shipments s where s.id = p_shipment_id)
  );
end
$$;

-- Client functions: alerts and location ------------------------------------------------------------

-- The server builds the ID. It is global, so if clients chose it, a member of one shipment could
-- claim the ID of another shipment's alert and stop that alert from ever being stored.
create function public.record_alert(
  p_shipment_id text,
  p_type text,
  p_excursion_start bigint,
  p_message text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_id text := p_shipment_id || ':' || p_type || ':' || p_excursion_start;
  v_created boolean;
begin
  if not private.is_member(p_shipment_id) then
    raise exception 'You are not on this shipment.' using errcode = 'CC002';
  end if;

  if p_excursion_start is null then
    raise exception 'An alert needs the time its event began.' using errcode = 'CC006';
  end if;

  -- An alert ID identifies one event, so seeing it again changes nothing. The first report stands, and
  -- the record shows who made it.
  insert into public.alerts (id, shipment_id, type, excursion_start, message, reported_by)
  values (v_id, p_shipment_id, p_type, p_excursion_start, p_message, (select auth.uid()))
  on conflict (id) do nothing;

  v_created := found;
  return jsonb_build_object('id', v_id, 'created', v_created);
end
$$;

create function public.escalate_alert(p_id text, p_level text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.alerts a set level = p_level
  where a.id = p_id
    and a.acknowledged_at is null
    and private.is_member(a.shipment_id)
    and array_position(array['WORKER', 'SUPERVISOR', 'PHARMACIST'], p_level)
        > array_position(array['WORKER', 'SUPERVISOR', 'PHARMACIST'], a.level);
end
$$;

create function public.acknowledge_alert(p_id text) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_shipment_id text;
begin
  select a.shipment_id into v_shipment_id from public.alerts a where a.id = p_id;

  if v_shipment_id is null or not private.can_oversee(v_shipment_id) then
    raise exception 'Only the sender or receiver can acknowledge an alert.' using errcode = 'CC002';
  end if;

  update public.alerts a
  set acknowledged_by = (select auth.uid()), acknowledged_at = now()
  where a.id = p_id and a.acknowledged_at is null;
end
$$;

create function public.append_gps_points(p_shipment_id text, p_device_id uuid, p_points jsonb) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform private.require_own_device(p_device_id);

  if private.current_custodian(p_shipment_id) is distinct from (select auth.uid()) then
    raise exception 'Only the current custodian can report location.' using errcode = 'CC002';
  end if;

  insert into public.gps_points (id, shipment_id, device_id, lat, lon, accuracy_m, device_ts)
  select (p ->> 'id')::uuid, p_shipment_id, p_device_id,
         (p ->> 'lat')::double precision, (p ->> 'lon')::double precision,
         (p ->> 'accuracy_m')::real, (p ->> 'device_ts')::bigint
  from jsonb_array_elements(p_points) p
  on conflict (id) do nothing;
end
$$;

-- Access -------------------------------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.product_profiles enable row level security;
alter table public.shipments enable row level security;
alter table public.shipment_members enable row level security;
alter table public.chain_heads enable row level security;
alter table public.readings enable row level security;
alter table public.reading_batches enable row level security;
alter table public.custody_events enable row level security;
alter table public.handover_confirmations enable row level security;
alter table public.alerts enable row level security;
alter table public.gps_points enable row level security;

create policy profiles_read on public.profiles for select to authenticated
  using (user_id = (select auth.uid()) or private.shares_shipment(user_id));
create policy devices_read on public.devices for select to authenticated
  using (user_id = (select auth.uid()) or private.shares_shipment(user_id));
create policy product_profiles_read on public.product_profiles for select to authenticated
  using (true);
create policy shipments_read on public.shipments for select to authenticated
  using (private.is_member(id));
create policy shipment_members_read on public.shipment_members for select to authenticated
  using (private.is_member(shipment_id));
create policy chain_heads_read on public.chain_heads for select to authenticated
  using (private.is_member(shipment_id));
create policy readings_read on public.readings for select to authenticated
  using (private.is_member(shipment_id));
create policy reading_batches_read on public.reading_batches for select to authenticated
  using (private.is_member(shipment_id));
create policy custody_events_read on public.custody_events for select to authenticated
  using (private.is_member(shipment_id));
create policy handover_confirmations_read on public.handover_confirmations for select to authenticated
  using (private.is_member(shipment_id));
create policy alerts_read on public.alerts for select to authenticated
  using (private.is_member(shipment_id));
create policy gps_points_read on public.gps_points for select to authenticated
  using (private.is_member(shipment_id));

grant select on
  public.profiles, public.devices, public.product_profiles, public.shipments, public.shipment_members,
  public.chain_heads, public.readings, public.reading_batches, public.custody_events,
  public.handover_confirmations, public.alerts, public.gps_points
to authenticated;

grant execute on function
  public.complete_profile(text, text),
  public.register_device(uuid, text),
  public.create_shipment(text, text, text, text, text),
  public.add_shipment_member(text, text, text),
  public.dispatch_shipment(text),
  public.start_custody(text, uuid),
  public.request_replacement(text),
  public.append_readings(text, jsonb, text, uuid, text),
  public.confirm_handover(text, integer, uuid, text, uuid, text, text, text, text),
  public.record_alert(text, text, bigint, text),
  public.escalate_alert(text, text),
  public.acknowledge_alert(text),
  public.append_gps_points(text, uuid, jsonb)
to authenticated;
