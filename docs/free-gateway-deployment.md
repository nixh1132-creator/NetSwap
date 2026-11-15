# NetSwap free gateway deployment decision

## Decision

Use an Oracle Cloud Infrastructure (OCI) Always Free Ubuntu VM as the first controlled-network WireGuard rendezvous/relay prototype, subject to the account's actual Always Free eligibility and available capacity.

Official OCI limits currently document:
- Ampere A1 allowance: 1,500 OCPU-hours and 9,000 GB-hours monthly, equivalent to 2 OCPUs and 12 GB RAM.
- Up to 10 TB/month outbound data transfer.
- Compute must be created in the tenancy home region.
- Always Free VMs can be reclaimed if CPU, network, and (for A1) memory utilization are all below OCI's stated thresholds for seven days.
- Capacity is not guaranteed; OCI can return "out of host capacity".

Official source: https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm

## Important product architecture boundary

The OCI VM is only a reachable UDP rendezvous/relay point. It is NOT the user's mobile-data source and does not itself make a friend's phone forward traffic.

Target packet path:
1. Receiver device sends its authorized traffic into its NetSwap VPN interface.
2. An authenticated encrypted peer tunnel carries packets to the relay.
3. Relay routes only the approved receiver session's packets to that request's sender peer.
4. Sender-side Android userspace gateway forwards supported TCP/UDP traffic through the sender phone's selected upstream network.
5. Return packets follow the reverse path.
6. A trusted data-plane component enforces quota and revocation; client-reported counters are not authoritative.

A normal WireGuard client configuration and an approved request do not implement steps 3-6. Do not set `tunnel_sessions.status=ready` or `active` until the data plane is operational and tested.

## Why not Cloudflare Tunnel for the WireGuard endpoint

Cloudflare Tunnel is useful for publishing supported applications, but it is not being selected as a generic public WireGuard UDP socket. NetSwap needs a directly reachable UDP endpoint for the WireGuard peer handshake. Do not point WireGuard at a Cloudflare HTTP hostname as if it were a raw UDP relay.

## Provisioning prerequisites

Before a real VM can be created, an eligible OCI account and a chosen home region are required. Account verification and payment-method requirements are controlled by OCI and can vary. Never enable paid resources or upgrade the account merely to get around Always Free capacity errors. If an adult/guardian is required to hold the cloud account, they must own and manage it.

## Safe initial server profile

- Ubuntu LTS, Always Free eligible shape only.
- One public IPv4 endpoint, if OCI offers it under the account's eligible resources.
- UDP 51820 ingress only after the WireGuard service is installed.
- SSH ingress restricted to the administrator's own IP; never expose SSH to the whole internet unnecessarily.
- No broad `0.0.0.0/0` forwarding or unauthenticated proxy.
- WireGuard private keys and relay admin credentials stay on the server or device that owns them; never commit them or store them in Supabase.
- Set a budget alert and inspect OCI's cost/usage page before creating resources.

## Next implementation sequence

1. Provision an eligible Ubuntu VM and verify public UDP reachability.
2. Install WireGuard and configure a hub peer model with unique per-request peer identities.
3. Add authenticated server-side peer provisioning and revocation, using server-held credentials only.
4. Prototype sender-side Android userspace packet forwarding on two controlled devices.
5. Add per-session byte accounting and hard quota enforcement in the forwarding path.
6. Test handshake, TCP, UDP, DNS, return traffic, quota exhaustion, revocation, device sleep, network changes, and disconnect behavior.

Until these tests pass, the product must continue to display that friend-to-friend internet sharing is not active.
