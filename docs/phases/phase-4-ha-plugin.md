# Phase 4: Home Assistant 自定义插件

> 预计耗时：1.5 天  
> 目标：HA 端插件可用，HA 设备自动上报到云端  
> 分支：`feat/phase-4-ha-plugin`

## 🎯 完成标志

- [ ] HA 通过 HACS 或手动方式安装 cloudlink 集成
- [ ] HA UI 配置流程可走通
- [ ] cloudlink 与云端建立 WebSocket 长连接
- [ ] HA 设备列表全量同步到云
- [ ] HA 状态变化实时推送到云
- [ ] 从云端下发的设备操作能被 HA 执行
- [ ] HA 重启后插件自动重连

## 📁 插件目录

```
ha-plugin/
├── README.md
└── custom_components/
    └── cloudlink/
        ├── __init__.py
        ├── manifest.json
        ├── config_flow.py
        ├── const.py
        ├── cloud_client.py
        ├── coordinator.py
        └── services.yaml
```

## 🛠️ 实施步骤

### 步骤 4.1：manifest.json

```json
{
  "domain": "cloudlink",
  "name": "CloudLink",
  "version": "0.1.0",
  "config_flow": true,
  "documentation": "https://github.com/yourname/cloudlink",
  "issue_tracker": "https://github.com/yourname/cloudlink/issues",
  "codeowners": ["@yourname"],
  "requirements": ["websockets>=12.0"],
  "iot_class": "local_polling",
  "integration_type": "service"
}
```

### 步骤 4.2：const.py

```python
DOMAIN = "cloudlink"
DEFAULT_CLOUD_URL = "https://api.your-domain.com"
PLATFORMS = []
```

### 步骤 4.3：config_flow.py

```python
import voluptuous as vol
from homeassistant import config_entries

from .const import DOMAIN, DEFAULT_CLOUD_URL

STEP_USER_DATA_SCHEMA = vol.Schema({
    vol.Required("cloud_url", default=DEFAULT_CLOUD_URL): str,
    vol.Required("cloud_token"): str,
})

class CloudLinkConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 1
    
    async def async_step_user(self, user_input=None):
        if user_input is not None:
            await self.async_set_unique_id(user_input["cloud_token"][:16])
            self._abort_if_unique_id_configured()
            return self.async_create_entry(
                title="CloudLink",
                data=user_input,
            )
        return self.async_show_form(
            step_id="user",
            data_schema=STEP_USER_DATA_SCHEMA,
        )
```

### 步骤 4.4：cloud_client.py（核心）

```python
import asyncio
import json
import logging
from typing import Optional

import websockets
from homeassistant.core import HomeAssistant, Event
from homeassistant.helpers.typing import ConfigType

_LOGGER = logging.getLogger(__name__)

class CloudClient:
    def __init__(self, hass: HomeAssistant, cloud_url: str, cloud_token: str):
        self.hass = hass
        self.cloud_url = cloud_url.replace("https://", "wss://").replace("http://", "ws://")
        self.cloud_token = cloud_token
        self.ws: Optional[websockets.WebSocketClientProtocol] = None
        self._stopped = False
        self._reconnect_delay = 5

    async def start(self):
        """主循环：建立并维护 WebSocket 连接"""
        while not self._stopped:
            try:
                url = f"{self.cloud_url}/ws/ha?token={self.cloud_token}"
                async with websockets.connect(url, ping_interval=30, ping_timeout=10) as ws:
                    self.ws = ws
                    self._reconnect_delay = 5
                    _LOGGER.info("Connected to cloud")
                    
                    await self._send_full_sync()
                    self.hass.bus.async_listen("state_changed", self._on_state_changed)
                    
                    async for msg in ws:
                        await self._handle_message(msg)
                        
            except websockets.ConnectionClosed:
                _LOGGER.warning("WebSocket disconnected, reconnecting...")
            except Exception as e:
                _LOGGER.exception("Unexpected error: %s", e)
            
            await asyncio.sleep(self._reconnect_delay)
            self._reconnect_delay = min(self._reconnect_delay * 2, 60)

    async def stop(self):
        self._stopped = True
        if self.ws:
            await self.ws.close()

    async def _send_full_sync(self):
        """连接成功后全量同步设备"""
        devices = []
        for state in self.hass.states.async_all():
            devices.append({
                "entity_id": state.entity_id,
                "domain": state.domain,
                "name": state.name,
                "state": state.state,
                "attributes": dict(state.attributes),
            })
        await self.ws.send(json.dumps({"type": "device_sync", "devices": devices}))
        _LOGGER.info("Synced %d devices", len(devices))

    async def _on_state_changed(self, event: Event):
        new = event.data.get("new_state")
        old = event.data.get("old_state")
        if not new or (old and old.state == new.state):
            return
        if not self.ws:
            return
        try:
            await self.ws.send(json.dumps({
                "type": "state_change",
                "entity_id": new.entity_id,
                "state": new.state,
                "attributes": dict(new.attributes),
            }))
        except Exception:
            _LOGGER.exception("Failed to send state change")

    async def _handle_message(self, msg: str):
        """处理云端下发的控制指令"""
        try:
            data = json.loads(msg)
            if data.get("type") == "device_action":
                await self.hass.services.async_call(
                    data["domain"],
                    data["service"],
                    {"entity_id": data["entity_id"], **data.get("data", {})},
                    blocking=False,
                )
                _LOGGER.info(
                    "Executed %s.%s on %s",
                    data["domain"], data["service"], data["entity_id"],
                )
        except Exception:
            _LOGGER.exception("Failed to handle message: %s", msg)
```

