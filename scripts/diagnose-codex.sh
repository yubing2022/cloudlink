#!/usr/bin/env bash
# =============================================================================
# Codex Shell 执行能力一键诊断脚本
# 用途：定位 unified exec process 起不来的原因
# 性质：只读，不修改任何系统状态
# 报告：保存到 /tmp/codex-diagnostic-<时间戳>.txt
# =============================================================================

set +e  # 不因单条命令失败而中止REPORT_FILE="/tmp/codex-diagnostic-$(date +%Y%m%d-%H%M%S).txt"
HOSTNAME_SHORT="$(hostname -s)"
USER_NAME="$(whoami)"

# 颜色（兼容老终端）
if [ -t 1 ] && command -v tput >/dev/null 2>&1 && [ -n "$TERM" ] && [ "$TERM" != "dumb" ]; then
 BOLD="$(tput bold 2>/dev/null || echo '')"; RESET="$(tput sgr0 2>/dev/null || echo '')"
    RED="$(tput setaf 1 2>/dev/null || echo '')"; GREEN="$(tput setaf 2 2>/dev/null || echo '')"
    YELLOW="$(tput setaf 3 2>/dev/null || echo '')"; BLUE="$(tput setaf 4 2>/dev/null || echo '')"
    CYAN="$(tput setaf 6 2>/dev/null || echo '')"
else
    BOLD=""; RESET=""; RED=""; GREEN=""; YELLOW=""; BLUE=""; CYAN=""
fi

PASS="${GREEN}✓${RESET}"
FAIL="${RED}✗${RESET}"
WARN="${YELLOW}⚠${RESET}"
INFO="${CYAN}ℹ${RESET}"

section() {
    echo "" | tee -a "$REPORT_FILE"
    echo "${BOLD}${BLUE}═══════════════════════════════════════════════════════════════${RESET}" | tee -a "$REPORT_FILE"
    echo "${BOLD}${BLUE}  $1${RESET}" | tee -a "$REPORT_FILE"
    echo "${BOLD}${BLUE}═══════════════════════════════════════════════════════════════${RESET}" | tee -a "$REPORT_FILE"
}

check() {
    local desc="$1"
    shift
    if eval "$@" >/dev/null 2>&1; then
        echo " ${PASS} ${desc}" | tee -a "$REPORT_FILE"
        return 0
    else
        echo "  ${FAIL} ${desc}" | tee -a "$REPORT_FILE"
        return 1
    fi
}

# 同时输出到屏幕和文件
exec > >(tee -a "$REPORT_FILE") 2>&1

echo "${BOLD}Codex Shell 诊断报告${RESET}" | tee -a "$REPORT_FILE" >/dev/null
echo "主机: ${HOSTNAME_SHORT}    用户: ${USER_NAME}    时间: $(date)" | tee -a "$REPORT_FILE" >/dev/null
echo "报告文件: ${REPORT_FILE}" | tee -a "$REPORT_FILE" >/dev/null

# ─────────────────────────────────────────────────────────────────────────────
section "1/9 系统基本信息"
# ─────────────────────────────────────────────────────────────────────────────

echo "${INFO} macOS 版本:" | tee -a "$REPORT_FILE" >/dev/null
sw_vers | tee -a "$REPORT_FILE"

echo "${INFO} 系统架构:" | tee -a "$REPORT_FILE" >/dev/null
uname -m | tee -a "$REPORT_FILE"

echo "${INFO} 内核版本:" | tee -a "$REPORT_FILE" >/dev/null
uname -a | tee -a "$REPORT_FILE"

echo "${INFO} SIP 状态:" | tee -a "$REPORT_FILE" >/dev/null
csrutil status 2>&1 | tee -a "$REPORT_FILE"

echo "${INFO} Gatekeeper 状态:" | tee -a "$REPORT_FILE" >/dev/null
spctl --status 2>&1 | tee -a "$REPORT_FILE"

# ─────────────────────────────────────────────────────────────────────────────
section "2/9 Shell 环境"
# ─────────────────────────────────────────────────────────────────────────────

echo "${INFO} 当前 SHELL 环境变量: ${SHELL:-<未设置>}" | tee -a "$REPORT_FILE"
echo "${INFO} PATH: $PATH" | tee -a "$REPORT_FILE"

