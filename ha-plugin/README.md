# CloudLink HA Plugin

> Custom Home Assistant integration that bridges your HA devices to a [CloudLink cloud server](../backend).

## 🎯 功能

- HA 启动后，**自动同步所有设备**到云端
- HA 设备状态变化时，**实时推送到云**
- 云端下发指令时，**自动调用 HA service**
- WebSocket 断线自动重连（指数退避）

## 📦 安装

### 方式 1：手动（HACS 之外）

```bash
# 在 HA 服务器上，把整个 cloudlink 目录软链或复制到 HA 的 custom_components
ln -s /path/to/cloudlink/ha-plugin/custom_components/cloudlink \
      /config/custom_components/cloudlink

# 或直接复制
cp -r /path/to/cloudlink/ha-plugin/custom_components/cloudlink /config/custom_components/

# 重启 HA
ha core restart
```

### 方式 2：HACS（发布后）

1. HACS → Integrations → ⋯ → Custom repositories
2. 添加 `https://github.com/yourname/cloudlink`
3. 搜索 "CloudLink" 安装
4. 重启 HA

## ⚙️ 配置

### 1. 获取 cloud_token（从云后端）

需要先用 REST API 在云后端注册你的 HA 实例：

```bash
# 先注册云端账号
curl -X POST https://api.your-domain.com/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your-password"}'

# 登录拿 access token
ACCESS_TOKEN=$(curl -sX POST https://api.your-domain.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your-password"}' | jq -r '.access_token')

# 注册 HA 实例（name 随便填，ha_token 是 HA 的 Long-Lived Token）
CLOUD_TOKEN=$(curl -sX POST https://api.your-domain.com/api/ha/register \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -d '{"name":"我的家","ha_token":"<HA-Long-Lived-Token>"}' | jq -r '.cloud_token')

echo $CLOUD_TOKEN  # 复制这串
```

### 2. 在 HA 里添加集成

1. HA → Settings → Devices & Services → Add Integration
2. 搜索 "CloudLink"
3. 填入：
   - **Cloud server URL**：`https://api.your-domain.com`（或 IP 地址）
   - **Cloud token**：上一步得到的 `CLOUD_TOKEN`
4. 点 Submit

## ✅ 验证

### 在 HA 里看日志

```
Settings → System → Logs → Search "cloudlink"
```

应该看到：
```
[cloudlink] CloudLink integration started for https://api.your-domain.com
[cloudlink] Connecting to wss://api.your-domain.com/api/ws/ha...
[cloudlink] CloudLink connected and synced
[cloudlink] Synced 23 devices to cloud
```

### 在云端验证

```bash
# 登录云后端
ACCESS_TOKEN=$(curl -sX POST https://api.your-domain.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your-password"}' | jq -r '.access_token')

# 看你的设备列表
curl -X GET https://api.your-domain.com/api/devices \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

应该看到类似：
```json
[
  {"entity_id":"light.living_room","domain":"light","name":"Living Room","state":"on",...},
  {"entity_id":"sensor.temp","domain":"sensor","name":"Temperature","state":"22.5",...}
]
```

## 🧪 测试

```bash
cd ha-plugin
pip install pytest pytest-asyncio websockets
python tests/test_cloud_client.py
```

## 🔧 故障排查

| 现象 | 原因 | 解决 |
|---|---|---|
| HA 启动后没看到连接日志 | cloud_url 错 | 检查配置项 |
| 看到 "认证失败" | cloud_token 错 | 重新跑 `/api/ha/register` |
| WS 频繁断连 | 网络问题 | 检查到云服务器的连通性 |
| 设备没同步 | HA 启动时 entities 还没加载完 | 等几秒 |

## 📁 文件结构

```
ha-plugin/
└── custom_components/cloudlink/
    ├── __init__.py          # 入口
    ├── cloud_client.py       # 核心 WebSocket 客户端
    ├── config_flow.py       # UI 配置流程
    ├── const.py             # 常量
    ├── manifest.json        # HA 元数据
    └── translations/
        └── en.json          # 英文翻译
```
