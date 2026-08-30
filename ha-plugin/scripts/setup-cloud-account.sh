#!/bin/bash
# CloudLink HA Plugin 一键配置助手
# 帮你在云端注册账号 + 注册 HA 实例，拿到 cloud_token

set -e

CLOUD_URL="${1:-http://118.31.225.109:8000}"
HA_TOKEN="${2:-}"
EMAIL="${3:-me@example.com}"
PASSWORD="${4:-changeme123456}"
NAME="${5:-我的家}"

echo "==========================================="
echo "CloudLink 云端配置助手"
echo "==========================================="
echo "云端 URL: $CLOUD_URL"
echo "HA Token: ${HA_TOKEN:0:20}..."
echo "邮箱:     $EMAIL"
echo "实例名:   $NAME"
echo ""

if [ -z "$HA_TOKEN" ]; then
    echo "❌ 错误: 请提供 HA Long-Lived Token 作为第 2 个参数"
    echo "   获取方式: HA → 用户资料 → 长期访问令牌 → 创建令牌"
    echo ""
    echo "用法: $0 <cloud_url> <ha_token> [email] [password] [name]"
    exit 1
fi

echo "[1/3] 注册云端账号..."
REGISTER=$(curl -sS -X POST "$CLOUD_URL/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" 2>&1)

if echo "$REGISTER" | grep -q "access_token"; then
    echo "    ✓ 注册成功"
    TOKEN=$(echo "$REGISTER" | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])")
elif echo "$REGISTER" | grep -q "409"; then
    echo "    ⚠ 邮箱已注册，改为登录"
    TOKEN=$(curl -sS -X POST "$CLOUD_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
        | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])")
else
    echo "    ❌ 注册失败: $REGISTER"
    exit 1
fi

echo ""
echo "[2/3] 注册 HA 实例..."
HA_REGISTER=$(curl -sS -X POST "$CLOUD_URL/api/ha/register" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"name\":\"$NAME\",\"ha_token\":\"$HA_TOKEN\"}")

CLOUD_TOKEN=$(echo "$HA_REGISTER" | python3 -c "import sys, json; print(json.load(sys.stdin)['cloud_token'])")
echo "    ✓ HA 实例已注册"
echo "    cloud_token = $CLOUD_TOKEN"

echo ""
echo "[3/3] 完成"
echo "==========================================="
echo "✅ 配置完成！"
echo "==========================================="
echo ""
echo "下一步："
echo "1. 在 HA 里添加 CloudLink 集成"
echo "2. 填入:"
echo "   Cloud server URL: $CLOUD_URL"
echo "   Cloud token:      $CLOUD_TOKEN"
echo ""
echo "或者保存到文件供以后参考:"
echo "$CLOUD_TOKEN" > ~/.cloudlink_cloud_token
echo "  → 已保存到 ~/.cloudlink_cloud_token"
