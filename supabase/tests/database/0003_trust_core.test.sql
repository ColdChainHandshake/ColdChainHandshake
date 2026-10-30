-- The attacks the server has to stop. Each one acts as a real role with a real JWT claim, the way a
-- modified app would, because the database is the only part of the system a courier doesn't control.

begin;

create extension if not exists pgtap with schema extensions;

select plan(164);

\set sender   '''aaaaaaaa-0000-0000-0000-000000000001'''
\set courier  '''aaaaaaaa-0000-0000-0000-000000000002'''
\set receiver '''aaaaaaaa-0000-0000-0000-000000000003'''
\set outsider '''aaaaaaaa-0000-0000-0000-000000000004'''
\set courier2 '''aaaaaaaa-0000-0000-0000-000000000005'''

\set device_sender   '''bbbbbbbb-0000-0000-0000-000000000001'''
\set device_courier  '''bbbbbbbb-0000-0000-0000-000000000002'''
\set device_receiver '''bbbbbbbb-0000-0000-0000-000000000003'''
\set device_outsider '''bbbbbbbb-0000-0000-0000-000000000004'''
\set device_courier2 '''bbbbbbbb-0000-0000-0000-000000000005'''

insert into auth.users (id, email) values
  (:sender,   'sender@example.test'),
  (:courier,  'courier@example.test'),
  (:receiver, 'receiver@example.test'),
  (:outsider, 'outsider@example.test'),
  (:courier2, 'courier2@example.test');

insert into public.profiles (user_id, display_name, role) values
  (:sender,   'Sender',    'sender'),
  (:courier,  'Courier',   'courier'),
  (:receiver, 'Receiver',  'receiver'),
  (:outsider, 'Outsider',  'courier'),
  (:courier2, 'Courier 2', 'courier');

insert into public.devices (device_id, user_id, public_key) values
  (:device_sender,   :sender,   'key-s'),
  (:device_courier,  :courier,  'key-c'),
  (:device_receiver, :receiver, 'key-r'),
  (:device_outsider, :outsider, 'key-o'),
  (:device_courier2, :courier2, 'key-c2');

-- Builds a valid batch the way an honest client would, continuing from a given head.
create function pg_temp.batch(p_shipment text, p_first bigint, p_temps int[], p_prev text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare
  v_prev text := p_prev;
  v_hash text;
  v_ts bigint;
  v_items jsonb := '[]'::jsonb;
  v_logger text;
begin
  select logger_id into v_logger from public.shipments where id = p_shipment;
  for i in 1 .. coalesce(array_length(p_temps, 1), 0) loop
    v_ts := (extract(epoch from now()) * 1000)::bigint + (p_first + i - 1) * 1000;
    v_hash := private.reading_hash(p_shipment, v_logger, p_first + i - 1, v_ts, p_temps[i], v_prev);
    v_items := v_items || jsonb_build_object(
      'seq', p_first + i - 1, 'logger_ts', v_ts, 'temp_centi_c', p_temps[i],
      'prev_hash', v_prev, 'hash', v_hash
    );
    v_prev := v_hash;
  end loop;
  return v_items;
end $$;
grant execute on function pg_temp.batch(text, bigint, int[], text) to authenticated;

create function pg_temp.hash(p_shipment text, p_logger text, p_seq bigint, p_ts bigint, p_temp int, p_prev text)
returns text language sql security definer set search_path = pg_catalog, public as
$$ select private.reading_hash(p_shipment, p_logger, p_seq, p_ts, p_temp, p_prev) $$;
grant execute on function pg_temp.hash(text, text, bigint, bigint, int, text) to authenticated;

create function pg_temp.head(p_shipment text) returns text
language sql security definer set search_path = pg_catalog, public as
$$ select head_hash from public.chain_heads where shipment_id = p_shipment $$;
grant execute on function pg_temp.head(text) to authenticated;

create function pg_temp.custodian(p_shipment text) returns uuid
language sql security definer set search_path = pg_catalog, public as
$$ select to_user from public.custody_events where shipment_id = p_shipment order by leg desc limit 1 $$;
grant execute on function pg_temp.custodian(text) to authenticated;

create function pg_temp.stored_count(p_shipment text) returns bigint
language sql security definer set search_path = pg_catalog, public as
$$ select count from public.chain_heads where shipment_id = p_shipment $$;
grant execute on function pg_temp.stored_count(text) to authenticated;

create function pg_temp.status(p_shipment text) returns text
language sql security definer set search_path = pg_catalog, public as
$$ select status from public.shipments where id = p_shipment $$;
grant execute on function pg_temp.status(text) to authenticated;

create function pg_temp.act_as(p_user text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
end $$;
grant execute on function pg_temp.act_as(text) to authenticated, postgres;

-- Setup -------------------------------------------------------------------------------------------

set local role authenticated;

select pg_temp.act_as(:sender);

select ok(
  (public.create_shipment('SHIP-1', 'vaccine-2-8-freeze-sensitive', 'LOG-1', 'A', 'B') ->> 'genesis_hash') is not null,
  'a sender can create a shipment'
);
select lives_ok(
  $$ select public.add_shipment_member('SHIP-1', 'courier@example.test', 'courier') $$,
  'a sender can add a courier'
);
select lives_ok(
  $$ select public.add_shipment_member('SHIP-1', 'courier2@example.test', 'courier') $$,
  'a sender can add a second courier'
);
select lives_ok(
  $$ select public.add_shipment_member('SHIP-1', 'receiver@example.test', 'receiver') $$,
  'a sender can add a receiver'
);
select is(pg_temp.status('SHIP-1'), 'CREATED', 'a new shipment is CREATED');
select lives_ok($$ select public.dispatch_shipment('SHIP-1') $$, 'a sender can dispatch');
select is(pg_temp.status('SHIP-1'), 'DISPATCHED', 'a dispatched shipment is DISPATCHED');

select pg_temp.act_as(:courier);
select lives_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-1', :device_courier::uuid),
  'the courier can start custody'
);
select is(pg_temp.status('SHIP-1'), 'IN_TRANSIT', 'starting custody puts the shipment in transit');
select is(pg_temp.custodian('SHIP-1'), :courier::uuid, 'the courier holds custody');

-- Appending readings ------------------------------------------------------------------------------

select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 1, array[500, 510], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_courier::uuid
  ),
  'the custodian can append a valid batch'
);
select is(pg_temp.stored_count('SHIP-1'), 2::bigint, 'the server counts what was appended');
select isnt(
  (select received_at from public.readings where shipment_id = 'SHIP-1' and seq = 1),
  null::timestamptz,
  'the server stamps the receive time'
);

