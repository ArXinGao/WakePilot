package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type AppSpec struct {
	ID             string   `json:"id"`
	DisplayName    string   `json:"display_name"`
	ProcessNames   []string `json:"process_names"`
	CandidatePaths []string `json:"candidate_paths"`
	CLIFallback    []string `json:"cli_fallback,omitempty"`
}

type AppStatus struct {
	ID          string `json:"id"`
	DisplayName string `json:"display_name"`
	Installed   bool   `json:"installed"`
	Executable  string `json:"executable,omitempty"`
	Running     bool   `json:"running"`
}

type LaunchResult struct {
	OK      bool   `json:"ok"`
	Status  string `json:"status"`
	PID     int    `json:"pid,omitempty"`
	Message string `json:"message"`
}

func defaultAppSpecs(pcName string) map[string]AppSpec {
	localApp := os.Getenv("LOCALAPPDATA")
	return map[string]AppSpec{
		"workbuddy": {
			ID:           "workbuddy",
			DisplayName:  "腾讯 WorkBuddy",
			ProcessNames: []string{"workbuddy.exe"},
			CandidatePaths: []string{
				`C:\Program Files\WorkBuddy\WorkBuddy.exe`,
				filepath.Join(localApp, `Programs\WorkBuddy\WorkBuddy.exe`),
				`D:\WorkBuddy\WorkBuddy.exe`,
			},
		},
		"doubao": {
			ID:           "doubao",
			DisplayName:  "豆包 PC 客户端",
			ProcessNames: []string{"doubao.exe"},
			CandidatePaths: []string{
				filepath.Join(localApp, `Doubao\Application\Doubao.exe`),
				filepath.Join(localApp, `Doubao\Application\app\Doubao.exe`),
				`C:\Program Files\Doubao\Doubao.exe`,
			},
		},
		"antigravity": {
			ID:           "antigravity",
			DisplayName:  "Google Antigravity",
			ProcessNames: []string{"antigravity.exe", "agy.exe"},
			CandidatePaths: []string{
				filepath.Join(localApp, `Programs\Antigravity\Antigravity.exe`),
				`C:\Program Files\Antigravity\Antigravity.exe`,
				`C:\Program Files\Google\Antigravity\Antigravity.exe`,
			},
			CLIFallback: []string{"agy", "remote-control", "start", "--name", pcName},
		},
	}
}

func resolveAppExe(spec AppSpec, customOverrides map[string]string) string {
	if customOverrides != nil {
		if ov := strings.TrimSpace(customOverrides[spec.ID]); ov != "" {
			if st, err := os.Stat(ov); err == nil && !st.IsDir() {
				return ov
			}
		}
	}
	for _, cand := range spec.CandidatePaths {
		if cand == "" {
			continue
		}
		if st, err := os.Stat(cand); err == nil && !st.IsDir() {
			return cand
		}
	}
	return ""
}

func detectRustDeskInstalled() (bool, string) {
	cands := []string{
		`C:\Program Files\RustDesk\rustdesk.exe`,
		`C:\Program Files (x86)\RustDesk\rustdesk.exe`,
	}
	for _, c := range cands {
		if st, err := os.Stat(c); err == nil && !st.IsDir() {
			return true, c
		}
	}
	return false, ""
}

func inspectAllApps(specs map[string]AppSpec, overrides map[string]string) map[string]AppStatus {
	running := listRunningProcessNames()
	out := make(map[string]AppStatus, len(specs))
	for id, spec := range specs {
		exe := resolveAppExe(spec, overrides)
		cliOK := false
		if exe == "" && len(spec.CLIFallback) > 0 {
			if _, err := exec.LookPath(spec.CLIFallback[0]); err == nil {
				cliOK = true
			}
		}
		isRunning := false
		for _, p := range spec.ProcessNames {
			if running[strings.ToLower(p)] {
				isRunning = true
				break
			}
		}
		out[id] = AppStatus{
			ID:          id,
			DisplayName: spec.DisplayName,
			Installed:   exe != "" || cliOK,
			Executable:  exe,
			Running:     isRunning,
		}
	}
	return out
}

func launchApps(specs map[string]AppSpec, overrides map[string]string, appIDs []string, dryRun bool) map[string]LaunchResult {
	running := listRunningProcessNames()
	results := make(map[string]LaunchResult, len(appIDs))

	for _, rawID := range appIDs {
		id := strings.ToLower(strings.TrimSpace(rawID))
		spec, ok := specs[id]
		if !ok {
			results[id] = LaunchResult{OK: false, Status: "unknown_app", Message: "不在白名单中: " + id}
			continue
		}

		already := false
		for _, p := range spec.ProcessNames {
			if running[strings.ToLower(p)] {
				already = true
				break
			}
		}
		if already {
			results[id] = LaunchResult{OK: true, Status: "already_running", Message: spec.DisplayName + " 已在运行（跳过重复启动）"}
			continue
		}

		if dryRun {
			results[id] = LaunchResult{OK: true, Status: "dry_run_launched", Message: "[dry-run] 模拟启动 " + spec.DisplayName}
			continue
		}

		exe := resolveAppExe(spec, overrides)
		var args []string
		workDir := ""
		if exe != "" {
			workDir = filepath.Dir(exe)
		} else if len(spec.CLIFallback) > 0 {
			if cliPath, err := exec.LookPath(spec.CLIFallback[0]); err == nil {
				exe = cliPath
				args = spec.CLIFallback[1:]
			}
		}

		if exe == "" {
			results[id] = LaunchResult{OK: false, Status: "not_found", Message: "未找到 " + spec.DisplayName + " 安装路径"}
			continue
		}

		pid, err := launchDetached(exe, args, workDir)
		if err != nil {
			results[id] = LaunchResult{OK: false, Status: "error", Message: fmt.Sprintf("启动失败: %v", err)}
			continue
		}
		results[id] = LaunchResult{OK: true, Status: "launched", PID: pid, Message: fmt.Sprintf("已启动 %s (PID=%d)", spec.DisplayName, pid)}
	}
	return results
}
