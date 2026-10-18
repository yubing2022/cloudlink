#!/usr/bin/env bash
#
# CloudLink 后端一键部署
#
# 用 workbench CLI 走 OSS 中转把 backend/ 下的文件送到 ECS，覆盖进运行中的容器，
# 清 __pycache__，重启，然后自己等 health 200。失败会直接打容器日志并退出非 0。
#
# 用法：
#   scripts/deploy.sh                              # 部署「上次部署之后」backend/ 的所有改动
#   scripts/deploy.sh backend/app/api/devices.py   # 只部署指定文件
#   scripts/deploy.sh --all                        # 部署 backend/app + backend/alembic 全部文件
#   scripts/deploy.sh --changed origin/main        # 指定比较基线
#   scripts/deploy.sh --full                       # 连 docker-compose.yml / Dockerfile / .env.production
#                                                  # 一起上传，并重建镜像（改配置才需要）
#   scripts/deploy.sh --migrate                    # 部署后跑 alembic upgrade head
#   scripts/deploy.sh --status                     # 只看线上状态，不做任何改动
#   scripts/deploy.sh --dry-run ...                # 只打印计划
#
# 依赖：workbench（已配置 profile，daemon 在跑）、git、curl
#
set -euo pipefail

# ---------------------------------------------------------------- 配置

INSTANCE_ID="${CLOUDLINK_INSTANCE_ID:-i-bp17ymd3baklc58tzbth}"
PUBLIC_URL="${CLOUDLINK_PUBLIC_URL:-http://118.31.225.109:8000}"
REMOTE_ROOT="/opt/cloudlink/backend"          # compose 工作目录，代码副本在这
CONTAINER="cloudlink-backend"
CONTAINER_APP="/app"                          # 容器内 app 根
MARKER="/opt/cloudlink/.deployed_commit"      # 记录上次部署的 commit
REMOTE_SCRIPT="/opt/cloudlink/.deploy_run.sh"
HEALTH_TRIES=30                               # 30 × 2s = 60s

TIMEOUT=300
DRY_RUN=0
MODE=""            # "", changed, all
REF=""
FULL=0
MIGRATE=0
RUN_TESTS=1
STATUS_ONLY=0

FILES=()

if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_DIM=""; C_OFF=""
fi

say()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$C_DIM" "$C_OFF" "$*"; }
ok()   { printf '%s ok %s %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%s!!%s %s\n' "$C_YEL" "$C_OFF" "$*" >&2; }
die()  { printf '%serror:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

usage() {
  sed -n '3,/^set -euo/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------- 参数

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)    usage; exit 0 ;;
    -a|--all)     MODE="all" ;;
    -c|--changed) MODE="changed"
                  if [ $# -ge 2 ] && [ "${2#-}" = "$2" ]; then REF="$2"; shift; fi ;;
    -f|--full)    FULL=1 ;;
    -m|--migrate) MIGRATE=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    --no-test)    RUN_TESTS=0 ;;
    --status)     STATUS_ONLY=1 ;;
    --timeout)    [ $# -ge 2 ] || die "--timeout 需要参数"; TIMEOUT="$2"; shift ;;
    --)           shift; while [ $# -gt 0 ]; do FILES+=("$1"); shift; done; break ;;
    -*)           die "未知参数 $1（--help 看用法）" ;;
    *)            FILES+=("$1") ;;
  esac
  shift
done

# ---------------------------------------------------------------- 定位仓库

cd "$(dirname "$0")/.." || die "进不去仓库根目录"
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die "当前目录不在 git 仓库里"
cd "$ROOT"

# ---------------------------------------------------------------- 工具函数

wb_exec() {  # wb_exec <command> [timeout]  —— 输出直接流到终端，退出码透传
  local cmd="$1" tmo="${2:-$TIMEOUT}"
  workbench exec --instance-id "$INSTANCE_ID" --command "$cmd" --timeout "$tmo"
}

wb_capture() {  # wb_capture <command> [timeout] —— 捕获输出
  local cmd="$1" tmo="${2:-$TIMEOUT}"
  workbench exec --instance-id "$INSTANCE_ID" --command "$cmd" --timeout "$tmo" 2>&1
}

add_file() {  # 去重后加入 FILES
  local f="$1" e
  [ -f "$f" ] || return 0
  for e in "${FILES[@]:-}"; do
    if [ "$e" = "$f" ]; then return 0; fi
  done
  FILES+=("$f")
}

