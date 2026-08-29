# CloudLink 实施计划

> 本文档是整个项目的总纲，详细分阶段指南见 [phases/](phases/) 目录。

## 📌 项目目标

构建一套完整的「Home Assistant ⇄ 云端 ⇄ Android」系统，让用户能在任何地方通过 Android App 控制家中的 HA 设备。

## 🎯 核心需求

1. **HA 插件**：参考 vivo ha_vivohomebridge 的架构，开发自定义集成，将 HA 设备信息推送到云端
2. **云端服务**：自建 FastAPI 后端，接收 HA 上报数据，对外提供 REST API 和 WebSocket
3. **Android 客户端**：通过云端 API 控制 HA 设备，实时显示状态变化
4. **安全**：JWT 鉴权、HTTPS 加密、HA Token 加密存储

## 🛣️ 实施路线图

```
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│ Phase 0 │ → │ Phase 1 │ → │ Phase 2 │ → │ Phase 3 │ → │ Phase 4 │ → │ Phase 5 │ → │Phase 6/7│
│  准备   │   │ 服务器  │   │ 后端开发│   │ 后端部署│   │ HA 插件 │   │Android  │   │联调/生产│
└─────────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘
   0.5 天        1 天          2 天          0.5 天         1.5 天        3 天        2 天
```

| 阶段 | 工作量 | 关键产出 |
|---|---|---|
| [Phase 0](phases/phase-0-prep.md) | 0.5 天 | 收集所有凭证（服务器、HA、域名、邮箱） |
| [Phase 1](phases/phase-1-server-init.md) | 1 天 | 服务器初始化、安全加固、Docker 装好 |
| [Phase 2](phases/phase-2-backend.md) | 2 天 | FastAPI 后端代码完成、API 测试通过 |
| [Phase 3](phases/phase-3-deploy.md) | 0.5 天 | 后端通过 HTTPS 对外提供服务 |
| [Phase 4](phases/phase-4-ha-plugin.md) | 1.5 天 | HA 插件可用，HA 设备上报到云 |
| [Phase 5](phases/phase-5-android.md) | 3 天 | Android APK 装到手机，能控制设备 |
| [Phase 6](phases/phase-6-integration.md) | 1 天 | 端到端跑通所有场景 |
| [Phase 7](phases/phase-7-production.md) | 1 天 | 生产化加固、监控告警 |
| **合计** | **约 10 天** | **整套系统可用** |

## 🔧 技术决策记录（ADR）

### ADR-001：后端语言选型 → Python 3.11 + FastAPI

**理由**：
- 与 HA 同语言（HA 插件也是 Python），代码风格统一
- FastAPI async 原生支持，WebSocket 简单
- Pydantic 数据校验直观
- SQLAlchemy 2.0 异步 ORM 成熟

### ADR-002：数据库选型 → PostgreSQL 15

**理由**：
- JSONB 字段适合存储设备属性（不规则结构）
- 全文检索、地理位置等高级特性按需可用
- 社区成熟，运维资料多

### ADR-003：通信架构 → HA 主动外连 WebSocket

**理由**：
- HA 通常在 NAT 后，云端不能主动连接 HA
- HA→Cloud WebSocket 单连接、双向复用（推状态 + 收指令）
- 备选方案：Cloud→HA HTTP polling（延迟大、浪费带宽）

### ADR-004：Android 客户端 → Kotlin + Jetpack Compose

**理由**：
- 现代官方推荐栈
- 声明式 UI 与状态管理契合实时数据
- Material 3 设计规范
- 备选：Flutter（如果更熟悉 Dart）

### ADR-005：部署方式 → Docker Compose

**理由**：
- 一键启动整套后端（PostgreSQL + Redis + FastAPI + Nginx）
- 本地开发与生产环境一致
- 升级、回滚简单

## 📂 目录约定

```
backend/
├── app/                  # 业务代码
│   ├── api/              # FastAPI 路由（按业务模块分文件）
│   ├── core/             # 配置、安全、加密
│   ├── models/           # SQLAlchemy ORM
│   ├── schemas/          # Pydantic 数据模型
│   ├── ws/               # WebSocket 管理
│   └── main.py           # FastAPI app 入口
├── tests/                # pytest 测试
├── alembic/              # 数据库迁移
└── pyproject.toml        # 依赖管理

android/
├── app/src/main/
│   ├── java/.../data/    # 数据层（API、WS、本地存储）
│   ├── java/.../domain/  # 业务逻辑
│   ├── java/.../ui/      # Compose 界面
│   ├── java/.../di/      # Hilt 依赖注入
│   └── res/              # 资源文件
└── build.gradle.kts      # Gradle 构建

ha-plugin/
└── custom_components/cloudlink/
    ├── __init__.py       # 入口
    ├── config_flow.py    # UI 配置流程
    ├── cloud_client.py   # 云端通信
    └── ...
```

## 🔁 Git 工作流

### 分支策略（GitHub Flow 简化版）

```
main (稳定)
  ↑
  ├── feat/phase-1-server-init
  ├── feat/phase-2-backend
  ├── feat/phase-3-deploy
  ├── feat/phase-4-ha-plugin
  ├── feat/phase-5-android
  └── fix/*
```

每个 Phase 在独立分支开发，测试通过后 PR 合并到 main。

### 提交规范（Conventional Commits）

```
feat(scope): 新功能
fix(scope): 修复
docs(scope): 文档
refactor(scope): 重构
test(scope): 测试
chore(scope): 杂项
```

示例：
```bash
git commit -m "feat(backend): add JWT auth middleware"
git commit -m "fix(ha-plugin): reconnect on websocket close"
git commit -m "docs: phase 2 backend implementation guide"
```

## ✅ 完成定义（DoD）

每个 Phase 完成需要满足：

1. 代码 / 配置已提交到对应分支
2. README 和 phase 文档已更新
3. 关键路径有测试覆盖
4. 在真实环境（服务器 / HA / 手机）验证通过
5. PR 合并到 main

## 📊 风险与缓解

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| HA 重启后 WebSocket 断连 | 设备不可控 | 重连机制 + 心跳 + 状态重同步 |
| 云服务器宕机 | 全系统失联 | 多 AZ 部署（后期）/ 监控告警 |
| Android App 杀进程 | 收不到推送 | Foreground Service + 系统白名单 |
| HA Token 泄漏 | 攻击者可控制设备 | Fernet 加密 + 最小权限 + 审计日志 |
| 公网暴露端口被扫描 | 安全风险 | fail2ban + 仅 22/80/443 + HTTPS |

## 📞 联系与反馈

- Issues: GitHub Issues
- Discussions: GitHub Discussions
