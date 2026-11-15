import base64
import importlib.util
import os
import pathlib
import unittest
from unittest import mock

os.environ.setdefault("NETSWAP_SUPABASE_URL", "https://example.supabase.co")
os.environ.setdefault("NETSWAP_SUPABASE_SERVICE_ROLE_KEY", "test-only-not-a-real-key")

MODULE_PATH = pathlib.Path(__file__).parents[1] / "scripts" / "netswap-relay-agent.py"
spec = importlib.util.spec_from_file_location("netswap_relay_agent", MODULE_PATH)
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


def public_key(seed: int) -> str:
    return base64.b64encode(bytes([seed]) * 32).decode()


class RelayAgentTests(unittest.TestCase):
    def test_valid_key_accepts_exactly_32_decoded_bytes(self):
        self.assertTrue(agent.valid_key(public_key(7)))
        self.assertFalse(agent.valid_key("not-a-wireguard-key"))
        self.assertFalse(agent.valid_key(base64.b64encode(b"short").decode()))

    @mock.patch.object(agent.subprocess, "run")
    def test_existing_peers_reads_nonempty_lines(self, run):
        run.return_value.stdout = "peer-one\n\npeer-two\n"
        self.assertEqual(agent.existing_peers(), {"peer-one", "peer-two"})
        run.assert_called_once_with(
            ["wg", "show", agent.WG_INTERFACE, "peers"],
            check=True, capture_output=True, text=True,
        )

    @mock.patch.object(agent, "wg")
    def test_remove_peer_is_idempotent(self, wg):
        peers = {"present-key"}
        agent.remove_peer_if_present("present-key", peers)
        agent.remove_peer_if_present("present-key", peers)
        agent.remove_peer_if_present("missing-key", peers)
        wg.assert_called_once_with("set", agent.WG_INTERFACE, "peer", "present-key", "remove")
        self.assertEqual(peers, set())

    @mock.patch.object(agent, "wg")
    @mock.patch.object(agent, "existing_peers", return_value=set())
    @mock.patch.object(agent, "http_json")
    def test_terminal_session_removes_registered_peers(self, http_json, _peers, wg):
        sender, receiver = public_key(1), public_key(2)
        http_json.return_value = [{
            "id": "session-1", "status": "revoked",
            "sender_public_key": sender, "receiver_public_key": receiver,
        }]
        agent.reconcile()
        self.assertEqual(wg.call_count, 0)

    @mock.patch.object(agent, "wg")
    @mock.patch.object(agent, "existing_peers", return_value=set())
    @mock.patch.object(agent, "http_json")
    def test_provisioning_session_without_both_keys_is_not_installed(self, http_json, _peers, wg):
        http_json.return_value = [{
            "id": "session-2", "status": "provisioning",
            "sender_public_key": public_key(3), "receiver_public_key": None,
            "sender_tunnel_ip": "10.77.0.2", "receiver_tunnel_ip": "10.77.0.3",
        }]
        agent.reconcile()
        wg.assert_not_called()


if __name__ == "__main__":
    unittest.main()