is_deployable() {
  case "$1" in
    # 备份/临时/日志类文件永远不部署（devices.py.bak 就躺在这个目录里）
    *.bak|*.orig|*.rej|*.pyc|*.log|*.swp|*.tmp)  return 1 ;;
    */__pycache__/*|*/.DS_Store|.DS_Store)       return 1 ;;
    backend/app/*|backend/alembic/*)             return 0 ;;
    backend/alembic.ini)                         return 0 ;;
    *)                                           return 1 ;;
  esac
}

rel_to_backend() {  # backend/app/api/x.py -> app/api/x.py
  printf '%s' "${1#backend/}"
}

normalize_path() {  # 允许传绝对路径 / 带 ./ 的相对路径
  local p="$1"
  p="${p#./}"
  if [ "${p#$ROOT/}" != "$p" ]; then p="${p#$ROOT/}"; fi
  printf '%s' "$p"
}

remote_marker() {
  local m
  m="$(wb_capture "cat $MARKER 2>/dev/null || true" 60 | tr -d '\r' | tr -d '[:space:]')"
  case "$m" in
    ""|*[!0-9a-f]*) printf '' ;;
    *)              printf '%s' "$m" ;;
  esac
}

# 找可用的 pytest。注意 `pytest -m xxx` 里的 -m 是 marker 过滤，不是 -m module，
# 所以解释器和 pytest 命令行必须分开调用。
# 找到后设置 PYTEST_BIN + PYTEST_MODE（module=解释器走 -m pytest，direct=直接跑 pytest）
detect_pytest() {
  local cand base mode
  for cand in "${PYTEST:-}" python3.14 python3 python pytest; do
    [ -n "$cand" ] || continue
    command -v "$cand" >/dev/null 2>&1 || continue
    base="$(basename "$cand")"
    case "$base" in
      python*) mode="module" ;;
      *)       mode="direct" ;;
    esac
    if [ "$mode" = "module" ]; then
      "$cand" -m pytest --version >/dev/null 2>&1 || continue
    else
      "$cand" --version >/dev/null 2>&1 || continue
    fi
    PYTEST_BIN="$cand"; PYTEST_MODE="$mode"; return 0
  done
  for cand in /Library/Frameworks/Python.framework/Versions/*/bin/python3; do
    [ -x "$cand" ] || continue
    "$cand" -m pytest --version >/dev/null 2>&1 || continue
    PYTEST_BIN="$cand"; PYTEST_MODE="module"; return 0
  done
  return 1
}

# ---------------------------------------------------------------- --status

print_status() {
  info "实例 $INSTANCE_ID"
  say ""
  say "容器："
  wb_capture "docker ps --filter name=cloudlink --format '  {{.Names}}\t{{.Status}}'" 60
  say ""
  say "后端 health（容器内）："
  wb_capture "curl -s -m 5 -o /dev/null -w '  HTTP %{http_code}\n' http://localhost:8000/health" 60
  say "后端 health（公网）："
  curl -s -m 8 -o /dev/null -w "  HTTP %{http_code}\n" "$PUBLIC_URL/health" || say "  连不上"
  say ""
  local m; m="$(remote_marker)"
  say "已部署 commit：${m:-（未记录）}"
  say "本地 HEAD：    $(git rev-parse --short HEAD 2>/dev/null || echo '?')"
  if [ -n "$m" ]; then
    say ""
    say "待部署（backend/）："
    local pending
    pending="$(git diff --name-only "$m" -- backend/ 2>/dev/null || true)"
    if [ -z "$pending" ]; then say "  （无）"; else printf '%s\n' "$pending" | sed 's/^/  /'; fi
  fi
}

if [ "$STATUS_ONLY" = 1 ]; then
  print_status
  exit 0
fi

# ---------------------------------------------------------------- 收集文件

