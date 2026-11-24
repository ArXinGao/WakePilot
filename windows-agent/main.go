package main

import (
	"crypto/subtle"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"
)

const (
	defaultAgentPort     = 18766
	defaultDiscoveryPort = 18767
	discoverMagic        = "WAKEPILOT_DISCOVER_V1"
)

type AgentConfig struct {
	PCName          string            `json:"pc_name"`
	ListenPort      int               `json:"listen_port"`
	LANKey          string            `json:"lan_key"`
	RustDeskID      string            `json:"rustdesk_id"`
	AutoLockSeconds int               `json:"auto_lock_seconds"`
	AppOverrides    map[string]string `json:"app_overrides"`
}

type AgentServer struct {
	cfg       AgentConfig
	specs     map[string]AppSpec
	startedAt time.Time
	dryRun    bool
}

func primaryWiredNetInfo() (mac string, ip string) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "", ""
	}
	virtualHints := []string{
		"virtual", "vethernet", "wsl", "hyper-v", "vmware", "vbox", "docker",
		"tailscale", "easytier", "zerotier", "loopback", "tap", "tun", "npcap", "bluetooth",
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || len(iface.HardwareAddr) != 6 {
			continue
		}
		nameLow := strings.ToLower(iface.Name)
		macStr := strings.ToUpper(iface.HardwareAddr.String())
		// Skip Hyper-V (00:15:5D) and common virtual adapters
		if strings.HasPrefix(macStr, "00:15:5D") || strings.HasPrefix(macStr, "00:50:56") || strings.HasPrefix(macStr, "08:00:27") {
			continue
		}
		isVirtual := false
		for _, h := range virtualHints {
			if strings.Contains(nameLow, h) {
				isVirtual = true
				break
			}
		}
		if isVirtual {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			if ipNet, ok := a.(*net.IPNet); ok && ipNet.IP.To4() != nil && !ipNet.IP.IsLinkLocalUnicast() {
				mac = macStr
				ip = ipNet.IP.String()
				if strings.Contains(nameLow, "ethernet") || strings.Contains(nameLow, "以太网") || strings.Contains(nameLow, "lan") {
					return mac, ip
				}
			}
		}
	}
	return mac, ip
}

func (a *AgentServer) authOK(r *http.Request) bool {
	if a.cfg.LANKey == "" {
		return true
	}
	got := strings.TrimSpace(r.Header.Get("X-WakePilot-Key"))
	return subtle.ConstantTimeCompare([]byte(got), []byte(a.cfg.LANKey)) == 1
}

func (a *AgentServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		if !a.authOK(r) {
			writeAgentJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid X-WakePilot-Key"})
			return
		}
		mac, ip := primaryWiredNetInfo()
		rdOK, _ := detectRustDeskInstalled()
		fastOK, fastMsg := checkFastStartupDisabled()
		writeAgentJSON(w, http.StatusOK, map[string]any{
			"ok":                  true,
			"service":             "wakepilot-windows-agent",
			"pc_name":             a.cfg.PCName,
			"mac":                 mac,
			"lan_ip":              ip,
			"uptime_sec":          int(time.Since(a.startedAt).Seconds()),
			"fast_startup_ok":     fastOK,
			"fast_startup_detail": fastMsg,
			"rustdesk_installed":  rdOK,
			"rustdesk_id":         a.cfg.RustDeskID,
			"apps":                inspectAllApps(a.specs, a.cfg.AppOverrides),
		})
	})

	mux.HandleFunc("/launch", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeAgentJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "POST required"})
			return
		}
		if !a.authOK(r) {
			writeAgentJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid X-WakePilot-Key"})
			return
		}
		var req struct {
			Apps     []string `json:"apps"`
			AutoLock bool     `json:"auto_lock"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeAgentJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid JSON"})
			return
		}
		if len(req.Apps) == 0 {
			req.Apps = []string{"workbuddy", "doubao", "antigravity"}
		}
		results := launchApps(a.specs, a.cfg.AppOverrides, req.Apps, a.dryRun)

		lockedScheduled := false
		if req.AutoLock {
			lockedScheduled = true
			delay := a.cfg.AutoLockSeconds
			if delay <= 0 {
				delay = 3
			}
			go func() {
				time.Sleep(time.Duration(delay) * time.Second)
				if !a.dryRun {
					if err := lockWorkStation(); err != nil {
						log.Printf("[auto-lock] failed: %v", err)
					} else {
						log.Printf("[auto-lock] desktop locked via Win32 LockWorkStation()")
					}
				}
			}()
		}

		writeAgentJSON(w, http.StatusOK, map[string]any{
			"ok":               true,
			"results":          results,
			"auto_lock_queued": lockedScheduled,
		})
	})

	mux.HandleFunc("/power", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeAgentJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "POST required"})
			return
		}
		if !a.authOK(r) {
			writeAgentJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid X-WakePilot-Key"})
			return
		}
		var req struct {
			Action string `json:"action"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeAgentJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid JSON"})
			return
		}
		if a.dryRun {
			writeAgentJSON(w, http.StatusOK, map[string]any{"ok": true, "message": "[dry-run] " + req.Action})
			return
		}
		msg, err := triggerPowerAction(strings.ToLower(strings.TrimSpace(req.Action)), 5)
		if err != nil {
			writeAgentJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeAgentJSON(w, http.StatusOK, map[string]any{"ok": true, "message": msg})
	})

	return mux
}

