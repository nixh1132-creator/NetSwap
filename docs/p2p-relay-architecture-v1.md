# NetSwap connectivity architecture decision

Status: Accepted for implementation planning
Date: 2026-11-19

## Decision

NetSwap should not require a self-hosted WireGuard gateway for phone-to-phone connectivity. Use WebRTC ICE as the peer transport, with direct connectivity preferred and an authenticated TURN relay as fallback.

- Supabase remains the control plane only: authentication, UID lookup, request/approval, session authorization, quota policy, and short-lived signaling exchange.
- WebRTC ICE performs candidate gathering and connectivity checks. Prefer a direct peer connection when possible.
- TURN is the fallback when direct connectivity fails (for example, restrictive carrier NAT or symmetric NAT). STUN alone is not a relay and cannot guarantee reachability.
- Use a WebRTC DataChannel as the encrypted peer transport. Do not send arbitrary raw IP packets through the existing WireGuard UDP bridge.
- Keep a strict session state machine: requested -> approved -> signaling -> connecting -> connected -> revoked/expired. Do not show connected until a DataChannel is open and an authenticated peer handshake succeeds.

## Android data path (required before claiming internet sharing works)

A WebRTC DataChannel only transports bytes; it does not by itself share internet access. The Android data plane still needs to be implemented:

1. Receiver VpnService owns the Android TUN interface.
2. A packet-to-proxy engine (for example, the already vendored/pinned Hev tun2socks component, after its JNI lifecycle is safely integrated) converts receiver TCP/UDP flows into SOCKS5 requests.
3. Those SOCKS5 control/data frames are multiplexed over authenticated WebRTC DataChannels to the sender.
4. Sender demultiplexes streams and opens upstream sockets using Android VpnService protection and the explicitly selected cellular Network.
5. Return traffic is sent back over the matching DataChannel stream and translated into receiver-side IP packets.
6. Enforce quota on actual payload bytes in both directions, per session; close all streams on revoke, expiry, or quota exhaustion.

Do not bind the current loopback SOCKS proxy to 0.0.0.0 as a shortcut. TUN packets are raw IP packets and are not automatically delivered to a TCP/UDP listening socket.

## TURN service and cost

TURN is necessary for dependable fallback across difficult NATs. It is not possible to promise zero cost or universal connectivity without selecting and testing a TURN provider. Prefer short-lived, server-issued TURN credentials; never embed a permanent TURN secret in the APK. Provider credentials must be minted by a trusted backend and protected from clients.

The first implementation should use a provider-neutral ICE configuration interface. A provider can be selected later based on verified free allowance, regional latency, privacy terms, and pricing. If no TURN credentials are configured, direct P2P may work on some networks, but the UI must report that fallback is unavailable.

## Security requirements

- Authenticate each peer using the approved NetSwap session and a per-session cryptographic handshake; do not trust a DataChannel merely because its UID was exchanged.
- Bind signaling records to the approved sender, receiver, and session; enforce RLS and expiry; never expose Supabase service-role keys.
- Issue TURN credentials with short TTL and per-session scope where supported.
- Reject duplicate, stale, or cross-session signaling messages.
- Apply backpressure and bounded buffers to DataChannels; enforce byte quotas before forwarding.
- Revoke closes the DataChannel, upstream sockets, proxy streams, and TUN service.
- Never report a session as connected until an end-to-end health check passes.

## Migration from the current prototype

The existing WireGuard gateway/UDP/TUN bridge is a prototype and is not the selected phone-to-phone transport. Keep it isolated until a complete data path is demonstrated. The existing STUN endpoint discovery helper is diagnostic only; it does not provide ICE, peer signaling, hole punching, or relay fallback.

Before implementation, inspect the current Android Gradle setup and native Hev API, then introduce transport/data-plane interfaces behind tests. Implement in small stages: (a) signaling + WebRTC DataChannel loopback test, (b) authenticated peer stream framing, (c) sender SOCKS5 stream adapter, (d) receiver TUN-to-SOCKS adapter, (e) quota/revocation integration, (f) two-physical-phone tests on separate carrier networks and restrictive NAT. Only after all stages pass should the app advertise internet sharing as working.