if [ ${#FILES[@]} -gt 0 ] && { [ "$MODE" = "all" ] || [ "$MODE" = "changed" ]; }; then
  die "不能同时给文件列表和 --all / --changed"
fi

if [ ${#FILES[@]} -gt 0 ]; then
  TMP=("${FILES[@]}"); FILES=()
  for f in "${TMP[@]}"; do
    p="$(normalize_path "$f")"
    [ -f "$p" ] || die "文件不存在：$f"
    is_deployable "$p" || die "只部署 backend/app、backend/alembic、backend/alembic.ini 下的文件：$f"
    add_file "$p"
  done
else
  [ -n "$MODE" ] || MODE="changed"
  if [ "$MODE" = "all" ]; then
    while IFS= read -r p; do
      if [ -n "$p" ] && is_deployable "$p"; then add_file "$p"; fi
    done <<EOF
$(git ls-files backend/ ; git ls-files --others --exclude-standard backend/)
EOF
  else
    if [ -z "$REF" ]; then
      REF="$(remote_marker)"
      if [ -z "$REF" ]; then
        die "服务器上没有部署记录（${MARKER}），没法自动判断改动。请显式给基线：scripts/deploy.sh --changed origin/main，或 --all 全量"
      fi
      info "比较基线：${REF}（服务器记录的已部署 commit）"
    fi
    git rev-parse --verify "$REF^{commit}" >/dev/null 2>&1 || die "基线不是有效的 commit：$REF"
    while IFS= read -r p; do
      if [ -n "$p" ] && is_deployable "$p"; then add_file "$p"; fi
    done <<EOF
$(git diff --name-only "$REF" -- backend/ ; git ls-files --others --exclude-standard -- backend/)
EOF
  fi
fi

if [ "$FULL" = 1 ]; then
  add_file "backend/docker-compose.yml"
  add_file "backend/Dockerfile"
  if [ -f "backend/.env.production" ]; then
    add_file "backend/.env.production"
  else
    warn "本地没有 backend/.env.production，不会上传环境变量（服务器上的保持不变）"
  fi
fi

if [ ${#FILES[@]} -eq 0 ]; then
  ok "没有需要部署的文件"
  if [ "$MIGRATE" = 0 ]; then exit 0; fi
  info "仍然执行迁移（--migrate）"
fi

# ---------------------------------------------------------------- 预检

info "预检"
command -v workbench >/dev/null 2>&1 || die "找不到 workbench CLI"
PYBIN="$(command -v python3 || true)"
[ -n "$PYBIN" ] || die "找不到 python3"

if [ "$FULL" = 1 ]; then
  MODE_DESC="重建镜像（docker compose build + up -d）"
else
  MODE_DESC="docker cp + 清 __pycache__ + restart"
fi
say "  模式：$MODE_DESC"

PY_FILES=()
for f in "${FILES[@]}"; do
  case "$f" in *.py) PY_FILES+=("$f") ;; esac
done
if [ ${#PY_FILES[@]} -gt 0 ]; then
  cat > /tmp/.cloudlink_ast_check.py <<'PY'
import ast, sys
bad = 0
for p in sys.argv[1:]:
    try:
        ast.parse(open(p, encoding="utf-8").read(), p)
    except SyntaxError as e:
        bad += 1
        print("  %s:%s: %s" % (p, e.lineno, e.msg), file=sys.stderr)
sys.exit(1 if bad else 0)
PY
  "$PYBIN" /tmp/.cloudlink_ast_check.py "${PY_FILES[@]}" || die "Python 语法检查失败，已中止（没有上传任何文件）"
  ok "语法检查通过（${#PY_FILES[@]} 个 .py）"
fi

if [ "$RUN_TESTS" = 1 ] && [ -d backend/tests ]; then
  if detect_pytest; then
    if [ "$PYTEST_MODE" = "module" ]; then PYTEST_DESC="$PYTEST_BIN -m pytest"; else PYTEST_DESC="$PYTEST_BIN"; fi
    info "跑测试（${PYTEST_DESC}，跳过 tests/integration）"
    if [ "$PYTEST_MODE" = "module" ]; then
      if ! ( cd backend && "$PYTEST_BIN" -m pytest tests/ -q --ignore=tests/integration 2>&1 | tail -15 ); then
        die "测试失败，已中止（没有上传任何文件；确认要强推就加 --no-test）"
      fi
    else
      if ! ( cd backend && "$PYTEST_BIN" tests/ -q --ignore=tests/integration 2>&1 | tail -15 ); then
        die "测试失败，已中止（没有上传任何文件；确认要强推就加 --no-test）"
      fi
    fi
    ok "测试通过"
  else
    warn "本机找不到能跑 pytest 的解释器，跳过测试（想静默就加 --no-test）"
  fi
fi

# ---------------------------------------------------------------- 计划

say ""
info "待部署 ${#FILES[@]} 个文件 → $REMOTE_ROOT"
for f in "${FILES[@]}"; do
  printf '  %-45s %6s  ->  %s/%s\n' "$f" "$(du -h "$f" | cut -f1 | tr -d ' ')" "$REMOTE_ROOT" "$(rel_to_backend "$f")"
done
if [ "$MIGRATE" = 1 ]; then say "  另外：alembic upgrade head"; fi
say ""

if [ "$DRY_RUN" = 1 ]; then
  ok "dry-run，什么都没做"
  exit 0
fi

# ---------------------------------------------------------------- 上传

info "上传到 ECS"
for f in "${FILES[@]}"; do
  rel="$(rel_to_backend "$f")"
  if ! workbench upload "$f" "$REMOTE_ROOT/$rel" --instance-id "$INSTANCE_ID" --force >/tmp/.wb_upload.log 2>&1; then
    cat /tmp/.wb_upload.log >&2
    die "上传失败：$f"
  fi
  printf '  %s\n' "$rel"
done
ok "上传完成"

# ---------------------------------------------------------------- 远端执行

{
  echo '#!/bin/bash'
  echo 'set -e'
  if [ "$FULL" = 1 ]; then
    cat <<'EOS'
echo "== docker compose build =="
cd /opt/cloudlink/backend
docker compose --env-file .env.production build backend
echo "== docker compose up -d =="
docker compose --env-file .env.production up -d
EOS
  else
    echo 'echo "== docker cp =="'
    for f in "${FILES[@]}"; do
      rel="$(rel_to_backend "$f")"
      echo "docker cp '$REMOTE_ROOT/$rel' '$CONTAINER:$CONTAINER_APP/$rel'"
    done
    cat <<'EOS'
echo "== clear __pycache__ =="
docker exec cloudlink-backend find /app -name __pycache__ -type d -prune -exec rm -rf {} +
echo "== restart =="
docker restart cloudlink-backend
EOS
  fi
  cat <<EOS
echo "== wait health =="
code=
for i in \$(seq 1 $HEALTH_TRIES); do
  code=\$(curl -s -m 3 -o /dev/null -w "%{http_code}" http://localhost:8000/health || true)
  if [ "\$code" = "200" ]; then echo "HEALTH_OK tries=\$i"; exit 0; fi
  sleep 2
done
echo "HEALTH_FAIL last_code=\${code:-none}"
echo "---- docker logs (tail 40) ----"
docker logs --tail 60 cloudlink-backend 2>&1 | tail -40
exit 1
EOS
} > /tmp/.cloudlink_deploy_remote.sh

info "在 ECS 上执行（超时 ${TIMEOUT}s）"
workbench upload /tmp/.cloudlink_deploy_remote.sh "$REMOTE_SCRIPT" --instance-id "$INSTANCE_ID" --force >/dev/null 2>&1 \
  || die "上传部署脚本失败"

if ! wb_exec "bash $REMOTE_SCRIPT" "$TIMEOUT"; then
  die "远端部署失败，看上面的日志。修好后重跑本脚本即可（可重复执行）"
fi
ok "容器已重启，容器内 health 200"

# ---------------------------------------------------------------- 迁移

if [ "$MIGRATE" = 1 ]; then
  info "alembic upgrade head"
  if ! wb_exec "cd /opt/cloudlink/backend && docker compose --env-file .env.production exec -T backend alembic upgrade head" 300; then
    die "迁移失败（服务本身已起来，迁移需要单独处理）"
  fi
  ok "迁移完成"
fi

# ---------------------------------------------------------------- 公网校验

info "公网校验 $PUBLIC_URL/health"
pub=""
for i in 1 2 3 4 5; do
  pub="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$PUBLIC_URL/health" || true)"
  if [ "$pub" = "200" ]; then break; fi
  sleep 2
done
if [ "$pub" != "200" ]; then
  die "公网 health 不是 200（${pub}）——容器内是好的，检查 nginx / 安全组"
fi
ok "公网 health 200"

# ---------------------------------------------------------------- 记录基线

if [ -n "$(git status --porcelain -- backend/ 2>/dev/null | grep -v '\.bak$' || true)" ]; then
  warn "backend/ 有未提交的改动：本次部署的不是纯净 commit。部署记录仍写 HEAD，"
  warn "下次 --changed 不会再包含这些文件——要么先提交，要么继续用显式文件列表部署。"
fi
HEAD_SHA="$(git rev-parse HEAD)"
wb_capture "echo $HEAD_SHA > $MARKER" 60 >/dev/null || warn "写部署记录失败（下次需用 --changed <ref> 或 --all）"

say ""
ok "部署完成：${#FILES[@]} 个文件，HEAD=$(git rev-parse --short HEAD)，$(date '+%H:%M:%S')"
