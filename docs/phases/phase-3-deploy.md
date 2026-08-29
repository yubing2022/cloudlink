# Phase 3: 云端后端部署

> 预计耗时：0.5 天  
> 目标：FastAPI 服务通过 HTTPS 对外提供  
> 分支：`feat/phase-3-deploy`

## 🎯 完成标志

- [ ] `https://api.your-domain.com/docs` 可访问
- [ ] HTTP 自动跳转 HTTPS
- [ ] SSL 证书自动续期
- [ ] 后端服务开机自启（Docker Compose 自动重启）

## 📁 部署文件结构

```
backend/
├── Dockerfile
├── docker-compose.yml
├── .env.production
└── docker/
    ├── postgres/init.sql
    └── nginx/
        ├── nginx.conf
        └── conf.d/
            └── default.conf
```

## 🛠️ 实施步骤

### 步骤 3.1：写 Dockerfile

`backend/Dockerfile`：

```dockerfile
FROM python:3.11-slim

WORKDIR /app

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# Python 依赖
COPY pyproject.toml ./
RUN pip install --no-cache-dir uv && \
    uv pip install --system --no-cache .

# 应用代码
COPY app ./app
COPY alembic ./alembic
COPY alembic.ini ./

# 非 root 运行
RUN useradd -m -u 1000 appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

### 步骤 3.2：写 docker-compose.yml

`backend/docker-compose.yml`：

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  backend:
    build: .
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      DATABASE_URL: postgresql+asyncpg://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
      REDIS_URL: redis://redis:6379/0
      JWT_SECRET: ${JWT_SECRET}
      FERNET_KEY: ${FERNET_KEY}
      ENVIRONMENT: production
    expose:
      - "8000"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  nginx:
    image: nginx:1.27-alpine
    restart: unless-stopped
    depends_on:
      - backend
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./docker/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./docker/nginx/conf.d:/etc/nginx/conf.d:ro
      - ./ssl:/etc/nginx/ssl:ro
      - ./logs/nginx:/var/log/nginx

volumes:
  postgres_data:
  redis_data:
```

### 步骤 3.3：Nginx 配置

`backend/docker/nginx/conf.d/default.conf`：

```nginx
upstream backend {
    server backend:8000;
}

# HTTP -> HTTPS 重定向
server {
    listen 80;
    server_name api.your-domain.com;
    return 301 https://$server_name$request_uri;
}

# HTTPS
server {
    listen 443 ssl http2;
    server_name api.your-domain.com;

    ssl_certificate     /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # 安全头
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # 日志
    access_log /var/log/nginx/access.log;
    error_log  /var/log/nginx/error.log;

    # API
    location /api/ {
        proxy_pass http://backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket
    location /ws/ {
        proxy_pass http://backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400;  # 长连接
    }

    # Swagger
    location /docs {
        proxy_pass http://backend/docs;
    }
    location /openapi.json {
        proxy_pass http://backend/openapi.json;
    }

    # 健康检查
    location /health {
        proxy_pass http://backend/health;
    }
}
```

### 步骤 3.4：环境变量

`backend/.env.production`（**不**提交到 git）：

```env
POSTGRES_DB=cloudlink
POSTGRES_USER=cloudlink
POSTGRES_PASSWORD=<随机生成强密码>
JWT_SECRET=<随机生成，至少 32 字节>
FERNET_KEY=<上一阶段生成的>
CORS_ORIGINS=https://your-domain.com
```

`.env.example`（提交到 git）：

```env
POSTGRES_DB=cloudlink
POSTGRES_USER=cloudlink
POSTGRES_PASSWORD=changeme
JWT_SECRET=changeme-min-32-bytes-please
FERNET_KEY=changeme-fernet-key-here
CORS_ORIGINS=
```

### 步骤 3.5：申请 SSL 证书

```bash
# 在服务器上
sudo apt install certbot
sudo certbot certonly --standalone -d api.your-domain.com

# 证书位置
ls /etc/letsencrypt/live/api.your-domain.com/

# 复制到项目目录
sudo cp /etc/letsencrypt/live/api.your-domain.com/fullchain.pem /opt/cloudlink/backend/ssl/
sudo cp /etc/letsencrypt/live/api.your-domain.com/privkey.pem /opt/cloudlink/backend/ssl/
sudo chown -R deploy:deploy /opt/cloudlink/backend/ssl
```

### 步骤 3.6：首次部署

```bash
cd /opt/cloudlink/backend

# 构建镜像
docker compose build

# 启动
docker compose --env-file .env.production up -d

# 查看日志
docker compose logs -f backend

# 跑迁移（首次）
docker compose exec backend alembic upgrade head

# 健康检查
curl -k https://api.your-domain.com/health
# 应返回 {"status": "ok"}
```

### 步骤 3.7：自动续期 SSL

```bash
# 创建续期脚本
cat > /opt/cloudlink/scripts/renew-ssl.sh <<'EOF'
#!/bin/bash
certbot renew --quiet
cp /etc/letsencrypt/live/api.your-domain.com/fullchain.pem /opt/cloudlink/backend/ssl/
cp /etc/letsencrypt/live/api.your-domain.com/privkey.pem /opt/cloudlink/backend/ssl/
cd /opt/cloudlink/backend && docker compose exec nginx nginx -s reload
EOF
chmod +x /opt/cloudlink/scripts/renew-ssl.sh

# crontab 每月1号检查
crontab -e
# 添加：
0 3 1 * * /opt/cloudlink/scripts/renew-ssl.sh >> /opt/cloudlink/logs/ssl-renew.log 2>&1
```

## 🔧 部署脚本

`scripts/deploy.sh`：

```bash
#!/bin/bash
set -e

cd /opt/cloudlink/backend

echo ">>> 拉取最新代码"
git pull origin main

echo ">>> 重新构建镜像"
docker compose --env-file .env.production build

echo ">>> 重启服务"
docker compose --env-file .env.production up -d

echo ">>> 跑数据库迁移"
docker compose exec backend alembic upgrade head

echo ">>> 健康检查"
sleep 5
curl -f https://api.your-domain.com/health || exit 1

echo ">>> 部署成功 ✓"
```

## ✅ 验收

```bash
# 1. HTTPS 工作
curl -I https://api.your-domain.com/health

# 2. Swagger UI 可访问
open https://api.your-domain.com/docs

# 3. 注册账号测试
curl -X POST https://api.your-domain.com/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"prodtest@example.com","password":"testpass123"}'

# 4. 服务日志
docker compose logs backend --tail 50
```

## 📦 提交

```bash
git checkout -b feat/phase-3-deploy
git add backend/Dockerfile backend/docker-compose.yml backend/docker/
git commit -m "feat(deploy): add Docker Compose production setup"
git commit -m "feat(deploy): add Nginx config with HTTPS and WebSocket"
git commit -m "feat(deploy): add SSL renewal automation script"
```

## 🚀 下一步

Phase 3 完成后，进入 **Phase 4：HA 插件开发**。
