# scripts/

部署与运维相关脚本

## 文件说明

| 脚本 | 用途 | 使用时机 |
|---|---|---|
| `init-server.sh` | 一键初始化阿里云 ECS | Phase 1 |
| `deploy.sh` | 一键部署后端到服务器 | Phase 3+ |
| `backup-postgres.sh` | PostgreSQL 自动备份 | Phase 7 |
| `renew-ssl.sh` | Let's Encrypt 证书续期 | Phase 3 |
| `diagnose-codex.sh` | Codex shell 故障诊断 | 出问题时 |

## 通用约定

- 所有脚本默认 deploy 用户运行，需要 root 时内部 sudo
- 日志统一输出到 `/opt/cloudlink/logs/`
- 错误退出码 1，成功 0
- 使用 `set -e`（遇到错误立即退出）
