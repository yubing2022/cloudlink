# CloudLink ↔ PalmClaw 对接方案（本地 MCP 路线）

> 文档状态：实施方案（供 CloudLink 维护方实现）
> 对接目标：让 PalmClaw（Android 端侧 Agent 框架）能够自然语言控制 CloudLink 管理的智能家居设备
> 路线：在 CloudLink Android App 内起一个**本地 HTTP MCP server（127.0.0.1）**，由 PalmClaw 自带的 HTTP MCP client 消费。**PalmClaw 核心零改动**。

---

## 1. 背景与目标

### 1.1 现状

- **CloudLink** 是用户自研的智能家居中转系统，由三部分组成：
  - Android App（`com.yubing.cloudlink`，Kotlin + Compose + Hilt + Retrofit）
  - Python 后端（FastAPI，部署在阿里云 ECS）
  - Home Assistant 插件（`ha-plugin/`）
  - 设备控制链路：`HA 设备 ⇄ HA + 插件 ⇄ 云端中继 ⇄ CloudLink 后端 ⇄ Android App`。
- CloudLink Android 端已存在 `CloudLinkCapabilityService`，通过 AgentBridge SDK 的 `ACTION_CAPABILITY_PROVIDER` 把 `cloudlink.device.list / get_state / control` 三类能力暴露给 AgentBridge（Mac 主控侧）。
- **PalmClaw** 是一个开源、Android 原生的 Agent 框架（Kotlin，AGPL-3.0）。其核心特征是**编排在端侧（手机 APK 内）、模型可走云侧 API**，并**内置一个 HTTP MCP client**，能消费外部 MCP server 把能力注册成工具。

### 1.2 目标

让 PalmClaw 用户在手机上直接用自然语言（如"关掉客厅的灯"）控制 CloudLink 接入的家居设备，**不改造 PalmClaw 源码**，且**不把 CloudLink 后端暴露为公网 MCP 端点**。

### 1.3 已确认的事实依据（非推测）

下列结论均来自对 PalmClaw 源码（`/Users/guoyubing/WorkBuddy/PalmClaw`）与 CloudLink 源码（`/Users/guoyubing/Codex/cloudlink`）的实际读取：

| 事实 | 来源 | 取值 |
|---|---|---|
| PalmClaw MCP client SDK | `app/build.gradle.kts` | `io.modelcontextprotocol:kotlin-sdk-client:0.10.0` |
| MCP 传输协议 | `mcp/transport/OfficialKotlinMcpTransportFactory.kt` | 首选 **Streamable HTTP（2025-11-25）**，回退 **legacy SSE** |
| 回环地址策略 | `mcp/McpEndpointPolicy.kt` | `127.0.0.1` / `localhost` 判定为 **LOOPBACK_HTTP → 直接 ALLOWED**，**无需 HTTPS、无需 authToken**（authToken 仅对 PRIVATE_LAN_HTTP 强制） |
| 工具命名 | PalmClaw MCP client | 远端工具自动加前缀 `mcp_<serverName>_` |
| 重连兜底 | PalmClaw | 内置 `McpConnectionRetry`，断线自动重连 |
| CloudLink 构建基线 | `android/app/build.gradle*` | compileSdk 35 / minSdk 26 / Kotlin JVM 17，已用 Retrofit + OkHttp + Moshi + Hilt |
| 设备控制核心 | `CloudLinkCapabilityService.kt` | 已实现 `listDevices` / `getState` / `control`，含校验、拼写纠错、离线检测、稳定错误码 |
| 登录态 | `TokenStore`（DataStore） | 与 UI 同会话，`getAccessToken(): String?` |
| 网络层 | `ApiService`（Retrofit） | `AuthInterceptor` 自动注入 Bearer 并在 401 刷新；`BASE_URL` 与 UI 一致 |
| 后端接口 | `backend/app/api/devices.py` | `GET /api/devices`、`GET /api/devices/{entity_id}/state`、`POST /api/devices/{entity_id}/action`（均带 JWT） |

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  PalmClaw App (手机)                                          │
│   编排 / Agent Loop / LLM(云侧 API)                          │
│        │  HTTP MCP client (Streamable HTTP / SSE, 0.10.0)     │
│        │  http://127.0.0.1:8731/mcp   (loopback, 免鉴权)     │
│        ▼                                                      │
│  CloudLink App (手机, 同设备)                                  │
│   ┌──────────────────────────────────────────┐              │
│   │  CloudLinkMcpServer (127.0.0.1:8731)      │              │
│   │     └─ DeviceControlCore                  │              │
│   │          ├─ TokenStore (登录态, 同会话)    │              │
│   │          └─ ApiService (Retrofit)         │              │
│   └──────────────────────────────────────────┘              │
│        │  HTTPS REST (JWT)                                    │
└────────┼──────────────────────────────────────────────────────┘
         ▼
   CloudLink 后端 (ECS, FastAPI)  ── WS ──>  HA 插件  ──>  Home Assistant  ──>  设备
