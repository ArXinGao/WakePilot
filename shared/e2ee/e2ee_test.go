package e2ee

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"testing"
)

func TestSealOpenAndSecurityGuards(t *testing.T) {
	roomKey, err := GenerateRoomKey()
	if err != nil {
		t.Fatalf("GenerateRoomKey: %v", err)
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("ed25519.GenerateKey: %v", err)
	}

	plain := []byte(`{"type":"WAKE","apps":["workbuddy","doubao","antigravity"],"auto_lock":true}`)
	env, err := Seal(roomKey, "sp_test01", DirC2S, "msg_1", plain, priv)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}

	guard := NewReplayGuard()
	got, err := Open(roomKey, "sp_test01", DirC2S, env, pub, guard)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatalf("plaintext mismatch: got %q want %q", got, plain)
	}

	// Replay with same nonce must fail
	if _, err := Open(roomKey, "sp_test01", DirC2S, env, pub, guard); err != ErrReplayDetected {
		t.Fatalf("expected ErrReplayDetected, got %v", err)
	}

	// Tampered ciphertext must fail signature or GCM check
	tampered := *env
	tampered.Cipher = tampered.Cipher[:len(tampered.Cipher)-2] + "AA"
	if _, err := Open(roomKey, "sp_test01", DirC2S, &tampered, pub, NewReplayGuard()); err == nil {
		t.Fatalf("expected tampered envelope to fail")
	}

	// Wrong direction must fail
	if _, err := Open(roomKey, "sp_test01", DirS2C, env, pub, NewReplayGuard()); err == nil {
		t.Fatalf("expected wrong direction to fail")
	}

	// Channel token deterministic & opaque
	tok := ChannelToken(roomKey)
	if len(tok) != 64 {
		t.Fatalf("expected 64-char sha256 hex channel token, got %d", len(tok))
	}
}
