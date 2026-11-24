// Package e2ee implements WakePilot's v1 end-to-end encryption protocol:
//   - ChannelToken: SHA-256("wakepilot-channel-v1:" || room_key)
//   - Key derivation: HKDF-SHA256(ikm=room_key, salt="wakepilot-e2ee-v1", info=direction)
//   - Authenticated encryption: AES-256-GCM (12-byte nonce, AAD = "v|sid|dir|ts")
//   - Optional identity signature: RFC 8032 Ed25519 over "v|sid|dir|ts|n|ct"
//   - Replay protection: ±120s timestamp window + seen-nonce LRU
package e2ee

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"
)

const (
	ProtocolVersion = 1
	DirC2S          = "c2s"
	DirS2C          = "s2c"

	channelPrefix = "wakepilot-channel-v1:"
	hkdfSalt      = "wakepilot-e2ee-v1"
	replayWindow  = 120 // seconds
	maxSeenNonces = 1024
)

var (
	ErrInvalidKey       = errors.New("e2ee: room_key must be 32 bytes")
	ErrUnsupportedVer   = errors.New("e2ee: unsupported protocol version")
	ErrInvalidDirection = errors.New("e2ee: invalid direction")
	ErrTimestampSkew    = errors.New("e2ee: envelope timestamp outside ±120s window")
	ErrReplayDetected   = errors.New("e2ee: duplicate nonce replay detected")
	ErrBadSignature     = errors.New("e2ee: invalid Ed25519 signature")
	ErrDecryptFailed    = errors.New("e2ee: AES-GCM authentication/decryption failed")
)

// Envelope is the opaque wire frame forwarded verbatim by the blind relay.
type Envelope struct {
	Version   int    `json:"v"`
	ID        string `json:"id"`
	Sentinel  string `json:"sid"`
	Direction string `json:"dir"`
	Timestamp int64  `json:"ts"`
	Nonce     string `json:"n"`
	Cipher    string `json:"ct"`
	PubKey    string `json:"pub,omitempty"`
	Signature string `json:"sig,omitempty"`
}

// GenerateRoomKey generates a cryptographically secure 32-byte root pairing secret.
func GenerateRoomKey() ([]byte, error) {
	key := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, key); err != nil {
		return nil, err
	}
	return key, nil
}

// ChannelToken derives the public routing token sent to the cloud blind relay.
// The relay uses this to pair sentinel & controller without ever knowing room_key.
func ChannelToken(roomKey []byte) string {
	h := sha256.New()
	h.Write([]byte(channelPrefix))
	h.Write(roomKey)
	return hex.EncodeToString(h.Sum(nil))
}

// DeriveDirectionalKey derives a 32-byte AES-256 key via RFC 5869 HKDF-SHA256.
func DeriveDirectionalKey(roomKey []byte, direction string) ([]byte, error) {
	if len(roomKey) != 32 {
		return nil, ErrInvalidKey
	}
	var info string
	switch direction {
	case DirC2S:
		info = "controller->sentinel"
	case DirS2C:
		info = "sentinel->controller"
	default:
		return nil, ErrInvalidDirection
	}

	// HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
	extract := hmac.New(sha256.New, []byte(hkdfSalt))
	extract.Write(roomKey)
	prk := extract.Sum(nil)

	// HKDF-Expand (single 32-byte block T(1) = HMAC-SHA256(PRK, info || 0x01))
	expand := hmac.New(sha256.New, prk)
	expand.Write([]byte(info))
	expand.Write([]byte{0x01})
	return expand.Sum(nil), nil
}

func buildAAD(version int, sentinelID, direction string, ts int64) []byte {
	return []byte(fmt.Sprintf("%d|%s|%s|%d", version, sentinelID, direction, ts))
}

func buildSignPayload(env *Envelope) []byte {
	return []byte(fmt.Sprintf("%d|%s|%s|%d|%s|%s",
		env.Version, env.Sentinel, env.Direction, env.Timestamp, env.Nonce, env.Cipher))
}

