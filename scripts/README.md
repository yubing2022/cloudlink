# scripts/

部署与运维相关脚本

## 文件说明

| 脚本 | 用途 | 使用时机 |
|---|---|---|
| `init-server.sh` | 一键初始化阿里云 ECS | Phase 1 |
| `deploy.sh` | **后端一键部署**（workbench → ECS）：语法检查 → pytest → 上传 → `docker cp` → 清 `__pycache__` → restart → 等 health | 每次改完 backend |
| `backup-postgres.sh` | PostgreSQL 自动备份 | Phase 7 |
| `renew-ssl.sh` | Let's Encrypt 证书续期 | Phase 3 |
| `diagnose-codex.sh` | Codex shell 故障诊断 | 出问题时 |

## deploy.sh 速查

```bash
scripts/deploy.sh --status      # 看线上容器/health/待部署文件，不做改动
scripts/deploy.sh --dry-run     # 只看计划
scripts/deploy.sh               # 增量部署（相对服务器记录的已部署 commit）
scripts/deploy.sh --all         # 全量
scripts/deploy.sh --full        # 改了 compose/Dockerfile/.env 时重建镜像
scripts/deploy.sh --migrate     # 顺带 alembic upgrade head
scripts/deploy.sh --no-test     # 跳过 pytest
```

失败会打 `docker logs --tail 60` 并以 1 退出；脚本可重复执行。详见 `docs/BACKEND_DEPLOYMENT.md` §2.0。

## 通用约定

- 所有脚本默认 deploy 用户运行，需要 root 时内部 sudo
- 日志统一输出到 `/opt/cloudlink/logs/`
- 错误退出码 1，成功 0
- 使用 `set -e`（遇到错误立即退出）
