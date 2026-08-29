# 系统架构

## 🎨 整体架构图

```
┌──────────────────────────────────────────────────────────────┐
│                       局域网 / 家庭网络                       │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ 智能灯具  │  │智能开关  │  │温湿度计  │  │ 摄像头   │      │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘      │
│       │             │             │             │            │
│       └─────────────┴──────┬──────┴─────────────┘            │
│                            ↓                                 │
│                  ┌────────────────────┐                      │
│                  │  Home Assistant    │                      │
│                  │  ┌───────────────┐ │                      │
│                  │  │ cloudlink 集成│ │                      │
│                  │  │ (本项目)      │ │                      │
│                  │  └───────┬───────┘ │                      │
│                  └──────────┼─────────┘                      │
└────────────────────────────┼─────────────────────────────────┘
                              │ ① HTTPS / WSS
                              │ (HA 主动外连，绕开 NAT)
                              ↓
┌──────────────────────────────────────────────────────────────┐
│                     公网 / 云服务器                           │
│                                                              │
│  ┌─────────────────────────────────────────────┐             │
│  │  Nginx :443 (HTTPS 终结 + WSS 升级)         │             │
│  └──────────────────────┬──────────────────────┘             │
│                         ↓                                    │
│  ┌─────────────────────────────────────────────┐             │
│  │  FastAPI  (uvicorn :8000)                   │             │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐    │             │
│  │  │/api/auth │ │/api/ha   │ │/api/dev  │    │             │
│  │  └──────────┘ └──────────┘ └──────────┘    │             │
│  │  ┌──────────┐ ┌──────────┐                  │             │
│  │  │/ws/ha    │ │/ws/client│  (WebSocket)    │             │
│  │  └──────────┘ └──────────┘                  │             │
│  └────────┬─────────────────────┬──────────────┘             │
│           ↓                     ↓                            │
│  ┌────────────────┐  ┌──────────────────┐                   │
│  │ PostgreSQL 15  │  │  Redis 7         │                   │
│  │ users, has,    │  │ (在线状态 /      │                   │
│  │ devices, ...   │  │  WS session)     │                   │
│  └────────────────┘  └──────────────────┘                   │
└──────────────────────────────────────────────────────────────┘
                         ↑
                         │ ② REST + WebSocket
                         │
┌────────────────────────┼─────────────────────────────────────┐
│                        │           移动网络 / Wi-Fi            │
│              ┌─────────┴──────────┐                           │
│              │  Android App        │                          │
│              │  (Kotlin/Compose)   │                          │
│              └────────────────────┘                          │
└──────────────────────────────────────────────────────────────┘
```

## 🔄 数据流

### 场景 A：HA 状态变化 → Android 收到推送

```
1. HA 设备状态变化（灯打开）
2. HA 事件总线触发 state_changed 事件
3. cloudlink 集成监听到事件
4. 通过 WebSocket 发送到云：{type: "state_change", entity_id, state}
5. 云端更新数据库
6. 云端通过 /ws/client 推送给该用户的所有 Android 客户端
7. Android 收到，更新 UI
```

### 场景 B：Android 操作设备 → HA 执行

```
1. 用户在 Android 点击"开灯"
2. App 调用 REST POST /api/devices/light.kitchen/action
3. 云端鉴权，验证用户拥有该设备
4. 云端通过 /ws/ha 向对应 HA 实例发送：{type: "device_action", domain, service, data}
5. HA 端 cloudlink 集成收到，调用 hass.services.async_call()
6. HA 执行实际服务调用，设备响应
7. HA 触发 state_changed 事件（回到场景 A）
8. Android 通过 /ws/client 收到状态变化推送（云端→App）
9. UI 更新
```

### 场景 C：HA 重启 → 自动重连

```
1. HA 重启，cloudlink 集成启动
2. 集成读取配置（cloud_url + cloud_token）
3. 建立 WebSocket 连接：/ws/ha?token={cloud_token}
4. 云端验证 token，更新 last_seen / is_online
5. HA 集成发送 device_sync 全量设备列表
6. 云端比对数据库，增量更新
7. 正常运行
```

## 🗄️ 数据模型

#### User（移动端用户）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int PK | |
| password_hash | str | bcrypt |
| email | str unique | |
| created_at | timestamp | |

#### HAInstance（用户注册的 HA 实例）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int PK | |
| user_id | int FK | 所属用户 |
| name | str | "我家" |
| cloud_token | str unique | HA 用来连接云的凭证 |
| encrypted_ha_token | bytes | HA 的 Long-Lived Token（Fernet 加密） |
| last_seen | timestamp | 最后心跳时间 |
| is_online | bool | 在线状态 |

#### Device（HA 设备）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int PK | |
| ha_instance_id | int FK | 所属 HA 实例 |
| entity_id | str unique | HA 的 entity_id（跨实例唯一） |
| domain | str | light / switch / sensor... |
| name | str | 显示名称 |
| state | str | 当前状态 |
| attributes | JSONB | 亮度、颜色、温度等 |
| capabilities | JSONB | 支持的 service |
| area | str nullable | 所在房间 |
| updated_at | timestamp | |

## 🔌 协议设计

