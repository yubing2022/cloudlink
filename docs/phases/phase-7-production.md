# Phase 7: 生产化加固

> 预计耗时：1 天  
> 目标：可对外提供服务的稳定版本

## 🎯 加固清单

### 7.1 HTTPS 强制

```nginx
# 已在 Phase 3 完成：HTTP 80 → 301 → HTTPS 443
# 验证：
curl -I http://api.your-domain.com/health
# 应返回 301 → https://...
```

### 7.2 速率限制

`backend/app/main.py`：

```python
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded

limiter = Limiter(key_func=get_remote_address)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

@app.post("/api/auth/login")
@limiter.limit("5/minute")
async def login(request: Request, ...):
    ...
```

### 7.3 日志聚合

```bash
# Docker 日志已配置到 ./logs/nginx
# 应用日志建议接入 Sentry（异常追踪）

# app/core/sentry.py
import sentry_sdk
sentry_sdk.init(
    dsn=settings.SENTRY_DSN,
    traces_sample_rate=0.1,
    environment=settings.ENVIRONMENT,
)
```

### 7.4 数据库自动备份

`scripts/backup-postgres.sh`：

```bash
#!/bin/bash
set -e
BACKUP_DIR=/opt/cloudlink/backups/postgres
mkdir -p $BACKUP_DIR

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
docker compose exec -T postgres pg_dump -U cloudlink cloudlink \
  | gzip > $BACKUP_DIR/cloudlink-$TIMESTAMP.sql.gz

# 保留最近 30 天
find $BACKUP_DIR -name "*.sql.gz" -mtime +30 -delete

# 上传到 OSS（可选）
# aliyun oss cp $BACKUP_DIR/cloudlink-$TIMESTAMP.sql.gz \
#   oss://your-bucket/backups/postgres/
```

crontab：

```bash
0 2 * * * /opt/cloudlink/scripts/backup-postgres.sh >> /opt/cloudlink/logs/backup.log 2>&1
```

### 7.5 密钥轮换

```bash
# 定期（如每季度）轮换：
# 1. JWT_SECRET
docker compose exec backend python -c "
import secrets; print(secrets.token_urlsafe(32))
"
# 更新 .env.production，重启服务，强制所有用户重新登录

# 2. FERNET_KEY（注意：轮换需重新加密所有 HA token）
```

### 7.6 监控告警

简单方案：

```bash
# health-check.sh（每 5 分钟跑）
#!/bin/bash
HEALTH=$(curl -fsS https://api.your-domain.com/health || echo "FAIL")
if [ "$HEALTH" = "FAIL" ]; then
    echo "ALERT: API down at $(date)" | mail -s "CloudLink Alert" you@example.com
fi
```

高级方案：Prometheus + Grafana + Alertmanager（超出本项目范围）。

### 7.7 文档完善

- [x] README.md
- [x] docs/PLAN.md
- [x] docs/ARCHITECTURE.md
- [x] docs/phases/*.md
- [ ] docs/api/api-reference.md（从 FastAPI 自动生成）
- [ ] docs/OPERATIONS.md（运维手册）

### 7.8 上线检查清单（最终）

部署到生产前最后一遍确认：

- [ ] `.env.production` 已配置，所有密钥已设置
- [ ] SSL 证书已申请并配置
- [ ] 数据库迁移已跑
- [ ] UFW 仅 22/80/443
- [ ] fail2ban 运行
- [ ] 自动备份 cron 配置
- [ ] SSL 自动续期 cron 配置
- [ ] Docker Compose restart=unless-stopped 已设置
- [ ] 服务域名 A 记录正确
- [ ] HA 端能成功连接云
- [ ] Android APK 至少能在 2 台手机正常运行

## 🚀 上线步骤

```bash
# 1. 合并所有 feat 分支到 main
git checkout main
git merge feat/phase-1-server-init
git merge feat/phase-2-backend
git merge feat/phase-3-deploy
git merge feat/phase-4-ha-plugin
git merge feat/phase-5-android
git merge feat/phase-6-integration
git tag v0.1.0  # 第一个正式版本

# 2. 推到 GitHub
git push origin main --tags

# 3. 在服务器上拉取
ssh deploy@<服务器IP>
cd /opt/cloudlink
git pull origin main
cd backend
docker compose --env-file .env.production up -d --build

# 4. 验证
curl https://api.your-domain.com/health
# 应返回 {"status": "ok"}

# 5. 通知 Android 测试人员安装 APK
```

## 📈 后续优化方向

- **短期**：增加 HA Token 自动刷新、UI 暗色模式、Push 通知
- **中期**：多用户协作、设备分组、场景模式、定时任务
- **长期**：插件市场、第三方集成、HomeKit 桥接、跨 HA 实例同步

## ✅ 项目完成！

到这里整套系统已经上线并稳定运行。后续根据使用反馈迭代即可。
