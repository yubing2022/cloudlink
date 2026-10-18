# CloudLink 仓库维护指南（后端 + Android + HA 插件）

> 这份仓库现在由**一个维护者**负责全部四个目录（`backend/` / `android/` / `ha-plugin/` / `infra/`），
> 不再有"后端 AI / APK AI"的分工。文档按这个前提写的。
> 包含：架构概览、**部署流程（重点）**、测试方法、硬约束、常见操作、跨组件契约。

## 0. 30 秒 TL;DR

后端跑在阿里云 ECS `i-bp17ymd3baklc58tzbth`（`118.31.225.109:8000`），用 `workbench` CLI 通过 OSS 中转部署。日常只需要一条命令：

```bash
cd /Users/guoyubing/Codex/cloudlink
scripts/deploy.sh --status     # 可选：先看要在线上改什么
scripts/deploy.sh              # 语法检查 → pytest → 上传 → docker cp → 清 __pycache__ → restart → health
```

`scripts/deploy.sh` 自带测试、语法检查、清 `__pycache__`、health 轮询和失败日志，失败退出码 1 且可重复执行。手敲的 5 步版本在 §2.1，脚本坏了才用。

```bash
# 提交照旧（脚本只负责发到线上）
git add <files> && git commit -m "..." && git push origin main
```

如果你只能记住 3 件事：(1) 永远删 `__pycache__` 再重启（脚本已内置）；(2) `Domain mismatch: device is ` 这串字符是已装机 APK 的兜底，**别动**；(3) 改后端错误语义必须同步检查 APK 的 `mapHttpError`，见第 7 节。

---

## 1. 架构与拓扑

### 1.1 服务栈（ECS 上的容器）

```
nginx (cloudlink-nginx, :80, :443)
  └─ /etc/nginx/nginx.conf → /app (cloudlink-backend) 端口 8000
cloudlink-backend (FastAPI + uvicorn, :8000)
  └─ cloudlink-postgres (PostgreSQL 15, 内部网络, 无对外端口)
  └─ cloudlink-redis    (Redis 7, 内部网络)
```

后端镜像构建自 `backend/Dockerfile`，**代码是 baked into the image**。`docker-compose.yml` 把 `app/`、`alembic/`、`alembic.ini` 拷进镜像。所以"改代码"等于"重建镜像 + 重启容器"——但目前我们的部署流程**不做镜像重建**，而是直接 `docker cp` 把单文件覆盖到运行中的容器里。

### 1.2 角色与本机环境

- **Mac（guoyubing 的本机）**：日常开发在这里。`/Users/guoyubing/Codex/cloudlink/` 是工作区。`git` 在这里跑。
- **`workbench` CLI** (`/usr/local/bin/workbench`)：阿里云提供的免 SSH 直连 ECS 工具，通过 OSS 中转文件 + 调 ECS API 执行命令。比 SSH 简单，比 SSH 慢。
- **ECS (`i-bp17ymd3baklc58tzbth`, `118.31.225.109:8000`)**：线上后端。
- **HA 实例** (`192.168.3.143`)：家里树莓派，HA 跑在这里。cloudlink 集成通过 WSS 连 ECS。
- **APK / Android 手机**：用户手机，装的是 **CloudLink App 本身**（`com.yubing.cloudlink`），capability 服务 `CloudLinkCapabilityService` 就内嵌在这个 APK 里（不是独立 APK）。AgentBridgeClient 通过 `ACTION_CAPABILITY_PROVIDER` 扫到它。

### 1.3 代码与配置布局

| 路径 | 说明 |
|---|---|
| `/Users/guoyubing/Codex/cloudlink/backend/` | 后端代码（git 仓库根下的 `backend/`） |
| `/opt/cloudlink/backend/` | ECS 上 `docker-compose.yml` 的工作目录，**代码是 backend 镜像构建时拷进来的副本** |
| `cloudlink-backend` 容器内 `/app/` | 容器里后端的运行时工作目录（也是 baked in） |
| `cloudlink-postgres` 容器内 `/var/lib/postgresql/data` | 数据库持久化卷 |
| `~/.codex/attachments/.../pasted-text.txt` 等 | 用户在 Codex 里粘的日志/报错，做调查用 |

---

## 2. 部署流程（**这一节是重点**）

