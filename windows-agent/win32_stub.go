//go:build !windows

package main

import "fmt"

func lockWorkStation() error { return fmt.Errorf("LockWorkStation requires Windows") }

func checkFastStartupDisabled() (bool, string) { return true, "non-windows stub" }

func fixWindowsWoL(agentPort int) []string { return []string{"non-windows stub"} }

func listRunningProcessNames() map[string]bool { return map[string]bool{} }

func launchDetached(exePath string, args []string, workDir string) (int, error) {
	return 0, fmt.Errorf("launch requires Windows")
}

func triggerPowerAction(action string, delaySec int) (string, error) {
	return "", fmt.Errorf("power action %s requires Windows", action)
}