-- Readings can't be changed by anyone.

select throws_ok(
  $$ update public.readings set temp_centi_c = 400 where shipment_id = 'SHIP-1' $$,
  '42501', null, 'a reading cannot be edited'
);
select throws_ok(
  $$ delete from public.readings where shipment_id = 'SHIP-1' and seq = 2 $$,
  '42501', null, 'a reading cannot be deleted'
);
select throws_ok(
  $$ insert into public.readings (shipment_id, seq, logger_id, logger_ts, temp_centi_c, prev_hash, hash, uploaded_by_device)
     values ('SHIP-1', 3, 'LOG-1', 1, 500, 'x', 'y', 'bbbbbbbb-0000-0000-0000-000000000002') $$,
  '42501', null, 'a reading cannot be inserted around the RPC'
);
select throws_ok('truncate public.readings', '42501', null, 'readings cannot be truncated');
select throws_ok(
  $$ update public.chain_heads set count = 0 where shipment_id = 'SHIP-1' $$,
  '42501', null, 'the chain head cannot be edited'
);
select throws_ok(
  $$ delete from public.chain_heads where shipment_id = 'SHIP-1' $$,
  '42501', null, 'the chain head cannot be deleted'
);

-- Each bad batch below must be refused and leave nothing behind.

select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')), repeat('0', 64), :device_courier::uuid
  ),
  'CC001', null, 'a stale previous head is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 4, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_courier::uuid
  ),
  'CC003', null, 'a gap in the sequence is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 2, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_courier::uuid
  ),
  'CC003', null, 'a repeated sequence number is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    (select jsonb_agg(x order by (x ->> 'seq')::bigint desc)
       from jsonb_array_elements(pg_temp.batch('SHIP-1', 3, array[500, 510], pg_temp.head('SHIP-1'))) x),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'CC003', null, 'a re-ordered batch is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    jsonb_set(pg_temp.batch('SHIP-1', 3, array[1200], pg_temp.head('SHIP-1')), '{0,temp_centi_c}', '500'),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'CC004', null, 'an edited temperature with its original hash is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    jsonb_set(pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')), '{0,hash}', to_jsonb(repeat('a', 64))),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'CC004', null, 'an edited hash is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    jsonb_set(pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')), '{0,prev_hash}', to_jsonb(repeat('b', 64))),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'CC004', null, 'a reading linked to the wrong predecessor is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', '[]', pg_temp.head('SHIP-1'), :device_courier::uuid
  ),
  'CC006', null, 'an empty batch is refused'
);
select is(pg_temp.stored_count('SHIP-1'), 2::bigint, 'none of the refused batches changed the count');

