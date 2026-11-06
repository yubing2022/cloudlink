# 设计文档：基于 LiteRT-LM + Gemma 的 CloudLink 端侧家居控制 Agent

> 状态：Draft（待评审）
> 日期：2026-10-27
> 适用范围：CloudLink Android App（`:app` 模块）
> 关联文档：`ARCHITECTURE.md`、`docs/api/*`、`AgentBridge_PalmClaw_MCP_Integration.md`（另一条 MCP 对接线）

---

## 1. 目标与范围

### 1.1 目标
在 **CloudLink Android App 内部** 嵌入一个**完全端侧编排**的智能家居 Agent：

- **大脑在手机本地**：用 Google AI Edge 的 **LiteRT-LM** 推理引擎加载 **Gemma** 小模型，自然语言理解（NLU）与工具调度（ReAct 循环）全部在设备上完成，**不依赖任何云端大模型 API**。
- **手脚复用既有链路**：Agent 的工具层直接调用 CloudLink 已有的设备控制能力，最终经 CloudLink 后端 → HA 插件 → Home Assistant 执行真实动作。
- **GMS-free**：你的国行 Honor（MAG-AN00 / PNM_AN10）无 Google Play 服务，本方案全程不依赖 GMS（LiteRT-LM + Gemma 是开放权重自托管、完全离线）。

### 1.2 不在范围
- 跨设备编排（已有 **AgentBridge**：Mac host + Python Relay + Android client）。
- 云端大模型接管（可选「混合编排」见 §8，但默认不启用）。
- 替代 `CloudLinkCapabilityService`（AgentBridge 能力提供者）——两者**共存**，共享同一份设备控制核心（§3）。

### 1.3 用户价值
用户对着 CloudLink App 说「把客厅灯调暗一点」「卧室空调设到 26 度」，App 本地下模型解析意图、调用工具、执行并回读结果，**指令与对话不出本机**（仅设备动作需联网到 CloudLink 后端，沿用现有鉴权）。

---

## 2. 架构总览

```
┌──────────────────────────────────────────────────────────────────────┐
│                   CloudLink Android App (单 :app 模块)                 │
│                                                                        │
│  ┌──────────────┐   文本/事件流    ┌──────────────────────────────┐   │
│  │  聊天 UI      │◀──────────────▶│  ADK for Kotlin Agent 运行时   │   │
│  │ (新增/复用)   │                 │  LlmAgent + ReAct Loop         │   │
│  └──────────────┘                 └──────────────┬───────────────┘   │
│                                                  │ 调用工具             │
│                                                  ▼                    │
│                         ┌────────────────────────────────────────┐    │
│                         │  工具层：3 个 @Tool (KSP 编译期生成 schema) │   │
│                         │  cloudlink_list_devices / get_state /     │    │
│                         │  control  ── 委托 ──▶  DeviceControlCore │    │
│                         └───────────────────┬────────────────────┘    │
│                                              ▼                       │
│                         ┌────────────────────────────────────────┐    │
│                         │  DeviceControlCore（从现有 Service 抽取） │   │
│                         │  校验 / 拼写纠错 / 错误码 / 离线检测      │    │
│                         └───────────────┬────────────────────────┘    │
│                                         │ 复用既有网络栈              │
│                                         ▼                            │
│              ┌──────────────┐   ┌────────────────┐   ┌─────────────┐  │
│              │  ApiService   │   │  TokenStore    │   │AuthInterceptor│  │
│              │ (Retrofit)    │   │ (DataStore)    │   │(注入 Bearer) │  │
│              └──────┬───────┘   └────────────────┘   └─────────────┘  │
└─────────────────────┼─────────────────────────────────────────────────┘
                      │ HTTPS (Bearer)
                      ▼
        ┌─────────────────────────────┐
        │  CloudLink 后端 (ECS 118.x)  │──WS──▶ HA 插件 ──▶ Home Assistant
        └─────────────────────────────┘
```