echo "" | tee -a "$REPORT_FILE"
echo "${INFO} 系统 Shell 二进制检查:" | tee -a "$REPORT_FILE" >/dev/null
for s in /bin/zsh /bin/bash /bin/sh /usr/bin/zsh /usr/bin/bash /usr/bin/env; do
    if [ -x "$s" ]; then
        echo "  ${PASS} ${s} (存在且可执行)" | tee -a "$REPORT_FILE"
    elif [ -e "$s" ]; then
        echo "  ${WARN} ${s} (存在但不可执行，权限: $(stat -f%Sp "$s" 2>/dev/null))" | tee -a "$REPORT_FILE"
    else
        echo "  ${FAIL} ${s} (不存在)" | tee -a "$REPORT_FILE"
    fi
done

echo "" | tee -a "$REPORT_FILE"
echo "${INFO} Shell 版本测试:" | tee -a "$REPORT_FILE" >/dev/null
for s in /bin/zsh /bin/bash /bin/sh; do
    if [ -x "$s" ]; then
        echo "  ${BOLD}$s --version:${RESET}" | tee -a "$REPORT_FILE"
        "$s" --version 2>&1 | head -2 | sed 's/^/    /' | tee -a "$REPORT_FILE"
    fi
done

echo "" | tee -a "$REPORT_FILE"
echo "${INFO} 实测能否启动一个简单子 Shell（这是 Codex exec 工具的关键路径）:" | tee -a "$REPORT_FILE" >/dev/null
TEST_OUT=$(/bin/zsh -c 'echo "shell-ok-$$"' 2>&1)
TEST_RC=$?
if [ "$TEST_RC" -eq 0 ] && [ "$TEST_OUT" = "shell-ok-$$" ]; then
    echo "  ${PASS} /bin/zsh -c 可用 (输出: $TEST_OUT)" | tee -a "$REPORT_FILE"
else
    echo "  ${FAIL} /bin/zsh -c 不可用 (RC=$TEST_RC, 输出: $TEST_OUT)" | tee -a "$REPORT_FILE"
fi

TEST_OUT=$(/bin/bash -c 'echo "shell-ok-$$"' 2>&1)
TEST_RC=$?
if [ "$TEST_RC" -eq 0 ] && [ "$TEST_OUT" = "shell-ok-$$" ]; then
    echo "  ${PASS} /bin/bash -c 可用" | tee -a "$REPORT_FILE"
else
    echo "  ${FAIL} /bin/bash -c 不可用 (RC=$TEST_RC)" | tee -a "$REPORT_FILE"
fi

# ─────────────────────────────────────────────────────────────────────────────
section "3/9 Codex桌面 App 安装情况"
# ─────────────────────────────────────────────────────────────────────────────

CODEX_APP=""
for path in "/Applications/Codex.app" "/Applications/Codex Desktop.app" "$HOME/Applications/Codex.app"; do
    if [ -d "$path" ]; then
        CODEX_APP="$path"
        break
    fi
done

if [ -z "$CODEX_APP" ]; then
    echo "  ${FAIL} 未在标准位置找到 Codex.app" | tee -a "$REPORT_FILE"
    echo "   搜索 Applications目录:" | tee -a "$REPORT_FILE"
    find /Applications -maxdepth 2 -iname "*codex*" -type d 2>/dev/null | sed 's/^/      /' | tee -a "$REPORT_FILE"
else
    echo "  ${PASS} Codex 安装位置: ${CODEX_APP}" | tee -a "$REPORT_FILE"
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} App 版本 (从 Info.plist):" | tee -a "$REPORT_FILE" >/dev/null
    /usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" "${CODEX_APP}/Contents/Info.plist" 2>/dev/null | sed 's/^/    版本: /' | tee -a "$REPORT_FILE"
    /usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "${CODEX_APP}/Contents/Info.plist" 2>/dev/null | sed 's/^/    Bundle ID: /' | tee -a "$REPORT_FILE"
    /usr/libexec/PlistBuddy -c "Print CFBundleExecutable" "${CODEX_APP}/Contents/Info.plist" 2>/dev/null | sed 's/^/    可执行文件: /' | tee -a "$REPORT_FILE"
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} 隔离属性 (quarantine):" | tee -a "$REPORT_FILE" >/dev/null
    QT=$(xattr -l "${CODEX_APP}" 2>/dev/null | grep -i quarantine)
    if [ -n "$QT" ]; then
        echo "  ${WARN} ${QT}" | tee -a "$REPORT_FILE"
        echo "    建议执行: xattr -dr com.apple.quarantine \"${CODEX_APP}\"" | tee -a "$REPORT_FILE"
    else
        echo "  ${PASS} 未发现 quarantine 属性" | tee -a "$REPORT_FILE"
    fi
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} 代码签名:" | tee -a "$REPORT_FILE" >/dev/null
    codesign -dv "${CODEX_APP}" 2>&1 | head -10 | sed 's/^/    /' | tee -a "$REPORT_FILE"
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} 签名验证:" | tee -a "$REPORT_FILE" >/dev/null
    codesign --verify --verbose=2 "${CODEX_APP}" 2>&1 | head -10 | sed 's/^/    /' | tee -a "$REPORT_FILE"
