package com.yubing.cloudlink.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** MCP server 的运行状态。 */
enum class McpRunState {
    /** 刚发出启动指令，还没拿到 bind 结果。 */
    STARTING,

    /** 端口已 bind，正常服务。 */
    RUNNING,

    /** bind 失败（绝大多数情况是端口被别的进程占了）。 */
    PORT_IN_USE,

    /** 进程里没有前台服务在跑（刚开机还没打开过 App，或进程被回收后重建）。 */
    STOPPED,
}

/**
 * 一条 MCP server 的状态快照。
 *
 * 全部由 [McpStatusTracker] 在 server/service 侧写入，设置页只读。
 */
data class McpStatus(
    val state: McpRunState = McpRunState.STOPPED,
    val host: String = CloudLinkMcpServer.HOST,
    val port: Int = CloudLinkMcpServer.PORT,
    val detail: String? = null,
    /** 进入当前状态的时刻，用于算运行时长。 */
    val sinceMs: Long? = null,
    val activeSessions: Int = 0,
    val toolCalls: Long = 0,
    val lastTool: String? = null,
    val lastCallMs: Long? = null,
    /** 最近一次失败的错误码；成功调用不清除它，方便回溯。 */
    val lastError: String? = null,
) {
    /** 给 PalmClaw 填的完整 URL。 */
    val endpoint: String get() = "http://$host:$port${CloudLinkMcpServer.PATH}"
}

/**
 * 进程内的 MCP 状态中心。
 *
 * 为什么是单例而不是广播/IPC：CloudLinkMcpService 就跑在 App 主进程里，
 * 直接写内存最简单也最实时；真正的跨进程场景（server 挂了但 UI 还活着）
 * 由 [McpProbe.isListening] 补一次真实探测，两者不一致时以探测为准。
 */
object McpStatusTracker {

    private val _status = MutableStateFlow(McpStatus())
    val status: StateFlow<McpStatus> = _status.asStateFlow()

    private inline fun update(block: McpStatus.() -> McpStatus) {
        _status.value = _status.value.block()
    }

    fun reportStarting() = update {
        copy(state = McpRunState.STARTING, detail = "正在启动…", sinceMs = System.currentTimeMillis())
    }

    fun reportRunning(port: Int) = update {
        copy(
            state = McpRunState.RUNNING,
            port = port,
            detail = "服务运行中",
            sinceMs = System.currentTimeMillis(),
        )
    }

    fun reportPortInUse(port: Int, cause: String?) = update {
        copy(
            state = McpRunState.PORT_IN_USE,
            port = port,
            detail = "端口被占用，未启动",
            sinceMs = null,
            lastError = cause,
        )
    }

    fun reportStopped() = update {
        copy(
            state = McpRunState.STOPPED,
            detail = "服务已停止",
            sinceMs = null,
            activeSessions = 0,
        )
    }

    fun setActiveSessions(count: Int) = update { copy(activeSessions = count) }

    fun recordCall(tool: String, ok: Boolean, errorCode: String? = null) = update {
        copy(
            toolCalls = toolCalls + 1,
            lastTool = tool,
            lastCallMs = System.currentTimeMillis(),
            lastError = if (ok) lastError else errorCode,
        )
    }
}

/**
 * 真实连一次回环端口。
 *
 * 状态有可能骗人：比如 service 被杀但 tracker 还留着上一次的 RUNNING。
 * 这里用一次短超时的 TCP connect 做事实校验。
 */
object McpProbe {

    suspend fun isListening(host: String, port: Int, timeoutMs: Int = 400): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            }.getOrDefault(false)
        }
}
