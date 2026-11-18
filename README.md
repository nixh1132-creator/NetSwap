# NetSwap

NetSwap is an Android app for permission-based internet sharing between users identified by unique UIDs.

## Product goal

A sender approves a specific receiver UID and grants a defined data allowance. The receiver should use the sender's internet connection through a secure, routed connection. Sharing must be revocable and limited by gateway-measured usage.

## Current implementation status

### Implemented

- Android account/session handling and server-generated NetSwap UIDs.
- Exact UID lookup and consent-based sharing requests.
- Receiver approval/rejection and sender cancellation.
- Supabase RLS-protected profiles, requests, and server-managed tunnel-session metadata.
- Device-local WireGuard key generation; private keys are encrypted with Android Keystore and public keys are registered through an authenticated Edge Function.
- Unique tunnel-IP allocation RPC and a relay peer reconciler that installs/removes WireGuard peers on a controlled Ubuntu hub.
- A userspace WireGuard engine, Android TUN packet device, protected UDP transport, IPv4 packet inspection, and bidirectional local quota-gate primitives.
- Sender-side protected upstream sockets and an authenticated loopback SOCKS5 TCP/UDP runtime prototype.
- Android, Rust, relay-agent, and packet/quota unit tests in GitHub Actions.

## Gateway v1 scaffold added\n\n- Added `docs/netswap-gateway-v1.md` with the NetSwap-specific Ubuntu WireGuard gateway layout, secure server-only credential handling, and install/verification steps.\n- Added `scripts/install-netswap-gateway.sh` to prepare the host-side WireGuard service and relay-agent systemd unit.\n- No public host has been provisioned. The gateway is not online, and this scaffold does not yet forward receiver traffic through the sender phone.\n\n## Not yet end-to-end internet sharing

The current build is still a prototype. A sender SOCKS5 runtime exists, but it is not yet connected to a complete TUN-to-SOCKS packet translation path. The receiver-to-sender-to-internet packet route has not been proven on two physical phones.

A reachable WireGuard relay endpoint is also not provisioned. The relay reconciler is peer-only: it does not enable IP forwarding/NAT, bridge receiver traffic to the sender, count authoritative traffic, enforce a hard server-side quota, or mark sessions ready/active.

Do not treat an approved request, registered keys, installed hub peers, or a running VPN as proof that mobile internet is being shared.

## Planned architecture

1. **Android client:** account, UID, friend lookup, consent, connection controls, usage display, and explicit stop/revoke.
2. **Supabase control plane:** authentication, profiles, sharing grants, public-key registration, and session metadata. Exposed tables use RLS; privileged gateway actions stay server-side.
3. **WireGuard relay:** reachable UDP endpoint, isolated peers per approved session, and revocation.
4. **Sender gateway:** userspace TUN-to-SOCKS/IP-stack bridge, protected sockets bound to the selected upstream network, and return-packet routing.
5. **Trusted quota enforcement:** atomic accounting of both traffic directions and immediate cutoff at quota exhaustion or revocation.

## Next milestones

- [x] Account, UID, request, and consent workflow.
- [x] Local WireGuard key generation and authenticated public-key registration.
- [x] Userspace WireGuard and packet-gate prototype.
- [x] Sender loopback SOCKS5 runtime prototype.
- [ ] Complete the sender TUN-to-SOCKS packet bridge and return path.
- [ ] Provision a reachable, eligible free relay endpoint (no paid resources without explicit approval).
- [ ] Securely provision per-session relay peers and verify a WireGuard handshake.
- [ ] Enforce quotas and revocation in the actual forwarding path.
- [ ] Test TCP, UDP, DNS, quota exhaustion, disconnect, sleep/wake, and network changes on two physical Android devices.

## Important constraints

- A UID is an identifier, never an authentication secret.
- Never ship Supabase service-role keys or WireGuard private keys in the APK or repository.
- An ordinary unrooted Android app cannot rely on kernel `iptables`, `sysctl`, or IP forwarding commands.
- Mobile carrier tethering policies, Android background execution, battery use, and data costs must be considered.
- Do not promise unlimited or reliable remote sharing until real multi-carrier tests pass.