-- A reading dated before its predecessor, or before the shipment existed, is a clock trick.
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    (select jsonb_build_array(jsonb_build_object(
       'seq', 3, 'logger_ts', ts, 'temp_centi_c', 500, 'prev_hash', pg_temp.head('SHIP-1'),
       'hash', pg_temp.hash('SHIP-1', 'LOG-1', 3, ts, 500, pg_temp.head('SHIP-1'))
     )) from (select (extract(epoch from now()) * 1000)::bigint as ts) t),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'CC009', null, 'a reading dated before the previous one is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    (select jsonb_build_array(jsonb_build_object(
       'seq', 3, 'logger_ts', ts, 'temp_centi_c', 500, 'prev_hash', pg_temp.head('SHIP-1'),
       'hash', pg_temp.hash('SHIP-1', 'LOG-1', 3, ts, 500, pg_temp.head('SHIP-1'))
     )) from (select (extract(epoch from now()) * 1000)::bigint + 2000 as ts) t),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'CC009', null, 'a reading with the same time as the previous one is refused'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, null)',
    'SHIP-1', pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_courier::uuid
  ),
  'CC006', null, 'an unsigned batch is refused'
);

-- A valid-looking batch from a chain belonging to another shipment doesn't fit.
select pg_temp.act_as(:sender);
select ok(
  (public.create_shipment('SHIP-2', 'vaccine-2-8-freeze-sensitive', 'LOG-1', 'A', 'B') ->> 'genesis_hash') <> pg_temp.head('SHIP-1'),
  'each shipment has its own genesis'
);
select lives_ok($$ select public.add_shipment_member('SHIP-2', 'courier@example.test', 'courier') $$, 'setup: second shipment member');
select lives_ok($$ select public.dispatch_shipment('SHIP-2') $$, 'setup: second shipment dispatched');
select pg_temp.act_as(:courier);
select lives_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-2', :device_courier::uuid),
  'setup: custody of the second shipment'
);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-2',
    (select jsonb_set(jsonb_set(pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')),
       '{0,seq}', '1'), '{0,prev_hash}', to_jsonb(pg_temp.head('SHIP-2')))),
    pg_temp.head('SHIP-2'),
    :device_courier::uuid
  ),
  'CC004', null, 'a reading from another shipment is refused'
);

-- Only the custodian, on their own registered device.

select pg_temp.act_as(:courier2);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_courier2::uuid
  ),
  'CC002', null, 'a member without custody cannot append'
);
select pg_temp.act_as(:outsider);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_outsider::uuid
  ),
  'CC002', null, 'an outsider cannot append'
);
select pg_temp.act_as(:courier);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 3, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_outsider::uuid
  ),
  'CC005', null, 'the custodian cannot upload under another person''s device'
);

-- Reading access ----------------------------------------------------------------------------------

select pg_temp.act_as(:outsider);
select is((select count(*) from public.shipments), 0::bigint, 'an outsider sees no shipments');
select is((select count(*) from public.readings), 0::bigint, 'an outsider sees no readings');
select is((select count(*) from public.chain_heads), 0::bigint, 'an outsider sees no chain heads');
select is((select count(*) from public.custody_events), 0::bigint, 'an outsider sees no custody events');
select is((select count(*) from public.shipment_members), 0::bigint, 'an outsider sees no members');

select pg_temp.act_as(:receiver);
select is((select count(*) from public.shipments), 1::bigint, 'a receiver sees only their own shipment');
select is((select count(*) from public.readings), 2::bigint, 'a receiver can read the shipment''s readings');

select pg_temp.act_as(:sender);
select is((select count(*) from public.shipments), 2::bigint, 'a sender sees their shipments');
select is((select count(*) from public.readings), 2::bigint, 'a sender can read every reading');

-- A future timestamp can't be refused, because clocks drift, but it can't pass unnoticed.
select pg_temp.act_as(:courier);
select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    (select jsonb_build_array(jsonb_build_object(
       'seq', 3, 'logger_ts', ts, 'temp_centi_c', 500, 'prev_hash', pg_temp.head('SHIP-1'),
       'hash', pg_temp.hash('SHIP-1', 'LOG-1', 3, ts, 500, pg_temp.head('SHIP-1'))
     )) from (select (extract(epoch from now()) * 1000)::bigint + 3600000 as ts) t),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'setup: a reading from an hour in the future'
);
select is(
  (select clock_ahead from public.readings where shipment_id = 'SHIP-1' and seq = 3),
  true,
  'the server flags a reading dated ahead of its arrival'
);
select is(
  (select clock_ahead from public.readings where shipment_id = 'SHIP-1' and seq = 2),
  false,
  'a reading dated in the past is not flagged'
);
select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    (select jsonb_build_array(jsonb_build_object(
       'seq', 4, 'logger_ts', ts, 'temp_centi_c', 500, 'prev_hash', pg_temp.head('SHIP-1'),
       'hash', pg_temp.hash('SHIP-1', 'LOG-1', 4, ts, 500, pg_temp.head('SHIP-1'))
     )) from (select (extract(epoch from now()) * 1000)::bigint + 5000 as ts) t),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'an honest reading can follow one with a bad clock'
);

-- Handover ----------------------------------------------------------------------------------------