// startDiscoveryResponder replies to LAN UDP broadcasts from the Home Sentinel app
// so users never have to manually copy MAC addresses or LAN IPs!
func (a *AgentServer) startDiscoveryResponder() {
	addr := &net.UDPAddr{Port: defaultDiscoveryPort}
	conn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		log.Printf("[discovery] UDP :%d unavailable: %v", defaultDiscoveryPort, err)
		return
	}
	log.Printf("[discovery] listening on UDP :%d for Sentinel auto-discovery", defaultDiscoveryPort)

	go func() {
		defer conn.Close()
		buf := make([]byte, 512)
		for {
			n, remote, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if strings.TrimSpace(string(buf[:n])) != discoverMagic {
				continue
			}
			mac, ip := primaryWiredNetInfo()
			reply, _ := json.Marshal(map[string]any{
				"service":     "wakepilot-windows-agent",
				"pc_name":     a.cfg.PCName,
				"mac":         mac,
				"lan_ip":      ip,
				"agent_port":  a.cfg.ListenPort,
				"rustdesk_id": a.cfg.RustDeskID,
			})
			_, _ = conn.WriteToUDP(reply, remote)
		}
	}()
}

func runSelfCheck(cfg AgentConfig, specs map[string]AppSpec) {
	fmt.Println("============================================================")
	fmt.Println("  WakePilot Windows 端红绿灯一键体检")
	fmt.Println("============================================================")
	fastOK, fastMsg := checkFastStartupDisabled()
	tag := "[绿灯]"
	if !fastOK {
		tag = "[红灯]"
	}
	fmt.Printf("1. 快速启动状态   : %s %s\n", tag, fastMsg)

	mac, ip := primaryWiredNetInfo()
	fmt.Printf("2. 主网卡与局域网 : [信息] MAC=%s | IP=%s\n", mac, ip)

	rdOK, rdPath := detectRustDeskInstalled()
	if rdOK {
		fmt.Printf("3. RustDesk 锁屏  : [绿灯] 已安装 (%s)\n", rdPath)
	} else {
		fmt.Println("3. RustDesk 锁屏  : [黄灯] 未检测到（若需远程输入开机密码建议安装开源 RustDesk）")
	}

	fmt.Println("4. AI 客户端探测  :")
	for _, st := range inspectAllApps(specs, cfg.AppOverrides) {
		flag := "[未发现]"
		if st.Installed {
			flag = "[绿灯]"
		}
		fmt.Printf("   - %-18s %s 运行中=%v 路径=%s\n", st.DisplayName, flag, st.Running, st.Executable)
	}
	fmt.Println("5. BIOS 提醒      : 请确认主板 BIOS 已关闭 ErP/EuP、已开启 Power On By PCI-E")
}

func writeAgentJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func loadAgentConfig(path string) AgentConfig {
	host, _ := os.Hostname()
	cfg := AgentConfig{
		PCName:          host,
		ListenPort:      defaultAgentPort,
		AutoLockSeconds: 3,
		AppOverrides:    map[string]string{},
	}
	if path != "" {
		if raw, err := os.ReadFile(path); err == nil {
			_ = json.Unmarshal(raw, &cfg)
		}
	}
	if cfg.ListenPort <= 0 {
		cfg.ListenPort = defaultAgentPort
	}
	if cfg.PCName == "" {
		cfg.PCName = host
	}
	return cfg
}

func main() {
	cfgPath := flag.String("config", "wakepilot-agent.json", "Path to optional JSON config")
	doCheck := flag.Bool("check", false, "Run WoL & AI client traffic-light self-check and exit")
	doFix := flag.Bool("fix", false, "Fix Windows Fast Startup, NIC WoL, and firewall rules (run as Admin)")
	dryRun := flag.Bool("dry-run", false, "Dry-run mode without launching apps or powering off")
	flag.Parse()

	cfg := loadAgentConfig(*cfgPath)
	specs := defaultAppSpecs(cfg.PCName)

	if *doFix {
		for _, line := range fixWindowsWoL(cfg.ListenPort) {
			fmt.Println(line)
		}
		return
	}
	if *doCheck {
		runSelfCheck(cfg, specs)
		return
	}

	srv := &AgentServer{
		cfg:       cfg,
		specs:     specs,
		startedAt: time.Now(),
		dryRun:    *dryRun,
	}
	srv.startDiscoveryResponder()

	addr := fmt.Sprintf("0.0.0.0:%d", cfg.ListenPort)
	log.Printf("WakePilot Windows Agent running on http://%s (PC=%s, dryRun=%v)", addr, cfg.PCName, *dryRun)
	log.Fatal(http.ListenAndServe(addr, srv.Handler()))
}