```

要点：MCP 传输完全本地化（手机内 PalmClaw → CloudLink 两 App 同进程/同设备）；设备实际控制仍经 CloudLink 后端 → HA（CloudLink App 是后端客户端，不直连 HA）。

---

## 3. 实现方案

### 3.1 新增依赖

在 `android/app/build.gradle.kts` 中增加（**版本必须与 PalmClaw 的 client 严格一致为 `0.10.0`，否则握手可能失败**）：

```kotlin
// MCP server（与 PalmClaw client 0.10.0 同版本）
implementation("io.modelcontextprotocol:kotlin-sdk-server:0.10.0")
// HTTP server 引擎：优先用 Ktor CIO（轻量、协程原生）；若工程已引入其它 HTTP 引擎可复用
implementation("io.ktor:ktor-server-core:2.x")
implementation("io.ktor:ktor-server-cio:2.x")
implementation("io.ktor:ktor-server-content-negotiation:2.x")
implementation("io.ktor:ktor-serialization-kotlinx-json:2.x")
```

> 若维护方评估 `kotlin-sdk-server` 与现有 Ktor 版本存在冲突，可改用官方 SDK 的 server 包 + `com.sun.net.httpserver.HttpServer` 手搓最小 Streamable HTTP 端点（SSE + JSON-RPC session 管理较繁琐，仅在确有必要时退此路）。

### 3.2 新增文件清单

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/yubing/cloudlink/data/mcp/DeviceControlCore.kt` | 从 `CloudLinkCapabilityService` 抽取 `listDevices` / `getState` / `control` 三方法的**全部业务核心**（校验、0 实体过滤、area/domain 过滤与报错、拼写纠错、离线检测、错误码映射）。持有 `api: ApiService` + `tokenStore: TokenStore`，对外暴露三个 `suspend fun`，返回结构化结果对象。 |
| `app/src/main/java/com/yubing/cloudlink/mcp/CloudLinkMcpServer.kt` | 在 `127.0.0.1:8731` 起 HTTP MCP server，注册 3 个 tool；每个 tool 的 handler 调用 `DeviceControlCore` 对应方法并将结果格式化为 MCP `ToolResult`。 |
| `app/src/main/java/com/yubing/cloudlink/mcp/CloudLinkMcpService.kt` | 前台 `Service`（或 Application 内常驻协程），负责启动/停止 `CloudLinkMcpServer`；通过 Hilt `@EntryPoint` 注入 `DeviceControlCore`。 |

### 3.3 核心逻辑抽取（DeviceControlCore）

**目的**：让"设备控制"只有一份实现，同时服务于 AgentBridge 通道与本地 MCP 通道，消除重复。

两种落地方式（维护方择一）：

- **方式 A（推荐，重构）**：将 `CloudLinkCapabilityService` 中三个能力的业务核心下沉到新建的 `DeviceControlCore`，`CloudLinkCapabilityService` 改为调用 `DeviceControlCore`；本地 MCP server 同样调用它。
- **方式 B（最小改动）**：不重构 `CloudLinkCapabilityService`，将其三个方法由 `private` 调整为 `internal`/`public`（如有 suspend 适配需求补齐），本地 MCP server 直接调用 `CloudLinkCapabilityService` 的公开方法；代价是逻辑仍耦合在 capability service 中，但改动面最小。

`DeviceControlCore` 需复用的关键能力（来自现有 `CloudLinkCapabilityService`，不要重写）：

- `listDevices`：支持 `area` / `domain` 过滤；对"0 实体"场景给出明确报错而非空列表；实体名拼写纠错。
- `getState`：按 `entity_id` 取状态，离线/未知实体有明确错误码。
- `control`：按 `domain` / `service` / `data` 下发控制（经后端 `POST /api/devices/{entity_id}/action`）。
- 稳定错误码集合（现有约 11 个，如 `NOT_AUTHENTICATED` / `INVALID_PARAMS` / `INVALID_FILTER` / `ENTITY_NOT_FOUND` / `DOMAIN_MISMATCH` / `SERVICE_NOT_FOUND` / `SERVICE_CALL_FAILED` / `DEVICE_OFFLINE` / `HA_UNAVAILABLE` / `HA_TIMEOUT` / `UPSTREAM_ERROR`）——**直接沿用**，保证 Agent 体验与 AgentBridge 通道一致。

