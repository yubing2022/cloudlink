# CloudLink

> 让 Home Assistant 设备突破局域网限制 —— 通过云端中转 + Android 客户端，随时随地控制你家的智能设备。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-in%20development-orange)]()
[![HA](https://img.shields.io/badge/Home%20Assistant-2024.1%2B-blue)](https://www.home-assistant.io/)

## ✨ 功能特性

- 🏠 **本地化优先**：所有设备控制走 HA 原生 service call，不绕开 HA 自动化体系
- ☁️ **云端中转**：自建云服务器，跨 NAT 也能控制
- 📱 **Android 客户端**：原生 Kotlin + Compose，Material 3 设计
- 🔐 **端到端加密**：JWT 鉴权 + Fernet 加密存储 HA Token
- 🔄 **实时同步**：WebSocket 双向通道，状态变更秒级推送
- 🧩 **即插即用**：HA 自定义组件，HACS 兼容

## 🏗️ 架构

```
[HA Devices] ⇄ [HA + cloudlink plugin] ⇄ HTTPS/WS ⇄ [Cloud Server] ⇄ REST/WS ⇄ [Android App]
```

详细架构：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 📦 仓库结构

```
cloudlink/
├── backend/          # FastAPI 云端服务（PostgreSQL + Redis）
├── android/          # Kotlin + Compose 客户端
├── ha-plugin/        # HA 自定义集成
├── docs/             # 完整项目文档
│   ├── PLAN.md       # 实施计划
│   └── phases/       # 分阶段实施指南
├── infra/            # Nginx / systemd 配置
└── scripts/          # 部署与诊断脚本
```

## 🚀 快速开始

### 前置条件

- 已购买阿里云 ECS（Ubuntu 22.04+）
- 已有域名并解析到服务器
- Home Assistant 实例 + Long-Lived Token
- Android Studio（开发客户端）

### 分阶段实施

| 阶段 | 内容 | 文档 |
|---|---|---|
| Phase 0 | 信息收集 | [phase-0-prep.md](docs/phases/phase-0-prep.md) |
| Phase 1 | 服务器初始化 | [phase-1-server-init.md](docs/phases/phase-1-server-init.md) |
| Phase 2 | 后端开发 | [phase-2-backend.md](docs/phases/phase-2-backend.md) |
| Phase 3 | 后端部署 | [phase-3-deploy.md](docs/phases/phase-3-deploy.md) |
| Phase 4 | HA 插件 | [phase-4-ha-plugin.md](docs/phases/phase-4-ha-plugin.md) |
| Phase 5 | Android 客户端 | [phase-5-android.md](docs/phases/phase-5-android.md) |
| Phase 6 | 端到端联调 | [phase-6-integration.md](docs/phases/phase-6-integration.md) |
| Phase 7 | 生产化 | [phase-7-production.md](docs/phases/phase-7-production.md) |

完整计划：[docs/PLAN.md](docs/PLAN.md)

## 🛠️ 技术栈

| 层 | 技术 |
|---|---|
| 云端后端 | Python 3.11 + FastAPI + SQLAlchemy 2.0 (async) |
| 数据库 | PostgreSQL 15 |
| 缓存/消息 | Redis 7 |
| 反向代理 | Nginx + Let's Encrypt |
| HA 集成 | Python（HA 官方标准） |
| Android | Kotlin + Jetpack Compose + Material 3 |
| 容器化 | Docker + Docker Compose |
| 通信协议 | HTTPS REST + WebSocket |

## 🔐 安全

- 所有外部通信走 HTTPS/WSS
- JWT 鉴权（access 15min + refresh 7d）
- HA Token Fernet 加密存储
- 速率限制（slowapi）
- 仅暴露 22/80/443 端口
- fail2ban 防爆破

## 📄 许可证

[MIT](LICENSE)

## 🙏 致谢

- [Home Assistant](https://www.home-assistant.io/) - 开源家庭自动化平台
- [vivo ha_vivohomebridge](https://github.com/vivo/ha_vivohomebridge) - 架构参考
- [FastAPI](https://fastapi.tiangolo.com/) - 现代化 Python Web 框架