**端侧模型位置**：LiteRT-LM + Gemma 运行在 App 进程内（CPU/GPU/NPU 加速），**不联网**。唯一出网的是 `ApiService` → CloudLink 后端这条既有链路。

---

## 3. 复用 CloudLink 既有资产（关键，避免重复造轮子）

当前 `CloudLinkCapabilityService`
（`com.yubing.cloudlink.capability.CloudLinkCapabilityService`）已经写好并且经过验证的部分：

- 三能力的 **JSON Schema / 描述**（list / get_state / control）；
- 参数校验、过滤值纠错（`INVALID_FILTER` 并返回可用取值）、`data` 必须是对象的强校验；
- **拼写纠错**：`ignoredDataKeys()` 用 Levenshtein 距离（≤2）发现疑似拼错的 service-data 字段，回 `warnings`；
- **稳定错误码**：`NOT_AUTHENTICATED / INVALID_PARAMS / INVALID_FILTER / ENTITY_NOT_FOUND / DOMAIN_MISMATCH / SERVICE_NOT_FOUND / SERVICE_CALL_FAILED / DEVICE_OFFLINE / HA_UNAVAILABLE / HA_TIMEOUT / UPSTREAM_ERROR`；
- **离线检测**：`state=unavailable` 抛 `DEVICE_OFFLINE`；
- **复用网络栈**：通过 Hilt `@EntryPoint Deps` 拿 `ApiService` + `TokenStore`，`AuthInterceptor` 注入 Bearer 并在 401 刷新。

**设计决策：抽取 `DeviceControlCore`**

把上述「纯业务逻辑」从 `CloudLinkCapabilityService` 中剥离成一个 **不依赖 `com.agentbridge.sdk`** 的可复用核心类/接口：

```kotlin
// 建议落点：com.yubing.cloudlink.capability.core.DeviceControlCore
interface DeviceControlCore {
    suspend fun listDevices(area: String? = null, domain: String? = null): DeviceListResult
    suspend fun getState(entityId: String): DeviceStateResult
    suspend fun control(
        entityId: String, domain: String, service: String,
        data: Map<String, Any?> = emptyMap(),
    ): DeviceControlResult
}
```

- `CloudLinkCapabilityService`（AgentBridge 提供者）改为**调用** `DeviceControlCore`，不再各自维护一份逻辑 → 两份暴露面（AgentBridge 能力 / 本地 Agent 工具）共用同一真相。
- `DeviceControlCore` 统一从 `TokenStore` 取 token（注意 `getEntityState` 当前走 `AuthInterceptor` 注入，而 `listDevices`/`controlDevice` 需显式传 `auth` 头；核心层应统一获取 `Bearer` 字符串并显式传入，避免两套取 token 路径分叉）。

---

## 4. 端侧模型选型：LiteRT-LM + Gemma

### 4.1 为什么是它
- **LiteRT-LM** 是 Google AI Edge 的**端侧 LLM 推理引擎**（Apache-2.0，2026-04 发布），负责自回归解码、KV cache、端侧 tokenizer、约束解码、**agentic tool calling**、MTP 投机解码（最高 ~3x）、会话保存/恢复；支持 Android/iOS/Web/桌面/IoT，硬件加速 CPU(XNNPACK)/GPU(OpenCL·Metal·WebGPU)/NPU(高通/联发科/三星/Tensor)。
- **Gemma** 是 Google 开放权重模型家族（与 Gemini 同源，但非 Gemini 本身；权重用 Gemma Terms of Use，宽松可商用、非 OSI 标准开源）。端侧主打 **E2B / E4B**：靠 MatFormer + PLE 把运行时内存压到 ~2GB / ~3GB，故能在手机跑。

### 4.2 对比其它后端（为什么不选）
| 后端 | 端侧？ | GMS 依赖 | tool call | 对你的 Honor | 结论 |
|---|---|---|---|---|---|
| **LiteRT-LM + Gemma** | ✅ | **否** | ✅ | ✅ 可用 | **唯一可行的全离线 tool-calling 路径** |
| ML Kit Gemini Nano | ✅ | **是**（Play 下发） | ❌ 暂不支持 | ❌ 无 GMS 跑不了 | 排除 |
| Firebase AI Logic（云） | ❌ | 云端（需联网） | ✅ | 可（需网络） | 仅作混合编排的云路（§8） |

