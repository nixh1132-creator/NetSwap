#!/usr/bin/env python3
"""NetSwap relay peer reconciler.

Runs on the trusted Ubuntu WireGuard relay as root. It reads server-managed
session metadata with a Supabase service-role key held only in the process
environment, allocates per-session tunnel IPs, adds/removes WireGuard peers,
and NEVER marks sessions ready/active. It does not enable IP forwarding.
"""
import base64
import json
import os
import subprocess
import time
import urllib.parse
import urllib.request
from typing import Any

SUPABASE_URL = os.environ["NETSWAP_SUPABASE_URL"].rstrip("/")
SERVICE_KEY = os.environ["NETSWAP_SUPABASE_SERVICE_ROLE_KEY"]
WG_INTERFACE = os.environ.get("NETSWAP_WG_INTERFACE", "wg0")
POLL_SECONDS = int(os.environ.get("NETSWAP_POLL_SECONDS", "5"))
API = SUPABASE_URL + "/rest/v1"
HEADERS = {
    "apikey": SERVICE_KEY,
    "Authorization": "Bearer " + SERVICE_KEY,
    "Content-Type": "application/json",
    "Accept": "application/json",
}


def http_json(path: str, method: str = "GET", body: Any = None) -> Any:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, headers=HEADERS, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def wg(*args: str) -> None:
    subprocess.run(["wg", *args], check=True, stdout=subprocess.DEVNULL)


def existing_peers() -> set[str]:
    result = subprocess.run(
        ["wg", "show", WG_INTERFACE, "peers"],
        check=True, capture_output=True, text=True,
    )
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def remove_peer_if_present(key: str, peers: set[str]) -> None:
    # Idempotent removal: revoked sessions are polled repeatedly.
    if key in peers:
        wg("set", WG_INTERFACE, "peer", key, "remove")
        peers.discard(key)


def valid_key(value: str) -> bool:
    try:
        decoded = base64.b64decode(value, validate=True)
        return len(decoded) == 32
    except Exception:
        return False


def reconcile() -> None:
    fields = (
        "id,sharing_request_id,status,sender_public_key,receiver_public_key,"
        "sender_tunnel_ip,receiver_tunnel_ip"
    )
    query = urllib.parse.urlencode({
        "select": fields,
        "status": "in.(provisioning,revoked,expired,failed)",
        "order": "updated_at.asc",
        "limit": "100",
    })
    sessions = http_json("/tunnel_sessions?" + query) or []
    peers = existing_peers()

    for session in sessions:
        status = session["status"]
        keys = [session.get("sender_public_key"), session.get("receiver_public_key")]

        if status in ("revoked", "expired", "failed"):
            for key in keys:
                if key and valid_key(key):
                    remove_peer_if_present(key, peers)
            continue

        if not all(keys):
            continue
        if not all(valid_key(key) for key in keys):
            print("Skipping session with malformed public key:", session["id"])
            continue

        if not session.get("sender_tunnel_ip") or not session.get("receiver_tunnel_ip"):
            rpc = http_json(
                "/rpc/allocate_tunnel_peer_ips",
                method="POST",
                body={"p_session_id": session["id"]},
            )
            # PostgREST table-returning RPC yields a one-row array.
            if isinstance(rpc, list) and rpc:
                session["sender_tunnel_ip"] = rpc[0].get("sender_ip")
                session["receiver_tunnel_ip"] = rpc[0].get("receiver_ip")
            elif isinstance(rpc, dict):
                session["sender_tunnel_ip"] = rpc.get("sender_ip")
                session["receiver_tunnel_ip"] = rpc.get("receiver_ip")

        sender_ip = session.get("sender_tunnel_ip")
        receiver_ip = session.get("receiver_tunnel_ip")
        if not sender_ip or not receiver_ip:
            continue

        # Hub peer registration only. No route forwarding, NAT, quota enforcement,
        # or session-ready transition is performed here.
        wg("set", WG_INTERFACE, "peer", keys[0], "allowed-ips", sender_ip + "/32")
        peers.add(keys[0])
        wg("set", WG_INTERFACE, "peer", keys[1], "allowed-ips", receiver_ip + "/32")
        peers.add(keys[1])


def main() -> None:
    if os.geteuid() != 0:
        raise SystemExit("Run as root so the process can manage WireGuard peers.")
    if not SUPABASE_URL.startswith("https://"):
        raise SystemExit("NETSWAP_SUPABASE_URL must use HTTPS.")
    print("NetSwap relay reconciler started; peer-only mode, forwarding disabled.")
    while True:
        try:
            reconcile()
        except Exception as exc:
            # Do not print request headers, secrets, or response bodies.
            print("Reconcile cycle failed:", type(exc).__name__, str(exc)[:240])
        time.sleep(max(3, min(POLL_SECONDS, 60)))


if __name__ == "__main__":
    main()
