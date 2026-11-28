-- Gate 5 review S8, location privacy: the courier's route is personal data. Only the sender and the person whose phone
-- recorded a point can read it, only until 30 days after the trip ends, and the server takes only points a real fix
-- could have, at most 500 at a time.

begin;

create extension if not exists pgtap with schema extensions;

select plan(27);

\set sender   '''dddddddd-0000-0000-0000-000000000001'''
\set courier  '''dddddddd-0000-0000-0000-000000000002'''
\set receiver '''dddddddd-0000-0000-0000-000000000003'''
\set courier2 '''dddddddd-0000-0000-0000-000000000004'''
\set outsider '''dddddddd-0000-0000-0000-000000000005'''

\set device_courier  '''eeeeeeee-0000-0000-0000-000000000002'''
\set device_courier2 '''eeeeeeee-0000-0000-0000-000000000004'''

insert into auth.users (id, email) values
  (:sender,   'sender-s8@example.test'),
  (:courier,  'courier-s8@example.test'),
  (:receiver, 'receiver-s8@example.test'),
  (:courier2, 'courier2-s8@example.test'),
  (:outsider, 'outsider-s8@example.test');

insert into public.profiles (user_id, display_name, role) values
  (:sender,   'Sender',    'sender'),
  (:courier,  'Courier',   'courier'),
  (:receiver, 'Receiver',  'receiver'),
  (:courier2, 'Courier 2', 'courier'),
  (:outsider, 'Outsider',  'courier');

insert into public.devices (device_id, user_id, public_key) values
  (:device_courier,  :courier,  'key-c'),
  (:device_courier2, :courier2, 'key-c2');

