# ADR: Sender-side userspace gateway

Date: 2026-11-15
Status: Proposed; implementation not yet merged

## Problem

NetSwap must forward packets received from an approved receiver peer through the sender phone's currently selected upstream network. A normal Android VPN client is not a gateway: the current app delegates the TUN interface to WireGuard's GoBackend, so the app does not own a packet interface that can be bridged into a second forwarding engine.

Android's supported `VpnService` API provides a TUN file descriptor. Reads yield IP packets routed to the VPN; writes inject return IP packets. The service must protect its own upstream sockets to prevent routing loops. Official references:
- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/develop/connectivity/vpn

## Decision direction

Do not attempt to add root-only `iptables`, `sysctl`, or kernel forwarding commands. They are not available to an ordinary unrooted Android app.

The sender data plane should be a single userspace packet-processing pipeline:

1. A userspace WireGuard engine terminates the sender's peer tunnel.
2. Decrypted inner IP packets are delivered to a userspace IP stack / tun2socks bridge owned by the app (not hidden inside GoBackend).
3. The userspace stack translates TCP and UDP flows into upstream sockets.
4. Every upstream socket is protected with `VpnService.protect(socket)` before connecting, and is bound to the selected Android `Network` where supported.
5. Return bytes are translated back into IP packets and injected into the userspace WireGuard TUN device.
6. A trusted accounting layer counts both directions and blocks new/ongoing flows at quota exhaustion or revocation.

A practical prototype can evaluate a Rust tun2socks stack using smoltcp (the ProxyDroid project is an implementation reference, not a dependency or endorsement): https://github.com/madeye/proxydroid

## Critical integration constraint

The current `com.wireguard.android.backend.GoBackend` owns its TUN descriptor. It does not expose decrypted packet reads to NetSwap's forwarding code. Therefore the current GoBackend path cannot simply be toggled into sender-gateway mode. A sender-gateway prototype needs a custom TUN bridge or a userspace WireGuard engine with a packet-device abstraction. Keep the existing imported-config client mode separate until a replacement passes regression tests.

## Security and quota requirements

- Only create a data-plane peer after the corresponding sharing request is approved and both participant public keys are registered.
- Bind every peer and route to a specific sharing request/session ID.
- Never accept arbitrary destination routes or expose a general unauthenticated proxy.
- Enforce quota in the packet path on both upload and download bytes; do not trust client-reported counters.
- On quota exhaustion, request cancellation, sender stop, VPN revoke, or lost control-plane authorization: stop forwarding immediately, close flow state, and remove the peer.
- Apply per-session rate/connection limits and short-lived provisioning credentials.
- Private keys remain device/server-local; public keys only in Supabase.
- Avoid logging packet payloads, DNS query contents, or private key material.

## Milestone plan

### M1 - Local packet engine proof
- Add a foreground sender-gateway service with an explicit user consent screen.
- Establish a TUN interface with a narrow test route.
- Integrate a userspace packet engine and protected upstream sockets.
- Test one TCP and one UDP flow on a single phone in a controlled lab.
- Demonstrate that the service disconnects cleanly and that no traffic loops into its own VPN.

### M2 - WireGuard bridge
- Replace or wrap the current hidden GoBackend TUN with a userspace WireGuard engine that exposes a packet device.
- Bridge decrypted receiver packets into the userspace stack and inject replies back.
- Test TCP, UDP, DNS, IPv4 fragmentation/MTU, IPv6 policy, network changes, and sleep/wake.

### M3 - Server relay and peer control
- Provision per-session WireGuard peers on the relay only through an authenticated server-side function.
- Add sender and receiver allowed-IP allocation without overlapping sessions.
- Verify peer handshake and bidirectional traffic using test-only destinations.

### M4 - Quota and abuse controls
- Count both directions at the sender gateway and/or trusted relay.
- Enforce hard cutoff atomically at the quota limit.
- Test race conditions, parallel flows, reconnects, replayed provisioning requests, and revoked sessions.

## Explicit non-goals for this milestone

- No global phone hotspot replacement.
- No root access requirement.
- No transparent forwarding of every app until packet routing and Android policy tests pass.
- No production claim of friend-to-friend internet sharing before two physical devices pass all acceptance tests.
