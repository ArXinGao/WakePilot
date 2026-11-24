# WakePilot E2EE Blind-Relay Protocol Specification (v1)

WakePilot replaces VPN mesh networking (Tailscale/EasyTier) with an **outbound HTTPS/WSS (443) blind-relay architecture** protected by **End-to-End Encryption (E2EE)**.

The cloud relay server (`wakepilot-relay`) **never** possesses the encryption keys, **never** sees MAC addresses, IP addresses, or commands in plaintext, and requires **zero database** and **zero Android VPN slots**.

---

## 1. Roles & Network Flow

```text
[Phone A: Controller] ──(WSS/HTTPS 443 · E2EE Envelope)──▶ [Cloud Blind Relay (Go)]
                                                                   ▲
                                                      (Outbound WSS 443 · Always-On)
                                                                   │
                                                      [Home Sentinel: Phone B / ESP32]
                                                         ├── Layer-2 UDP WoL (Port 9/7)
                                                         ├── LAN ARP + ICMP Diagnostics
                                                         └── LAN HTTP to Windows Agent (:18766)
                                                                   │
                                                      [Windows PC: wakepilot-agent.exe]
                                                         ├── Auto-detect & launch AI clients
                                                         ├── Report live boot stages
                                                         └── Auto-lock screen (LockWorkStation)
```

---

## 2. Cryptographic Primitives & Key Derivation

All devices share these exact primitives (natively interoperable across Go, Android Kotlin/BouncyCastle, and ESP32 mbedTLS):

| Purpose | Algorithm | Details |
| :--- | :--- | :--- |
| **Root Pairing Secret** | 256-bit CSPRNG (`room_key`) | Generated on Sentinel, transferred optically via QR code only |
| **Blind Channel Token** | `SHA-256("wakepilot-channel-v1:" \|\| room_key)` | Hex-encoded; given to Cloud Relay purely for room routing. Relay cannot reverse `room_key` |
| **Encryption Key Derivation** | `HKDF-SHA256(ikm=room_key, salt="wakepilot-e2ee-v1", info=direction)` | Produces 32-byte `c2s_key` (`"controller->sentinel"`) and `s2c_key` (`"sentinel->controller"`) |
| **Authenticated Encryption** | **AES-256-GCM** | 12-byte random nonce (`n`), 16-byte auth tag appended to ciphertext (`ct`) |
| **Identity Signature** | **Ed25519 (RFC 8032)** | Optional/recommended per-controller signature over `(sentinel_id \|\| ts \|\| nonce \|\| ct)` pinned on first pair |
| **Replay Protection** | Timestamp window (`±120s`) + seen-nonce LRU cache | Prevents captured envelopes from being replayed |

### QR Pairing URI Format
Displayed on the Sentinel (Phone B) screen and scanned by the Controller (Phone A):
```text
wakepilot://pair?r=https%3A%2F%2Frelay.example.com%3A8443&s=sp_8f3a1c2d9e4b&k=<base64url(32-byte room_key)>&pub=<base64url(32-byte sentinel_ed25519_pub)>&name=Home-PC&rd=123456789
```
Parameters:
- `r`: Cloud Blind Relay base URL (`https://relay.example.com` or `http://YOUR_SERVER_IP:8443`)
- `s`: `sentinel_id`
- `k`: Base64URL (no padding) encoded 32-byte `room_key`
- `pub`: Base64URL encoded 32-byte Sentinel Ed25519 public key
- `name`: Display name of target PC
- `rd` (optional): RustDesk ID or direct IP for one-tap remote password unlock

---

## 3. Wire Frame Format (Opaque to Cloud Relay)

Every WebSocket text frame or `POST /api/v1/relay/{sentinel_id}` body is a JSON **Envelope**:

```json
{
  "v": 1,
  "id": "msg_9a1b2c3d",
  "sid": "sp_8f3a1c2d9e4b",
  "dir": "c2s",
  "ts": 1763900000,
  "n": "<base64url(12-byte GCM nonce)>",
  "ct": "<base64url(AES-256-GCM ciphertext + 16-byte tag)>",
  "pub": "<optional base64url(32-byte sender Ed25519 pub)>",
  "sig": "<optional base64url(64-byte Ed25519 signature)>"
}
```

- **AES-GCM AAD (Additional Authenticated Data)** = ASCII bytes of:
  `"{v}|{sid}|{dir}|{ts}"`
- **Ed25519 signed message** (when `sig` is present) = ASCII bytes of:
  `"{v}|{sid}|{dir}|{ts}|{n}|{ct}"`
- The Cloud Relay **ONLY** reads `sid` (for routing) and forwards the JSON frame verbatim. It never decrypts `ct`.

---

## 4. Decrypted Plaintext Payloads (JSON inside `ct`)

### 4.1 Controller → Sentinel (`dir: "c2s"`)
| `type` | Fields | Meaning |
| :--- | :--- | :--- |
| `PING` | `{}` | Check sentinel & PC status |
| `WAKE` | `{"apps": ["workbuddy","doubao","antigravity"], "auto_lock": true}` | Send WoL magic packets, run diagnostic pipeline, trigger Windows Agent app launch + auto-lock |
| `LAUNCH_APPS` | `{"apps": [...], "auto_lock": true}` | Launch allowlisted AI clients on already-awake PC |
| `POWER` | `{"action": "shutdown" \| "reboot" \| "sleep" \| "lock" \| "cancel_shutdown"}` | Execute safe power action via Windows Agent |
| `SCAN_LAN` | `{}` | Trigger Sentinel LAN ARP/subnet scan |

### 4.2 Sentinel → Controller (`dir: "s2c"`)
| `type` | Fields | Meaning |
| :--- | :--- | :--- |
| `STATUS` | `{"sentinel_online": true, "pc_name": "...", "pc_mac": "...", "pc_ip": "...", "pc_reachable": true, "agent_online": true, "apps": {...}, "rustdesk_id": "..."}` | Full current telemetry |
| `PROGRESS` | `{"job_id": "...", "stage": "WOL_SENT" \| "RETRY_WOL" \| "ARP_UP" \| "AGENT_ONLINE" \| "APPS_LAUNCHED" \| "SCREEN_LOCKED" \| "DONE" \| "DIAGNOSIS_FAILED", "elapsed_sec": 12, "message": "...", "diagnosis": "..."}` | Live step-by-step boot timeline & failure diagnosis |
| `ACK` | `{"reply_to": "msg_...", "ok": true, "message": "..."}` | Direct acknowledgement |
