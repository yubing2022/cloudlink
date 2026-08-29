# CloudLink Backend

> FastAPI cloud service for Home Assistant device relay

## 🚀 Quick Start

### 本地开发

```bash
# 1. 起依赖服务
docker compose -f docker-compose.dev.yml up -d

# 2. 安装依赖
pip install -e .

# 4. 配置环境
cp .env.example .env.development
# 编辑 .env.development

# 5. 跑迁移
alembic upgrade head

# 6. 启动
uvicorn app.main:app --reload --port 8000
```

打开 http://localhost:8000/docs

### 生产部署

参见 [Phase 3 文档](../docs/phases/phase-3-deploy.md)

## 📁 目录结构

参见 [Phase 2 文档 §项目骨架](../docs/phases/phase-2-backend.md)

## 🧪 测试

```bash
pytest -v
pytest --cov=app --cov-report=html
```

## 🔧 常用命令

```bash
# 创建迁移
alembic revision --autogenerate -m "add new field"

# 应用迁移
alembic upgrade head

# 回滚一步
alembic downgrade -1

# 进入 Python REPL (已加载 app)
python -c "from app.main import app; import IPython; IPython.embed()"

# 查看日志
docker compose logs -f backend
```

## 📡 API

参见 [docs/api/api-reference.md](../docs/api/api-reference.md)（从 OpenAPI 自动生成）
