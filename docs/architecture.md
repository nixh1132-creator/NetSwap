# NetSwap architecture

## Product rule

Every account receives a server-generated UID such as `NS-XXXXXXXXXX`. A user can look up a friend by exact UID and request a defined allowance. The receiver must explicitly approve before any tunnel can carry traffic. A grant is limited to the approved receiver and quota and can be revoked.

## System boundaries

- **Android app (Kotlin):** sign-in, UID display, exact-UID lookup, send/receive requests, consent UI, VPN permission flow, connection state, usage display, and stop/revoke controls.
- **Supabase control plane:** authentication, profiles, UID lookup, sharing requests, consent state, and non-authoritative usage summaries.
- **Networking data plane:** a separate service responsible for secure tunnel setup, peer/relay coordination, sender-side packet forwarding and NAT, traffic accounting, quota enforcement, and disconnecting at quota/revocation.
- **Relay infrastructure:** only when direct peer connectivity fails due to NAT/firewalls/carrier network behavior.

## Security rules

1. UID is an identifier, never a password or bearer credential.
2. Only authenticated users may look up a UID.
3. A user may read only their own profile and sharing requests in which they participate.
4. A receiver must approve a pending request before the data plane issues connection credentials.
5. The data plane, not the Android UI, is authoritative for bytes transferred and quota enforcement.
6. Never ship Supabase service-role/secret keys or WireGuard private keys in the APK.
7. All public database tables use RLS; privileged networking actions must be authorized server-side.

## Milestones

1. [x] Create dedicated GitHub repository and project documentation.
2. [x] Create initial Supabase profile and sharing-request tables with RLS.
3. [ ] Add safe receiver approval/rejection and sender cancellation actions.
4. [ ] Build Kotlin Android app shell and authentication.
5. [ ] Add UID profile display and exact UID lookup.
6. [ ] Add sharing request UI and consent workflow.
7. [ ] Prototype nearby traffic forwarding on two physical phones.
8. [ ] Prototype remote tunnel and relay path.
9. [ ] Enforce traffic quotas in the networking data plane.
10. [ ] Test carrier compatibility, battery behavior, privacy, and failure recovery.

## Current limitations

The current database is only the control-plane foundation. It does not create a VPN tunnel, forward packets, make one phone an internet gateway, or guarantee that a mobile carrier permits tethering. A working remote-sharing prototype still needs Android VPN/tethering engineering and a tested gateway/relay service.
