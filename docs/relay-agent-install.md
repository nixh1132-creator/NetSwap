# NetSwap relay systemd installation

This unit is for the peer-only reconciler. It does not enable IP forwarding or provide mobile internet sharing.

1. Provision a trusted Ubuntu LTS VM and complete the empty WireGuard hub bootstrap.
2. Install Python 3 and copy `scripts/netswap-relay-agent.py` to `/opt/netswap/netswap-relay-agent.py`.
3. Create `/etc/netswap/relay.env` owned by root with mode 600. It must contain:
   - `NETSWAP_SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co`
   - `NETSWAP_SUPABASE_SERVICE_ROLE_KEY=...`
   - `NETSWAP_WG_INTERFACE=wg0`
   - `NETSWAP_POLL_SECONDS=5`
4. Copy `deploy/netswap-relay-agent.service` to `/etc/systemd/system/`.
5. Run `sudo systemctl daemon-reload`, inspect the unit and environment file, then enable/start only after confirming the VM and wg0 interface are yours and trusted.
6. Inspect `sudo journalctl -u netswap-relay-agent -f` and `sudo wg show wg0`.

Never put the service-role key in Git, APK, screenshots, chat, or command-line arguments. Keep SSH restricted. This unit is intentionally root because WireGuard peer management requires netlink privileges; hardening options reduce, but do not eliminate, the risk of a service-role credential compromise. Prefer a dedicated relay VM and rotate the service-role key if exposure is suspected.

Do not enable forwarding/NAT or tell users that data sharing works until the sender packet bridge and quota enforcement are implemented and tested.
