# NetSwap relay bootstrap

## What this milestone does

`scripts/bootstrap-wireguard-relay.sh` installs WireGuard tools on a fresh Ubuntu LTS VM, creates a server private/public key pair under `/etc/wireguard`, writes an empty `wg0` interface at `10.77.0.1/24` listening on UDP 51820, and enables the systemd unit.

It intentionally does **not**:
- enable kernel IP forwarding;
- add NAT/masquerade rules;
- add client peers;
- route receiver traffic to a sender;
- enforce per-request quotas;
- expose a SOCKS/HTTP proxy.

This is a safe relay bootstrap only. An empty WireGuard interface is not a working NetSwap data-sharing gateway.

## Prerequisites

- A trusted Ubuntu LTS VM that you or your parent/guardian is authorized to administer.
- A reachable public IPv4 address.
- UDP 51820 allowed in the cloud firewall/security list.
- SSH access restricted to the administrator's IP.
- Confirm the VM is within the provider's free allowance before creating it. Never enable paid resources to bypass a free-tier restriction.

## Run

Copy the script to the VM, inspect it, then run:

```bash
sudo bash scripts/bootstrap-wireguard-relay.sh
```

The script refuses to overwrite an existing `/etc/wireguard/wg0.conf`. It prints only the server public key. The private key stays on the server with root-only permissions.

## Verification

```bash
sudo wg show wg0
sudo systemctl status wg-quick@wg0 --no-pager
sudo cat /etc/wireguard/netswap-server.pub
```

Expected at this stage: interface exists and listens on UDP 51820, with **zero peers**.

## Next required milestones

1. Authenticated peer provisioning from the server-side control plane.
2. Unique isolated peer allocation per approved sharing request.
3. Sender Android userspace packet forwarding.
4. Receiver route setup and return-path routing.
5. Byte counting and hard quota enforcement in the data plane.
6. Revoke/stop behavior and two-device tests for TCP, UDP, DNS, sleep, and network changes.

Do not mark any session `ready` or `active` until the complete packet path and quota enforcement have been verified.
