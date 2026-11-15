# BoringTun 0.7.1 JNI integration audit

Date: 2026-11-15
Status: API audited; native integration blocked on lifecycle/error-safety shim.

## Pinned source

- Crate: `boringtun` 0.7.1
- Repository tag: `boringtun-0.7.1`
- Tag object: `56fc417ef7e3c85ca530aa4ddee6e8a646bace55`
- License: BSD-3-Clause
- JNI feature: `jni-bindings` enables `ffi-bindings` and `jni`.
- Library crate types: `staticlib`, `cdylib`, `rlib`.

The inspected tag contains `boringtun/src/jni.rs` with Java exports under the fixed class name `com.cloudflare.app.boringtun.BoringTunJNI`.

## Existing JNI operations

The audited JNI file exports:
- X25519 secret/public key helpers and key encoding helpers.
- `new_tunnel`.
- `wireguard_write` to encrypt inner IP packets.
- `wireguard_read` to decrypt received WireGuard datagrams.
- `wireguard_tick` for timer-driven output.

The packet read/write/tick operations use direct ByteBuffers for output and an operation byte to indicate the underlying result.

## Blockers before adoption

1. The inspected JNI source does not export a tunnel-destroy/close function. A long-lived Android app must not leak native tunnel allocations when users stop sharing, revoke, or switch sessions.
2. Several JNI paths convert byte arrays with `unwrap()`; malformed or unexpected JNI input can panic across the native boundary.
3. Error reporting is weak: some JNI failures return zero/null without a structured error code.
4. JNI exports are tied to a fixed Java package/class name; changing the Kotlin package or obfuscating the bridge without keep rules can break symbol resolution.
5. The library does not supply the Android UDP transport, Android `VpnService` TUN bridge, socket protection, selected-`Network` binding, or TCP/UDP proxy stack.
6. The JNI file itself labels the bindings temporary and says they need further verification.

## NetSwap interface contract

`UserspaceWireGuardEngine` is now the app-side abstraction for:
- encrypting inner IP packets;
- decrypting received WireGuard datagrams;
- running protocol timers;
- releasing native resources through idempotent `close()`.

No implementation is registered and no native library is loaded. The current GoBackend remains the separate imported-config VPN client.

## Required native shim before build integration

- Add a reviewed destroy function that frees the `Tunn` allocation exactly once.
- Replace panic-prone conversions with checked length and pointer validation.
- Return explicit status/error codes for packet, timer, queue, and failure states.
- Validate direct ByteBuffer capacity and position/limit handling.
- Ensure key material is zeroized where feasible and never logged.
- Add JNI keep rules and ABI tests for arm64-v8a.
- Build and test against the pinned tag, not a moving branch.

## Acceptance tests

- Keypair and handshake interoperability against an independent WireGuard peer.
- Encrypt/decrypt round trip for IPv4 packets.
- Timer-driven handshake and keepalive output.
- Close twice without crash; no native memory leak across repeated create/close cycles.
- Reject null, undersized, oversized, and non-direct output buffers safely.
- Stop/revoke while packets and timer callbacks are in flight.
- Android ARM64 physical-device test before any production activation.

## Sources

- https://github.com/cloudflare/boringtun/tree/boringtun-0.7.1
- https://github.com/cloudflare/boringtun/blob/boringtun-0.7.1/boringtun/src/jni.rs
- https://github.com/cloudflare/boringtun/blob/boringtun-0.7.1/boringtun/Cargo.toml