select pg_temp.act_as(:outsider);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, null, %L)', 'SHIP-1', :device_outsider::uuid, pg_temp.head('SHIP-1'), 'PASS'),
  'CC002', null, 'an outsider cannot confirm a handover'
);

select pg_temp.act_as(:sender);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, null, %L)', 'SHIP-1', :device_sender::uuid, pg_temp.head('SHIP-1'), 'PASS'),
  'CC002', null, 'the sender cannot stand in as a party to the handover'
);

select pg_temp.act_as(:receiver);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, null, %L)', 'SHIP-1', :device_courier::uuid, pg_temp.head('SHIP-1'), 'PASS'),
  'CC005', null, 'one device cannot confirm for both people'
);
select throws_ok(
  format('select public.confirm_handover(%L, 2, %L, %L, null, %L)', 'SHIP-1', :device_receiver::uuid, pg_temp.head('SHIP-1'), 'PASS'),
  'CC007', null, 'a confirmation for the wrong leg is refused'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, null, %L)', 'SHIP-1', :device_receiver::uuid, repeat('0', 64), 'PASS'),
  'CC001', null, 'a verdict must be on the current history'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, null, %L)', 'SHIP-1', :device_receiver::uuid, pg_temp.head('SHIP-1'), 'FAIL'),
  'CC006', null, 'a FAIL needs a reason'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, null, %L)', 'SHIP-1', :device_receiver::uuid, pg_temp.head('SHIP-1'), 'UNKNOWN'),
  'CC006', null, 'an UNKNOWN needs a reason'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L)', 'SHIP-1', :device_receiver::uuid, pg_temp.head('SHIP-1')),
  'CC006', null, 'the receiving person must give a verdict'
);

select throws_ok(
  $$ insert into public.custody_events (shipment_id, to_user, kind, leg) values ('SHIP-1', 'aaaaaaaa-0000-0000-0000-000000000003', 'TRANSFER', 2) $$,
  '42501', null, 'custody cannot be written directly'
);
select throws_ok(
  $$ update public.custody_events set to_user = 'aaaaaaaa-0000-0000-0000-000000000003' $$,
  '42501', null, 'custody cannot be rewritten'
);
select throws_ok(
  $$ update public.shipments set status = 'ACCEPTED' where id = 'SHIP-1' $$,
  '42501', null, 'a status cannot be set directly'
);
select throws_ok(
  $$ select private.transfer_custody('SHIP-1', 1, 'aaaaaaaa-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000003') $$,
  '42501', null, 'the internal transfer cannot be called by a client'
);

select is(
  public.confirm_handover('SHIP-1', 1, :device_receiver::uuid, pg_temp.head('SHIP-1'), null, 'PASS', null, 'STAGE_1', 'PASSED') ->> 'state',
  'WAITING',
  'one confirmation is not enough'
);
select is(pg_temp.custodian('SHIP-1'), :courier::uuid, 'custody has not moved on one confirmation');
select is(pg_temp.status('SHIP-1'), 'IN_TRANSIT', 'the status has not moved on one confirmation');

select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, null, %L)', 'SHIP-1', :device_receiver::uuid, pg_temp.head('SHIP-1'), 'PASS'),
  'CC008', null, 'nobody can confirm the same history twice'
);

select pg_temp.act_as(:courier2);
select is(
  public.confirm_handover('SHIP-1', 1, :device_courier2::uuid, pg_temp.head('SHIP-1'), null, 'PASS') ->> 'state',
  'WAITING',
  'another member can record a confirmation'
);

select pg_temp.act_as(:courier);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L)', 'SHIP-1', :device_courier::uuid, pg_temp.head('SHIP-1')),
  'CC006', null, 'the custodian must name who is taking over'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, %L)', 'SHIP-1', :device_courier::uuid, pg_temp.head('SHIP-1'), :courier::uuid),
  'CC006', null, 'the custodian cannot hand over to themselves'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, %L)', 'SHIP-1', :device_courier::uuid, pg_temp.head('SHIP-1'), :outsider::uuid),
  'CC006', null, 'the custodian cannot hand over to an outsider'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, %L)', 'SHIP-1', :device_courier::uuid, pg_temp.head('SHIP-1'), :sender::uuid),
  'CC006', null, 'the custodian cannot hand back to the sender'
);

-- The courier uploads hot readings after the receiver has passed the shipment.
select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1',
    (select jsonb_build_array(jsonb_build_object(
       'seq', 5, 'logger_ts', ts, 'temp_centi_c', 1500, 'prev_hash', pg_temp.head('SHIP-1'),
       'hash', pg_temp.hash('SHIP-1', 'LOG-1', 5, ts, 1500, pg_temp.head('SHIP-1'))
     )) from (select (extract(epoch from now()) * 1000)::bigint + 6000 as ts) t),
    pg_temp.head('SHIP-1'),
    :device_courier::uuid
  ),
  'setup: new readings arrive after the verdict'
);
select is(
  public.confirm_handover('SHIP-1', 1, :device_courier::uuid, pg_temp.head('SHIP-1'), :receiver::uuid) ->> 'state',
  'WAITING',
  'a verdict on older history does not move custody'
);
select is(pg_temp.custodian('SHIP-1'), :courier::uuid, 'custody has not moved on a stale verdict');

