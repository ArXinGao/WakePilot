package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestWindowsAgentEndpoints(t *testing.T) {
	cfg := AgentConfig{
		PCName:          "UnitTest-PC",
		ListenPort:      18766,
		LANKey:          "lan-secret-123",
		AutoLockSeconds: 1,
	}
	srv := &AgentServer{
		cfg:       cfg,
		specs:     defaultAppSpecs(cfg.PCName),
		startedAt: time.Now(),
		dryRun:    true,
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// 1. Unauthorized health check fails
	resp, err := http.Get(ts.URL + "/health")
	if err != nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got err=%v status=%v", err, resp)
	}

	// 2. Authorized health check succeeds
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/health", nil)
	req.Header.Set("X-WakePilot-Key", "lan-secret-123")
	resp, err = http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got err=%v status=%v", err, resp)
	}

	// 3. Launch all 3 AI apps with auto_lock=true (dry-run)
	body := bytes.NewBufferString(`{"apps":["workbuddy","doubao","antigravity"],"auto_lock":true}`)
	req2, _ := http.NewRequest(http.MethodPost, ts.URL+"/launch", body)
	req2.Header.Set("X-WakePilot-Key", "lan-secret-123")
	resp2, err := http.DefaultClient.Do(req2)
	if err != nil || resp2.StatusCode != http.StatusOK {
		t.Fatalf("launch failed: err=%v status=%v", err, resp2)
	}
	var res struct {
		OK             bool                    `json:"ok"`
		AutoLockQueued bool                    `json:"auto_lock_queued"`
		Results        map[string]LaunchResult `json:"results"`
	}
	_ = json.NewDecoder(resp2.Body).Decode(&res)
	if !res.OK || !res.AutoLockQueued || len(res.Results) != 3 {
		t.Fatalf("unexpected launch response: %+v", res)
	}
}
