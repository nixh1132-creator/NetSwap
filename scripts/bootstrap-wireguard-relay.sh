#!/usr/bin/env bash
# NetSwap relay bootstrap: installs an empty WireGuard hub on Ubuntu LTS.
# This deliberately does NOT enable IP forwarding, NAT, peer routing, or a proxy.
# Run only on a fresh, trusted Ubuntu VM that you control.

set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash bootstrap-wireguard-relay.sh" >&2
  exit 1
fi

if [[ ! -r /etc/os-release ]]; then
  echo "Cannot identify operating system; expected Ubuntu LTS." >&2
  exit 1
fi
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "This bootstrap supports Ubuntu only; detected: ${ID:-unknown}" >&2
  exit 1
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "apt-get not found." >&2
  exit 1
fi

if [[ -e /etc/wireguard/wg0.conf ]]; then
  echo "/etc/wireguard/wg0.conf already exists. Refusing to overwrite it." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y wireguard-tools

install -d -m 700 /etc/wireguard
umask 077
wg genkey | tee /etc/wireguard/netswap-server.key | wg pubkey > /etc/wireguard/netswap-server.pub
chmod 600 /etc/wireguard/netswap-server.key
chmod 644 /etc/wireguard/netswap-server.pub

SERVER_PRIVATE_KEY="$(cat /etc/wireguard/netswap-server.key)"
cat > /etc/wireguard/wg0.conf <<EOF
[Interface]
Address = 10.77.0.1/24
ListenPort = 51820
PrivateKey = ${SERVER_PRIVATE_KEY}
EOF
chmod 600 /etc/wireguard/wg0.conf

systemctl enable --now wg-quick@wg0

echo
echo "NetSwap WireGuard hub installed."
echo "Server public key (safe to share with an authenticated peer provisioner):"
cat /etc/wireguard/netswap-server.pub
echo
echo "Open inbound UDP 51820 in your cloud firewall/security list."
echo "Do not open broad forwarding or enable IP forwarding yet."
echo "This is an EMPTY hub: no peers, no NAT, and NO internet sharing."
echo "Server private key remains in /etc/wireguard/netswap-server.key."
