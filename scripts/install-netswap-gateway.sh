#!/usr/bin/env bash
# Install the NetSwap WireGuard control-plane agent on a fresh Ubuntu LTS host.
# This provisions peer reconciliation only. It deliberately does NOT enable
# IP forwarding, NAT, or mark sessions ready/active.
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash scripts/install-netswap-gateway.sh" >&2
  exit 1
fi
if [[ ! -r /etc/os-release ]]; then echo "Ubuntu required." >&2; exit 1; fi
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then echo "Ubuntu LTS required; found ${ID:-unknown}." >&2; exit 1; fi
command -v apt-get >/dev/null || { echo "apt-get missing." >&2; exit 1; }
if [[ -e /etc/netswap/relay.env ]]; then
  echo "/etc/netswap/relay.env already exists; refusing to overwrite secrets." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y wireguard-tools python3 ca-certificates
install -d -m 700 /etc/wireguard /etc/netswap /opt/netswap
umask 077

if [[ ! -e /etc/wireguard/wg0.conf ]]; then
  wg genkey | tee /etc/wireguard/netswap-server.key | wg pubkey > /etc/wireguard/netswap-server.pub
  SERVER_PRIVATE_KEY="$(cat /etc/wireguard/netswap-server.key)"
  cat > /etc/wireguard/wg0.conf <<EOF
[Interface]
Address = 10.77.0.1/24
ListenPort = 51820
PrivateKey = ${SERVER_PRIVATE_KEY}
EOF
  chmod 600 /etc/wireguard/wg0.conf /etc/wireguard/netswap-server.key
  chmod 644 /etc/wireguard/netswap-server.pub
else
  echo "Existing wg0.conf found; leaving it untouched."
fi

if [[ ! -e /opt/netswap/netswap-relay-agent.py ]]; then
  echo "Copy scripts/netswap-relay-agent.py to /opt/netswap/ first." >&2
  exit 1
fi
install -m 600 deploy/netswap-relay-agent.service /etc/systemd/system/netswap-relay-agent.service
cat > /etc/netswap/relay.env.example <<'EOF'
NETSWAP_SUPABASE_URL=https://YOUR_PROJECT.supabase.co
NETSWAP_SUPABASE_SERVICE_ROLE_KEY=SET_ON_SERVER_ONLY
NETSWAP_WG_INTERFACE=wg0
NETSWAP_POLL_SECONDS=5
EOF
chmod 600 /etc/netswap/relay.env.example
systemctl daemon-reload
systemctl enable --now wg-quick@wg0

echo
echo "WireGuard gateway hub installed; relay agent is NOT started yet."
echo "1) Copy relay.env.example to relay.env and fill it with server-only values."
echo "2) Copy the repository's scripts/netswap-relay-agent.py to /opt/netswap/."
echo "3) Open UDP 51820 in the host firewall/security group."
echo "4) Review deploy/netswap-relay-agent.service, then enable it:"
echo "   systemctl enable --now netswap-relay-agent"
echo "Public key:"
cat /etc/wireguard/netswap-server.pub
echo "No IP forwarding, NAT, or internet sharing has been enabled."
