# Security Policy

## Threat Model & Cryptographic Guarantees

WakePilot is engineered around a **Zero-Knowledge Cloud Blind Relay** model:

- **Offline Key Exchange**: The 256-bit `room_key` is generated locally on the Home Sentinel and transferred optically via QR code (`wakepilot://pair?...`). It is never sent in plaintext to the cloud relay.
- **Blind Channel Routing**: The cloud relay only sees `SHA-256("wakepilot-channel-v1:" || room_key)` and cannot invert it to recover `room_key`.
- **Authenticated Encryption**: Every frame is encrypted with **AES-256-GCM** using direction-separated keys derived via **HKDF-SHA256 (RFC 5869)** with AAD binding `(version, sentinel_id, direction, timestamp)`.
- **Origin Authentication & Anti-Replay**: Envelopes support **Ed25519 (RFC 8032)** signatures and enforce a strict `±120s` clock-skew window plus a bounded seen-nonce LRU guard.
- **Least-Privilege Windows Execution**: `wakepilot-agent.exe` exposes no arbitrary shell execution endpoints; only strictly allowlisted AI desktop clients and power actions are permitted.

## Reporting a Vulnerability

If you discover a security vulnerability, please open a **GitHub Security Advisory** (private vulnerability report) on this repository rather than posting a public issue.
