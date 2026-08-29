# Phase 1: 服务器初始化

> 预计耗时：1 天  
> 目标：把裸机变成可部署的安全 Linux 服务器  
> 分支：`feat/phase-1-server-init`

## 🎯 完成标志

- [ ] `deploy` 用户（非 root）能 SSH 密钥登录
- [ ] root 密码登录已禁用
- [ ] UFW 防火墙仅放行 22/80/443
- [ ] fail2ban 正常运行
- [ ] Docker / Docker Compose 已装好
- [ ] 时区为 Asia/Shanghai
- [ ] 系统已 update 到最新
- [ ] `/opt/cloudlink/` 目录结构建好

## 📝 实施步骤

### 步骤 1.1：首次 SSH 登录

```bash
# 在 macOS 终端
ssh root@<服务器IP>
# 输入 root 密码
```

### 步骤 1.2：创建 deploy 用户

```bash
# 创建用户并设置密码
adduser deploy
# （输入两次密码）

# 加入 sudo 组
usermod -aG sudo deploy

# 验证 sudo 能力
su - deploy
sudo whoami   # 应该输出 root
```

### 步骤 1.3：配置 SSH 密钥登录

**在 macOS 上**（不连服务器）：

```bash
# 如果还没有 SSH 密钥
ssh-keygen -t ed25519 -C "your_email@example.com"

# 复制公钥到服务器（替换 deploy@SERVER_IP）
ssh-copy-id -i ~/.ssh/id_ed25519.pub deploy@<服务器IP>
```

**测试密钥登录**：

```bash
ssh deploy@<服务器IP>   # 不应再问密码
```

### 步骤 1.4：禁用 root 密码登录 + 改 SSH 端口

**在服务器上**（用 deploy 用户 sudo）：

```bash
sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak
sudo nano /etc/ssh/sshd_config
```

修改以下项：

```
Port 2222                            # 改端口（可选项）
PermitRootLogin no                   # 禁止 root 登录
PasswordAuthentication no            # 禁止密码登录
PubkeyAuthentication yes
AllowUsers deploy                    # 只允许 deploy
```

重启 SSH：

```bash
sudo systemctl restart sshd
```

**测试**（新开终端，新端口、deploy 用户、密钥）：

```bash
ssh -p 2222 deploy@<服务器IP>
```

确认能登录后再关旧窗口。

### 步骤 1.5：系统更新

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl git vim ufw fail2ban jq htop chrony
```

### 步骤 1.6：时区设置

```bash
sudo timedatectl set-timezone Asia/Shanghai
sudo systemctl enable chrony
date   # 验证
```

### 步骤 1.7：UFW 防火墙

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing

# 如果 SSH 改了端口
sudo ufw allow 2222/tcp comment 'SSH'

# Web 服务
sudo ufw allow 80/tcp comment 'HTTP'
sudo ufw allow 443/tcp comment 'HTTPS'

sudo ufw enable
sudo ufw status verbose
```

### 步骤 1.8：fail2ban 配置

```bash
sudo cp /etc/fail2ban/jail.conf /etc/fail2ban/jail.local
sudo nano /etc/fail2ban/jail.local
```

在 `[sshd]` 段下添加（或确认）：

```
enabled = true
port = 2222
maxretry = 3
bantime = 3600
findtime = 600
```

启动：

```bash
sudo systemctl enable fail2ban
sudo systemctl restart fail2ban
sudo fail2ban-client status sshd
```

### 步骤 1.9：安装 Docker

```bash
# 使用官方脚本
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh --dry-run   # 先 dry-run 看会装什么
sudo sh get-docker.sh             # 实际安装

# 把 deploy 加入 docker 组（免 sudo）
sudo usermod -aG docker deploy
newgrp docker  # 立即生效，或重新登录

# 验证
docker --version
docker compose version
docker run hello-world
```

### 步骤 1.10：创建项目目录结构

```bash
sudo mkdir -p /opt/cloudlink/{backend,android,docs,scripts,ssl,logs,data}
sudo chown -R deploy:deploy /opt/cloudlink
```

### 步骤 1.11：配置 swap（如内存 < 4G）

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h   # 验证
```

### 步骤 1.12：域名解析

到域名注册商（或 Cloudflare）配置：

| 主机记录 | 记录类型 | 记录值 |
|---|---|---|
| @ | A | 服务器 IP |
| api | A | 服务器 IP |
| * | A | 服务器 IP（通配） |

验证：

```bash
dig api.your-domain.com +short   # 应返回服务器 IP
```

## 🤖 一键脚本（推荐）

以上步骤可以合并为一个脚本 `scripts/init-server.sh`，在服务器上跑一次完成。

详见 [`scripts/init-server.sh`](../../scripts/init-server.sh)（Phase 1 完成后会生成）。

## ✅ 验收测试

```bash
# 1. SSH 密钥登录正常
ssh -p 2222 deploy@<服务器IP>

# 2. 防火墙状态
sudo ufw status   # 仅 2222/80/443

# 3. Docker 可用
docker run hello-world   # 不报错

# 4. 时间正确
date   # Asia/Shanghai 时区

# 5. fail2ban 运行
sudo fail2ban-client status sshd
```

## 📦 提交规范

```bash
git checkout -b feat/phase-1-server-init
git add scripts/init-server.sh
git commit -m "feat(scripts): add server init script for phase 1"
```

## 🚀 下一步

Phase 1 完成后，进入 **Phase 2：后端开发**。
