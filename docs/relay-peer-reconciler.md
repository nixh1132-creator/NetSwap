# NetSwap relay peer reconciler

## Scope

`scripts/netswap-relay-agent.py` is the next control-plane/data-plane bridge. On a trusted Ubuntu relay with an already-installed empty `wg0`, it:

- polls server-managed tunnel sessions over HTTPS;
- allocates two unique addresses from `10.77.0.0/24` through a service-role-only database RPC;
- adds the sender and receiver public keys to the relay's WireGuard interface with one /32 AllowedIP each;
- removes peer keys when the session is revoked, expired, or failed.

It intentionally does **not** enable IP forwarding, add NAT, route receiver traffic through sender, count bytes, enforce quota, or mark a session ready/active. A peer being present on the hub is not proof of an end-to-end handshake or internet access.

## Database migration

Apply `backend/supabase/migrations/20261115071000_allocate_tunnel_peer_ips.sql`. The function uses a transaction advisory lock, checks the parent request is approved and both public keys exist, then allocates two unused addresses. Only `service_role` can execute it.

## Configure the relay

Keep the Supabase service-role key out of Git, Android, chat, and shell history. Put it in a root-readable environment file such as `/etc/netswap/relay.env` (mode 600):

```ini
NETSWAP_SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
NETSWAP_SUPABASE_SERVICE_ROLE_KEY=YOUR_SERVICE_ROLE_KEY
NETSWAP_WG_INTERFACE=wg0
NETSWAP_POLL_SECONDS=5
```

Install Python 3 and wireguard-tools. Run:

```bash
sudo install -d -m 700 /etc/netswap
sudo chmod 600 /etc/netswap/relay.env
sudo bash -c 'set -a; . /etc/netswap/relay.env; set +a; python3 scripts/netswap-relay-agent.py'
```

For persistent operation, use a systemd unit with `EnvironmentFile=/etc/netswap/relay.env`, `User=root`, `NoNewPrivileges=true`, `ProtectSystem=strict`, and only the required writable paths. Do not start it until the Ubuntu VM and WireGuard interface are provisioned.

## Important safety boundary

- This agent only reconciles WireGuard peer entries.
- It does not install or expose an unauthenticated proxy.
- It does not set `net.ipv4.ip_forward` or `net.ipv6.conf.all.forwarding`.
- It does not add firewall/NAT rules.
- It does not mark sessions `ready` or `active`.
- It does not implement hard byte quota. Those controls must be implemented and tested in the actual forwarding path before sharing can be advertised.

## Validation plan

On a disposable test relay, first verify address allocation is unique under concurrent requests, then inspect `sudo wg show wg0` for exactly the expected two peers. Cancel the sharing request and verify the database trigger changes the session to `revoked`; the next agent poll should remove both peers. Do not test with real user traffic until sender-side forwarding, quota, and abuse controls are ready.
