-- Allocate unique per-session peer IPs transactionally for the relay agent.
-- This only assigns addresses; it does not enable routing or mark sessions ready.
create or replace function public.allocate_tunnel_peer_ips(p_session_id uuid)
returns table(sender_ip inet, receiver_ip inet)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_session public.tunnel_sessions%rowtype;
  v_sender inet;
  v_receiver inet;
begin
  perform pg_advisory_xact_lock(hashtext('netswap_tunnel_ip_allocator'));

  select * into v_session
    from public.tunnel_sessions
   where id = p_session_id
   for update;

  if not found then raise exception 'Tunnel session not found'; end if;
  if v_session.status <> 'provisioning' then raise exception 'Tunnel session is not provisioning'; end if;
  if v_session.sender_public_key is null or v_session.receiver_public_key is null then
    raise exception 'Both participant public keys must be registered';
  end if;
  if not exists (select 1 from public.sharing_requests where id = v_session.sharing_request_id and status = 'approved') then
    raise exception 'Sharing request is not approved';
  end if;

  if v_session.sender_tunnel_ip is not null and v_session.receiver_tunnel_ip is not null then
    return query select v_session.sender_tunnel_ip, v_session.receiver_tunnel_ip;
    return;
  end if;

  select ('10.77.0.' || n)::inet into v_sender
    from generate_series(2,253) as gs(n)
   where not exists (
     select 1 from public.tunnel_sessions t
      where t.id <> p_session_id
        and t.status in ('provisioning','ready','active')
        and (t.sender_tunnel_ip = ('10.77.0.' || n)::inet
          or t.receiver_tunnel_ip = ('10.77.0.' || n)::inet)
   )
   order by n limit 1;
  if v_sender is null then raise exception 'No free NetSwap relay addresses'; end if;

  select ('10.77.0.' || n)::inet into v_receiver
    from generate_series(2,253) as gs(n)
   where ('10.77.0.' || n)::inet <> v_sender
     and not exists (
       select 1 from public.tunnel_sessions t
        where t.id <> p_session_id
          and t.status in ('provisioning','ready','active')
          and (t.sender_tunnel_ip = ('10.77.0.' || n)::inet
            or t.receiver_tunnel_ip = ('10.77.0.' || n)::inet)
     )
   order by n limit 1;
  if v_receiver is null then raise exception 'No second free NetSwap relay address'; end if;

  update public.tunnel_sessions
     set sender_tunnel_ip = v_sender, receiver_tunnel_ip = v_receiver, updated_at = now()
   where id = p_session_id;
  return query select v_sender, v_receiver;
end;
$$;

revoke all on function public.allocate_tunnel_peer_ips(uuid) from public, anon, authenticated;
grant execute on function public.allocate_tunnel_peer_ips(uuid) to service_role;