fi

# ─────────────────────────────────────────────────────────────────────────────
section "4/9 Codex 运行时目录完整性"
# ─────────────────────────────────────────────────────────────────────────────

RUNTIME_BASE="$HOME/.cache/codex-runtimes"
echo "${INFO} 运行时根目录: ${RUNTIME_BASE}" | tee -a "$REPORT_FILE"
if [ -d "$RUNTIME_BASE" ]; then
    echo "  ${PASS} 目录存在" | tee -a "$REPORT_FILE"
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} 目录结构:" | tee -a "$REPORT_FILE" >/dev/null
    find "$RUNTIME_BASE" -maxdepth 3 -type d 2>/dev/null | head -30 | sed 's/^/    /' | tee -a "$REPORT_FILE"
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} 关键文件检查:" | tee -a "$REPORT_FILE" >/dev/null
    # 关键可执行文件
    for f in \
        "$RUNTIME_BASE/codex-primary-runtime/dependencies/bin/fallback/git" \
        "$RUNTIME_BASE/codex-primary-runtime/dependencies/python/bin/python3" \
        "$RUNTIME_BASE/codex-primary-runtime/dependencies/node/bin/node"
    do
        if [ -x "$f" ]; then
            echo "  ${PASS} ${f#$RUNTIME_BASE/}" | tee -a "$REPORT_FILE"
        else
            echo "  ${FAIL} ${f#$RUNTIME_BASE/} (缺失或不可执行)" | tee -a "$REPORT_FILE"
        fi
    done
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} unified exec 相关文件:" | tee -a "$REPORT_FILE" >/dev/null
    find "$RUNTIME_BASE" -iname "*exec*" -o -iname "*sandbox*" -o -iname "*unified*" 2>/dev/null | head -20 | sed 's/^/    /' | tee -a "$REPORT_FILE"
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} 总占用空间:" | tee -a "$REPORT_FILE" >/dev/null
    du -sh "$RUNTIME_BASE" 2>/dev/null | sed 's/^/    /' | tee -a "$REPORT_FILE"
else
    echo "  ${FAIL} 目录不存在！Codex 可能尚未下载运行时" | tee -a "$REPORT_FILE"
fi

# ─────────────────────────────────────────────────────────────────────────────
section "5/9 Codex 相关进程"
# ─────────────────────────────────────────────────────────────────────────────

echo "${INFO} 当前 Codex 相关进程:" | tee -a "$REPORT_FILE" >/dev/null
ps aux | grep -i -E "codex|codex-primary" | grep -v grep | head -20 | sed 's/^/  /' | tee -a "$REPORT_FILE"

CODEX_PROC_COUNT=$(ps aux | grep -i codex | grep -v grep | wc -l | tr -d ' ')
echo "" | tee -a "$REPORT_FILE"
if [ "$CODEX_PROC_COUNT" -gt 0 ]; then
    echo "  ${INFO} 共 ${CODEX_PROC_COUNT} 个 Codex 相关进程在运行" | tee -a "$REPORT_FILE"
else
    echo "  ${WARN} 当前没有 Codex 进程在运行（Codex 可能未启动）" | tee -a "$REPORT_FILE"
fi

# ─────────────────────────────────────────────────────────────────────────────
section "6/9 Codex 日志（最近错误）"
# ─────────────────────────────────────────────────────────────────────────────

LOG_DIR="$HOME/Library/Logs"
if [ -d "$LOG_DIR" ]; then
    echo "${INFO} Codex 相关日志文件:" | tee -a "$REPORT_FILE" >/dev/null
    find "$LOG_DIR" -iname "*codex*" -type f 2>/dev/null | head -10 | sed 's/^/    /' | tee -a "$REPORT_FILE"
    
    echo "" | tee -a "$REPORT_FILE"
    echo "${INFO} 最近 20 行日志（最常见文件）:" | tee -a "$REPORT_FILE" >/dev/null
    LATEST_LOG=$(find "$LOG_DIR" -iname "*codex*" -type f -mtime -7 2>/dev/null | head -1)
    if [ -n "$LATEST_LOG" ]; then
        echo "    文件: $LATEST_LOG" | tee -a "$REPORT_FILE"
        tail -20 "$LATEST_LOG" 2>/dev/null | sed 's/^/    /' | tee -a "$REPORT_FILE"
    else
        echo "    未找到 7 天内的 Codex 日志" | tee -a "$REPORT_FILE"
    fi
