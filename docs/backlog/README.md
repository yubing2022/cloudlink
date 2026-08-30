# 遗留任务 / Backlog

> 暂不做但后续要补的事项

## 📋 列表

### 1. 域名 + HTTPS 配置
**状态**：待办  
**优先级**：高  
**触发条件**：Phase 3 部署完成、跑通 IP 访问后  
**任务**：
- [ ] 注册/配置域名
- [ ] 域名 A 记录指向 `118.31.225.109`
- [ ] 申请 Let's Encrypt SSL 证书（certbot）
- [ ] 配置 Nginx HTTPS (443)
- [ ] HTTP → HTTPS 301 重定向
- [ ] 更新 Android 客户端 BASE_URL
- [ ] 更新 HA 插件 cloud_url

**关联文件**：
- `docs/phases/phase-3-deploy.md`
- `infra/nginx/conf.d/default.conf`

---

### 2. SSH 安全组关闭（推荐）
**状态**：可选  
**优先级**：中  
**触发条件**：workbench 工具完全替代 SSH 时  
**任务**：
- [ ] 阿里云 ECS 安全组删除 22 端口规则
- [ ] 完全 SSH-less 管理

---

### 3. 监控告警
**状态**：待办  
**优先级**：中  
**触发条件**：Phase 7 生产化  
**任务**：
- [ ] Prometheus + Grafana
- [ ] Alertmanager
- [ ] 健康检查脚本
- [ ] 邮件/钉钉告警

---

### 4. 备份策略
**状态**：待办  
**优先级**：中  
**触发条件**：Phase 7 生产化  
**任务**：
- [ ] PostgreSQL 每日自动备份
- [ ] 上传到阿里云 OSS
- [ ] 备份保留策略（30 天）

---

### 5. 密钥轮换计划
**状态**：待办  
**优先级**：低  
**触发条件**：运行 >3 个月后  
**任务**：
- [ ] JWT_SECRET 季度轮换
- [ ] FERNET_KEY 轮换 + 重新加密 HA token