### 2.0 一键部署（日常就用这个）

```bash
scripts/deploy.sh                 # 部署「上次部署之后」backend/ 的所有改动
scripts/deploy.sh --status        # 只看线上状态 + 待部署文件，不做改动
scripts/deploy.sh --dry-run       # 只打印计划
scripts/deploy.sh --all           # 全量（首次部署 / 记录丢了）
scripts/deploy.sh --changed origin/main   # 指定比较基线
scripts/deploy.sh --full          # 连 compose/Dockerfile/.env.production 一起传，重建镜像
scripts/deploy.sh --migrate       # 顺带 alembic upgrade head
scripts/deploy.sh backend/app/api/devices.py   # 只部署指定文件
```

脚本做的就是下面 §2.1 那 5 步，另外加了几个保险：

| 阶段 | 行为 |
|---|---|
| 预检 | 检查 workbench/python3；对要传的 `.py` 做 `ast.parse` 语法检查（**不过就不上传**） |
| 测试 | 自动找带 pytest 的解释器，跑 `tests/ --ignore=tests/integration`（避免打到线上）；`--no-test` 跳过 |
| 上传 | 逐个 `workbench upload --force` |
| 应用 | 一个远端脚本里 `docker cp` → 清 `__pycache__` → `docker restart` → 轮询 health（最多 60s） |
| 失败 | 自动打 `docker logs --tail 60`，退出码 1；脚本可重复执行 |
| 收尾 | 容器内 health + 公网 health 都 200 才算成功，然后把当前 HEAD 写进服务器 `/opt/cloudlink/.deployed_commit` |

下次跑 `scripts/deploy.sh` 时就用这个 marker 做增量判断（`git diff <marker> -- backend/` 加上未跟踪的新文件）。`.bak` / `*.pyc` / `__pycache__` 之类永远不会被上传。

`--full` 会走 `docker compose build backend && up -d`（超时 1500s），只在你改了 `docker-compose.yml` / `Dockerfile` / `.env.production` 时才需要。

下面的手工流程留着：脚本坏了、或者需要精细控制（比如只 cp 不改 compose、单独跑迁移）时照着手敲。

### 2.1 完整流程

1. **本地改代码 + 跑测试**
   ```bash
   cd /Users/guoyubing/Codex/cloudlink/backend
   # 改 app/api/xxx.py 或 app/ws/yyy.py 等
   python3 -m pytest tests/ -q       # 至少 tests/test_devices.py 必跑
   ```

2. **commit + push**
   ```bash
   cd /Users/guoyubing/Codex/cloudlink
   git add backend/...
   git diff --cached --stat          # 自检：只动了 backend/，没意外带入 android/ 或 ha-plugin/
   git commit -m "..."
   git push origin main
   ```

3. **用 workbench 上传文件到 ECS 上的副本**
   ```bash
   workbench upload <local_file> /opt/cloudlink/backend/<path> \
     --instance-id i-bp17ymd3baklc58tzbth --force
   ```
   - `<local_file>` 是 Mac 上的绝对路径，比如 `/Users/guoyubing/Codex/cloudlink/backend/app/api/devices.py`
   - `<path>` 是 ECS 上 `/opt/cloudlink/backend/` 之后的相对路径，比如 `app/api/devices.py`
   - `--force` 覆盖已存在的文件

4. **用 `docker cp` 覆盖到运行中的容器里 + 清 `__pycache__` + 重启**
   ```bash
   workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "
     docker cp /opt/cloudlink/backend/<path> cloudlink-backend:/app/<path> &&
     docker exec cloudlink-backend find /app/app -name __pycache__ -type d -exec rm -rf {} + &&
     docker restart cloudlink-backend
   "
   ```
   - **删 `__pycache__` 是必须的**——容器里如果缓存了旧字节码，新文件可能不被加载
   - `docker restart` 让 uvicorn 重新读代码（约 5-10s）

5. **健康检查**
   ```bash
   workbench exec --instance-id i-bp17ymd3baklc58tzbth \
     --command "curl -s -o /dev/null -w 'health HTTP %{http_code}\\n' http://localhost:8000/health"
   ```
   - 必须 `HTTP 200`。如果不是，看 `docker logs --tail 50 cloudlink-backend` 排查

### 2.2 完整一行（实操里基本都用这个）