else
    echo "  ${WARN} ~/Library/Logs 不存在" | tee -a "$REPORT_FILE"
fi

# ─────────────────────────────────────────────────────────────────────────────
section "7/9 macOS 权限（Codex 相关）"
# ─────────────────────────────────────────────────────────────────────────────

echo "${INFO} 自动化/辅助功能权限 (TCC.db):" | tee -a "$REPORT_FILE" >/dev/null
TCC_DB="$HOME/Library/Application Support/com.apple.TCC/TCC.db"
if [ -f "$TCC_DB" ]; then
    sqlite3 "$TCC_DB" "SELECT client, service, auth_value FROM access WHERE client LIKE '%codex%' OR client LIKE '%Codex%';" 2>/dev/null | sed 's/^/    /' | tee -a "$REPORT_FILE" || echo "    (无法查询 TCC.db，可能需要完全磁盘访问权限)" | tee -a "$REPORT_FILE"
else
    echo " ${WARN} TCC.db 不存在或不可访问" | tee -a "$REPORT_FILE"
fi

echo "" | tee -a "$REPORT_FILE"
echo "${INFO} 屏幕录制/输入监控权限（Codex 桌面交互可能用到）:" | tee -a "$REPORT_FILE" >/dev/null

# ─────────────────────────────────────────────────────────────────────────────
section "8/9 网络与沙箱连通性"
# ─────────────────────────────────────────────────────────────────────────────

echo "${INFO} Codex 可能访问的域名解析测试:" | tee -a "$REPORT_FILE" >/dev/null
for host in api.openai.com api.codex.openai.com github.com aliyun.com; do
    if command -v nslookup >/dev/null 2>&1; then
        RES=$(nslookup "$host" 2>&1 | grep -E "^Address:" | tail -1)
        if [ -n "$RES" ]; then
            echo "  ${PASS} ${host} → ${RES}" | tee -a "$REPORT_FILE"
        else
            echo "  ${FAIL} ${host} 解析失败" | tee -a "$REPORT_FILE"
        fi
    fi
done

echo "" | tee -a "$REPORT_FILE"
echo "${INFO} 基本 HTTPS 连通性:" | tee -a "$REPORT_FILE" >/dev/null
if command -v curl >/dev/null 2>&1; then
    HTTP_CODE=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 5 https://api.openai.com 2>&1)
    if [ "$HTTP_CODE" -ge200 ] && [ "$HTTP_CODE" -lt 500 ]; then
        echo "  ${PASS} api.openai.com → HTTP ${HTTP_CODE}" | tee -a "$REPORT_FILE"
    else
        echo "  ${FAIL} api.openai.com → ${HTTP_CODE}" | tee -a "$REPORT_FILE"
    fi
fi

# ─────────────────────────────────────────────────────────────────────────────
section "9/9 总结与建议"
# ─────────────────────────────────────────────────────────────────────────────

echo "${BOLD}报告已保存到:${RESET}" | tee -a "$REPORT_FILE"
echo "  ${REPORT_FILE}" | tee -a "$REPORT_FILE"
echo "" | tee -a "$REPORT_FILE"

echo "${BOLD}请把上面的报告（特别是 FAIL 和 WARN 项）截图或复制给我。${RESET}" | tee -a "$REPORT_FILE"
echo "" | tee -a "$REPORT_FILE"

echo "${BOLD}常见修复路径:${RESET}" | tee -a "$REPORT_FILE"
echo "  1. 重启 Codex（Cmd+Q 后重新打开）" | tee -a "$REPORT_FILE"
echo "  2. 如有 quarantine：sudo xattr -dr com.apple.quarantine \"/Applications/Codex.app\"" | tee -a "$REPORT_FILE"
echo "  3. 完全重置运行时：rm -rf ~/.cache/codex-runtimes/ 后重启 Codex" | tee -a "$REPORT_FILE"
echo "  4. 重装 Codex" | tee -a "$REPORT_FILE"
echo "" | tee -a "$REPORT_FILE"
echo "${GREEN}诊断完成${RESET}" | tee -a "$REPORT_FILE"

exec >&-1 2>&-  # 关闭 tee