### 4.3 具体型号与资源
- **默认 Gemma 4 E2B**（~2.58GB 文件，部分设备运行时 <1.5GB 内存；S26 Ultra GPU 上 ~52 tok/s，纯 CPU 约 7–8 tok/s）。
- E4B 留给 RAM 充裕、需要更强指令遵循的设备（~3GB 内存）。
- 模型以 `.litertlm` 格式交付，来源：`litert-community` HuggingFace org 或 Google AI Edge Gallery。

### 4.4 模型文件交付方式（关键决策）
2.5GB 直接打进 APK assets 不现实。推荐：
- **首启懒下载 + 本地缓存**：首次进入 Agent 时按需从 CDN/HuggingFace 下载 `.litertlm` 到应用私有目录，展示进度；之后完全离线可用。
- 备选：提供「离线优先」构建时把模型放 assets（APK 体积大，仅内部分发用）。

---

## 5. ADK for Kotlin 集成方案

用 **Google ADK for Kotlin 1.0 GA**（Apache-2.0，Kotlin Multiplatform）作为 Agent 框架——它是目前唯一把 **LiteRT-LM 作为一等 Model 后端**、且官方支持 Android 的生产级框架。

### 5.1 依赖（示例，`app/build.gradle.kts`）
```kotlin
plugins {
    id("com.google.devtools.ksp")   // 编译期生成工具 schema
}

dependencies {
    // ADK 核心（不依赖 GMS）
    implementation("com.google.adk:google-adk-kotlin-core:1.2.0")
    // 端侧推理后端：LiteRT-LM + Gemma
    implementation("com.google.adk:google-adk-kotlin-litertlm:1.2.0")
    // KSP 注解处理器
    ksp("com.google.adk:google-adk-kotlin-ksp:1.2.0")
}
```
> 版本号按 Maven Central 最新 1.x GA 对齐；需验证与现有 AGP / JDK 17 toolchain 的兼容性（ADK 要求 minSdk 24、compileSdk 34+）。

### 5.2 工具定义（KSP 编译期生成 schema，无运行时反射）
```kotlin
class CloudLinkTools(
    private val core: DeviceControlCore,
) {
    @Tool(
        description = "列出已接入 CloudLink 的设备/实体；先取 entity_id 再控制"
    )
    fun listDevices(
        @Param("area") area: String? = null,
        @Param("domain") domain: String? = null,
    ): String = runBlocking { core.listDevices(area, domain).toJson() }

    @Tool(description = "读取单个实体当前状态")
    fun getState(@Param("entity_id") entityId: String): String =
        runBlocking { core.getState(entityId).toJson() }

    @Tool(description = "调用 HA service 控制设备（开关/调光/设温等）")
    fun control(
        @Param("entity_id") entityId: String,
        @Param("domain") domain: String,
        @Param("service") service: String,
        @Param("data") data: Map<String, Any?> = emptyMap(),
    ): String = runBlocking { core.control(entityId, domain, service, data).toJson() }
}
```
ADK 的 KSP 处理器把 `@Tool`/`@Param` 在**编译期**生成 `Tool` 定义，启动快、Release 包干净——这点对移动端很重要。

### 5.3 组装 Agent 与运行
- `LlmAgent` 配置为 LiteRT-LM 的 `Model` 实现（litertlm 模块提供，指向本地 `.litertlm` 路径），与 Firebase/Gemini 后端**同构、一行切换**。
- 用 ADK 的 `Runner` + `Session`（Room 持久化、可恢复会话）驱动 ReAct 循环；UI 订阅事件流做打字机输出与「工具调用中」提示。
- 多轮记忆由 AppSearch 索引（ADK 原生支持）。

---