```bash
# 假设刚改完 backend/app/api/devices.py
workbench upload /Users/guoyubing/Codex/cloudlink/backend/app/api/devices.py \
  /opt/cloudlink/backend/app/api/devices.py --instance-id i-bp17ymd3baklc58tzbth --force && \
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command \
  "docker cp /opt/cloudlink/backend/app/api/devices.py cloudlink-backend:/app/app/api/devices.py && docker exec cloudlink-backend find /app/app -name __pycache__ -type d -exec rm -rf {} + && docker restart cloudlink-backend" && \
sleep 6 && \
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "curl -s -o /dev/null -w 'health HTTP %{http_code}\\n' http://localhost:8000/health"
```

### 2.3 多文件改动

可以批量上传，单条 `workbench exec` 一次性 cp 多个文件。例：

```bash
workbench upload /Users/guoyubing/Codex/cloudlink/backend/app/api/devices.py \
  /opt/cloudlink/backend/app/api/devices.py --instance-id i-bp17ymd3baklc58tzbth --force
workbench upload /Users/guoyubing/Codex/cloudlink/backend/app/api/ws.py \
  /opt/cloudlink/backend/app/api/ws.py --instance-id i-bp17ymd3baklc58tzbth --force
workbench upload /Users/guoyubing/Codex/cloudlink/backend/app/ws/manager.py \
  /opt/cloudlink/backend/app/ws/manager.py --instance-id i-bp17ymd3baklc58tzbth --force

workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "
  docker cp /opt/cloudlink/backend/app/api/devices.py cloudlink-backend:/app/app/api/devices.py
  docker cp /opt/cloudlink/backend/app/api/ws.py cloudlink-backend:/app/app/api/ws.py
  docker cp /opt/cloudlink/backend/app/ws/manager.py cloudlink-backend:/app/app/ws/manager.py
  docker exec cloudlink-backend find /app/app -name __pycache__ -type d -exec rm -rf {} +
  docker restart cloudlink-backend
"
sleep 6
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "curl -s -o /dev/null -w 'health HTTP %{http_code}\\n' http://localhost:8000/health"
```

### 2.4 配置文件改动（`docker-compose.yml`, `.env.production`, `nginx`, `Dockerfile`）

这些**不**走上面的 `docker cp` 流程。改动后必须重建镜像：

```bash
cd /Users/guoyubing/Codex/cloudlink/backend
# 上传新文件到 ECS
workbench upload docker-compose.yml /opt/cloudlink/backend/docker-compose.yml --instance-id i-bp17ymd3baklc58tzbth --force
# 重建并重启（注意 build 会拉镜像、慢一点）
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "cd /opt/cloudlink/backend && docker compose --env-file .env.production build backend && docker compose --env-file .env.production up -d"
```

数据库 schema 变更走 alembic：

```bash
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "cd /opt/cloudlink/backend && docker compose --env-file .env.production exec backend alembic upgrade head"
```

**当前没有需要重建镜像的改动**——代码改动都在 Python 文件里，全部走 §2.1 流程。

### 2.5 不用 SSH

理论上可以用 SSH 登 ECS（`ssh root@118.31.225.109`），但**走 workbench 更省事**。除非 workbench 挂了，否则别用 SSH。

---

## 3. 怎么写代码 & 测试

### 3.1 项目结构（只列与维护相关的部分）

```
backend/
├── app/
│   ├── main.py                  # FastAPI app 实例
│   ├── database.py              # SQLAlchemy async engine + get_db 依赖
│   ├── deps.py                  # get_current_user 等鉴权依赖
│   ├── config.py                # Settings（pydantic-settings）
│   ├── api/
│   │   ├── auth.py              # /api/auth/{register,login,refresh,me}
│   │   ├── devices.py           # /api/devices/*   ← 这次错误频发的文件
│   │   ├── ha.py                # /api/ha/*        ← 设备注册、heartbeat、device_sync
│   │   └── ws.py                # /api/ws/{ha,client}  ← WebSocket handler
│   ├── ws/
│   │   └── manager.py           # ConnectionManager + request_action_and_wait
│   ├── models/                  # SQLAlchemy ORM
│   │   ├── user.py
│   │   ├── ha_instance.py
│   │   └── device.py            # HADevice + DeviceEntity
│   ├── schemas/                 # Pydantic 请求/响应
│   ├── core/                    # security (JWT, password) + crypto (Fernet)
│   └── alembic/                 # 迁移
├── tests/
│   ├── conftest.py              # SQLite-in-memory 测试引擎 + 公共 fixture
│   ├── test_devices.py          # 主测试文件（21 用例）
│   ├── test_auth.py
│   └── integration/             # 跑线上 ECS 的脚本（默认目标 http://118.31.225.109）
├── docker-compose.yml
├── Dockerfile
├── .env.production              # ECS 用，**未 git-tracked**，含 JWT_SECRET/FERNET_KEY/DB pwd
└── pyproject.toml               # 含 dev deps（pytest, ruff, etc.）
```

