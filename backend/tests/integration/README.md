# 集成测试

针对**已部署** API 的端到端验证。

## 与单元测试的区别

| 类型 | 范围 | 依赖 |
|---|---|---|
| 单元测试 (`tests/`) | 单个函数/类，mock 外部依赖 | 需要本地 Postgres |
| **集成测试 (`tests/integration/`)** | **真实 HTTP 请求 / WebSocket** | **需要后端已部署并可访问** |

## 运行

### 前提
- 后端已部署并在 `BASE_URL` 可访问
- Python 依赖：`httpx`、`websockets`

### 本地运行（macOS）

```bash
# 默认地址（部署后的服务器公网 IP）
python tests/integration/test_api.py

# 自定义地址
python tests/integration/test_api.py http://118.31.225.109
python tests/integration/test_api.py https://api.your-domain.com
```

### 在服务器上运行（通过 workbench）

```bash
# 上传脚本
workbench upload tests/integration/test_api.py /tmp/test_api.py --instance-id <id>

# 在服务器上跑（需要装 httpx 和 websockets）
workbench exec --instance-id <id> --command "pip install httpx websockets && python /tmp/test_api.py"
```

## 测试覆盖

### 1. 认证（5 个场景）
- ✅ 注册新用户
- ✅ 登录
- ✅ `/me` 带 token
- ✅ `/me` 不带 token → 401
- ✅ `/me` 无效 token → 401
- ✅ Refresh token
- ✅ 用 access token 调 refresh → 401
- ✅ 重复注册 → 409
- ✅ 错误密码 → 401
- ✅ 不存在的用户 → 401
- ✅ 非法邮箱格式 → 422
- ✅ 短密码 → 422

### 2. HA 实例管理（10 个场景）
- ✅ 注册实例返回 cloud_token
- ✅ 新实例默认 offline
- ✅ cloud_token 长度合理
- ✅ List 实例
- ✅ 多实例管理
- ✅ 删除实例
- ✅ 删除不存在的实例 → 404
- ✅ 无 auth 访问 → 401
- ✅ 空 name → 422
- ✅ 跨用户隔离（每个测试用 UUID）

### 3. HA-facing 端点（13 个场景）
- ✅ Heartbeat（合法 + 非法 token）
- ✅ last_seen 更新
- ✅ Device sync（新建 / 更新 / 删除）
- ✅ Device state report
- ✅ 未知设备自动创建
- ✅ 多用户隔离

### 4. 设备控制（5 个场景）
- ✅ HA offline 时控制 → 503
- ✅ 不存在的设备 → 404
- ✅ Domain 不匹配 → 400
- ✅ 无 auth → 401

### 5. WebSocket（8 个场景）
- ✅ HA WS ping/pong
- ✅ WS 连接后 is_online=True
- ✅ WS 断开后 is_online=False
- ✅ 非法 cloud_token 被拒绝
- ✅ Client WS ping/pong
- ✅ 非法 client token 被拒绝

## 输出格式

```
[1/5] Authentication
  ✓ register returns 201
  ✓ register returns access_token
  ✓ register returns refresh_token
  ...

============================================================
Results: 38/41 passed, 3 failed
============================================================

Failures:
  - WS connection: timeout
  - refresh token: expected 200, got 500
```

## 退出码

- **0**：全部通过
- **1**：有失败用例，或网络不可达

## 添加新测试

每个测试函数应该是 async的，使用 `assert_eq(actual, expected, name)` 报告结果。

```python
async def test_my_feature():
    print("\n[N/M] My feature")
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=10) as c:
        # setup
        token, _ = await setup_user(c, "mylabel")
        
        # test
        r = await c.get("/api/my-endpoint", headers={"Authorization": f"Bearer {token}"})
        assert_eq(r.status_code, 200, "my-endpoint returns 200")
        assert_eq(r.json()["key"], "expected", "my-endpoint returns expected value")
```

记得在 `main()` 里 `await` 它。
