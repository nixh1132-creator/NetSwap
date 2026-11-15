# Packet forwarding gate prototype

Date: 2026-11-15
Status: Unit-tested prototype; not connected to Android TUN, WireGuard, sockets, or relay.

## What exists

`PacketForwardingGate` is a fail-closed boundary around three operations:

1. Validate an IPv4 packet with `Ipv4PacketInspector`.
2. Reject fragmented packets until fragment-safe handling is implemented.
3. Reserve the declared IPv4 packet length against the shared bidirectional `SessionTrafficQuota` before passing a bounded packet copy to a supplied `PacketSink`.

A revoked or exhausted quota prevents subsequent packets from reaching the sink. Sink failure is conservatively charged because the gate cannot prove that no bytes escaped the sink.

## Tests

- Only the IPv4 declared total length is handed to the sink; trailing buffer bytes are excluded.
- Malformed and fragmented packets never reach the sink.
- Quota is reserved before the sink call and packets that exceed the remaining budget are rejected.
- Failed sink sends remain accounted.
- Explicit revocation blocks later packets.
- Concurrent quota reservations across both directions cannot exceed the shared limit.

## Security and correctness limits

- This is not a packet router, TCP/UDP proxy, NAT implementation, or WireGuard engine.
- The supplied sink must not be treated as a network transport until its semantics and partial-write behavior are reviewed.
- Accounting currently uses IPv4 total length, not physical cellular billing bytes. Layer overhead and retransmissions may differ from carrier-metered usage.
- Fragmented IPv4 is rejected. IPv6 is not supported by the current inspector.
- The gate does not authenticate sessions itself; its caller must bind it to a server-authorized session and revoke it on every control-plane closure.
- It is not integrated into `NetSwapVpnService`. The current WireGuard `GoBackend` owns its TUN descriptor and does not expose decrypted packet reads to this gate.

## Next integration requirements

1. Select and audit a userspace WireGuard engine that exposes a packet-device/TUN abstraction on Android.
2. Select and audit a userspace TCP/UDP forwarding stack.
3. Ensure upstream sockets call `VpnService.protect(socket)` and bind to the selected Android network.
4. Bridge decrypted packets through this gate and inject replies into the WireGuard packet device.
5. Test TCP, UDP, DNS, MTU, sleep/wake, network changes, revocation races, and quota cutoff on physical devices.

Do not advertise live friend-to-friend internet sharing until those tests pass.
