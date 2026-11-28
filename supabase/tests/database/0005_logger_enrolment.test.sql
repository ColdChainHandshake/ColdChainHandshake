-- 4.3: a shipment records its logger's public key and the sequence number its trip starts at, so the receiver can
-- check the logger's own signed history against what the sender enrolled. The key the protocol's test vectors
-- publish is refused: anyone can sign as it. The app pinned for Gate 3 (05b342e) creates shipments without either,
-- so both stay optional.

begin;

create extension if not exists pgtap with schema extensions;

select plan(14);

\set sender  '''cccccccc-0000-0000-0000-000000000001'''
\set courier '''cccccccc-0000-0000-0000-000000000002'''

-- P-256's generator point: a valid key that isn't the test key.
\set real_key '''046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5'''
-- docs/protocol/logger_vectors.json: public_key_uncompressed, and its mirror (same X, Y = p - Y), which anyone with
-- the published private key d can sign as too (with n - d).
\set test_key '''046f56e353e87d63e976bf24b09e68063378c5d5611385f9069700c438834d6ef7ed15fd6fc81393ebdaa001840f2b7cd640fbd417cb169b50edc20376b509df62'''
\set test_key_mirror '''046f56e353e87d63e976bf24b09e68063378c5d5611385f9069700c438834d6ef712ea028f37ec6c15255ffe7bf0d48329bf042be934e964af123dfc894af6209d'''

insert into auth.users (id, email) values
  (:sender,  'sender-43@example.test'),
  (:courier, 'courier-43@example.test');

insert into public.profiles (user_id, display_name, role) values
  (:sender,  'Sender',  'sender'),
  (:courier, 'Courier', 'courier');

create function pg_temp.act_as(p_user text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
end $$;
grant execute on function pg_temp.act_as(text) to authenticated, postgres;

create function pg_temp.enrolled(p_shipment text) returns text
language sql security definer set search_path = pg_catalog, public as
$$ select coalesce(logger_public_key, '-') || ' ' || coalesce(logger_start_seq::text, '-')
   from public.shipments where id = p_shipment $$;
grant execute on function pg_temp.enrolled(text) to authenticated;

set local role authenticated;
select pg_temp.act_as(:sender);

-- Recorded at creation -------------------------------------------------------------------------------

select ok(
  (public.create_shipment('SHIP-43A', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B', :real_key, 120)
     ->> 'genesis_hash') is not null,
  'a sender can create a shipment with its logger''s key and start sequence'
);
select is(pg_temp.enrolled('SHIP-43A'), :real_key || ' 120', 'the key and start sequence are stored as given');

select ok(
  (public.create_shipment('SHIP-43B', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B') ->> 'genesis_hash') is not null,
  'a shipment can still be created without them, as the pinned 05b342e app does'
);
select is(pg_temp.enrolled('SHIP-43B'), '- -', 'without them nothing is enrolled');

select is(
  public.create_shipment('SHIP-43C', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B', upper(:real_key), 0) ->> 'sender_id',
  :sender,
  'a key in capitals is accepted'
);
select is(pg_temp.enrolled('SHIP-43C'), :real_key || ' 0', 'and stored in lower case');

-- Refused ---------------------------------------------------------------------------------------------

select throws_ok(
  format($$ select public.create_shipment('SHIP-43D', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B', %L, 1) $$, :test_key),
  'CC006', null, 'the published test key is refused'
);
select throws_ok(
  format($$ select public.create_shipment('SHIP-43E', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B', %L, 1) $$, :test_key_mirror),
  'CC006', null, 'its mirror point is refused too'
);
select throws_ok(
  $$ select public.create_shipment('SHIP-43F', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B', '04abcd', 1) $$,
  'CC006', null, 'a key that isn''t an uncompressed P-256 point is refused'
);
select throws_ok(
  format($$ select public.create_shipment('SHIP-43G', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B', %L, -1) $$, :real_key),
  'CC006', null, 'a negative start sequence is refused'
);
select throws_ok(
  format($$ select public.create_shipment('SHIP-43H', 'vaccine-2-8-freeze-sensitive', 'LOG-43', 'A', 'B', null, 5) $$),
  'CC006', null, 'a start sequence without a key is refused'
);
select is_empty(
  $$ select id from public.shipments where id in ('SHIP-43D', 'SHIP-43E', 'SHIP-43F', 'SHIP-43G', 'SHIP-43H') $$,
  'nothing refused was created'
);

-- Locked once dispatched ------------------------------------------------------------------------------

select lives_ok($$ select public.dispatch_shipment('SHIP-43A') $$, 'the sender dispatches it');

reset role;
select throws_ok(
  format($$ update public.shipments set logger_public_key = %L where id = 'SHIP-43A' $$, :test_key),
  'CC010', null, 'the enrolled key is part of the manifest and cannot change, even for the database owner'
);

select * from finish();
rollback;