### 3.2 测试

```bash
cd /Users/guoyubing/Codex/cloudlink/backend
python3 -m pytest tests/test_devices.py -q   # 主回归
python3 -m pytest tests/ -q                  # 全跑
```

测试用 **SQLite in-memory**（`tests/conftest.py` 把 `DATABASE_URL` 替换），所以**本地不需要 Postgres**。`test_devices.py` 当前 21 用例全绿。

注意 `tests/integration/test_api.py` 跑的是**真实 ECS**（默认 `http://118.31.225.109`），会做实际的 HTTP 请求——**别在本地无脑跑**。如果要跑，需要：
- HA 插件已经连上云
- 后端已经跑新版代码
- 接受它会创建/删除测试数据

### 3.3 lint / format

```bash
cd /Users/guoyubing/Codex/cloudlink/backend
ruff check app/ tests/
ruff format app/ tests/
```

（`ruff` 通过 `pip install -e .[dev]` 装。）

---

## 4. 硬约束（**改了会爆**）

### 4.1 `Domain mismatch: device is ` 这串字符

`backend/app/api/devices.py` 的 `control_device` 在 domain 不符时返回：

```python
f"Domain mismatch: device is {device.domain}, request says {body.domain}",
```

**已装机的 Android capability**（`com.yubing.cloudlink.capability.CloudLinkCapabilityService.kt::mapHttpError`）在 400 + 缺 `error_type` 的情况下，**fallback 用 `detail.contains("Domain mismatch")` 匹配**。所以这串字符**一个字符都不能动**——改了，已装机 APK 的分类会直接退化成 `INVALID_PARAMS` 而非 `DOMAIN_MISMATCH`。

如果你要加新功能，把这个分支移到 `_error("domain_mismatch", f"Domain mismatch: ...")`（结构化 detail），**保留前缀不变**。

### 4.2 路由顺序

`backend/app/api/devices.py` 的路由声明顺序是固定的：

```python
@router.get("")                                       # 列表
@router.get("/{entity_id:path}/state", ...)           # ← 必须在 catch-all 之前
@router.get("/{entity_id:path}", ...)                 # catch-all
@router.post("/{entity_id:path}/action", ...)
```

`{entity_id:path}` 是个贪婪匹配，**会吞掉 `/{id}/state` 路径**。把 `state` 路由放后面的话，`/api/devices/light.x/state` 会被 catch-all 抢走然后报"父设备"而不是 state。**新加路由时检查这个顺序。**

### 4.3 `error_type` 与 `message` 必须语义一致

后端 404 路径有 3 处，**全部按 `entity_id` 查 `DeviceEntity`**，所以语义统一为 `entity_not_found`：
- `get_device_state` 404 → `error_type=entity_not_found`，`message="Entity not found"`
- `get_device` 404 → `error_type=entity_not_found`，`message="Entity not found"`
- `control_device` 404 → `error_type=entity_not_found`，`message="Entity not found"`

**改了其中一个 message 但忘了改对应 error_type，就会自相矛盾**——APK 看到 `error_type=device_not_found` 配 `message="Entity not found"` 会困惑（`99b9fd7` 就是这么翻的车）。所有 4 处 detail 都通过 `_error()` 构造（`backend/app/api/devices.py:21-36`），保持它作为单一来源。

### 4.4 四个目录都在维护范围内

以前这里写着"只改 `backend/`，别动 `android/`"——那是两个 AI 分工时的边界，**现在已经不成立**。四个目录都可以改，区别只在于改动后怎么生效：

