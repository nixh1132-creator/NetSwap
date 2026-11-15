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



/// Stable status values for the narrow C ABI used by Android's JNI adapter.
pub const NETSWAP_OK: i32 = 0;
pub const NETSWAP_INVALID_ARGUMENT: i32 = -1;
pub const NETSWAP_BUFFER_TOO_SMALL: i32 = -2;
pub const NETSWAP_ENGINE_ERROR: i32 = -3;
pub const NETSWAP_PANIC: i32 = -4;

fn write_engine_output(
    result: Result<EngineOutput, EngineError>,
    output: *mut u8,
    output_capacity: usize,
    output_len: *mut usize,
    output_kind: *mut u32,
) -> i32 {
    if output_len.is_null() || output_kind.is_null() {
        return NETSWAP_INVALID_ARGUMENT;
    }
    let (kind, bytes) = match result {
        Ok(EngineOutput::Done) => (0u32, Vec::new()),
        Ok(EngineOutput::Network(bytes)) => (1, bytes),
        Ok(EngineOutput::TunnelV4 { packet, .. }) => (2, packet),
        Ok(EngineOutput::TunnelV6 { packet, .. }) => (3, packet),
        Err(_) => return NETSWAP_ENGINE_ERROR,
    };
    unsafe {
        *output_len = bytes.len();
        *output_kind = kind;
    }
    if bytes.len() > output_capacity || (!bytes.is_empty() && output.is_null()) {
        return NETSWAP_BUFFER_TOO_SMALL;
    }
    if !bytes.is_empty() {
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), output, bytes.len()) };
    }
    NETSWAP_OK
}

/// Creates an opaque tunnel handle. Returns null for invalid pointers or panic.
/// Key inputs must each point to exactly 32 readable bytes.
#[no_mangle]
pub unsafe extern "C" fn netswap_tunnel_new(
    private_key: *const u8,
    peer_public_key: *const u8,
    keepalive_secs: u16,
    index: u32,
) -> *mut NetSwapTunnel {
    if private_key.is_null() || peer_public_key.is_null() {
        return std::ptr::null_mut();
    }
    std::panic::catch_unwind(|| {
        let private = std::slice::from_raw_parts(private_key, 32);
        let peer = std::slice::from_raw_parts(peer_public_key, 32);
        let mut private_bytes = [0u8; 32];
        let mut peer_bytes = [0u8; 32];
        private_bytes.copy_from_slice(private);
        peer_bytes.copy_from_slice(peer);
        Box::into_raw(Box::new(NetSwapTunnel::new(
            private_bytes,
            peer_bytes,
            if keepalive_secs == 0 { None } else { Some(keepalive_secs) },
            index,
        )))
    }).unwrap_or(std::ptr::null_mut())
}

/// Destroys a handle returned by netswap_tunnel_new. Null is a no-op.
#[no_mangle]
pub unsafe extern "C" fn netswap_tunnel_free(handle: *mut NetSwapTunnel) {
    if !handle.is_null() {
        let _ = std::panic::catch_unwind(|| drop(Box::from_raw(handle)));
    }
}

/// Encapsulates one IP packet. output_kind: 0=done, 1=network datagram,
/// 2=IPv4 tunnel packet, 3=IPv6 tunnel packet.
#[no_mangle]
pub unsafe extern "C" fn netswap_tunnel_encapsulate(
    handle: *mut NetSwapTunnel,
    packet: *const u8,
    packet_len: usize,
    output: *mut u8,
    output_capacity: usize,
    output_len: *mut usize,
    output_kind: *mut u32,
) -> i32 {
    if handle.is_null() || packet.is_null() || packet_len == 0 || packet_len > 65_535 {
        return NETSWAP_INVALID_ARGUMENT;
    }
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let input = std::slice::from_raw_parts(packet, packet_len);
        let result = (*handle).encapsulate(input);
        write_engine_output(result, output, output_capacity, output_len, output_kind)
    })).unwrap_or(NETSWAP_PANIC)
}

/// Decapsulates one WireGuard UDP datagram.
#[no_mangle]
pub unsafe extern "C" fn netswap_tunnel_decapsulate(
    handle: *mut NetSwapTunnel,
    datagram: *const u8,
    datagram_len: usize,
    output: *mut u8,
    output_capacity: usize,
    output_len: *mut usize,
    output_kind: *mut u32,
) -> i32 {
    if handle.is_null() || (datagram.is_null() && datagram_len != 0) || datagram_len > 65_535 {
        return NETSWAP_INVALID_ARGUMENT;
    }
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let input = if datagram_len == 0 { &[] } else {
            std::slice::from_raw_parts(datagram, datagram_len)
        };
        let result = (*handle).decapsulate(input);
        write_engine_output(result, output, output_capacity, output_len, output_kind)
    })).unwrap_or(NETSWAP_PANIC)
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
