//go:build windows

package main

import (
	"fmt"
	"os/exec"
	"strings"
	"syscall"
)

var (
	user32              = syscall.NewLazyDLL("user32.dll")
	procLockWorkStation = user32.NewProc("LockWorkStation")
)

// lockWorkStation calls the native Win32 LockWorkStation() API to immediately lock the desktop.
func lockWorkStation() error {
	r1, _, err := procLockWorkStation.Call()
	if r1 == 0 {
		return fmt.Errorf("LockWorkStation failed: %w", err)
	}
	return nil
}

// runHiddenPS runs a PowerShell snippet silently without flashing a console window.
func runHiddenPS(script string) (string, error) {
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, err := cmd.CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// checkFastStartupDisabled checks HKLM HiberbootEnabled == 0.
func checkFastStartupDisabled() (bool, string) {
	out, err := runHiddenPS(`(Get-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power" -Name HiberbootEnabled -ErrorAction Stop).HiberbootEnabled`)
	if err != nil {
		return false, "未能读取快速启动注册表项"
	}
	if strings.TrimSpace(out) == "0" {
		return true, "快速启动已关闭 (HiberbootEnabled=0)"
	}
	return false, "快速启动处于开启状态 (HiberbootEnabled=" + out + ")，会阻断 S5 冷开机 WoL"
}

// fixWindowsWoL disables Fast Startup, enables NIC Wake on Magic Packet, and opens firewall port.
func fixWindowsWoL(agentPort int) []string {
	logs := []string{}
	ps := fmt.Sprintf(`
$ErrorActionPreference = 'Continue'
Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power" -Name HiberbootEnabled -Value 0 -Type DWord
Write-Output "OK: Fast Startup disabled"

$nics = Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.MediaType -eq '802.3' }
foreach ($nic in $nics) {
  $props = Get-NetAdapterAdvancedProperty -Name $nic.Name -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -match 'Magic Packet|Wake on LAN|Shutdown Wake|魔术封包|魔术包|关机.*唤醒' }
  foreach ($p in $props) {
    foreach ($v in @('Enabled','开启','启用')) {
      try { Set-NetAdapterAdvancedProperty -Name $nic.Name -DisplayName $p.DisplayName -DisplayValue $v -ErrorAction Stop; Write-Output "OK: $($nic.Name) $($p.DisplayName)=$v"; break } catch {}
    }
  }
  powercfg /deviceenablewake "$($nic.InterfaceDescription)" 2>$null | Out-Null
}

foreach ($rule in @(
  @{Name="WakePilot Agent TCP %d"; Proto="TCP"; Port=%d},
  @{Name="WakePilot Discovery UDP 18767"; Proto="UDP"; Port=18767},
  @{Name="RustDesk Direct IP TCP 21118"; Proto="TCP"; Port=21118}
)) {
  if (Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue) { Remove-NetFirewallRule -DisplayName $rule.Name }
  New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Protocol $rule.Proto -LocalPort $rule.Port -Action Allow -Profile Any | Out-Null
  Write-Output "OK: Firewall opened $($rule.Proto)/$($rule.Port)"
}
`, agentPort, agentPort)

	out, err := runHiddenPS(ps)
	if out != "" {
		logs = append(logs, strings.Split(out, "\n")...)
	}
	if err != nil {
		logs = append(logs, "WARN: 部分操作需要管理员权限运行: "+err.Error())
	}
	return logs
}

// listRunningProcessNames returns lowercase process image names via tasklist.
func listRunningProcessNames() map[string]bool {
	res := make(map[string]bool)
	cmd := exec.Command("tasklist", "/FO", "CSV", "/NH")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, err := cmd.Output()
	if err != nil {
		return res
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, `"`) {
			continue
		}
		end := strings.Index(line[1:], `"`)
		if end > 0 {
			res[strings.ToLower(line[1:1+end])] = true
		}
	}
	return res
}

// launchDetached spawns a GUI executable detached from the agent console.
func launchDetached(exePath string, args []string, workDir string) (int, error) {
	cmd := exec.Command(exePath, args...)
	if workDir != "" {
		cmd.Dir = workDir
	}
	const (
		detachedProcess        = 0x00000008
		createNewProcessGroup  = 0x00000200
	)
	cmd.SysProcAttr = &syscall.SysProcAttr{
		CreationFlags: detachedProcess | createNewProcessGroup,
	}
	if err := cmd.Start(); err != nil {
		return 0, err
	}
	return cmd.Process.Pid, nil
}

// triggerPowerAction executes allowlisted Windows power commands.
func triggerPowerAction(action string, delaySec int) (string, error) {
	if delaySec < 1 {
		delaySec = 5
	}
	switch action {
	case "lock":
		if err := lockWorkStation(); err != nil {
			return "", err
		}
		return "已立即锁定 Windows 桌面", nil
	case "shutdown":
		cmd := exec.Command("shutdown", "/s", "/t", fmt.Sprintf("%d", delaySec), "/c", "WakePilot remote shutdown")
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		return fmt.Sprintf("Windows 将在 %d 秒后安全关机", delaySec), cmd.Start()
	case "reboot":
		cmd := exec.Command("shutdown", "/r", "/t", fmt.Sprintf("%d", delaySec), "/c", "WakePilot remote reboot")
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		return fmt.Sprintf("Windows 将在 %d 秒后安全重启", delaySec), cmd.Start()
	case "cancel_shutdown":
		cmd := exec.Command("shutdown", "/a")
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		_ = cmd.Run()
		return "已尝试取消待执行的关机任务", nil
	case "sleep":
		go func() {
			_, _ = runHiddenPS(`Start-Sleep -Seconds 2; Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false)`)
		}()
		return "Windows 将在 2 秒后进入 S3 睡眠", nil
	default:
		return "", fmt.Errorf("unsupported power action: %s", action)
	}
}
