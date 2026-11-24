package main

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	maxFrameBytes       = 64 * 1024 // 64KB max per E2EE frame
	maxControllersRoom  = 8
	writeWait           = 10 * time.Second
	pongWait            = 60 * time.Second
	pingPeriod          = 25 * time.Second
	rateLimitBurst      = 30
	rateLimitRefillRate = 5.0 // tokens/sec per sentinel room
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(r *http.Request) bool { return true },
}

// clientConn wraps a WebSocket with serialized outbound writes.
type clientConn struct {
	ws   *websocket.Conn
	send chan []byte
	role string
}

// room holds one sentinel and up to N authenticated controllers sharing the same blind channel_token.
type room struct {
	mu           sync.Mutex
	sentinelID   string
	channelToken string
	sentinel     *clientConn
	controllers  map[*clientConn]struct{}
	lastActivity time.Time
	tokens       float64
	lastRefill   time.Time
}

func (r *room) allowFrame() bool {
	now := time.Now()
	elapsed := now.Sub(r.lastRefill).Seconds()
	r.tokens += elapsed * rateLimitRefillRate
	if r.tokens > rateLimitBurst {
		r.tokens = rateLimitBurst
	}
	r.lastRefill = now
	if r.tokens < 1.0 {
		return false
	}
	r.tokens -= 1.0
	r.lastActivity = now
	return true
}

// BlindRelayHub routes opaque E2EE envelopes without inspecting or decrypting payloads.
type BlindRelayHub struct {
	mu        sync.RWMutex
	rooms     map[string]*room
	startedAt time.Time
}

func NewBlindRelayHub() *BlindRelayHub {
	return &BlindRelayHub{
		rooms:     make(map[string]*room),
		startedAt: time.Now(),
	}
}

func (h *BlindRelayHub) getOrCreateRoom(sentinelID, channelToken string) (*room, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if existing, ok := h.rooms[sentinelID]; ok {
		existing.mu.Lock()
		defer existing.mu.Unlock()
		if subtle.ConstantTimeCompare([]byte(existing.channelToken), []byte(channelToken)) != 1 {
			// Allow reclaiming an empty stale room if sentinel rotated keys
			if existing.sentinel == nil && len(existing.controllers) == 0 {
				existing.channelToken = channelToken
				existing.lastActivity = time.Now()
				return existing, true
			}
			return nil, false
		}
		return existing, true
	}

	rm := &room{
		sentinelID:   sentinelID,
		channelToken: channelToken,
		controllers:  make(map[*clientConn]struct{}),
		lastActivity: time.Now(),
		tokens:       rateLimitBurst,
		lastRefill:   time.Now(),
	}
	h.rooms[sentinelID] = rm
	return rm, true
}

func (h *BlindRelayHub) stats() map[string]any {
	h.mu.RLock()
	defer h.mu.RUnlock()

	activeSentinels := 0
	activeControllers := 0
	for _, rm := range h.rooms {
		rm.mu.Lock()
		if rm.sentinel != nil {
			activeSentinels++
		}
		activeControllers += len(rm.controllers)
		rm.mu.Unlock()
	}
	return map[string]any{
		"ok":                 true,
		"service":            "wakepilot-blind-relay",
		"rooms":              len(h.rooms),
		"online_sentinels":   activeSentinels,
		"online_controllers": activeControllers,
		"uptime_sec":         int(time.Since(h.startedAt).Seconds()),
	}
}

func (h *BlindRelayHub) HandleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.stats())
}

// HandleWS upgrades Sentinel or Controller connections:
//   GET /ws?role=sentinel|controller&id=<sentinel_id>&channel=<sha256_channel_token>
func (h *BlindRelayHub) HandleWS(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	role := strings.ToLower(strings.TrimSpace(q.Get("role")))
	sentinelID := strings.TrimSpace(q.Get("id"))
	channelToken := strings.TrimSpace(q.Get("channel"))
	if channelToken == "" {
		channelToken = strings.TrimSpace(r.Header.Get("X-Channel-Token"))
	}

	if (role != "sentinel" && role != "controller") || len(sentinelID) < 4 || len(sentinelID) > 64 || len(channelToken) < 16 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid role, id, or channel token"})
		return
	}

	rm, ok := h.getOrCreateRoom(sentinelID, channelToken)
	if !ok {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "channel token mismatch for sentinel room"})
		return
	}

	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[ws] upgrade failed for %s/%s: %v", role, sentinelID, err)
		return
	}

	cc := &clientConn{
		ws:   ws,
		send: make(chan []byte, 32),
		role: role,
	}

	rm.mu.Lock()
	if role == "sentinel" {
		if rm.sentinel != nil {
			// Replace stale sentinel connection cleanly
			close(rm.sentinel.send)
		}
		rm.sentinel = cc
		notifyControllersLocked(rm, sentinelPresenceFrame(sentinelID, true))
	} else {
		if len(rm.controllers) >= maxControllersRoom {
			rm.mu.Unlock()
			_ = ws.Close()
			return
		}
		rm.controllers[cc] = struct{}{}
		// Immediately notify the newly connected controller whether sentinel is online
		trySend(cc, sentinelPresenceFrame(sentinelID, rm.sentinel != nil))
	}
	rm.mu.Unlock()

	log.Printf("[ws] connected role=%s sentinel=%s remote=%s", role, sentinelID, r.RemoteAddr)

	go writePump(cc)
	readPump(rm, cc)

	// Cleanup on disconnect
	rm.mu.Lock()
	if role == "sentinel" {
		if rm.sentinel == cc {
			rm.sentinel = nil
			notifyControllersLocked(rm, sentinelPresenceFrame(sentinelID, false))
		}
	} else {
		delete(rm.controllers, cc)
	}
	rm.mu.Unlock()
	close(cc.send)
	log.Printf("[ws] disconnected role=%s sentinel=%s", role, sentinelID)
}

