package main

import (
	"crypto/tls"
	"flag"
	"log"
	"net/http"
	"time"
)

func main() {
	addr := flag.String("listen", ":8443", "Listen address (e.g. :443 with TLS or :8443 behind reverse proxy/direct)")
	tlsCert := flag.String("tls-cert", "", "Optional path to TLS certificate PEM file (enables HTTPS/WSS)")
	tlsKey := flag.String("tls-key", "", "Optional path to TLS private key PEM file")
	flag.Parse()

	hub := NewBlindRelayHub()

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", hub.HandleHealth)
	mux.HandleFunc("/ws", hub.HandleWS)
	mux.HandleFunc("/api/v1/relay/", hub.HandleOneShotRelay)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"service": "WakePilot Blind E2EE Relay",
			"status":  "running",
			"endpoints": []string{
				"GET /healthz",
				"GET /ws?role=sentinel|controller&id=<sid>&channel=<sha256_token>",
				"POST /api/v1/relay/<sid>",
			},
		})
	})

	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       90 * time.Second,
	}

	if *tlsCert != "" && *tlsKey != "" {
		srv.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
		log.Printf("WakePilot Blind Relay listening with TLS (HTTPS/WSS) on %s", *addr)
		log.Fatal(srv.ListenAndServeTLS(*tlsCert, *tlsKey))
	} else {
		log.Printf("WakePilot Blind Relay listening on %s (HTTP/WS; place behind Caddy/Nginx 443 or pass --tls-cert/--tls-key)", *addr)
		log.Fatal(srv.ListenAndServe())
	}
}
