# NetSwap gateway implementation plan

## Current implementation

- Android app has account authentication, UID lookup, consent-based sharing requests, and request accept/reject/cancel controls.
- Android app now imports and validates a WireGuard client configuration and runs it through the official WireGuard GoBackend.
- The user can disconnect from the app or the persistent VPN notification.
- Supabase now has a `tunnel_sessions` registry. It stores participant IDs, WireGuard public keys, tunnel IP metadata, endpoint metadata, status, and quota counters. RLS lets only the two participants read a session. Clients cannot insert, update, or delete sessions.
- The APK build has passed in GitHub Actions.

## What is still missing

This is not yet the friend-to-friend mobile-data gateway. A WireGuard client profile alone only connects a phone to a configured WireGuard server. It does not make one Android phone forward its cellular internet to another phone.

A real sender gateway needs all of the following:
1. A reachable WireGuard rendezvous/relay endpoint (public IP or a supported NAT traversal path).
2. A sender-side Android packet-forwarding engine that receives the receiver's tunnel packets and safely forwards TCP/UDP traffic through the sender's active cellular/Wi-Fi network.
3. A receiver-side peer tunnel and authenticated peer provisioning.
4. A server-side control API that issues/revokes peer configuration only after an approved sharing request.
5. Byte accounting and quota enforcement in the forwarding engine, not client-reported UI values.
6. Tests on two physical Android devices and multiple carriers.

## Important Android constraint

A normal Android app cannot enable kernel IP forwarding/NAT with root-only system commands. The sender gateway must therefore use a deliberately implemented userspace packet router/proxy, or use an explicitly supported hotspot/tethering route. Do not advertise an approved request as working data sharing until packets are proven to pass through the sender device and quota/revocation tests pass.

## Key handling

- Never store WireGuard private keys in Supabase or commit them to Git.
- Generate private keys on-device or in the gateway and store them in app-private storage.
- Supabase stores public keys and session metadata only.
- Keep gateway admin credentials in server-side environment secrets, never in the APK.
- Reject provisioning unless the matching sharing request is approved and not cancelled/revoked.

## Next engineering milestone

Implement and test a sender-side userspace forwarding prototype on a controlled network. Only after that prototype forwards real TCP and UDP packets should the remote relay and quota enforcement be connected.


## First secure provisioning endpoint deployed

The Supabase Edge Function `register-tunnel-public-key` is deployed with JWT verification enabled. It:

- validates the caller's Supabase session;
- checks that the sharing request exists and is already approved;
- checks that the caller is exactly the sender or receiver;
- accepts only a WireGuard public key (never a private key);
- creates or updates the server-managed tunnel session using the service role only inside the Edge Function;
- returns whether both public keys are registered.

This endpoint is control-plane groundwork only. It does not create a WireGuard interface, configure a peer endpoint, relay packets, or provide internet access. The Android app now generates a WireGuard keypair on-device, encrypts the private key with an Android Keystore AES-GCM key, and calls this endpoint with only the public key for approved requests. This registers key material only; it does not configure peers or route traffic.


## First secure provisioning endpoint deployed

The Supabase Edge Function `register-tunnel-public-key` is deployed with JWT verification enabled. It validates the caller's Supabase session, checks that the sharing request is approved, verifies the caller is exactly the sender or receiver, accepts only a WireGuard public key (never a private key), and creates or updates the server-managed tunnel session using the service role only inside the Edge Function.

This is control-plane groundwork only. It does not create a WireGuard interface, configure a peer endpoint, relay packets, or provide internet access. The Android app does not call it yet; local key generation and authenticated client integration remain the next step.


## Android key-registration milestone

- Added `SecureTunnelKeys`, which creates a WireGuard keypair locally and encrypts the private key using an AES-GCM key held by Android Keystore.
- The encrypted private key is stored in app-private preferences, scoped to the sharing request ID. The private key is never sent to Supabase or written to the repository.
- Added the authenticated Android API call to `register-tunnel-public-key`.
- Approved sharing requests now show a **Prepare secure tunnel key** action. Each participant must run it on their own phone.
- The Android debug APK workflow passed for this milestone.

## Current hard blocker before real packet forwarding

No public UDP WireGuard relay/gateway endpoint is provisioned. The database's `gateway_endpoint` is metadata only. Do not create peer configs with a placeholder endpoint or mark a session ready. The next milestone requires choosing and provisioning a reachable relay/gateway host, then implementing a userspace packet-forwarding data plane with per-session byte counters and revocation.