| 目录 | 运行在哪 | 改动后如何生效 |
|---|---|---|
| `backend/` | ECS 的 `cloudlink-backend` 容器 | §2 的 `docker cp` + 清 `__pycache__` + `docker restart` |
| `android/` | 用户手机（`com.yubing.cloudlink`） | 本地 `gradle assembleDebug` → `adb install -r` → **手动打开一次 App**（见 §4.6） |
| `ha-plugin/` | HA 主机 `192.168.3.143` 的 `/config/custom_components/cloudlink/` | 拷文件 + `ha core restart`（不是 reload，reload 不重跑 `device_sync`） |
| `infra/` | ECS 的 nginx 等容器 | 按 §2.4 重建对应服务 |

`docs/phases/` 是历史 phase 文档，只读不改。

唯一真正的硬约束是**跨目录契约**：改 `backend/` 的 HTTP 行为时，同一轮里必须检查 `android/` 的 `mapHttpError` 还匹配不匹配（第 7 节）。

### 4.5 工作区里的 untracked 文件

`backend/app/api/devices.py.bak`（2026-10-17 22:21）是上次回退 commit 留下的，**故意保留**（用户的保险）。**别 commit 它，别删它**。

`android/` 的包名迁移（`com.yourname.cloudlink` → `com.yubing.cloudlink`）已于 2026-10-18 全部入库，工作区应当是 clean 的。如果哪天又冒出一批 untracked 的 `.kt`，先确认是不是新建文件的正常状态，别默认"这些不重要"。

### 4.6 Android 构建与装机

仓库**没有 `gradlew`**，用系统 `gradle`；而且默认 `JAVA_HOME` 若是 openjdk 26，`androidJdkImage` 的 jlink 会直接失败。必须切 Android Studio 自带的 JBR：

```bash
cd /Users/guoyubing/Codex/cloudlink/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装机后**必须手动打开一次 CloudLink App**。`adb install -r` 会杀掉进程，而这台荣耀手机不允许 AgentBridgeClient 在后台把能力提供方 bind 起来（`stopped=false`、无崩溃、manifest 正常，纯粹是系统后台拉起策略）。不打开的话所有 `cloudlink.device.*` 调用都返回：

```
provider_offline: provider unavailable: com.yubing.cloudlink
```

这不是代码问题，别去改代码排查。

---

## 5. 常见操作

### 5.1 改密码来跑测试（重要工具）

线上用户密码不可见（`password_hash` 存在 DB 里）。要登录测试，最方便的是临时把密码设成已知的：

```bash
# 写个 SQL 脚本到 ECS
cat > /tmp/set_pw.sql << 'EOF'
UPDATE users SET password_hash='$pbkdf2-sha256$600000$<salt>$<hash>' WHERE email='guoyubing123@163.com';
EOF
```

其中 hash 是用后端的 `hash_password()` 算出来的。**最稳的方式**是用 workbench exec 跑 Python：

```bash
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "
  docker exec cloudlink-backend python -c \"from app.core.security import hash_password; print(hash_password('Cloud@2024'))\"
"
# 把输出塞到 SQL 里
NEW_HASH=$(workbench exec ... --command "docker exec cloudlink-backend python -c \"...\"")
# 然后 cat > /tmp/set_pw.sql 拼 SQL，docker cp 进去，psql -f 执行
# 完事之后再用 cat 出来的原 hash 写回去
```

**永远记得测完把原 hash 写回去**。我每次都把原始 hash 提前抓下来备份：

```bash
ORIG=$(workbench exec --instance-id i-bp17ymd3baklc58tzbth --command \
  "docker exec cloudlink-postgres psql -U cloudlink -d cloudlink -At -c \"SELECT password_hash FROM users WHERE email='guoyubing123@163.com';\"")
# 存到本地一个文件
echo "$ORIG" > /tmp/_orig_hash.txt
```

恢复：
```bash
# 读出来，写成 SQL，docker cp，psql -f
```

### 5.2 跑一个完整的"调一次设备 + 读 state" 测试

```bash
# 设临时密码、登录、打 turn_off、读 state、恢复亮度、恢复密码
# 完整脚本示例见 /tmp/smoke_test.sh （每次都重新写）
```

最常用的 4 个验证：

```bash
# 1. 列表：14 个非空设备
curl -s -H "Authorization: Bearer $TOKEN" http://118.31.225.109:8000/api/devices | \
  python3 -c "import json,sys;d=json.load(sys.stdin);print(f'total {len(d)}  empty {sum(1 for x in d if not x[\"entities\"])}')"

