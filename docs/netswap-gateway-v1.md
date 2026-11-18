# NetSwap self-hosted gateway v1

This folder documents the gateway deployment target for the NetSwap Android app.

## What v1 contains

- WireGuard UDP hub on `10.77.0.1/24`, listening on UDP 51820.
- NetSwap relay agent that polls the server-managed `tunnel_sessions` table using a service-role key held only on the host.
- Per-session public-key peer reconciliation and removal after the request is revoked.
- A systemd unit with filesystem and process hardening.

The existing peer reconciler is deliberately a **control-plane/peer-management component only**. It is not yet a complete data plane. It does not route a receiver's packets through the sender's phone, implement Android TCP/UDP packet translation, enforce authoritative byte quotas, or make sessions ready/active.

## Install on a host you control

Prerequisites:
- Fresh supported Ubuntu LTS host with a reachable public IPv4 address.
- Administrator SSH access restricted to your own IP.
- UDP 51820 allowed by the host/cloud firewall.
- A NetSwap Supabase project. The service-role key must remain on this host and must never be put in the APK, GitHub, chat, or client-side configuration.
- Confirm the host is genuinely free/eligible before creating it. Never enable paid resources without explicit approval. If the provider requires an adult account holder, a parent/guardian must own and manage it.

From a checkout of this repository, copy the peer agent and service unit to the host, then run:

```bash
sudo install -d -m 700 /opt/netswap /etc/netswap
sudo install -m 700 scripts/netswap-relay-agent.py /opt/netswap/netswap-relay-agent.py
sudo bash scripts/bootstrap-wireguard-relay.sh
sudo install -m 600 deploy/netswap-relay-agent.service /etc/systemd/system/
```

Create `/etc/netswap/relay.env` on the host (never commit it):

```ini
NETSWAP_SUPABASE_URL=https://YOUR_PROJECT.supabase.co
NETSWAP_SUPABASE_SERVICE_ROLE_KEY=YOUR_SERVER_ONLY_SERVICE_ROLE_KEY
NETSWAP_WG_INTERFACE=wg0
NETSWAP_POLL_SECONDS=5
```

Then:

```bash
sudo chmod 600 /etc/netswap/relay.env
sudo systemctl daemon-reload
sudo systemctl enable --now netswap-relay-agent
sudo wg show wg0
sudo systemctl status netswap-relay-agent --no-pager
```

The server's WireGuard public key is in `/etc/wireguard/netswap-server.pub`. The private key stays on the host in `/etc/wireguard/netswap-server.key`.

## Safety and current limitations

- Do not enable IP forwarding or NAT as a shortcut. The product needs a per-approved-session route through the sender Android userspace gateway, not a generic VPN exit.
- Do not write a placeholder public endpoint into `tunnel_sessions`.
- Do not mark sessions `ready` or `active` until handshake, both-way packet flow, quota cutoff, and revocation are verified.
- Keep SSH closed to the public internet where possible; expose only the required UDP port.
- The Android client still needs server public-key/endpoint provisioning and complete sender/receiver packet-path integration.

A real public gateway cannot be started from GitHub alone: a reachable host and its public IP are required. This repository now contains the v1 installation recipe, but no cloud host has been created.