### HTTP REST（同步调用）

```
POST   /api/auth/register
POST   /api/auth/login
POST   /api/auth/refresh

POST   /api/ha/register                （用户从 App 发起）
GET    /api/ha/instances
DELETE /api/ha/instances/{id}

POST   /api/ha/{cloud_token}/heartbeat （HA 调用）
POST   /api/ha/{cloud_token}/devices/sync
POST   /api/ha/{cloud_token}/state

GET    /api/devices                    （App 调用）
GET    /api/devices/{entity_id}
POST   /api/devices/{entity_id}/action
```

### WebSocket 协议

#### HA → 云（`/ws/ha?token={cloud_token}`）

```json
// 设备全量同步（连接成功后立即发送）
{"type": "device_sync", "devices": [...]}

// 单设备状态变化
{"type": "state_change", "entity_id": "light.kitchen", "state": "on", "attributes": {...}}

// 心跳响应
{"type": "pong"}
```

#### 云 → HA

```json
// 设备操作指令
{"type": "device_action", "domain": "light", "service": "turn_on", "entity_id": "light.kitchen", "data": {"brightness": 200}}

// 心跳
{"type": "ping"}
```

#### 云 → Android（`/ws/client?token={access_token}`）

```json
// 状态变化推送
{"type": "state_change", "entity_id": "light.kitchen", "state": "on", "attributes": {...}}

// 新设备添加
{"type": "device_added", "device": {...}}

// 设备删除
{"type": "device_removed", "entity_id": "light.kitchen"}

// HA 实例上下线
{"type": "ha_online", "ha_instance_id": 1}
{"type": "ha_offline", "ha_instance_id": 1}
```

## 🔐 安全架构

### 鉴权层级

```
┌──────────────────────────────────────────┐
│  Layer 1: HTTPS / WSS（传输加密）        │
│  - Let's Encrypt SSL                     │
│  - TLS 1.3                               │
├──────────────────────────────────────────┤
│  Layer 2: JWT 鉴权（API 调用）           │
│  - Access Token: 15 分钟                 │
│  - Refresh Token: 7 天                   │
│  - HS256 签名                            │
├──────────────────────────────────────────┤
│  Layer 3: HA Token 加密存储             │
│  - Fernet (AES-128-CBC + HMAC-SHA256)    │
│  - 密钥独立管理（不进代码仓库）          │
├──────────────────────────────────────────┤
│  Layer 4: 速率限制                       │
│  - slowapi                              │
│  - 登录: 5 次/分钟                       │
│  - 普通 API: 60 次/分钟                  │
├──────────────────────────────────────────┤
│  Layer 5: 网络层                         │
│  - 仅暴露 22/80/443                      │
│  - fail2ban 防 SSH 爆破                  │
│  - UFW 防火墙                           │
└──────────────────────────────────────────┘
```

### Token 流转

```
用户密码 → bcrypt hash → 数据库
                ↓
用户登录 → 验证 → 颁发 JWT (access + refresh)
                ↓
App 每次请求带：Authorization: Bearer {access_token}
                ↓
Access 过期 → 用 refresh 换新 access
                ↓
HA 端 WebSocket：?token={cloud_token}（独立凭证）
```

## 📈 性能与扩展性

### 当前规模（个人 / 小团队）

- 设备数: < 100
- 并发用户: < 10
- 单服务器 2C4G 足够

### 扩展路径（>1000 设备 / >100 用户）

1. **水平扩展 FastAPI**：gunicorn 多 worker + nginx upstream
2. **Redis Pub/Sub**：替换直接 WS 广播，跨节点同步
3. **PostgreSQL 读写分离**：主写从读
4. **对象存储**：日志、备份存 OSS
5. **CDN**：静态资源加速

## 🛠️ 部署架构

```
┌─────────────────────────────────────┐
│  Host (阿里云 ECS 2C4G)             │
│  ┌───────────────────────────────┐  │
│  │  Docker Compose              │  │
│  │  ┌──────────┐ ┌──────────┐   │  │
│  │  │ nginx    │ │ fastapi  │   │  │
│  │  │ :443     │ │ :8000    │   │  │
│  │  └──────────┘ └──────────┘   │  │
│  │  ┌──────────┐ ┌──────────┐   │  │
│  │  │postgres  │ │ redis    │   │  │
│  │  │ :5432    │ │ :6379    │   │  │
│  │  └──────────┘ └──────────┘   │  │
│  └───────────────────────────────┘  │
│                                     │
│  数据卷：                            │
│  - postgres_data:/var/lib/postgresql│
│  - redis_data:/data                │
│  - ./ssl:/etc/nginx/ssl             │
│  - ./logs:/var/log/cloudlink        │
└─────────────────────────────────────┘
```

## 🔗 模块依赖关系

```
android  ──┐
           ├──→ cloud backend ──→ postgres
ha plugin ─┘                ──→ redis
            (HTTPS REST + WebSocket)
```

- HA 插件 → 云：单向 HTTPS POST + WebSocket
- 云 → HA 插件：WebSocket（HA 主动连入）
- Android → 云：HTTPS REST + WebSocket
- 云 → Android：WebSocket 推送
