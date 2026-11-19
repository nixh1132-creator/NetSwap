-- Return sharing requests with the other participant's public display name.
-- SECURITY DEFINER is scoped to the authenticated caller's own requests only.
create or replace function public.list_netswap_requests()
returns table (
  id uuid,
  sender_id uuid,
  receiver_id uuid,
  quota_bytes bigint,
  used_bytes bigint,
  status text,
  created_at timestamptz,
  sender_name text,
  receiver_name text
)
language sql
stable
security definer
set search_path = ''
as $$
  select r.id, r.sender_id, r.receiver_id, r.quota_bytes, r.used_bytes,
         r.status, r.created_at,
         coalesce(nullif(ps.display_name, ''), 'NetSwap user'),
         coalesce(nullif(pr.display_name, ''), 'NetSwap user')
  from public.sharing_requests r
  left join public.profiles ps on ps.id = r.sender_id
  left join public.profiles pr on pr.id = r.receiver_id
  where auth.uid() is not null
    and (auth.uid() = r.sender_id or auth.uid() = r.receiver_id)
  order by r.created_at desc;
$$;

revoke all on function public.list_netswap_requests() from public, anon;
grant execute on function public.list_netswap_requests() to authenticated;
