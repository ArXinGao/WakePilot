@echo off
chcp 65001 >nul
title WakePilot Windows Agent One-Click Installer
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell.exe -NoProfile -Command "Start-Process -FilePath 'cmd.exe' -ArgumentList '/c \"%~f0\"' -Verb RunAs"
    exit /b
)

set "EXE=%~dp0..\dist\wakepilot-agent.exe"
if not exist "%EXE%" set "EXE=%~dp0wakepilot-agent.exe"
if not exist "%EXE%" (
    echo [ERROR] 未找到 wakepilot-agent.exe！
    pause
    exit /b 1
)

echo === [1/3] 自动修复 Windows 快速启动、网卡 WoL 与防火墙 ===
"%EXE%" --fix

echo.
echo === [2/3] 注册用户交互式登录计划任务（开机自动在桌面会话启动，避开 Session 0）===
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$u = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name; " ^
  "$a = New-ScheduledTaskAction -Execute '%EXE%' -WorkingDirectory (Split-Path '%EXE%' -Parent); " ^
  "$t = New-ScheduledTaskTrigger -AtLogOn -User $u; " ^
  "$p = New-ScheduledTaskPrincipal -UserId $u -LogonType Interactive -RunLevel Highest; " ^
  "$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Days 365); " ^
  "Register-ScheduledTask -TaskName 'WakePilotWindowsAgent' -Action $a -Trigger $t -Principal $p -Settings $s -Force | Out-Null; " ^
  "Start-ScheduledTask -TaskName 'WakePilotWindowsAgent'; " ^
  "Write-Host 'OK: WakePilotWindowsAgent scheduled task registered and started!' -ForegroundColor Green"

echo.
echo === [3/3] 运行红绿灯自检 ===
"%EXE%" --check

echo.
echo 安装完成！现在打开备用手机 WakePilot App（哨兵模式）点「扫描局域网电脑」，即可自动识别本机！
pause