select pg_temp.act_as(:receiver);
select is(
  public.confirm_handover('SHIP-1', 1, :device_receiver::uuid, pg_temp.head('SHIP-1'), null, 'PASS', null, 'STAGE_1', 'PASSED') ->> 'state',
  'TRANSFERRED',
  'custody moves once both have confirmed the same history'
);
select is(pg_temp.custodian('SHIP-1'), :receiver::uuid, 'the named receiver now holds custody');
select is(pg_temp.status('SHIP-1'), 'ACCEPTED', 'a passed handover to the receiver is ACCEPTED');

select pg_temp.act_as(:courier);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 6, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_courier::uuid
  ),
  'CC002', null, 'a former custodian cannot append'
);

select pg_temp.act_as(:receiver);
select throws_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-1', pg_temp.batch('SHIP-1', 6, array[500], pg_temp.head('SHIP-1')), pg_temp.head('SHIP-1'), :device_receiver::uuid
  ),
  'CC007', null, 'nothing can be added once a shipment is accepted'
);

select pg_temp.act_as(:sender);
select is(
  (select count(*) from public.handover_confirmations where shipment_id = 'SHIP-1' and leg = 1),
  4::bigint,
  'every confirmation stays on the record, including the one made on older history'
);
select is(
  (select count(distinct device_id) from public.handover_confirmations where shipment_id = 'SHIP-1'),
  3::bigint,
  'each confirmation records its own device'
);
select is(
  (select count(*) from public.handover_confirmations
   where shipment_id = 'SHIP-1' and user_id = :receiver::uuid and head_hash = pg_temp.head('SHIP-1')),
  1::bigint,
  'a confirmation records the history it was made on'
);
select isnt(
  (select server_time from public.handover_confirmations where shipment_id = 'SHIP-1' limit 1),
  null::timestamptz,
  'each confirmation has a server time'
);

-- Failed handover and multi-leg custody

select pg_temp.act_as(:sender);
select lives_ok($$ select public.add_shipment_member('SHIP-2', 'courier2@example.test', 'courier') $$, 'setup: next-leg courier');
select lives_ok($$ select public.add_shipment_member('SHIP-2', 'receiver@example.test', 'receiver') $$, 'setup: receiver');

select pg_temp.act_as(:courier);
select is(
  public.confirm_handover('SHIP-2', 1, :device_courier::uuid, pg_temp.head('SHIP-2'), :courier2::uuid) ->> 'state',
  'WAITING',
  'the releasing courier confirms first'
);
select throws_ok(
  format('select public.confirm_handover(%L, 1, %L, %L, %L)', 'SHIP-2', :device_courier::uuid, pg_temp.head('SHIP-2'), :courier2::uuid),
  'CC008', null, 'the releasing courier cannot confirm twice'
);

select pg_temp.act_as(:receiver);
select is(
  public.confirm_handover('SHIP-2', 1, :device_receiver::uuid, pg_temp.head('SHIP-2'), null, 'PASS') ->> 'state',
  'WAITING',
  'someone other than the named person cannot take custody'
);
select is(pg_temp.custodian('SHIP-2'), :courier::uuid, 'custody stays put for the wrong person');

select pg_temp.act_as(:courier2);
select is(
  public.confirm_handover('SHIP-2', 1, :device_courier2::uuid, pg_temp.head('SHIP-2'), null, 'PASS') ->> 'state',
  'TRANSFERRED',
  'custody can pass from courier to courier'
);
select is(pg_temp.custodian('SHIP-2'), :courier2::uuid, 'the next courier holds custody');
select is(pg_temp.status('SHIP-2'), 'IN_TRANSIT', 'a courier-to-courier leg stays in transit');

select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-2', pg_temp.batch('SHIP-2', 1, array[500], pg_temp.head('SHIP-2')), pg_temp.head('SHIP-2'), :device_courier2::uuid
  ),
  'the next courier can keep the same chain going'
);

-- The custodian can't stop a bad verdict by refusing to confirm.
select pg_temp.act_as(:receiver);
select is(
  public.confirm_handover('SHIP-2', 2, :device_receiver::uuid, pg_temp.head('SHIP-2'), null, 'FAIL', 'Seal broken', 'STAGE_3', 'NOT_DONE') ->> 'status',
  'QUARANTINED',
  'a failed verdict quarantines at once, without the custodian'
);
select is(pg_temp.custodian('SHIP-2'), :courier2::uuid, 'quarantine does not move custody by itself');

