-- Tune signaling lookups and avoid per-row JWT helper evaluation in RLS.
create index if not exists netswap_signals_request_id_idx
    on public.netswap_signals (request_id);
create index if not exists netswap_signals_from_user_id_idx
    on public.netswap_signals (from_user_id);

drop policy if exists netswap_signals_participant_read on public.netswap_signals;
create policy netswap_signals_participant_read
on public.netswap_signals
for select to authenticated
using (
    (select auth.uid()) = to_user_id
    and expires_at > now()
    and exists (
        select 1 from public.sharing_requests r
        where r.id = request_id
          and r.status = 'approved'
          and (select auth.uid()) in (r.sender_id, r.receiver_id)
          and from_user_id in (r.sender_id, r.receiver_id)
          and to_user_id in (r.sender_id, r.receiver_id)
          and from_user_id <> to_user_id
    )
);

drop policy if exists netswap_signals_approved_peer_insert on public.netswap_signals;
create policy netswap_signals_approved_peer_insert
on public.netswap_signals
for insert to authenticated
with check (
    (select auth.uid()) = from_user_id
    and expires_at > now()
    and exists (
        select 1 from public.sharing_requests r
        where r.id = request_id
          and r.status = 'approved'
          and (select auth.uid()) in (r.sender_id, r.receiver_id)
          and to_user_id in (r.sender_id, r.receiver_id)
          and from_user_id in (r.sender_id, r.receiver_id)
          and from_user_id <> to_user_id
    )
);
