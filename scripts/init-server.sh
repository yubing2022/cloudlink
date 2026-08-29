#!/usr/bin/env bash
# =============================================================================
# CloudLink 服务器一键初始化脚本
# 适用于：Ubuntu 22.04+ 阿里云 ECS
# 运行：sudo bash init-server.sh
# 注意：此脚本需要用 deploy 用户运行，deploy 用户本身需手动创建
# =============================================================================

set -e
set -u

# ---- 配置 ----
DEPLOY_USER="deploy"
SSH_PORT=2222
SERVER_NAME="cloudlink-server"

# ---- 颜色输出 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] WARNING:${NC} $*"; }
err() { echo -e "${RED}[$(date +%H:%M:%S)] ERROR:${NC} $*"; exit 1; }

# ---- 前置检查 ----
if [ "$EUID" -ne 0 ]; then
    err "请用 sudo 运行: sudo bash $0"
fi

log "🚀 开始初始化 CloudLink 服务器..."

# ---- 1. 系统更新 ----
log "📦 更新系统..."
apt update && apt upgrade -y

# ---- 2. 装基础工具 ----
log "🔧 安装基础工具..."
apt install -y curl git vim ufw fail2ban jq htop chrony software-properties-common

# ---- 3. 时区 ----
log "🕐 设置时区为 Asia/Shanghai..."
timedatectl set-timezone Asia/Shanghai
systemctl enable chrony

# ---- 4. SSH 配置 ----
if id "$DEPLOY_USER" &>/dev/null; then
    log "👤 用户 $DEPLOY_USER 已存在，跳过创建"
else
    log "👤 创建 deploy 用户..."
    adduser --disabled-password --gecos "" $DEPLOY_USER
    usermod -aG sudo $DEPLOY_USER
fi

# 备份原配置
cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.$(date +%Y%m%d)

log "🔐 配置 SSH..."
cat > /etc/ssh/sshd_config.d/cloudlink.conf <<EOF
Port $SSH_PORT
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
AllowUsers $DEPLOY_USER
EOF

systemctl restart sshd || warn "SSH 重启失败，请手动检查端口 $SSH_PORT 是否被占用"

# ---- 5. UFW 防火墙 ----
log "🛡️ 配置 UFW 防火墙..."
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow $SSH_PORT/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP'
ufw allow 443/tcp comment 'HTTPS'
ufw --force enable

# ---- 6. fail2ban ----
log "🚨 配置 fail2ban..."
cat > /etc/fail2ban/jail.local <<EOF
[DEFAULT]
bantime = 3600
findtime = 600
maxretry = 3

[sshd]
enabled = true
port = $SSH_PORT
filter = sshd
logpath = /var/log/auth.log
maxretry = 3
EOF
systemctl enable fail2ban
systemctl restart fail2ban

# ---- 7. Docker ----
if command -v docker &>/dev/null; then
    log "🐳 Docker 已安装: $(docker --version)"
else
    log "🐳 安装 Docker..."
    # 确保 docker 组存在（某些 Docker 安装脚本不会自动创建）
    groupadd -f docker

    curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
    sh /tmp/get-docker.sh --dry-run   # 先 dry-run 看会装什么
    sh /tmp/get-docker.sh             # 实际安装

    # 把 deploy 加入 docker 组（免 sudo）
    # 再次确保 docker 组存在（Docker 安装可能清理）
    groupadd -f docker
    usermod -aG docker $DEPLOY_USER

    log "    ℹ deploy 用户需要重新登录才能免 sudo 用 docker"
fi

# ---- 8. 项目目录 ----
log "📁 创建项目目录..."
mkdir -p /opt/cloudlink/{backend,android,docs,scripts,ssl,logs,data,backups/postgres}
chown -R $DEPLOY_USER:$DEPLOY_USER /opt/cloudlink
chmod 755 /opt/cloudlink

# ---- 9. Swap (如果内存 < 4G) ----
TOTAL_MEM=$(free -g | awk '/^Mem:/{print $2}')
if [ "$TOTAL_MEM" -lt 4 ]; then
    if [ ! -f /swapfile ]; then
        log "💾 创建 2G swap 文件..."
        fallocate -l 2G /swapfile
        chmod 600 /swapfile
        mkswap /swapfile
        swapon /swapfile
        echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi
else
    log "💾 内存充足 ($TOTAL_MEM GB)，跳过 swap 创建"
fi

# ---- 10. 内核优化 ----
log "⚙️ 应用内核优化..."
cat > /etc/sysctl.d/99-cloudlink.conf <<EOF
# 网络优化
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 15

# 文件描述符
fs.file-max = 100000

# 虚拟内存
vm.swappiness = 10
EOF
sysctl -p /etc/sysctl.d/99-cloudlink.conf

# ---- 11. 主机名 ----
hostnamectl set-hostname $SERVER_NAME

# ---- 完成 ----
log ""
log "═══════════════════════════════════════════════════════════════"
log "✅ CloudLink 服务器初始化完成！"
log "═══════════════════════════════════════════════════════════════"
log ""
log "📋 接下来你需要做："
log "  1. 在 macOS 上: ssh-copy-id -i ~/.ssh/id_ed25519.pub deploy@<服务器IP> -p $SSH_PORT"
log "  2. 测试新端口登录: ssh -p $SSH_PORT deploy@<服务器IP>"
log "  3. 关闭旧 SSH 连接窗口"
log "  4. 进入 Phase 2: cd /opt/cloudlink/backend && 编写后端代码"
log ""
log "📊 服务器信息："
log "  - 用户: $DEPLOY_USER (sudo)"
log "  - SSH 端口: $SSH_PORT (密钥登录)"
log "  - 防火墙: 22(已禁用) / $SSH_PORT / 80 / 443"
log "  - 项目目录: /opt/cloudlink/"
log "  - 时区: $(timedatectl | grep 'Time zone')"
log ""