create function pg_temp.act_as(p_user text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
end $$;
grant execute on function pg_temp.act_as(text) to authenticated, postgres;

-- [n] points as an honest phone sends them: now, a metre or so apart, 5 m accuracy.
create function pg_temp.points(p_count int) returns jsonb language sql as $$
  select jsonb_agg(jsonb_build_object(
    'id', gen_random_uuid(), 'lat', 51.5 + i / 100000.0, 'lon', -0.12, 'accuracy_m', 5,
    'device_ts', (extract(epoch from now()) * 1000)::bigint
  ))
  from generate_series(1, p_count) i
$$;
grant execute on function pg_temp.points(int) to authenticated;

-- One point like the honest one, with [p_key] set to [p_value].
create function pg_temp.point_with(p_key text, p_value jsonb) returns jsonb language sql as $$
  select jsonb_build_array((pg_temp.points(1) -> 0) || jsonb_build_object(p_key, p_value))
$$;
grant execute on function pg_temp.point_with(text, jsonb) to authenticated;

create function pg_temp.append(p_points jsonb) returns void language plpgsql as $$
begin
  perform public.append_gps_points('SHIP-S8', 'eeeeeeee-0000-0000-0000-000000000002'::uuid, p_points);
end $$;
grant execute on function pg_temp.append(jsonb) to authenticated;

create function pg_temp.visible() returns bigint language sql as $$
  select count(*) from public.gps_points where shipment_id = 'SHIP-S8'
$$;
grant execute on function pg_temp.visible() to authenticated;

-- Setup -------------------------------------------------------------------------------------------------

set local role authenticated;
select pg_temp.act_as(:sender);
select public.create_shipment('SHIP-S8', 'vaccine-2-8-freeze-sensitive', 'LOG-S8', 'A', 'B');
select public.add_shipment_member('SHIP-S8', 'courier-s8@example.test', 'courier');
select public.add_shipment_member('SHIP-S8', 'courier2-s8@example.test', 'courier');
select public.add_shipment_member('SHIP-S8', 'receiver-s8@example.test', 'receiver');
select public.dispatch_shipment('SHIP-S8');
select pg_temp.act_as(:courier);
select public.start_custody('SHIP-S8', :device_courier::uuid);

-- What the server takes --------------------------------------------------------------------------------

select lives_ok($$ select pg_temp.append(pg_temp.points(2)) $$, 'the custodian sends two honest points');
select lives_ok($$ select pg_temp.append(pg_temp.points(500)) $$, '500 points in one call are taken');
select throws_ok($$ select pg_temp.append(pg_temp.points(501)) $$, 'CC011', null, '501 points in one call are refused');
select throws_ok($$ select pg_temp.append('{"id": "x"}'::jsonb) $$, 'CC011', null, 'a single object instead of a list is refused');
select throws_ok($$ select pg_temp.append(pg_temp.point_with('lat', '90.5')) $$, 'CC011', null, 'a latitude past the pole is refused');
select throws_ok($$ select pg_temp.append(pg_temp.point_with('lon', '-180.5')) $$, 'CC011', null, 'a longitude past -180 is refused');
select throws_ok($$ select pg_temp.append(pg_temp.point_with('lat', '"NaN"')) $$, 'CC011', null, 'a latitude that isn''t a number is refused');
select throws_ok($$ select pg_temp.append(pg_temp.point_with('lat', '1e400')) $$, 'CC011', null, 'a huge latitude is refused, not overflowed');
select throws_ok($$ select pg_temp.append(pg_temp.point_with('accuracy_m', '-1')) $$, 'CC011', null, 'a negative accuracy is refused');
select throws_ok($$ select pg_temp.append(pg_temp.point_with('accuracy_m', '"Infinity"')) $$, 'CC011', null, 'an infinite accuracy is refused');
select throws_ok($$ select pg_temp.append(pg_temp.points(1) #- '{0,lon}') $$, 'CC011', null, 'a point with no longitude is refused');
select throws_ok(
  format($$ select pg_temp.append(pg_temp.point_with('device_ts', %L)) $$, ((extract(epoch from now()) * 1000)::bigint + 86400000)::text),
  'CC011', null, 'a point dated a day ahead is refused'
);
select throws_ok(
  format($$ select pg_temp.append(pg_temp.point_with('device_ts', %L)) $$, ((extract(epoch from now()) * 1000)::bigint - 86400000)::text),
  'CC011', null, 'a point dated before the shipment existed is refused'
);
select throws_ok($$ select pg_temp.append(pg_temp.point_with('device_ts', '1700000000000.5')) $$, 'CC011', null, 'a fractional time is refused');

select pg_temp.act_as(:courier2);
select throws_ok(
  format($$ select public.append_gps_points('SHIP-S8', %L, pg_temp.points(1)) $$, :device_courier2),
  'CC002', null, 'only the custodian can send location, as before'
);

-- Who can read it ------------------------------------------------------------------------------------------

select pg_temp.act_as(:courier);
select is(pg_temp.visible(), 502::bigint, 'the courier reads the points their phone sent, and nothing refused was stored');
select pg_temp.act_as(:sender);
select is(pg_temp.visible(), 502::bigint, 'the sender reads them');
select pg_temp.act_as(:receiver);
select is(pg_temp.visible(), 0::bigint, 'the receiver doesn''t');
select pg_temp.act_as(:courier2);
select is(pg_temp.visible(), 0::bigint, 'another courier on the shipment doesn''t');
select pg_temp.act_as(:outsider);
select is(pg_temp.visible(), 0::bigint, 'a stranger doesn''t');

-- How long --------------------------------------------------------------------------------------------------

reset role;
update public.custody_events set server_time = now() - interval '40 days' where shipment_id = 'SHIP-S8';
set local role authenticated;
select pg_temp.act_as(:sender);
select is(pg_temp.visible(), 502::bigint, 'while the trip goes on, however long, the sender still reads them');

reset role;
update public.shipments set status = 'ACCEPTED' where id = 'SHIP-S8';
update public.custody_events set server_time = now() - interval '29 days' where shipment_id = 'SHIP-S8';
set local role authenticated;
select pg_temp.act_as(:sender);
select is(pg_temp.visible(), 502::bigint, '29 days after the trip ended the sender still reads them');

reset role;
update public.custody_events set server_time = now() - interval '31 days' where shipment_id = 'SHIP-S8';
set local role authenticated;
select pg_temp.act_as(:sender);
select is(pg_temp.visible(), 0::bigint, '31 days after it ended the sender no longer does');
select pg_temp.act_as(:courier);
select is(pg_temp.visible(), 0::bigint, 'nor does the courier');

select throws_ok($$ select private.purge_expired_location() $$, '42501', null, 'a client can''t run the purge');

reset role;
select is(private.purge_expired_location(), 502::bigint, 'the owner''s purge deletes the expired points');
select is((select count(*) from public.gps_points where shipment_id = 'SHIP-S8'), 0::bigint, 'and they are gone');

select * from finish();
rollback;
