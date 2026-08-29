# CloudLink HA Plugin

> Custom Home Assistant integration that bridges HA devices with CloudLink cloud

## 📦 安装

### 方式 1：HACS（推荐）

1. HACS → 集成 → 自定义仓库
2. 添加 `https://github.com/yourname/cloudlink`（类别：Integration）
3. 搜索 "CloudLink" 安装

### 方式 2：手动

```bash
# 把 custom_components/cloudlink 复制到 HA 配置目录
cp -r custom_components/cloudlink /config/custom_components/

# 重启 HA
```

## ⚙️ 配置

```
HA UI → 设备与服务 → 集成 → 添加集成 → 搜 "CloudLink"
```

需要：

- **Cloud URL**：云端地址，如 `https://api.your-domain.com`
- **Cloud Token**：从 `/api/ha/register` 接口返回的 token

## 🔍 日志

```bash
tail -f /config/home-assistant.log | grep cloudlink
```

## 🐛 故障排查

| 现象 | 原因 | 解决 |
|---|---|---|
| 集成添加失败 | cloud_token 错误 | 在云端重新注册 |
| WebSocket 频繁重连 | 网络问题 | 检查服务器连通性 |
| 设备列表为空 | 没获取到状态 | 确认 HA 用户有读权限 |
| 设备控制没反应 | service 不存在 | 检查 domain/service 拼写 |

## 📁 代码结构

```
custom_components/cloudlink/
├── __init__.py          # 入口、生命周期
├── manifest.json        # 元数据
├── config_flow.py       # UI 配置流程
├── const.py             # 常量
└── cloud_client.py      # 与云通信的核心
```

详细开发参见 [Phase 4 文档](../docs/phases/phase-4-ha-plugin.md)