# 2. 单个 entity state
curl -s -H "Authorization: Bearer $TOKEN" \
  http://118.31.225.109:8000/api/devices/light.yeelink_cn_1208334138_mbulb3_s_2/state

# 3. action（控制）
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"domain":"light","service":"turn_off"}' \
  http://118.31.225.109:8000/api/devices/light.yeelink_cn_1208334138_mbulb3_s_2/action

# 4. 后端日志
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "docker logs --tail 50 cloudlink-backend"
```

### 5.3 强制 HA plugin 重新 sync

```bash
# 通过 HA 端 reload 集成（用户家那台 192.168.3.143）
ssh root@192.168.3.143 "ha core restart"
# 或只 reload 集成
ssh root@192.168.3.143 "ha core reload"
```

注意：reload 集成不会重新执行 device_sync，**只有 `ha core restart`（重启整个 HA core）会触发**。

### 5.4 看 HA plugin 端日志

```bash
ssh root@192.168.3.143 "ha core logs --lines 200 2>&1 | grep -i cloudlink | tail -30"
```

### 5.5 看 ECS 后端实时日志

```bash
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "docker logs --tail 100 cloudlink-backend 2>&1 | grep -iE 'device_sync|action|error'"
```

### 5.6 直查 Postgres

```bash
# 注意 shell escaping：双层引号，里面的双引号要 escape
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "
  docker exec cloudlink-postgres psql -U cloudlink -d cloudlink -c \"
    SELECT id, ha_device_id, name, manufacturer, model
    FROM ha_devices
    WHERE manufacturer IS NULL OR model IS NULL
    ORDER BY id;
  \"
"
```

或者用 stdin：
```bash
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "
  echo \"SELECT * FROM users WHERE id=60;\" | docker exec -i cloudlink-postgres psql -U cloudlink -d cloudlink
"
```

### 5.7 看 HA 插件是否连上云（WS）

```bash
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "
  docker logs --tail 200 cloudlink-backend 2>&1 | grep -iE 'HA connected|HA disconnected|device_sync for instance'
"
```

`HA connected: p0TPXiQ1...` 表示插件在线。`device_sync for instance N: devices +X ~Y -Z, entities +...` 表示每次 sync 的增量。

---

## 6. 部署前自检清单

部署任何 backend 改动前，按这张表走一遍：

| 检查项 | 命令 | 期望 |
|---|---|---|
| 1. pytest 全绿 | `cd backend && python3 -m pytest tests/ -q` | `XX passed, 0 failed` |
| 2. 没有意外带入未跟踪文件 | `git status --short backend/` | 只能看到 `M` 或 `A`，没有 `??` 残留 |
| 3. staged 的只有本次要部署的文件 | `git diff --cached --stat` | 路径都在预期范围内，没有误带 `.bak` |
| 4. push 成功 | `git push origin main` | 看到 `To github.com:yubing2022/cloudlink.git` |
| 5. health check | `curl -s -o /dev/null -w '%{http_code}\n' http://118.31.225.109:8000/health` | `200` |
| 6. HA 重新连接 | `ha core logs` 看 `WS connected` | 看到一行新的 connected 日志 |
| 7. 实测 endpoint | curl 3-5 个 endpoint | 状态码 + 响应内容符合预期 |

**没跑第 1 步就不要部署。**后端 Python 改动没测试覆盖 ≈ 一定会出线上事故。

---

## 7. 跨组件契约（改后端时必须连带检查的清单）

现在没有"另一个 AI 在改 APK"这回事，这一节的内容从「协调协议」变成了「自己跟自己对契约」。风险本身没变：**后端和 APK 是两个独立的发布时间**——后端改完几十秒就上线，APK 却要重新编译、装机、再由用户手动打开。一旦后端的 HTTP 行为变了而手机上的 APK 还是旧版，线上就是错配状态。

### 7.1 一套固定的联动步骤

改任何影响 HTTP 行为的后端代码时，按这个顺序走：

