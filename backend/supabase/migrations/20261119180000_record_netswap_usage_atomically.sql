-- Authenticated, atomic traffic accounting for peer sessions.
create or replace function public.record_netswap_usage(p_request_id uuid, p_bytes bigint)
returns table(used_bytes bigint, quota_bytes bigint, status text)
language plpgsql security definer set search_path = ''
as $$
declare v_request public.sharing_requests%rowtype; v_total bigint;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if p_request_id is null or p_bytes is null or p_bytes <= 0 or p_bytes > 67108864 then
    raise exception 'Usage increment must be between 1 byte and 64 MiB';
  end if;
  select * into v_request from public.sharing_requests where id = p_request_id for update;
  if not found then raise exception 'Sharing request not found'; end if;
  if auth.uid() <> v_request.receiver_id then raise exception 'Only the approved provider can report usage'; end if;
  if v_request.status <> 'approved' then raise exception 'Sharing request is not active'; end if;
  v_total := least(v_request.quota_bytes, v_request.used_bytes + p_bytes);
  update public.sharing_requests set used_bytes = v_total,
    status = case when v_total >= quota_bytes then 'completed' else 'approved' end, updated_at = now()
    where id = p_request_id;
  return query select r.used_bytes, r.quota_bytes, r.status from public.sharing_requests r where r.id = p_request_id;
end; $$;
revoke all on function public.record_netswap_usage(uuid, bigint) from public, anon;
grant execute on function public.record_netswap_usage(uuid, bigint) to authenticated;