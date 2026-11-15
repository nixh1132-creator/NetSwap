-- Revocation safety: close the tunnel session whenever its sharing request
-- leaves the approved state.
create or replace function public.revoke_tunnel_session_for_closed_request()
returns trigger
language plpgsql
security definer
set search_path = public
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

drop trigger if exists trg_revoke_tunnel_on_request_close on public.sharing_requests;
create trigger trg_revoke_tunnel_on_request_close
after update of status on public.sharing_requests
for each row
execute function public.revoke_tunnel_session_for_closed_request();

update public.tunnel_sessions ts
   set status = 'revoked',
       revoked_at = coalesce(ts.revoked_at, now()),
       updated_at = now()
  from public.sharing_requests sr
 where sr.id = ts.sharing_request_id
   and sr.status <> 'approved'
   and ts.status in ('provisioning', 'ready', 'active');
