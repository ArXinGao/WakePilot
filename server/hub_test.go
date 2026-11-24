package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"wakepilot/shared/e2ee"
)

func TestBlindRelayEndToEnd(t *testing.T) {
	hub := NewBlindRelayHub()
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", hub.HandleHealth)
	mux.HandleFunc("/ws", hub.HandleWS)
	mux.HandleFunc("/api/v1/relay/", hub.HandleOneShotRelay)

	ts := httptest.NewServer(mux)
	defer ts.Close()

	wsBase := "ws" + strings.TrimPrefix(ts.URL, "http")
	roomKey, _ := e2ee.GenerateRoomKey()
	channelTok := e2ee.ChannelToken(roomKey)
	sentinelID := "sp_e2e_test01"

	// 1. Sentinel connects outbound to Blind Relay
	sConn, _, err := websocket.DefaultDialer.Dial(
		wsBase+"/ws?role=sentinel&id="+sentinelID+"&channel="+channelTok, nil)
	if err != nil {
		t.Fatalf("sentinel dial: %v", err)
	}
	defer sConn.Close()

	// 2. Wrong channel token must be rejected with 403
	_, resp, err := websocket.DefaultDialer.Dial(
		wsBase+"/ws?role=controller&id="+sentinelID+"&channel=00000000000000000000000000000000", nil)
	if err == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 for bad channel token, got err=%v resp=%v", err, resp)
	}

	// 3. Authorized Controller connects and receives sentinel_online=true presence frame
	cConn, _, err := websocket.DefaultDialer.Dial(
		wsBase+"/ws?role=controller&id="+sentinelID+"&channel="+channelTok, nil)
	if err != nil {
		t.Fatalf("controller dial: %v", err)
	}
	defer cConn.Close()

	_ = cConn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, presenceRaw, err := cConn.ReadMessage()
	if err != nil {
		t.Fatalf("read presence: %v", err)
	}
	if !strings.Contains(string(presenceRaw), `"sentinel_online":true`) {
		t.Fatalf("unexpected presence frame: %s", presenceRaw)
	}

	// 4. Controller seals E2EE WAKE command and sends via WebSocket
	ctrlPub, ctrlPriv, _ := ed25519.GenerateKey(rand.Reader)
	wantCmd := []byte(`{"type":"WAKE","apps":["workbuddy","antigravity"],"auto_lock":true}`)
	env, err := e2ee.Seal(roomKey, sentinelID, e2ee.DirC2S, "m1", wantCmd, ctrlPriv)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	envBytes, _ := json.Marshal(env)
	if err := cConn.WriteMessage(websocket.TextMessage, envBytes); err != nil {
		t.Fatalf("controller write: %v", err)
	}

	// 5. Sentinel receives opaque envelope and decrypts it
	_ = sConn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, gotRaw, err := sConn.ReadMessage()
	if err != nil {
		t.Fatalf("sentinel read: %v", err)
	}
	var rxEnv e2ee.Envelope
	if err := json.Unmarshal(gotRaw, &rxEnv); err != nil {
		t.Fatalf("unmarshal envelope: %v", err)
	}
	guard := e2ee.NewReplayGuard()
	gotPlain, err := e2ee.Open(roomKey, sentinelID, e2ee.DirC2S, &rxEnv, ctrlPub, guard)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if !bytes.Equal(gotPlain, wantCmd) {
		t.Fatalf("decrypted mismatch: got %s want %s", gotPlain, wantCmd)
	}

	// 6. Test stateless HTTP one-shot POST (used by Android Widget & Quick Settings Tile)
	env2, _ := e2ee.Seal(roomKey, sentinelID, e2ee.DirC2S, "m2", []byte(`{"type":"WAKE"}`), ctrlPriv)
	env2Bytes, _ := json.Marshal(env2)
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/v1/relay/"+sentinelID, bytes.NewReader(env2Bytes))
	req.Header.Set("X-Channel-Token", channelTok)
	httpResp, err := http.DefaultClient.Do(req)
	if err != nil || httpResp.StatusCode != http.StatusAccepted {
		t.Fatalf("one-shot POST failed: err=%v status=%v", err, httpResp)
	}
	_ = sConn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, gotRaw2, err := sConn.ReadMessage()
	if err != nil {
		t.Fatalf("sentinel read one-shot: %v", err)
	}
	var rxEnv2 e2ee.Envelope
	_ = json.Unmarshal(gotRaw2, &rxEnv2)
	if _, err := e2ee.Open(roomKey, sentinelID, e2ee.DirC2S, &rxEnv2, ctrlPub, guard); err != nil {
		t.Fatalf("Open one-shot: %v", err)
	}
}