`DeviceControlCore` 拿依赖的方式（与现有 capability service 一致）：通过 Hilt `@EntryPoint` 取得 `ApiService` 与 `TokenStore`，确保与 UI **同一登录会话**（MCP server 不需要独立登录，复用 App 已登录态）。

### 3.4 MCP server 实现要点

`CloudLinkMcpServer.kt` 伪代码骨架（示意，非最终实现）：

```kotlin
class CloudLinkMcpServer(
    private val core: DeviceControlCore,
    private val port: Int = 8731
) {
    fun start() {
        // 1. 用 Ktor CIO 在 127.0.0.1 监听 port
        // 2. 挂载 MCP Streamable HTTP 端点（路径建议 /mcp）
        //    需实现：initialize / tools/list / tools/call 三个 JSON-RPC 方法
        //    - initialize: 返回 serverInfo + capabilities(tools)
        //    - tools/list: 返回 3 个 tool 的 name/description/inputSchema
        //    - tools/call: 按 name 分发到 DeviceControlCore，返回 ToolResult
        // 3. (可选) 同时支持 legacy SSE 端点，作为 PalmClaw 回退路径
    }

    fun stop() { /* 关闭 server、释放端口 */ }
}
```

**必须遵循的协议约束**：

- 协议**首选 Streamable HTTP（MCP spec 2025-11-25）**；若 PalmClaw 握手失败回退 SSE，server 应能响应。
- `tools/list` 返回的 `inputSchema` 直接用第 5 节定义的 JSON Schema（与 `CloudLinkCapabilityService` 现有中文 schema 字段、描述、required 一致）。
- `tools/call` 返回的 `ToolResult.content` 用结构化文本（JSON 或可读文本），错误场景返回对应错误码与 message，便于 PalmClaw 的 Agent 理解并纠错。

### 3.5 保活

- 建议实现为**前台 Service**（带常驻通知），避免系统回收；同时监听 App 登录/登出事件，登录后确保 server 启动、登出后停服或拒绝未鉴权调用。
- 端口固定为 `8731`（与 PalmClaw 配置保持一致；如调整需同步）。
- PalmClaw 自带 `McpConnectionRetry` 会兜底重连，因此短暂断服可接受，但应尽量保证常驻。

### 3.6 端口与安全

- **只绑定 `127.0.0.1`（回环）**，**绝不监听 `0.0.0.0`**，避免被同网其它设备访问。
- 基于 `McpEndpointPolicy`：loopback 地址 PalmClaw 侧**免 HTTPS、免 authToken**。
- （可选增强）若希望防止本机其它 App 误调，可在 server 侧加一个静态 local token，PalmClaw 配置时填入 `authToken` 字段；loopback 下非强制。
- 明文 HTTP 在回环场景可接受（无出设备流量）。

---

## 4. 三个 MCP 工具契约

名称建议（PalmClaw 会自动加 `mcp_<serverName>_` 前缀；`serverName` 取 `cloudlink` 时最终为 `mcp_cloudlink_device_list` 等）：

| Tool name | 对应 CloudLink 能力 | 说明 |
|---|---|---|
| `cloudlink_device_list` | `listDevices` | 列出设备，支持 area/domain 过滤 |
| `cloudlink_device_get_state` | `getState` | 取单个设备/实体状态 |
| `cloudlink_device_control` | `control` | 下发控制（domain/service/data） |

`inputSchema`（骨架，字段名/描述/required **以 `CloudLinkCapabilityService` 现有实现为准**，保持一致）：

```jsonc
// cloudlink_device_list
{
  "type": "object",
  "properties": {
    "area":     { "type": "string", "description": "按区域过滤，如 '客厅'；省略则返回全部" },
    "domain":   { "type": "string", "description": "按设备域过滤，如 'light'/'switch'；省略则返回全部" }
  },
  "required": []
}

// cloudlink_device_get_state
{
  "type": "object",
  "properties": {
    "entity_id": { "type": "string", "description": "设备实体 ID，如 'light.living_room'" }
  },
  "required": ["entity_id"]
}

// cloudlink_device_control
{
  "type": "object",
  "properties": {
    "entity_id": { "type": "string", "description": "目标设备实体 ID" },
    "domain":    { "type": "string", "description": "服务域，如 'light'" },
    "service":   { "type": "string", "description": "服务名，如 'turn_on'/'turn_off'" },
    "data":      { "type": "object", "description": "附加参数，如亮度/颜色等" }
  },
  "required": ["entity_id", "domain", "service"]
}
```

`outputSchema` / 返回内容：建议以 JSON 文本返回设备列表、状态对象或控制结果；错误时返回 `{ "error_code": "...", "message": "..." }`。