-- New readings arrive, and the receiver changes their mind to PASS on the newer history.
select pg_temp.act_as(:courier2);
select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-2', pg_temp.batch('SHIP-2', 2, array[500], pg_temp.head('SHIP-2')), pg_temp.head('SHIP-2'), :device_courier2::uuid
  ),
  'setup: readings continue under quarantine'
);
select pg_temp.act_as(:receiver);
select is(
  public.confirm_handover('SHIP-2', 2, :device_receiver::uuid, pg_temp.head('SHIP-2'), null, 'PASS') ->> 'status',
  'QUARANTINED',
  'a later PASS does not undo a quarantine'
);
select is(
  (select count(*) from public.handover_confirmations
   where shipment_id = 'SHIP-2' and leg = 2 and user_id = :receiver::uuid and verdict = 'FAIL'),
  1::bigint,
  'a later PASS does not erase the FAIL'
);

select pg_temp.act_as(:courier2);
select is(
  public.confirm_handover('SHIP-2', 2, :device_courier2::uuid, pg_temp.head('SHIP-2'), :receiver::uuid) ->> 'state',
  'TRANSFERRED',
  'custody can still pass once the courier confirms'
);
select is(pg_temp.status('SHIP-2'), 'QUARANTINED', 'the shipment stays quarantined');

-- Quarantine doesn't depend on who confirms first. Here a courier fails the leg before being named.
select pg_temp.act_as(:sender);
select lives_ok(
  $$ select public.create_shipment('SHIP-4', 'vaccine-2-8-freeze-sensitive', 'LOG-1', 'A', 'B') $$,
  'setup: a fourth shipment'
);
select lives_ok($$ select public.add_shipment_member('SHIP-4', 'courier@example.test', 'courier') $$, 'setup: first courier');
select lives_ok($$ select public.add_shipment_member('SHIP-4', 'courier2@example.test', 'courier') $$, 'setup: next courier');
select lives_ok($$ select public.dispatch_shipment('SHIP-4') $$, 'setup: dispatched');
select pg_temp.act_as(:courier);
select lives_ok(format('select public.start_custody(%L, %L)', 'SHIP-4', :device_courier::uuid), 'setup: picked up');

select pg_temp.act_as(:courier2);
select is(
  public.confirm_handover('SHIP-4', 1, :device_courier2::uuid, pg_temp.head('SHIP-4'), null, 'FAIL', 'Box crushed') ->> 'status',
  'IN_TRANSIT',
  'a courier who has not been named cannot quarantine a shipment'
);

select pg_temp.act_as(:courier);
select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-4', pg_temp.batch('SHIP-4', 1, array[500], pg_temp.head('SHIP-4')), pg_temp.head('SHIP-4'), :device_courier::uuid
  ),
  'setup: the history moves on'
);
select is(
  public.confirm_handover('SHIP-4', 1, :device_courier::uuid, pg_temp.head('SHIP-4'), :courier2::uuid) ->> 'status',
  'QUARANTINED',
  'naming someone who has already failed the leg quarantines, even on older history'
);
select is(
  (select reason from public.handover_confirmations where shipment_id = 'SHIP-2' and verdict = 'FAIL'),
  'Seal broken',
  'the reason is on the record'
);
select throws_ok(
  $$ select public.request_replacement('SHIP-2') $$,
  'CC002', null, 'a courier cannot ask for a replacement'
);
select pg_temp.act_as(:outsider);
select throws_ok(
  $$ select public.request_replacement('SHIP-2') $$,
  'CC002', null, 'an outsider cannot ask for a replacement'
);
select pg_temp.act_as(:receiver);
select lives_ok($$ select public.request_replacement('SHIP-2') $$, 'the receiver can ask for a replacement');
select is(pg_temp.status('SHIP-2'), 'REPLACEMENT_REQUESTED', 'quarantine leads to a replacement request');

-- Starting custody --------------------------------------------------------------------------------

select pg_temp.act_as(:sender);
select lives_ok(
  $$ select public.create_shipment('SHIP-3', 'vaccine-2-8-freeze-sensitive', 'LOG-1', 'A', 'B') $$,
  'setup: a third shipment'
);
select lives_ok($$ select public.add_shipment_member('SHIP-3', 'receiver@example.test', 'receiver') $$, 'setup: its receiver');
select lives_ok($$ select public.add_shipment_member('SHIP-3', 'courier@example.test', 'courier') $$, 'setup: its courier');
select throws_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-3', :device_sender::uuid),
  'CC002', null, 'the sender cannot take custody'
);

select pg_temp.act_as(:courier);
select throws_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-3', :device_courier::uuid),
  'CC007', null, 'custody cannot start before dispatch'
);

select pg_temp.act_as(:sender);
select lives_ok($$ select public.dispatch_shipment('SHIP-3') $$, 'setup: dispatched');
select throws_ok(
  $$ select public.dispatch_shipment('SHIP-3') $$,
  'CC007', null, 'a shipment cannot be dispatched twice'
);

