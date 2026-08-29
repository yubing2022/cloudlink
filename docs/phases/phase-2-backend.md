# Phase 2: 云端后端开发（FastAPI）

> 预计耗时：2 天  
> 目标：完成 FastAPI 后端所有业务代码，关键 API 通过测试  
> 分支：`feat/phase-2-backend`

## 🎯 完成标志

- [ ] 项目骨架完整（app/、tests/、alembic/）
- [ ] 数据库表能成功 migrate
- [ ] 注册 / 登录 / 刷新 token API 工作
- [ ] HA 实例注册、heartbeat、device sync API 工作
- [ ] 设备列表、设备操作 API 工作
- [ ] WebSocket（HA 端和 Android 端）能连接
- [ ] pytest 单元测试通过

## 📁 项目骨架

```
backend/
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── config.py
│   ├── database.py
│   ├── deps.py
│   ├── core/
│   │   ├── __init__.py
│   │   ├── security.py
│   │   ├── crypto.py
│   │   └── logging.py
│   ├── models/
│   │   ├── __init__.py
│   │   ├── base.py
│   │   ├── user.py
│   │   ├── ha_instance.py
│   │   └── device.py
│   ├── schemas/
│   │   ├── __init__.py
│   │   ├── auth.py
│   │   ├── ha.py
│   │   └── device.py
│   ├── api/
│   │   ├── __init__.py
│   │   ├── router.py
│   │   ├── auth.py
│   │   ├── ha.py
│   │   ├── devices.py
│   │   └── ws.py
│   └── ws/
│       ├── __init__.py
│       └── manager.py
├── tests/
│   ├── __init__.py
│   ├── conftest.py
│   ├── test_auth.py
│   ├── test_ha.py
│   └── test_devices.py
├── alembic/
│   ├── env.py
│   ├── script.py.mako
│   └── versions/
├── alembic.ini
├── pyproject.toml
├── .env.example
├── .env.development
├── Dockerfile
├── docker-compose.dev.yml
└── README.md
```

## 🔧 技术栈版本

```toml
# pyproject.toml 核心依赖
python = "^3.11"
fastapi = "^0.115"
uvicorn = {extras = ["standard"], version = "^0.32"}
sqlalchemy = {extras = ["asyncio"], version = "^2.0"}
asyncpg = "^0.30"
alembic = "^1.14"
pydantic = "^2.10"
pydantic-settings = "^2.6"
python-jose = {extras = ["cryptography"], version = "^3.3"}
passlib = {extras = ["bcrypt"], version = "^1.7"}
cryptography = "^43"
python-multipart = "^0.0.20"
websockets = "^13"
redis = "^5.2"
slowapi = "^0.1.9"
httpx = "^0.28"
pytest = "^8.3"
pytest-asyncio = "^0.24"
```

## 🛠️ 实施步骤

### 步骤 2.1：初始化项目

```bash
cd /opt/cloudlink/backend

# 用 uv（推荐）或 poetry
pip install uv
uv init --package
# 或 poetry init

# 创建目录结构
mkdir -p app/{api,core,models,schemas,ws} tests alembic/versions
touch app/__init__.py
# （依次创建所有 __init__.py）
```

### 步骤 2.2：数据模型（app/models/）

