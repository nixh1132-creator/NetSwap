//! Safe Rust ownership layer around pinned BoringTun 0.7.1.
//! This crate is deliberately not wired into Android yet. The JNI/UDP/TUN bridge
//! must be added and tested separately before it can carry user traffic.

use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use std::net::IpAddr;

#[derive(Debug, PartialEq, Eq)]
pub enum EngineOutput {
    Done,
    Network(Vec<u8>),
    TunnelV4 { packet: Vec<u8>, source: [u8; 4] },
    TunnelV6 { packet: Vec<u8>, source: [u8; 16] },
}

#[derive(Debug, PartialEq, Eq)]
pub enum EngineError {
    EmptyOrOversizedPacket,
    Protocol(String),
}

/// Owns one WireGuard peer tunnel. Dropping this value releases BoringTun's
/// tunnel state; callers must serialize access to each instance.
pub struct NetSwapTunnel {
    tunnel: Tunn,
}

impl NetSwapTunnel {
    pub fn new(
        private_key: [u8; 32],
        peer_public_key: [u8; 32],
        persistent_keepalive_secs: Option<u16>,
        index: u32,
    ) -> Self {
        let secret = StaticSecret::from(private_key);
        let public = PublicKey::from(peer_public_key);
        Self {
            tunnel: Tunn::new(secret, public, None, persistent_keepalive_secs, index, None),
        }
    }

    /// Encapsulate one IP packet for the WireGuard UDP transport.
    pub fn encapsulate(&mut self, packet: &[u8]) -> Result<EngineOutput, EngineError> {
        if packet.is_empty() || packet.len() > 65_535 {
            return Err(EngineError::EmptyOrOversizedPacket);
        }
        let mut output = vec![0u8; (packet.len() + 32).max(148)];
        match self.tunnel.encapsulate(packet, &mut output) {
            TunnResult::Done => Ok(EngineOutput::Done),
            TunnResult::Err(error) => Err(EngineError::Protocol(format!("{error:?}"))),
            TunnResult::WriteToNetwork(bytes) => Ok(EngineOutput::Network(bytes.to_vec())),
            TunnResult::WriteToTunnelV4(bytes, source) => Ok(EngineOutput::TunnelV4 {
                packet: bytes.to_vec(),
                source: source.octets(),
            }),
            TunnResult::WriteToTunnelV6(bytes, source) => Ok(EngineOutput::TunnelV6 {
                packet: bytes.to_vec(),
                source: source.octets(),
            }),
        }
    }

    /// Decapsulate one received WireGuard UDP datagram.
    pub fn decapsulate(&mut self, datagram: &[u8]) -> Result<EngineOutput, EngineError> {
        if datagram.len() > 65_535 {
            return Err(EngineError::EmptyOrOversizedPacket);
        }
        let mut output = vec![0u8; datagram.len().max(256)];
        match self.tunnel.decapsulate(None, datagram, &mut output) {
            TunnResult::Done => Ok(EngineOutput::Done),
            TunnResult::Err(error) => Err(EngineError::Protocol(format!("{error:?}"))),
            TunnResult::WriteToNetwork(bytes) => Ok(EngineOutput::Network(bytes.to_vec())),
            TunnResult::WriteToTunnelV4(bytes, source) => Ok(EngineOutput::TunnelV4 {
                packet: bytes.to_vec(),
                source: source.octets(),
            }),
            TunnResult::WriteToTunnelV6(bytes, source) => Ok(EngineOutput::TunnelV6 {
                packet: bytes.to_vec(),
                source: source.octets(),
            }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_independent_tunnel_instances_and_rejects_empty_ip_packets() {
        let a_private = [7u8; 32];
        let b_private = [9u8; 32];
        let b_public = PublicKey::from(&StaticSecret::from(b_private)).to_bytes();
        let mut tunnel = NetSwapTunnel::new(a_private, b_public, None, 1);

        assert_eq!(
            tunnel.encapsulate(&[]),
            Err(EngineError::EmptyOrOversizedPacket)
        );
    }

    #[test]
    fn creates_wireguard_handshake_for_valid_ipv4_packet() {
        let a_private = [7u8; 32];
        let b_private = [9u8; 32];
        let b_public = PublicKey::from(&StaticSecret::from(b_private)).to_bytes();
        let mut tunnel = NetSwapTunnel::new(a_private, b_public, None, 2);
        let packet = [
            0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 10, 77, 0, 2, 10, 77, 0, 1,
        ];

        assert!(matches!(
            tunnel.encapsulate(&packet),
            Ok(EngineOutput::Network(_))
        ));
    }
}