1. 改 `backend/`，跑 `pytest tests/ -q`
2. 同步看 `android/app/src/main/java/com/yubing/cloudlink/capability/CloudLinkCapabilityService.kt::mapHttpError` 是否还能正确分类新的 detail 形状；需要改就在**同一个 commit** 里改完
3. 本地编译 APK 并装机（§4.6）
4. 部署后端（§2）
5. 经 MCP 全链路验证（§7.5）

**如果只部署后端、没装新 APK**，旧 APK 会按老规则解析新 detail，表现是错误码降级（例如 `DOMAIN_MISMATCH` 变成 `INVALID_PARAMS`）却完全不报错。这类问题最难查——它不崩，只是答错。

### 7.2 当前已固化的契约

| HTTP | `error_type` | message 前缀 | APK 侧错误码 |
|---|---|---|---|
| 404 | `entity_not_found` | `Entity not found` | `ENTITY_NOT_FOUND` |
| 400 | `domain_mismatch` | `Domain mismatch: device is ` | `DOMAIN_MISMATCH` |
| 400 | `service_not_found` | `Action <domain>.<service> not found` | `SERVICE_NOT_FOUND` |
| 400 | `invalid_params` | — | `INVALID_PARAMS` |
| 502 | `call_failed` | — | `SERVICE_CALL_FAILED` |
| 503 | `ha_offline` | — | `HA_UNAVAILABLE` |
| 504 | `ha_timeout` | — | `HA_TIMEOUT` |

APK 侧还有 3 个**不来自 HTTP** 的错误码，判定完全在 `CloudLinkCapabilityService.kt` 内完成，后端不参与：

| APK 错误码 | 触发条件 | 为什么必须在 APK 侧判 |
|---|---|---|
| `INVALID_FILTER` | `device.list` 的 `area`/`domain` 过滤值在该账号下不存在（或组合结果为空） | 过滤是客户端做的；旧行为返回空列表，调用方会把拼写错误当成"这个房间没设备" |
| `DEVICE_OFFLINE` | `control` 成功后读回 `state=unavailable` | HA 对离线实体的 service 调用**不报错也不生效**，插件拿不到异常，只有看状态才知道 |
| `INVALID_PARAMS` | `data` 不是 JSON object（如传了 `"brightness=50"`）、缺必填参数 | 旧实现用 `optJSONObject` 读，字符串会被静默丢成空 data，然后"成功" |

`detail` 统一是 `{"error_type": ..., "message": ..., "entity_id"?: ...}`，由 `devices.py` 的 `_error()` 单一构造。**新加错误分支必须走 `_error()`**，不要手写 `HTTPException(400, "字符串")`——那会让 APK 拿到字符串 shape，只能落到兜底逻辑。

### 7.3 `mapHttpError` 里有两处靠字符串兜底

`CloudLinkCapabilityService.kt` 在 detail 里**没有** `error_type` 时（兼容旧后端）会退化成字符串匹配：

- `detail.contains("Domain mismatch")` → `DOMAIN_MISMATCH`
- `detail.contains("not found")`（配合状态码）→ `ENTITY_NOT_FOUND` / `SERVICE_NOT_FOUND`

所以 `Domain mismatch: device is ` 这串字符不能动（§4.1 已单列）。真要改文案，先发新 APK，等线上全升完再改后端。

### 7.4 历史踩过的坑（别再重犯）

- `68fb8d9` action 错误语义（service_not_found / call_failed / ha_offline / ha_timeout）——首次把 detail 结构化
- `701b832` 404 的 detail 从字符串改成 dict
- `99b9fd7` 把 404 的 message 改成 `Entity not found` 却没改 `error_type=device_not_found` → **类型和文案自相矛盾**，`988a5cd` 回退；最终在 `entity_not_found` 上把两边统一
- `80aea8f` capability 的 `getState` 原来用 `runCatching{}` 吞掉一切异常，导致 500 被误报成 `ENTITY_NOT_FOUND`；改成只有 404/405 才降级拉全量列表
- 2026-10-18 审计（Agent 视角）修掉的三个"静默失败"：离线设备控制返回成功、`data` 传字符串被吞、过滤值写错返回空列表。这三个的共同点不是"报错文案不好"，而是**不报错**——调用方拿到的是与事实相反的结论

这条线上的教训只有一条：**`error_type` 和 `message` 是两个字段，必须同时改、语义一致。**

### 7.5 验证入口（改完必跑）