select pg_temp.act_as(:receiver);
select throws_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-3', :device_receiver::uuid),
  'CC002', null, 'a receiver cannot take custody at pickup'
);

select pg_temp.act_as(:outsider);
select throws_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-3', :device_outsider::uuid),
  'CC002', null, 'an outsider cannot take custody'
);

select pg_temp.act_as(:courier);
select lives_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-3', :device_courier::uuid),
  'setup: picked up'
);
select throws_ok(
  format('select public.start_custody(%L, %L)', 'SHIP-3', :device_courier::uuid),
  'CC007', null, 'custody cannot start twice'
);

-- Alerts and location -----------------------------------------------------------------------------

select lives_ok(
  format(
    'select public.append_gps_points(%L, %L, %L::jsonb)',
    'SHIP-3', :device_courier::uuid,
    '[{"id":"cccccccc-0000-0000-0000-000000000001","lat":1.5,"lon":2.5,"accuracy_m":3.5,"device_ts":1700000000000}]'
  ),
  'the custodian can report location'
);
select lives_ok(
  format(
    'select public.append_gps_points(%L, %L, %L::jsonb)',
    'SHIP-3', :device_courier::uuid,
    '[{"id":"cccccccc-0000-0000-0000-000000000001","lat":1.5,"lon":2.5,"accuracy_m":3.5,"device_ts":1700000000000}]'
  ),
  'sending the same location twice is harmless'
);
select is((select count(*) from public.gps_points where shipment_id = 'SHIP-3'), 1::bigint, 'a repeated point is stored once');

select pg_temp.act_as(:receiver);
select throws_ok(
  format(
    'select public.append_gps_points(%L, %L, %L::jsonb)',
    'SHIP-3', :device_receiver::uuid,
    '[{"id":"cccccccc-0000-0000-0000-000000000002","lat":1.5,"lon":2.5,"accuracy_m":3.5,"device_ts":1700000000000}]'
  ),
  'CC002', null, 'only the custodian can report location'
);

select pg_temp.act_as(:courier);
select is(
  public.record_alert('SHIP-3', 'FREEZE_BREACH', 1, 'Frozen') ->> 'id',
  'SHIP-3:FREEZE_BREACH:1',
  'the server builds the alert ID'
);
select is(
  (select reported_by from public.alerts where id = 'SHIP-3:FREEZE_BREACH:1'),
  :courier::uuid,
  'an alert records who reported it'
);

select pg_temp.act_as(:outsider);
select throws_ok(
  $$ select public.record_alert('SHIP-3', 'DATA_GAP', 1, 'Gap') $$,
  'CC002', null, 'an outsider cannot record an alert'
);
select throws_ok(
  $$ select public.acknowledge_alert('SHIP-3:FREEZE_BREACH:1') $$,
  'CC002', null, 'an outsider cannot acknowledge an alert'
);

select pg_temp.act_as(:courier);
select throws_ok(
  $$ select public.acknowledge_alert('SHIP-3:FREEZE_BREACH:1') $$,
  'CC002', null, 'a courier cannot silence an alert about their own shipment'
);

select pg_temp.act_as(:receiver);
select lives_ok($$ select public.escalate_alert('SHIP-3:FREEZE_BREACH:1', 'SUPERVISOR') $$, 'a member can escalate');
select lives_ok($$ select public.escalate_alert('SHIP-3:FREEZE_BREACH:1', 'WORKER') $$, 'setup: try to lower the level');
select is(
  (select level from public.alerts where id = 'SHIP-3:FREEZE_BREACH:1'),
  'SUPERVISOR',
  'an alert level never goes down'
);
select lives_ok($$ select public.acknowledge_alert('SHIP-3:FREEZE_BREACH:1') $$, 'the receiver can acknowledge');
select lives_ok($$ select public.escalate_alert('SHIP-3:FREEZE_BREACH:1', 'PHARMACIST') $$, 'setup: try to escalate after acknowledgement');
select is(
  (select level from public.alerts where id = 'SHIP-3:FREEZE_BREACH:1'),
  'SUPERVISOR',
  'an acknowledged alert stops escalating'
);

select pg_temp.act_as(:courier);
select is(
  public.record_alert('SHIP-3', 'FREEZE_BREACH', 1, 'Frozen again') ->> 'created',
  'false',
  'recording the same alert again says it already existed'
);
select isnt(
  (select acknowledged_at from public.alerts where id = 'SHIP-3:FREEZE_BREACH:1'),
  null::timestamptz,
  'recording an alert again keeps its acknowledgement'
);
select is(
  (select message from public.alerts where id = 'SHIP-3:FREEZE_BREACH:1'),
  'Frozen',
  'recording an alert again changes nothing'
);

select throws_ok(
  $$ select public.record_alert('SHIP-3', 'FREEZE_BREACH', null, 'No time') $$,
  'CC006', null, 'an alert needs a start time'
);

