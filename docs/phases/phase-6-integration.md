# Phase 6: 端到端联调

> 预计耗时：1 天  
> 目标：所有组件协同工作，全场景验证

## 🧪 测试场景

### 场景 6.1：HA 设备上报

```
前置：HA 重启，cloudlink 插件运行
操作：在 HA 里添加一个新设备（或启用一个新 entity）
预期：
  - 5秒内云端数据库 devices 表新增一行
  - Android 端刷新能看到新设备
  - 云端 /api/devices 接口返回包含该设备
```

验证命令：
```bash
# 后端日志
docker compose logs -f backend | grep "device_added"

# 数据库
docker compose exec postgres psql -U cloudlink -d cloudlink \
  -c "SELECT entity_id, domain, name FROM devices ORDER BY updated_at DESC LIMIT 5;"
```

### 场景 6.2：Android 操作设备

```
前置：App 已登录，看到设备列表
操作：在 App 点亮 "客厅主灯"
预期：
  - App UI 立即显示"开"状态（乐观更新）
  - 1秒内 HA 端实际灯亮
  - HA 触发 state_changed，云端通过 WS 推送给 App
  - App 收到推送，UI 状态与实际一致
```

### 场景 6.3：HA 状态推送

```
前置：HA 已上报设备，App 已登录
操作：在 HA 网页手动开/关一个灯
预期：
  - 1秒内 App 上对应设备状态变化
```

### 场景 6.4：HA 重连

```
前置：HA 与云已建立连接
操作：重启 HA
预期：
  - HA 启动后 10秒内自动重连
  - 全量设备列表重新同步
  - App 上设备状态恢复
```

### 场景 6.5：网络抖动

```
前置：HA 与云已建立连接
操作：在 HA 服务器上 `tc qdisc add dev eth0 root netem loss 30%` 30秒后撤销
预期：
  - HA 端重连机制触发，连接恢复
  - 中间丢失的状态在重连后通过全量同步补齐
```

### 场景 6.6：App 杀进程恢复

```
操作：杀掉 App 进程，重新打开
预期：
  - 自动用 refresh token 换取 access token
  - 自动重新连接 WebSocket
  - 设备列表加载
```

### 场景 6.7：服务端重启

```
操作：docker compose restart backend
预期：
  - HA 端 10秒内自动重连
  - App 端 WebSocket 重连，刷新数据
  - 期间触发的操作可以重试或被忽略
```

### 场景 6.8：多用户隔离

```
操作：注册第二个用户，尝试访问第一个用户的设备
预期：
  - 返回 403/404，鉴权失败
  - 不能看到或控制别人的设备
```

### 场景 6.9：JWT 过期

```
操作：等 access token 过期（15分钟）
预期：
  - App 自动用 refresh token 换新 access token
  - 用户无感知
```

### 场景 6.10：高频状态变化

```
操作：让一个灯快速闪烁（脚本控制）
预期：
  - App 收到多次状态推送
  - UI 不卡顿（节流）
```

## 📊 性能基线

| 指标 | 目标 |
|---|---|
| HA → 云 状态推送延迟 | < 500ms |
| 云 → App 推送延迟 | < 500ms |
| App → HA 操作延迟（端到端） | < 1.5s |
| API 平均响应时间 | < 100ms |
| 启动到首屏可见设备 | < 3s |
| WebSocket 重连时间 | < 10s |

## 🐛 Bug 修复流程

每个发现的问题：

1. 复现：写最小复现步骤
2. 定位：日志 + 断点
3. 修复：dev 分支
4. 验证：场景测试通过
5. 提交：`fix(scope): 描述`

```bash
git checkout -b fix/<short-desc>
# 修复
git commit -m "fix(backend): handle websocket race condition on reconnect"
# 验证
pytest -v
# 合并
git checkout main && git merge fix/<short-desc>
```

## ✅ 完成标志

- [ ] 所有 10 个场景测试通过
- [ ] 性能指标达标
- [ ] 无 P0/P1 Bug 遗留