## 6. 工具设计（直接映射既有能力）

| 工具 | 委托核心方法 | 对应既有能力 | 关键校验（复用） |
|---|---|---|---|
| `cloudlink_list_devices(area?, domain?)` | `DeviceControlCore.listDevices` | `cloudlink.device.list@v1` | 过滤值不存在 → `INVALID_FILTER` + 可用值；剔除 0 实体设备 |
| `cloudlink_get_state(entity_id)` | `DeviceControlCore.getState` | `cloudlink.device.get_state@v1` | entity_id 缺失/未知 → `INVALID_PARAMS`/`ENTITY_NOT_FOUND`；404 回退全量扫描 |
| `cloudlink_control(entity_id, domain, service, data?)` | `DeviceControlCore.control` | `cloudlink.device.control@v1` | 必填校验、domain 前缀一致（`DOMAIN_MISMATCH`）、service 存在（`SERVICE_NOT_FOUND` + 可用 service 提示）、`data` 须为对象、`unavailable`→`DEVICE_OFFLINE`、拼错字段回 `warnings` |

工具返回 **结构化 JSON**（沿用既有 `statePart`/`jsonPart` 形状），保证 Agent 可读、可解析、可自愈。

---

## 7. 安全护栏（端侧 Agent 的生命线）

1. **敏感动作二次确认**：`control` 工具标注 `requireConfirmation`（ADK 原生 human-in-the-loop）。执行前 UI 弹出「将对 `<entity_id>` 执行 `<service>` + `<data>`，是否继续？」，用户确认后才真正调用核心。
2. **危险域/实体白名单（可选）**：`lock`、`cover`、`vacuum` 等不可逆或高影响域可额外要求显式授权或禁止自动执行。
3. **速率限制**：单位时间 control 调用次数上限，防误触发（如循环指令）。
4. **错误码 → 用户可读提示**：把 §3 的稳定错误码翻译成自然语言（如 `DEVICE_OFFLINE` → 「设备当前离线，指令未生效，请检查电源/网络」）。
5. **提示注入防护**：工具返回结果一律视为**不可信数据**，不回灌为指令；Agent 只允许通过工具接口影响设备，不能直接拼 shell/URL。

---

## 8. 离线 / 隐私 / 混合编排（可选）

- **默认全离线编排**：模型与 ReAct 循环在手机内，对话与意图**不出本机**。设备动作仍须联网到 CloudLink 后端（沿用 `AuthInterceptor` + 401 刷新，无需新鉴权）。
- **可选混合**：ADK 允许「云模型当架构师 + 端侧模型当执行」的混合编排。例如复杂长指令拆解可路由到 Firebase AI Logic（需联网 + Firebase 项目），简单/隐私步骤留本地 Gemma。这是**一行 Model 切换**的事，默认关闭。

---

## 9. 分阶段实施计划

| 阶段 | 内容 | 产出 / 验收 |
|---|---|---|
| **P0** | 抽取 `DeviceControlCore`，与 `CloudLinkCapabilityService` 共用；`CloudLinkCapabilityService` 改为调用核心 | 两份暴露面共用同一逻辑，单测覆盖三能力 |
| **P1** | `:app` 引入 ADK + LiteRT-LM + KSP；下载 Gemma 4 E2B 到本地；搭最小 `LlmAgent` 跑通本地推理（先不接工具） | 真机上离线生成文本 |
| **P2** | 实现 3 个 `@Tool`，委托 `DeviceControlCore`；端到端联调 list → control | 自然语言「打开客厅灯」能解析并真实控制 HA 设备 |
| **P3** | 聊天 UI（复用或新增）+ 流式输出 + `requireConfirmation` 确认弹窗 | 可见对话与确认交互 |
| **P4** | 护栏（确认/限流/错误映射）+ 评估 | 在 **Honor 非 GMS 真机** 跑通；覆盖未登录/离线/参数错等错误场景 |
| **P5（可选）** | 同核心暴露本地 MCP server（127.0.0.1:8731）给 PalmClaw | 与本文档正交，复用 `DeviceControlCore` |

