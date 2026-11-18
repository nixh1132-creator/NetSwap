-- Public identity of the WireGuard hub. Private key remains on the gateway host.
alter table public.tunnel_sessions
  add column if not exists gateway_public_key text;

comment on column public.tunnel_sessions.gateway_public_key is
  'WireGuard hub public key only; configured by trusted gateway administration, never by Android clients.';