APK 侧能力的入口是 AgentBridge 的 MCP（`http://127.0.0.1:9023/mcp`），不是 adb：

```bash
# 列工具（确认三个能力在线）
curl -s http://127.0.0.1:9023/mcp -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 调一次（示例：读落地灯状态）
curl -s http://127.0.0.1:9023/mcp -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"cloudlink.device.get_state@v1","arguments":{"entity_id":"light.yeelink_cn_1208334138_mbulb3_s_2"}}}'
```

一次完整回归至少覆盖：正常 `get_state`、正常 `turn_on`、404 实体、domain 不符、假 service、真调用失败（如 `brightness:"not_a_number"`）。后三个是错误码正确性的关键，不能只看正常路径。


---

## 8. 故障排查（按出现频率排序）

### 8.1 health check 503 / 502

```bash
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "docker logs --tail 30 cloudlink-backend 2>&1"
```

常见原因：
- `__pycache__` 没清（4xx/5xx Python 错误但容器在跑）：按 §2.1 流程重做，**注意 step 4 的 `find ... -name __pycache__ -exec rm`**
- alembic 没跑：数据库表不存在（`relation "users" does not exist`）→ 跑 `docker compose ... exec backend alembic upgrade head`
- 配置问题：`.env.production` 的 JWT_SECRET 改过但没重启 → 重新 `docker restart`

### 8.2 HA plugin 连不上 / 一直在 reconnect

```bash
# 1. 看插件端
ssh root@192.168.3.143 "ha core logs --lines 200 2>&1 | grep -iE 'cloudlink|connect error' | tail -20"

# 2. 看后端
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "docker logs --tail 100 cloudlink-backend 2>&1 | grep -iE 'HA connected|HA disconnected' | tail -10"
```

常见原因：
- HA 那台机器的 DNS/网络问题
- 防火墙挡住了 WSS（看 `/var/log/ufw.log` 或 `sudo ufw status`）
- 后端代码引入了 import 错误，连接建立后立刻断开

### 8.3 action 永远 timeout（5s 后 504）

```bash
workbench exec --instance-id i-bp17ymd3baklc58tzbth --command "docker logs --tail 50 cloudlink-backend 2>&1 | grep -iE 'request_action_and_wait|device_action_result|504'"
```

如果看到 `device_action_result` 但时间戳对不上 → plugin 在 process 但是慢。
如果完全没看到 `device_action_result` → plugin 收到但没回，**看 HA 端 `cloudlink._execute_action` 日志**。

### 8.4 部署后没生效

99% 是 `__pycache__`。`docker restart` 不会清字节码缓存，所以**必须**显式 `find ... -name __pycache__ -type d -exec rm -rf {} +`。看到诡异行为先做这步。

### 8.5 `workbench` 卡住 / 超时

```bash
# 看看 daemon 状态
workbench daemon status
# 必要时重启 daemon
workbench daemon stop
sleep 3
nohup workbench daemon start > /tmp/wb.log 2>&1 &
```

---

## 9. 必读文件清单（如果只能看 5 个文件）

1. `backend/app/api/devices.py` — 80% 的日常改动在这里
2. `backend/app/ws/manager.py` — WebSocket + 同步请求协议
3. `backend/app/main.py` — app 实例
4. `backend/tests/conftest.py` + `backend/tests/test_devices.py` — 21 个测试，看懂怎么 mock
5. `backend/app/api/ws.py` — WebSocket handler，device_sync / state_change / device_action_result 三种消息

---

## 10. 版本

- 文档基线 commit：`5e21813`（`refactor(android): rename package to com.yubing.cloudlink, embed capability`）
- 修订：2026-10-18 第二版 —— 按**单一维护者**重写 §4.4 / §4.5 / §7，新增 §4.6 Android 构建与装机
- 修订：2026-10-18 第三版 —— §2.0 增加 `scripts/deploy.sh` 一键部署（脚本已实现，旧的手工 5 步保留在 §2.1）
- 撰写时间：2026-10-18
- 适用 Python：3.11+（容器内是 python:3.11-slim）
- FastAPI：≥ 0.115
- SQLAlchemy：2.x async
- Android：AGP + compose BOM，`compileSdk 35` / `minSdk 26`，构建需 JDK 17（Android Studio JBR）

更新这份文档时，请把上面那行 commit hash 一起更新。