按 [ARCHITECTURE.md §数据模型](../ARCHITECTURE.md#数据模型) 定义：

- `user.py`：用户表
- `ha_instance.py`：HA 实例表
- `device.py`：设备表

使用 SQLAlchemy 2.0 async API。

### 步骤 2.3：Pydantic Schemas（app/schemas/）

请求 / 响应数据模型：

- `auth.py`：RegisterReq / LoginReq / TokenResp / RefreshReq
- `ha.py`：RegisterHAResp / HeartbeatReq / DeviceSyncReq
- `device.py`：DeviceResp / ActionReq

### 步骤 2.4：核心工具（app/core/）

- `security.py`：JWT 编解码、bcrypt 密码
- `crypto.py`：Fernet 加解密 HA Token
- `logging.py`：日志配置（structlog）

### 步骤 2.5：API 路由（app/api/）

按 [ARCHITECTURE.md §协议设计](../ARCHITECTURE.md#协议设计) 实现所有端点。

**关键端点**：

```python
# auth.py
POST /api/auth/register     # 注册
POST /api/auth/login         # 登录，返回 access+refresh
POST /api/auth/refresh       # 刷新 access

# ha.py
POST /api/ha/register                # 用户注册 HA 实例
GET  /api/ha/instances               # 列出用户的 HA
DEL  /api/ha/instances/{id}          # 删除
POST /api/ha/{cloud_token}/heartbeat # HA 端
POST /api/ha/{cloud_token}/devices/sync
POST /api/ha/{cloud_token}/state

# devices.py
GET  /api/devices
GET  /api/devices/{entity_id}
POST /api/devices/{entity_id}/action

# ws.py
WS /ws/ha?token={cloud_token}
WS /ws/client?token={access_token}
```

### 步骤 2.6：WebSocket 管理（app/ws/manager.py）

实现 `ConnectionManager`：

```python
class ConnectionManager:
    async def connect_ha(cloud_token, ws)
    async def disconnect_ha(cloud_token)
    async def connect_client(user_id, ws)
    async def disconnect_client(user_id, ws)
    async def send_to_ha(cloud_token, msg)
    async def broadcast_to_user(user_id, msg)
```

### 步骤 2.7：数据库迁移（alembic）

```bash
# 初始化
alembic init alembic

# 配置 alembic.ini 和 alembic/env.py 用 async URL

# 生成首次迁移
alembic revision --autogenerate -m "init schema"

# 应用
alembic upgrade head
```

### 步骤 2.8：本地运行

```bash
# 用 docker-compose.dev.yml 起 postgres + redis
docker compose -f docker-compose.dev.yml up -d

# 跑迁移
alembic upgrade head

# 启动 FastAPI
uvicorn app.main:app --reload --port 8000
```

访问 `http://localhost:8000/docs` 看 Swagger UI。

### 步骤 2.9：单元测试

```bash
pytest -v
```

关键测试：

- test_auth.py：注册、登录、刷新、错误密码
- test_ha.py：注册实例、心跳、token 验证
- test_devices.py：列出、操作、鉴权失败

## 📡 API 设计要点

### 设备控制（POST /api/devices/{entity_id}/action）

请求：

```json
{
  "domain": "light",
  "service": "turn_on",
  "data": {"brightness": 200, "color_temp": 300}
}
```

后端处理：

```python
1. 鉴权（JWT）
2. 验证 entity_id 属于当前用户
3. 找到对应 HA 实例的 WebSocket 连接
4. 发送 {type: "device_action", domain, service, entity_id, data}
5. 等待 HA 端 ack（可选，超时 5s）
6. 返回 202 Accepted
```

### HA Token 加密

```python
from cryptography.fernet import Fernet

# 密钥来自环境变量 FERNET_KEY
def encrypt_ha_token(plain: str) -> bytes:
    return Fernet(settings.FERNET_KEY).encrypt(plain.encode())

def decrypt_ha_token(cipher: bytes) -> str:
    return Fernet(settings.FERNET_KEY).decrypt(cipher).decode()
```

密钥生成（一次性）：

```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

存到 `.env` 的 `FERNET_KEY=...`，**绝不**进代码仓库。

## ✅ 验收测试

### 自动化测试

```bash
pytest -v --cov=app --cov-report=term-missing
```

覆盖率目标 > 70%。

### 手动 API 测试

```bash
# 1. 注册
curl -X POST http://localhost:8000/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"testpass123"}'

# 2. 登录
TOKEN=$(curl -s -X POST http://localhost:8000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"testpass123"}' \
  | jq -r '.access_token')

# 3. 注册 HA 实例
curl -X POST http://localhost:8000/api/ha/register \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"我的家"}'
```

## 📦 提交规范

```bash
git checkout -b feat/phase-2-backend

# 阶段性提交
git add backend/
git commit -m "feat(backend): scaffold FastAPI project structure"
git commit -m "feat(backend): add user auth with JWT"
git commit -m "feat(backend): add HA instance management"
git commit -m "feat(backend): add device sync and control APIs"
git commit -m "feat(backend): add WebSocket manager"
git commit -m "test(backend): add unit tests for auth and HA APIs"
```

## 🚀 下一步

Phase 2 完成后，进入 **Phase 3：后端部署**（Docker + Nginx + HTTPS）。
