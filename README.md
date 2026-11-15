# NetSwap

NetSwap is an Android app concept for permission-based internet sharing between users identified by unique UIDs.

## Product goal

A sender can approve a specific receiver UID and grant a defined data allowance. The receiver can use the sender's internet connection through a secure, routed connection. Sharing must be revocable and limited by measured usage.

## Important technical constraints

- A UID identifies an account; it is not an authentication secret or a network tunnel.
- Android's `VpnService` can create a local TUN interface, but it does not by itself make the phone a gateway for another user's traffic.
- Remote sharing requires a secure tunnel, sender-side packet forwarding/NAT, routing, and often relay infrastructure for NAT/firewall traversal.
- Mobile carrier terms, tethering restrictions, Android background execution, battery use, and data costs must be considered.
- Data quotas must be enforced by the traffic gateway, not only by a client-side counter.
- Never promise unlimited or reliable remote sharing until it has been tested across real mobile networks.

## Planned architecture

1. **Android client (Kotlin):** onboarding, account, UID, friend lookup, sharing requests, consent, usage display, and connection controls.
2. **Identity/control plane (Supabase):** authentication, profiles, UID mapping, sharing grants, device/session metadata, and usage summaries. Enable Row Level Security on exposed tables.
3. **Data plane (separate networking service):** WireGuard-based secure transport or another reviewed tunnel design, sender gateway packet forwarding/NAT, relay/STUN/TURN as required, and server-side quota enforcement.
4. **Operations:** explicit consent, revoke/stop controls, abuse prevention, logs with minimal personal data, and clear data-cost warnings.

## MVP milestones

- [ ] Confirm product rules and threat model.
- [ ] Create Android project skeleton and build a basic APK.
- [ ] Implement authentication and unique UID profiles.
- [ ] Implement friend lookup and consent-based sharing requests.
- [ ] Prototype nearby sharing first and verify packet routing.
- [ ] Prototype remote tunnel and sender-side gateway.
- [ ] Add enforceable data quotas, usage reporting, and disconnect behavior.
- [ ] Test on two physical Android devices and multiple carriers.

## Current status

Repository initialized. No VPN, packet routing, relay, or internet-sharing feature has been implemented yet.
