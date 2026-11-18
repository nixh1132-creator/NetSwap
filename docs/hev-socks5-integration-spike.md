# HevSocks5Tunnel integration spike

Date: 2026-11-18
Status: Candidate selected for a bounded native-build/API spike; not approved for production integration.

## Why this candidate

The upstream `heiher/hev-socks5-tunnel` project documents Android NDK builds, a shared/static library build, IPv4 and IPv6, TCP and UDP forwarding, and an MIT licence. It is a smaller Android-native integration target than embedding a general Go/gVisor service.

Upstream:
- https://github.com/heiher/hev-socks5-tunnel
- README Android build: clone recursively and run `ndk-build`.
- README library build: `make shared` or `make static`.
- README SOCKS5 configuration: local/remote SOCKS5 address and port, UDP mode, optional authentication.

## Important mismatch with NetSwap

Hev is a TUN-to-SOCKS5 engine. It is not a direct arbitrary-socket dialer and it does not itself provide the sender-side SOCKS5 server. NetSwap would need a loopback-only SOCKS5 server whose TCP and UDP outbound sockets are created through `ProtectedUpstreamSockets`, protected before connect, and bound to the sender's selected cellular `Network`.

The current NetSwap TUN descriptor also cannot simply be handed to Hev while `PacketTunnelBridge` independently reads the same descriptor. Two concurrent readers would race for packets. A packet ownership/dispatch design must be implemented first; do not duplicate the descriptor and assume that creates independent packet queues.

## Proposed safe integration shape

1. Keep the current WireGuard engine as the sole owner of the Android VPN TUN read path.
2. Add a packet-device adapter boundary between decrypted WireGuard inner packets and a userspace TCP/IP engine.
3. For the Hev spike, determine whether the engine can be driven through a dedicated TUN FD without competing with the WireGuard packet reader. If not, stop and do not integrate it into the existing descriptor.
4. Implement a loopback-only SOCKS5 server only after the packet boundary is proven. It must reject non-loopback clients, require a per-session random credential, support TCP CONNECT and UDP ASSOCIATE, and use only protected, cellular-network-bound upstream sockets.
5. Place quota accounting at the packet/flow boundary in both directions; a proxy byte counter alone must not replace the existing session quota gate.
6. Keep IPv6, fragments, and unsupported UDP cases blocked until explicit tests pass.

## Pinned JNI API audit (2026-11-18)

The pinned source at `e3839498a821b45d2dd19e0603e5894355a32d29` exposes the following Android JNI surface in `src/hev-jni.c`:

- `TProxyStartService(String configPath, int tunFd): boolean` starts a native worker and blocks that worker inside `hev_socks5_tunnel_main(configPath, tunFd)`.
- `TProxyStopService(): boolean` calls `hev_socks5_tunnel_quit()` and joins the worker.
- `TProxyIsRunning(): boolean` reports native worker state.
- `TProxyGetStats(): long[]` returns transmitted packet/byte and received packet/byte counters.
- The JNI registration defaults to Java class `hev.htproxy.TProxyService` (compile-time `PKGNAME` / `CLSNAME` overrides exist).

The native entry point accepts a single file descriptor and runs until stopped. This confirms that merely packaging `libhev-socks5-tunnel.so` does not start it; NetSwap still needs a correctly registered Kotlin/JNI wrapper, a valid config, and a packet-preserving descriptor adapter.

**Descriptor ownership remains the blocker.** Passing the Android VPN TUN fd directly to Hev while `TunPacketPump` reads it would create competing readers and lose packets. The next experiment should test a dedicated packet-preserving local descriptor pair (for example, a datagram socketpair) between the WireGuard packet pump and Hev, and prove that Hev's Android tunnel backend accepts it without requiring TUN-specific ioctls. This is an experiment only; do not enable it in the app until packet boundaries, bidirectional delivery, cancellation, and descriptor lifetime are tested.

## Spike acceptance gates

- Pin an immutable upstream commit and vendor the MIT licence/copyright notices.
- Build the native library for Android `arm64-v8a` in CI with the project's NDK.
- Verify the exact native start/stop API and ownership/duplication semantics for the TUN file descriptor.
- Demonstrate a single TCP and UDP test flow through a loopback SOCKS5 server.
- Prove every outbound socket is protected before connect and bound to the chosen cellular `Network`.
- Prove no concurrent TUN readers, no routing loop, bounded buffers, cancellation, and clean close.
- Verify bidirectional quota cutoff and immediate revoke behavior.

## Go / no-go

**Go** only for the bounded native-build/API spike. **No-go** for adding Hev to the production APK or enabling a default route until all acceptance gates pass. The current app remains a test scaffold and does not provide internet sharing.
