-- Short-lived SDP/ICE signaling only. No user internet traffic is stored here.
create table if not exists public.netswap_signals (
    id uuid primary key default gen_random_uuid(),
    request_id uuid not null references public.sharing_requests(id) on delete cascade,
    from_user_id uuid not null references auth.users(id) on delete cascade,
    to_user_id uuid not null references auth.users(id) on delete cascade,
    message_type text not null check (message_type in ('offer', 'answer', 'ice', 'hangup')),
    payload jsonb not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '5 minutes'),
    constraint netswap_signals_distinct_peers check (from_user_id <> to_user_id),
    constraint netswap_signals_payload_size check (pg_column_size(payload) <= 12288),
    constraint netswap_signals_expiry_window check (
        expires_at > created_at and expires_at <= created_at + interval '10 minutes'
    )
);

create index if not exists netswap_signals_recipient_pending_idx
    on public.netswap_signals (to_user_id, request_id, created_at)
    where expires_at > now();

alter table public.netswap_signals enable row level security;
revoke all on public.netswap_signals from anon, authenticated;
grant select, insert on public.netswap_signals to authenticated;

drop policy if exists netswap_signals_participant_read on public.netswap_signals;
create policy netswap_signals_participant_read
on public.netswap_signals
for select to authenticated
using (
    auth.uid() = to_user_id
    and expires_at > now()
    and exists (
        select 1 from public.sharing_requests r
        where r.id = request_id
          and r.status = 'approved'
          and auth.uid() in (r.sender_id, r.receiver_id)
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
    auth.uid() = from_user_id
    and expires_at > now()
    and exists (
        select 1 from public.sharing_requests r
        where r.id = request_id
          and r.status = 'approved'
          and auth.uid() in (r.sender_id, r.receiver_id)
          and to_user_id in (r.sender_id, r.receiver_id)
          and from_user_id in (r.sender_id, r.receiver_id)
          and from_user_id <> to_user_id
    )
);

comment on table public.netswap_signals is
    'Short-lived WebRTC SDP/ICE signaling metadata for approved NetSwap peers; never user data traffic.';