### 步骤 4.5：__init__.py

```python
from homeassistant.core import HomeAssistant

from .const import DOMAIN
from .cloud_client import CloudClient

async def async_setup_entry(hass: HomeAssistant, entry):
    client = CloudClient(
        hass,
        entry.data["cloud_url"],
        entry.data["cloud_token"],
    )
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = client
    
    hass.async_create_task(client.start())
    return True

async def async_unload_entry(hass: HomeAssistant, entry):
    client: CloudClient = hass.data[DOMAIN].pop(entry.entry_id)
    await client.stop()
    return True
```

### 步骤 4.6：services.yaml（可选，扩展自定义服务）

```yaml
resync_devices:
  name: Resync devices
  description: Manually trigger full device sync to cloud.
```

### 步骤 4.7：本地测试

```bash
# 在 HA 服务器上，把 cloudlink 软链到 custom_components
ln -s /path/to/cloudlink/custom_components/cloudlink \
      /config/custom_components/cloudlink

# 重启 HA
# 然后 HA UI → 设备与服务 → 集成 → 添加集成 → 搜索 "CloudLink"
```

配置项：

- Cloud URL: `https://api.your-domain.com`
- Cloud Token: 从云端 /api/ha/register 拿到的 token

### 步骤 4.8：日志观察

```bash
tail -f /config/home-assistant.log | grep cloudlink
```

期望看到：

```
[cloudlink] Connected to cloud
[cloudlink] Synced 23 devices
[cloudlink] Executed light.turn_on on light.living_room
```

### 步骤 4.9：HACS 发布（可选）

```json
// hacs.json
{
  "name": "CloudLink",
  "country": ["CN"],
  "zip_release": false,
  "filename": "cloudlink.zip",
  "hide_default_branch": false,
  "homeassistant": "2024.1.0"
}
```

打包发布到 GitHub 后用户即可通过 HACS 安装。

## ✅ 验收测试

```bash
# 1. WebSocket 连接（从云端看）
docker compose logs backend | grep "ws/ha"

# 2. 设备入库（数据库）
docker compose exec postgres psql -U cloudlink -d cloudlink \
  -c "SELECT COUNT(*) FROM devices;"

# 3. 状态推送（手动触发）
# 在 HA 里开/关一个灯，看云端日志：
docker compose logs backend -f | grep state_change

# 4. 反向控制（从云端发指令）
curl -X POST https://api.your-domain.com/api/devices/light.test/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"domain":"light","service":"toggle"}'
```

## 📦 提交

```bash
git checkout -b feat/phase-4-ha-plugin
git add ha-plugin/
git commit -m "feat(ha-plugin): scaffold custom_components structure
"
git commit -m "feat(ha-plugin): implement WebSocket cloud client
"
git commit -m "feat(ha-plugin): add config flow UI
"
git commit -m "docs(ha-plugin): installation and usage guide
"
```

## 🚀 下一步

Phase 4 完成后，进入 **Phase 5：Android 客户端**。
