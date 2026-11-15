# Android userspace networking engine evaluation

Date: 2026-11-15
Status: Evaluation only; no third-party native engine added to the APK yet.

## Requirements

NetSwap needs two distinct data-plane capabilities on Android:

1. A WireGuard implementation that can exchange encrypted UDP datagrams and expose decrypted inner IP packets through an app-owned packet device.
2. A userspace TCP/UDP IP stack or tun2socks engine that can translate those inner packets to upstream sockets. Every upstream socket must be protected with Android `VpnService.protect(socket)` and bound to the chosen Android `Network` where possible.

The current `GoBackend` client integration does not expose its decrypted TUN packets to application code, so it cannot be reused as the sender packet bridge without a different integration layer.

## Candidate review

### Cloudflare BoringTun

- Repository: https://github.com/cloudflare/boringtun
- License: BSD-3-Clause.
- The project README describes a portable userspace WireGuard library, lists Android ARM targets, and documents JNI bindings in `src/jni.rs`.
- Important caveat: the README on the moving master branch warns that the project is undergoing restructuring and recommends using the crates.io release rather than relying on master.
- Assessment: strongest candidate for a native Android WireGuard protocol layer because JNI is documented. Before adoption, pin a published crate version, inspect its JNI API and Android build instructions, and prove it can be driven by a custom packet-device bridge.

### wireguard-go

- Repository mirror: https://github.com/WireGuard/wireguard-go
- License: MIT.
- The README describes a userspace WireGuard implementation and foreground interface model.
- The README does not document an Android JNI interface. Embedding it would require a separately maintained Go/mobile bridge and careful Android socket-protection integration.
- Assessment: viable research fallback, but not the lowest-friction Android integration based on the inspected README.

### xjasonlyu/tun2socks

- Repository: https://github.com/xjasonlyu/tun2socks
- License metadata: MIT.
- The README describes a Go tun2socks engine using the gVisor TCP/IP stack, with proxy and Layer-3 gateway features.
- The README's listed platform support does not explicitly establish an Android app integration or the exact API needed to feed packets from a WireGuard userspace device.
- Assessment: promising TCP/IP forwarding candidate, but Android embedding, socket protection, Network binding, UDP/DNS semantics, and API stability must be proven before dependency adoption.

### eycorsican/go-tun2socks

- Repository: https://github.com/eycorsican/go-tun2socks
- License: MIT.
- Repository metadata marks it archived.
- Assessment: do not select for new development.

## Provisional direction

Prototype BoringTun as the WireGuard protocol engine and evaluate xjasonlyu/tun2socks as a separate IP forwarding engine. Do not assume the two can be connected without an explicit packet-device API. Do not add either as an APK dependency until a minimal Android proof demonstrates:

- ARM64 Android build and load;
- packet injection and packet extraction APIs;
- UDP transport sockets protected with `VpnService.protect`;
- upstream sockets bound to the selected Android `Network`;
- cancellation, close, and error propagation;
- bounded memory and backpressure;
- no packet loops through NetSwap's own VPN.

## Security gates before integration

- Pin immutable release versions and verify checksums/commit IDs.
- Review transitive licenses and native build scripts.
- Never ship private keys in logs, crash reports, or Supabase.
- Bind each packet engine instance to one approved session ID and one quota object.
- Account bytes before forwarding; fail closed on revoke, quota exhaustion, engine failure, or lost authorization.
- Keep IPv6 and fragmented IPv4 blocked until fully supported and tested.
- Test on physical ARM64 Android devices before advertising internet sharing.

## References inspected

- https://github.com/cloudflare/boringtun
- https://github.com/WireGuard/wireguard-go
- https://github.com/xjasonlyu/tun2socks
- https://github.com/eycorsican/go-tun2socks