// Seal encrypts plaintext JSON into an opaque E2EE Envelope.
// If signingKey is non-nil, the envelope is also signed with Ed25519.
func Seal(roomKey []byte, sentinelID, direction, msgID string, plaintext []byte, signingKey ed25519.PrivateKey) (*Envelope, error) {
	aesKey, err := DeriveDirectionalKey(roomKey, direction)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}

	ts := time.Now().Unix()
	aad := buildAAD(ProtocolVersion, sentinelID, direction, ts)
	ct := gcm.Seal(nil, nonce, plaintext, aad)

	env := &Envelope{
		Version:   ProtocolVersion,
		ID:        msgID,
		Sentinel:  sentinelID,
		Direction: direction,
		Timestamp: ts,
		Nonce:     base64.RawURLEncoding.EncodeToString(nonce),
		Cipher:    base64.RawURLEncoding.EncodeToString(ct),
	}

	if len(signingKey) == ed25519.PrivateKeySize {
		pub := signingKey.Public().(ed25519.PublicKey)
		env.PubKey = base64.RawURLEncoding.EncodeToString(pub)
		sig := ed25519.Sign(signingKey, buildSignPayload(env))
		env.Signature = base64.RawURLEncoding.EncodeToString(sig)
	}
	return env, nil
}

// ReplayGuard prevents duplicate nonce replay attacks within the timestamp window.
type ReplayGuard struct {
	mu    sync.Mutex
	seen  map[string]int64
	order []string
}

func NewReplayGuard() *ReplayGuard {
	return &ReplayGuard{seen: make(map[string]int64, 128)}
}

func (rg *ReplayGuard) CheckAndRemember(nonce string, nowUnix int64) bool {
	if rg == nil {
		return true
	}
	rg.mu.Lock()
	defer rg.mu.Unlock()

	if _, exists := rg.seen[nonce]; exists {
		return false
	}
	rg.seen[nonce] = nowUnix
	rg.order = append(rg.order, nonce)
	if len(rg.order) > maxSeenNonces {
		evict := rg.order[0]
		rg.order = rg.order[1:]
		delete(rg.seen, evict)
	}
	return true
}

// Open verifies timestamp, optional replay guard, optional Ed25519 signature, and decrypts the Envelope.
func Open(roomKey []byte, expectedSentinel, expectedDir string, env *Envelope, pinnedPub ed25519.PublicKey, guard *ReplayGuard) ([]byte, error) {
	if env == nil || env.Version != ProtocolVersion {
		return nil, ErrUnsupportedVer
	}
	if env.Sentinel != expectedSentinel || env.Direction != expectedDir {
		return nil, ErrInvalidDirection
	}

	now := time.Now().Unix()
	diff := now - env.Timestamp
	if diff < -replayWindow || diff > replayWindow {
		return nil, ErrTimestampSkew
	}

	if pinnedPub != nil {
		if env.Signature == "" {
			return nil, ErrBadSignature
		}
		sigBytes, err := base64.RawURLEncoding.DecodeString(env.Signature)
		if err != nil || !ed25519.Verify(pinnedPub, buildSignPayload(env), sigBytes) {
			return nil, ErrBadSignature
		}
	} else if env.Signature != "" && env.PubKey != "" {
		pubBytes, err1 := base64.RawURLEncoding.DecodeString(env.PubKey)
		sigBytes, err2 := base64.RawURLEncoding.DecodeString(env.Signature)
		if err1 != nil || err2 != nil || len(pubBytes) != ed25519.PublicKeySize ||
			!ed25519.Verify(ed25519.PublicKey(pubBytes), buildSignPayload(env), sigBytes) {
			return nil, ErrBadSignature
		}
	}

	aesKey, err := DeriveDirectionalKey(roomKey, expectedDir)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonce, err := base64.RawURLEncoding.DecodeString(env.Nonce)
	if err != nil || len(nonce) != gcm.NonceSize() {
		return nil, ErrDecryptFailed
	}
	ct, err := base64.RawURLEncoding.DecodeString(env.Cipher)
	if err != nil {
		return nil, ErrDecryptFailed
	}

	aad := buildAAD(env.Version, env.Sentinel, env.Direction, env.Timestamp)
	plain, err := gcm.Open(nil, nonce, ct, aad)
	if err != nil {
		return nil, ErrDecryptFailed
	}

	if !guard.CheckAndRemember(env.Nonce, now) {
		return nil, ErrReplayDetected
	}
	return plain, nil
}
