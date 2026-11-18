-- Keep the request-close trigger callable only by PostgreSQL's trigger machinery.
-- The trigger remains functional, but the function is no longer exposed as an RPC.
create or replace function public.revoke_tunnel_session_for_closed_request()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.status <> 'approved' and old.status is distinct from new.status then
    update public.tunnel_sessions
       set status = 'revoked',
           revoked_at = coalesce(revoked_at, now()),
           updated_at = now()
     where sharing_request_id = new.id
       and status in ('provisioning', 'ready', 'active');
  end if;
  return new;
end;
$$;

revoke all on function public.revoke_tunnel_session_for_closed_request()
  from public, anon, authenticated;