// HandleOneShotRelay accepts a stateless HTTPS POST containing an E2EE envelope
// (designed for Android AppWidget & Quick Settings Tile one-tap wake without opening a WebSocket).
//   POST /api/v1/relay/{sentinel_id}
//   Header: X-Channel-Token: <sha256_channel_token>
func (h *BlindRelayHub) HandleOneShotRelay(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "POST required"})
		return
	}
	sentinelID := strings.TrimPrefix(r.URL.Path, "/api/v1/relay/")
	sentinelID = strings.Trim(sentinelID, "/")
	channelToken := strings.TrimSpace(r.Header.Get("X-Channel-Token"))
	if sentinelID == "" || len(channelToken) < 16 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing sentinel_id or X-Channel-Token"})
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxFrameBytes))
	if err != nil || len(body) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "empty or unreadable envelope body"})
		return
	}

	h.mu.RLock()
	rm, exists := h.rooms[sentinelID]
	h.mu.RUnlock()
	if !exists {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "sentinel offline", "sentinel_online": false})
		return
	}

	rm.mu.Lock()
	defer rm.mu.Unlock()
	if subtle.ConstantTimeCompare([]byte(rm.channelToken), []byte(channelToken)) != 1 {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "invalid X-Channel-Token"})
		return
	}
	if rm.sentinel == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "sentinel offline", "sentinel_online": false})
		return
	}
	if !rm.allowFrame() {
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "rate limit exceeded"})
		return
	}

	if !trySend(rm.sentinel, body) {
		writeJSON(w, http.StatusBadGateway, map[string]any{"error": "sentinel send buffer full"})
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{
		"ok":              true,
		"forwarded":       true,
		"sentinel_online": true,
		"bytes":           len(body),
	})
}

func readPump(rm *room, cc *clientConn) {
	defer cc.ws.Close()
	cc.ws.SetReadLimit(maxFrameBytes)
	_ = cc.ws.SetReadDeadline(time.Now().Add(pongWait))
	cc.ws.SetPongHandler(func(string) error {
		_ = cc.ws.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		msgType, payload, err := cc.ws.ReadMessage()
		if err != nil {
			return
		}
		if msgType != websocket.TextMessage && msgType != websocket.BinaryMessage {
			continue
		}

		rm.mu.Lock()
		if !rm.allowFrame() {
			rm.mu.Unlock()
			continue
		}
		if cc.role == "controller" {
			if rm.sentinel != nil {
				trySend(rm.sentinel, payload)
			} else {
				trySend(cc, sentinelPresenceFrame(rm.sentinelID, false))
			}
		} else {
			// Sentinel broadcasts E2EE telemetry/progress to all connected controllers
			notifyControllersLocked(rm, payload)
		}
		rm.mu.Unlock()
	}
}

func writePump(cc *clientConn) {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		_ = cc.ws.Close()
	}()
	for {
		select {
		case msg, ok := <-cc.send:
			_ = cc.ws.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = cc.ws.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := cc.ws.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-ticker.C:
			_ = cc.ws.SetWriteDeadline(time.Now().Add(writeWait))
			if err := cc.ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func notifyControllersLocked(rm *room, frame []byte) {
	for ctrl := range rm.controllers {
		trySend(ctrl, frame)
	}
}

func trySend(cc *clientConn, frame []byte) bool {
	select {
	case cc.send <- frame:
		return true
	default:
		return false
	}
}

func sentinelPresenceFrame(sentinelID string, online bool) []byte {
	b, _ := json.Marshal(map[string]any{
		"relay_event":     "sentinel_presence",
		"sid":             sentinelID,
		"sentinel_online": online,
		"ts":              time.Now().Unix(),
	})
	return b
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
