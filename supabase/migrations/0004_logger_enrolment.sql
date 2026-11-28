-- 4.3: the logger is enrolled on the shipment when the sender creates it. Its public key (an uncompressed P-256
-- point, `04 || X || Y` in hex, as the logger's GATT public-key characteristic gives it, LOGGER_PROTOCOL.md) and the
-- sequence number its trip starts at are kept with the manifest, so a receiver can check the logger's own signed
-- history against what the sender enrolled, not against whatever key a logger presents at the door.
--
-- Backward compatible with the app pinned for Gate 3 (05b342e): both parameters are optional and the function keeps
-- its name, so a five-argument call still works and enrols nothing. The chain's genesis is unchanged.

alter table public.shipments
  add column logger_public_key text,
  add column logger_start_seq bigint,
  add constraint shipments_logger_key_is_an_uncompressed_point
    check (logger_public_key is null or logger_public_key ~ '^04[0-9a-f]{128}$'),
  add constraint shipments_logger_start_seq_needs_a_key
    check (logger_start_seq is null or (logger_public_key is not null and logger_start_seq >= 0));

-- The key docs/protocol/logger_vectors.json publishes, by its X coordinate only: the point with the same X and the
-- other Y belongs to n - d, which anyone holding d can sign as. The app refuses it too (TestLoggerKey).
create function private.is_published_test_key(p_key text) returns boolean
language sql immutable set search_path = '' as $$
  select substr(p_key, 3, 64) = '6f56e353e87d63e976bf24b09e68063378c5d5611385f9069700c438834d6ef7'
$$;

drop function public.create_shipment(text, text, text, text, text);

create function public.create_shipment(
  p_id text,
  p_product_profile_id text,
  p_logger_id text,
  p_origin text,
  p_destination text,
  p_logger_public_key text default null,
  p_logger_start_seq bigint default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_key text := lower(p_logger_public_key);
  v_shipment public.shipments;
  v_created_ms bigint;
  v_genesis text;
begin
  if not exists (select 1 from public.profiles p where p.user_id = v_uid and p.role = 'sender') then
    raise exception 'Only a sender can create a shipment.' using errcode = 'CC002';
  end if;

  if v_key is not null and v_key !~ '^04[0-9a-f]{128}$' then
    raise exception 'A logger key is an uncompressed P-256 point, in hex.' using errcode = 'CC006';
  end if;
  if v_key is not null and private.is_published_test_key(v_key) then
    raise exception 'This logger uses the published test key, so anyone could sign its history.' using errcode = 'CC006';
  end if;
  if p_logger_start_seq is not null and (v_key is null or p_logger_start_seq < 0) then
    raise exception 'A start sequence needs the logger''s key and can''t be negative.' using errcode = 'CC006';
  end if;

  insert into public.shipments (
    id, sender_id, product_profile_id, logger_id, origin, destination, logger_public_key, logger_start_seq
  )
  values (p_id, v_uid, p_product_profile_id, p_logger_id, p_origin, p_destination, v_key, p_logger_start_seq)
  returning * into v_shipment;

  insert into public.shipment_members (shipment_id, user_id, role) values (p_id, v_uid, 'sender');

  v_created_ms := (extract(epoch from v_shipment.created_at) * 1000)::bigint;
  v_genesis := private.chain_genesis(p_id, v_uid, p_product_profile_id, p_logger_id, v_created_ms);

  insert into public.chain_heads (shipment_id, head_hash) values (p_id, v_genesis);

  return jsonb_build_object('genesis_hash', v_genesis, 'created_at_ms', v_created_ms, 'sender_id', v_uid);
end
$$;

grant execute on function public.create_shipment(text, text, text, text, text, text, bigint) to authenticated;

-- The enrolled logger is part of the manifest: once the shipment exists, nobody changes it.
create or replace function private.protect_shipment_manifest() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (new.id, new.sender_id, new.product_profile_id, new.logger_id, new.created_at,
      new.logger_public_key, new.logger_start_seq)
     is distinct from
     (old.id, old.sender_id, old.product_profile_id, old.logger_id, old.created_at,
      old.logger_public_key, old.logger_start_seq) then
    raise exception 'A shipment''s manifest cannot change.' using errcode = 'CC010';
  end if;
  return new;
end
$$;
