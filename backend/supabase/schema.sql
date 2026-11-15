-- NetSwap initial identity and sharing schema
-- Applied to the dedicated NetSwap Supabase project.
-- All exposed tables have RLS enabled. Do not put service-role keys in the Android app.

create schema if not exists private;

create or replace function private.generate_netswap_uid()
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  candidate text;
begin
  loop
    candidate := 'NS-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 10));
    exit when not exists (
      select 1 from public.profiles where uid = candidate
    );
  end loop;
  return candidate;
end;
$$;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  uid text not null unique default private.generate_netswap_uid(),
  display_name text check (display_name is null or char_length(display_name) <= 40),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create or replace function private.handle_new_netswap_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id)
  values (new.id)
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created_netswap on auth.users;
create trigger on_auth_user_created_netswap
  after insert on auth.users
  for each row execute function private.handle_new_netswap_user();

insert into public.profiles (id)
select id from auth.users
on conflict (id) do nothing;

create table if not exists public.sharing_requests (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references public.profiles(id) on delete cascade,
  receiver_id uuid not null references public.profiles(id) on delete cascade,
  quota_bytes bigint not null check (quota_bytes > 0 and quota_bytes <= 1099511627776),
  used_bytes bigint not null default 0 check (used_bytes >= 0),
  status text not null default 'pending'
    check (status in ('pending', 'approved', 'rejected', 'cancelled', 'completed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  responded_at timestamptz,
  constraint sharing_requests_different_users check (sender_id <> receiver_id)
);

create index if not exists sharing_requests_sender_created_idx
  on public.sharing_requests (sender_id, created_at desc);
create index if not exists sharing_requests_receiver_created_idx
  on public.sharing_requests (receiver_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.sharing_requests enable row level security;

drop policy if exists "Users can read own NetSwap profile" on public.profiles;
create policy "Users can read own NetSwap profile"
  on public.profiles for select to authenticated
  using ((select auth.uid()) = id);

drop policy if exists "Users can update own NetSwap display name" on public.profiles;
create policy "Users can update own NetSwap display name"
  on public.profiles for update to authenticated
  using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);

drop policy if exists "Participants can read their sharing requests" on public.sharing_requests;
create policy "Participants can read their sharing requests"
  on public.sharing_requests for select to authenticated
  using ((select auth.uid()) = sender_id or (select auth.uid()) = receiver_id);

drop policy if exists "Users can create sharing requests as sender" on public.sharing_requests;
create policy "Users can create sharing requests as sender"
  on public.sharing_requests for insert to authenticated
  with check (
    (select auth.uid()) = sender_id
    and sender_id <> receiver_id
    and status = 'pending'
    and used_bytes = 0
  );

grant usage on schema public to authenticated;
grant select on public.profiles to authenticated;
grant update (display_name) on public.profiles to authenticated;
grant select, insert on public.sharing_requests to authenticated;
grant usage on schema private to authenticated;

create or replace function private.lookup_netswap_user_by_uid(p_uid text)
returns table (user_id uuid, uid text, display_name text)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.uid, p.display_name
  from public.profiles as p
  where auth.uid() is not null
    and p.id <> auth.uid()
    and p.uid = upper(trim(p_uid))
  limit 1;
$$;

revoke all on function private.lookup_netswap_user_by_uid(text) from public, anon;
grant execute on function private.lookup_netswap_user_by_uid(text) to authenticated;

create or replace function public.lookup_netswap_user_by_uid(p_uid text)
returns table (user_id uuid, uid text, display_name text)
language sql
stable
security invoker
set search_path = ''
as $$
  select * from private.lookup_netswap_user_by_uid(p_uid);
$$;

revoke all on function public.lookup_netswap_user_by_uid(text) from public, anon;
grant execute on function public.lookup_netswap_user_by_uid(text) to authenticated;

revoke all on function private.generate_netswap_uid() from public, anon, authenticated;
revoke all on function private.handle_new_netswap_user() from public, anon, authenticated;
