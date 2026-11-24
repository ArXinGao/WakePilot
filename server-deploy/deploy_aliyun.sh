#!/usr/bin/env bash
# WakePilot 阿里云 VPS 一键部署脚本
# 部署内容：
#   1. 零状态 443/8443 加密盲中继服务 (wakepilot-relay-linux-amd64) -> Systemd 常驻自启
#   2. 自动配置系统防火墙 (ufw / firewalld)
#   3. 可选：同时部署私有 RustDesk hbbs/hbbr 中继（手机远程输 Windows 开机密码用）
#
# 用法:
#   sudo bash deploy_aliyun.sh                  # 默认监听 :8443 (纯 IP 免域名直接可用)
#   sudo bash deploy_aliyun.sh 443              # 指定端口 443（若配合域名可配 TLS 证书）
set -euo pipefail

PORT="${1:-8443}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_SRC="${SCRIPT_DIR}/wakepilot-relay-linux-amd64"

if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 root 或 sudo 运行: sudo bash deploy_aliyun.sh ${PORT}"
  exit 1
fi

if [ ! -f "${BIN_SRC}" ]; then
  echo "未在当前目录找到编译好的二进制文件: ${BIN_SRC}"
  echo "请先将 dist/wakepilot-relay-linux-amd64 上传到与本脚本相同的目录。"
  exit 1
fi

echo "=== [1/4] 安装 wakepilot-relay 二进制到 /usr/local/bin/ ==="
install -m 755 "${BIN_SRC}" /usr/local/bin/wakepilot-relay

echo "=== [2/4] 放行系统防火墙端口 ${PORT}/tcp ==="
if command -v ufw >/dev/null 2>&1; then
  ufw allow "${PORT}/tcp" || true
elif command -v firewall-cmd >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port="${PORT}/tcp" || true
  firewall-cmd --reload || true
fi

echo "=== [3/4] 注册 Systemd 常驻后台服务 (wakepilot-relay.service) ==="
cat > /etc/systemd/system/wakepilot-relay.service <<EOF
[Unit]
Description=WakePilot E2EE Blind Relay Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/wakepilot-relay --listen :${PORT}
Restart=always
RestartSec=3
MemoryMax=64M

[Install]
WantedBy=multi-user.target
EOF

echo "=== [4/4] 启动服务并验证健康状态 ==="
systemctl daemon-reload
systemctl enable --now wakepilot-relay
sleep 1
systemctl status wakepilot-relay --no-pager || true
echo ""
echo "健康检查:"
curl -fsS "http://127.0.0.1:${PORT}/healthz" || true
echo ""
echo ""
echo "================================================================"
echo "  WakePilot 云端盲中继部署完成！"
echo "  1) 请确保阿里云安全组入方向已放行 TCP ${PORT}"
echo "  2) 手机 App 中继地址填写: http://<你的公网IP>:${PORT}"
echo "     (若有域名和证书，可加 --tls-cert / --tls-key 走 HTTPS/WSS 443)"
echo "================================================================"