---

## 5. PalmClaw 侧配置（零代码，仅 UI 操作）

> 此部分由使用方（在手机上装了 PalmClaw + CloudLink 的用户）完成，CloudLink 维护方无需改动 PalmClaw。

1. 打开 PalmClaw → **Settings → MCP → Add HTTP server**
2. Server URL：`http://127.0.0.1:8731/mcp`
3. Auth token：留空（loopback 免鉴权）或填 CloudLink 设置的 local token（若启用）
4. 保存 → PalmClaw 自动 `initialize` + `tools/list` → 工具列表出现 `mcp_cloudlink_device_*`
5. 在 PalmClaw 聊天中自然语言指令（如"关掉客厅的灯"），Agent 会自动选择并调用对应 tool。

---

## 6. 构建与联调验证

1. CloudLink Android 改动完成后：`./gradlew assembleDebug`（JDK 17，与现有基线一致）。
2. `adb install -r <cloudlink-debug.apk>`（USB 直连）。
3. 打开 CloudLink App **登录一次**（确保 `TokenStore` 有有效 token；MCP server 复用此会话）。
4. 验证端口监听：`adb shell netstat -tln | grep 8731`（应看到 `127.0.0.1:8731` 处于 LISTEN）。
5. PalmClaw 按第 5 节配置 MCP server。
6. 在 PalmClaw 发一条指令，确认：
   - PalmClaw 日志/状态显示工具被调用；
   - CloudLink 侧 `adb logcat -s CloudLinkMcp`（或自定义 tag）看到请求与响应；
   - 目标设备状态实际变化（以 Home Assistant / 实物为准）。
7. 异常排查：
   - 握手失败 → 确认 `kotlin-sdk-server` 版本为 `0.10.0`、端点路径为 `/mcp`、协议为 Streamable HTTP。
   - 401/未登录 → 确认 CloudLink App 已登录且 MCP server 与 UI 同 `TokenStore`。
   - 工具不出现 → 检查 `tools/list` 返回是否为合法 JSON-RPC、`inputSchema` 合法。

---

## 7. 风险与权衡

- **"本地"指 MCP 传输本地化**：设备实际控制仍经 CloudLink 后端 → HA（App 是客户端，不直连 HA）。若未来要求"断网也能控本地 HA"，需在 Android 端加 HA 本地 REST 直连，超出本次范围。
- **保活**：前台 Service + 固定端口；PalmClaw 重连逻辑兜底。
- **SDK 体积**：MCP server + Ktor 引擎约增加几 MB APK，可接受。
- **协议版本耦合**：server 必须用 `0.10.0` 同版本，升级需与 PalmClaw 同步评估。
- **多 App 同设备**：PalmClaw 与 CloudLink 需同时安装在用户手机上；MCP 客户端（PalmClaw）与服务端（CloudLink）是独立 App，通过回环 TCP 通信，无需特殊权限。

---

## 8. 后续演进（本次不做，供参考）

- 让 CloudLink 同时以 **Android 16 AppFunctions（系统级绑定 MCP）** 形态暴露，供系统级 Agent 调用（需 Android 16+ 设备）。
- 或在 **CloudLink 后端**也部署一份 MCP server（跨设备场景，供 Mac 主控等远程消费），与本地 server 并存。
- 在 MCP tool 中补充"场景（scene）"执行能力（如"晚安模式"），复用 CloudLink 现有场景接口（如有）。

---

## 9. 验收标准（维护方自测 + 联调）

- [ ] CloudLink Debug 包编译通过（JDK 17）。
- [ ] 安装并登录后，`127.0.0.1:8731` 处于 LISTEN 且仅绑回环。
- [ ] PalmClaw 添加该 HTTP MCP server 后，`tools/list` 返回 3 个 `cloudlink_device_*` 工具。
- [ ] 在 PalmClaw 中执行"列出设备 / 查状态 / 控制开关"三类指令，设备侧产生预期变化。
- [ ] 未登录 CloudLink 时，MCP 工具调用返回 `NOT_AUTHENTICATED` 而非崩溃。
- [ ] 非法参数（不存在的 entity_id / 错误 domain）返回既有稳定错误码。
- [ ] 断开并重连 CloudLink App 后，PalmClaw 经重连恢复工具调用。

---

*本方案基于 2026-10-18 对 PalmClaw（`ModalityDance/PalmClaw`，已本地克隆编译通过）与 CloudLink（`/Users/guoyubing/Codex/cloudlink`）源码的实测结论整理，供 CloudLink 维护方据此实现。PalmClaw 侧无需任何代码改动。*