-- A custodian who confirmed older history has not confirmed what is there now.

select pg_temp.act_as(:sender);
select lives_ok(
  $$ select public.create_shipment('SHIP-5', 'vaccine-2-8-freeze-sensitive', 'LOG-1', 'A', 'B') $$,
  'setup: a fifth shipment'
);
select lives_ok($$ select public.add_shipment_member('SHIP-5', 'courier@example.test', 'courier') $$, 'setup: its courier');
select lives_ok($$ select public.add_shipment_member('SHIP-5', 'receiver@example.test', 'receiver') $$, 'setup: its receiver');
select lives_ok($$ select public.dispatch_shipment('SHIP-5') $$, 'setup: dispatched');

select pg_temp.act_as(:courier);
select lives_ok(format('select public.start_custody(%L, %L)', 'SHIP-5', :device_courier::uuid), 'setup: picked up');
select is(
  public.confirm_handover('SHIP-5', 1, :device_courier::uuid, pg_temp.head('SHIP-5'), :receiver::uuid) ->> 'state',
  'WAITING',
  'the custodian confirms the history as it stands'
);

-- One batch: a reading with a bad clock, then an honest one right after it.
select lives_ok(
  format(
    'select public.append_readings(%L, %L::jsonb, %L, %L, ''signature'')',
    'SHIP-5',
    (select jsonb_build_array(
       jsonb_build_object('seq', 1, 'logger_ts', ahead, 'temp_centi_c', 500, 'prev_hash', g, 'hash', h1),
       jsonb_build_object('seq', 2, 'logger_ts', honest, 'temp_centi_c', 500, 'prev_hash', h1,
         'hash', pg_temp.hash('SHIP-5', 'LOG-1', 2, honest, 500, h1))
     )
     from (
       select g, ahead, honest, pg_temp.hash('SHIP-5', 'LOG-1', 1, ahead, 500, g) as h1
       from (
         select pg_temp.head('SHIP-5') as g,
                (extract(epoch from now()) * 1000)::bigint + 3600000 as ahead,
                (extract(epoch from now()) * 1000)::bigint + 1000 as honest
       ) a
     ) b),
    pg_temp.head('SHIP-5'),
    :device_courier::uuid
  ),
  'an honest reading can follow a bad clock within one batch'
);

select pg_temp.act_as(:receiver);
select is(
  public.confirm_handover('SHIP-5', 1, :device_receiver::uuid, pg_temp.head('SHIP-5'), null, 'PASS') ->> 'state',
  'WAITING',
  'a custodian''s confirmation of older history no longer counts'
);
select is(pg_temp.custodian('SHIP-5'), :courier::uuid, 'custody stays with the courier');

select pg_temp.act_as(:courier);
select is(
  public.confirm_handover('SHIP-5', 1, :device_courier::uuid, pg_temp.head('SHIP-5'), :receiver::uuid) ->> 'state',
  'TRANSFERRED',
  'custody moves once the custodian confirms the current history'
);

-- Accounts and roles ------------------------------------------------------------------------------

select pg_temp.act_as(:sender);
select throws_ok(
  $$ select public.add_shipment_member('SHIP-3', 'sender@example.test', 'receiver') $$,
  'CC006', null, 'a sender cannot be the receiver of their own shipment'
);

select pg_temp.act_as(:courier);
select throws_ok(
  $$ select public.create_shipment('SHIP-3', 'vaccine-2-8-freeze-sensitive', 'LOG-1', 'A', 'B') $$,
  'CC002', null, 'only a sender can create a shipment'
);
select throws_ok(
  $$ select public.add_shipment_member('SHIP-1', 'outsider@example.test', 'receiver') $$,
  'CC002', null, 'only the shipment''s sender can add members'
);
select throws_ok(
  $$ update public.profiles set role = 'sender' where user_id = 'aaaaaaaa-0000-0000-0000-000000000002' $$,
  '42501', null, 'a user cannot edit their own role'
);
select throws_ok(
  $$ select public.complete_profile('Courier', 'sender') $$,
  'CC006', null, 'a chosen role cannot be changed'
);
select throws_ok(
  format('select public.register_device(%L, %L)', :device_receiver::uuid, 'key-r'),
  'CC005', null, 'a device registered to someone else cannot be taken over'
);
select throws_ok(
  format('select public.register_device(%L, %L)', :device_courier::uuid, 'new-key'),
  'CC005', null, 'a device cannot change its key'
);
select lives_ok(
  format('select public.register_device(%L, %L)', :device_courier::uuid, 'key-c'),
  'registering the same device again is harmless'
);

-- The manifest behind the genesis hash can't change, even for the database owner.
reset role;
select throws_ok(
  $$ update public.shipments set logger_id = 'LOG-2' where id = 'SHIP-1' $$,
  'CC010', null, 'the shipment manifest is immutable'
);

select * from finish();

rollback;