---

## 10. 风险与未决问题

1. **小模型工具调用可靠性**：Gemma E2B 可能偶尔参数格式不稳。缓解：工具层先做**强 schema 校验 + 复刻既有拼写纠错**；失败时返回稳定错误码并引导 Agent 用自然语言澄清，而非静默成功。
2. **模型体积/下载**：~2.5GB 首启下载需进度与失败重试；考虑懒下载 + 断点续传。
3. **ADK + 现有 AGP/JDK 兼容性**：litertlm 模块需验证在你当前 AGP/toolchain 下能编过；必要时升 AGP/Java 17。
4. **两套取 token 路径**：`getEntityState`（AuthInterceptor 注入）vs `listDevices`/`controlDevice`（显式 `auth` 头）。`DeviceControlCore` 应统一取 `Bearer` 显式传入三者，消除分叉。
5. **与 AgentBridge 能力提供者共存**：两套暴露同一核心，需保证逻辑不分叉（核心层单点修改、双方复用）。
6. **Gemma 权重许可**：Gemma Terms of Use 非 OSI 标准开源，正式发布前需核验条款（不可用于训练竞争基座模型、输出需标注等）。

---

## 11. 验收标准

- [ ] 真机上**不登录任何云端模型**也能离线理解并执行「打开客厅灯 / 空调设 26 度」等指令，动作真实生效于 HA 设备。
- [ ] 在 **国行 Honor（无 GMS）真机** 可运行，无 GMS 依赖。
- [ ] 错误场景（未登录 `NOT_AUTHENTICATED` / 设备离线 `DEVICE_OFFLINE` / 参数错 `INVALID_PARAMS`/`DOMAIN_MISMATCH`/`SERVICE_NOT_FOUND`）返回稳定错误码 + 可读提示。
- [ ] `control` 类动作触发二次确认弹窗，展示将要执行的 entity/service/data。
- [ ] `CloudLinkCapabilityService`（AgentBridge 线）行为不回归（共用 `DeviceControlCore`）。

---

## 附录 A：错误码映射（供 Agent 自愈参考）

| 错误码 | 含义 | Agent 建议动作 |
|---|---|---|
| `NOT_AUTHENTICATED` | CloudLink 未登录/登录失效 | 提示用户打开 CloudLink 登录 |
| `INVALID_PARAMS` | 缺参/类型错（如 data 非对象） | 补全参数后重试 |
| `INVALID_FILTER` | area/domain 过滤值不存在 | 改用报错中给出的可用值 |
| `ENTITY_NOT_FOUND` | entity_id 不存在 | 先 list 取正确 entity_id |
| `DOMAIN_MISMATCH` | domain 与实体前缀不符 | 用实体真实 domain |
| `SERVICE_NOT_FOUND` | 该 domain 不支持此 service | 改用提示中的可用 service |
| `SERVICE_CALL_FAILED` | HA 服务调用失败 | 稍后重试 / 查 HA 日志 |
| `DEVICE_OFFLINE` | 设备离线，指令未生效 | 检查设备电源/网络 |
| `HA_UNAVAILABLE` / `HA_TIMEOUT` | HA 不可达/超时 | 稍后重试（超时可能已生效） |
| `UPSTREAM_ERROR` | 后端异常 | 联系维护方 / 重试 |

## 附录 B：与既有 MCP 对接线的关系

本文档（**端侧 Agent**）与此前 `AgentBridge_PalmClaw_MCP_Integration.md`（**本地 MCP 暴露给 PalmClaw**）是**两条互补而非互斥**的路线：

- 都建立在同一个 `DeviceControlCore` 之上；
- 端侧 Agent = CloudLink App 自己拥有大脑（Gemma）；
- MCP 线 = CloudLink 把能力暴露给外部 Agent（PalmClaw）当工具。

若两者都要，P5 阶段复用核心起一个 127.0.0.1 的 MCP server 即可，无需重复业务逻辑。
