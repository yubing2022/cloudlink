package com.yubing.cloudlink.mcp

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CloudLink 的本地 MCP server —— 供同设备的 PalmClaw（端侧 Agent）调用。
 *
 * 协议：MCP **Streamable HTTP（2025-11-25）**，PalmClaw 的 AUTO 模式首选它，
 * 只有拿到 400/404/405 才会回退 legacy SSE，所以这里不做 SSE 流（GET 直接 405）。
 *
 * 端口固定在 8731，且**只绑 127.0.0.1**：
 *  - 绝不能监听 0.0.0.0，否则同网其它设备都能控制你家设备；
 *  - PalmClaw 的 McpEndpointPolicy 把 loopback 判为免 HTTPS、免 authToken，
 *    所以明文 HTTP 在回环上既可接受也无需鉴权。
 *
 * 三个工具的业务实现全部来自 [DeviceControlCore]，与 AgentBridge capability
 * 通道共用一份，错误码也沿用那 11 个。
 */
class CloudLinkMcpServer(
    private val core: DeviceControlCore,
    private val versionName: String = "0.1.0",
    port: Int = 8731,
) : NanoHTTPD(HOST, port) {

    /** 已发放的会话 id。MCP 要求 server 在 initialize 响应里下发，之后客户端回带。 */
    private val sessions: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // ------------------------------------------------------------------ HTTP

    override fun serve(session: IHTTPSession): Response {
        val uri = (session.uri ?: "").substringBefore('?')
        return try {
            when {
                uri != PATH -> json(Status.NOT_FOUND, rpcError(null, METHOD_NOT_FOUND, "not found: $uri"))
                session.method == Method.POST -> handlePost(session)
                session.method == Method.DELETE -> handleDelete(session)
                else -> response(Status.METHOD_NOT_ALLOWED)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "serve failed: ${t.message}", t)
            json(Status.INTERNAL_ERROR, rpcError(null, INTERNAL_JSONRPC_ERROR, t.message ?: "internal error"))
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        val body = readBody(session)
        if (body.isBlank()) {
            return json(Status.BAD_REQUEST, rpcError(null, PARSE_ERROR, "Parse error: empty body"))
        }
        val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()
            ?: return json(Status.BAD_REQUEST, rpcError(null, PARSE_ERROR, "Parse error"))

        val incoming = if (parsed is JSONArray) {
            (0 until parsed.length()).mapNotNull { parsed.optJSONObject(it) }
        } else {
            listOfNotNull(parsed as? JSONObject)
        }
        if (incoming.isEmpty()) {
            return json(Status.BAD_REQUEST, rpcError(null, INVALID_REQUEST, "Invalid Request"))
        }

        // 会话校验：initialize 不需要已有会话（它自己开一个）；
        // 其余请求必须带一个我们发出去的 Mcp-Session-Id，否则 404。
        val sentId = header(session, HDR_SESSION_ID)
        val isInit = incoming.any { it.optString("method") == "initialize" }
        if (!isInit && (sentId.isNullOrBlank() || !sessions.contains(sentId))) {
            Log.w(TAG, "rejected: missing/unknown $HDR_SESSION_ID ($sentId)")
            return json(
                Status.NOT_FOUND,
                rpcError(null, SESSION_ERROR, "unknown $HDR_SESSION_ID; send initialize first"),
            )
        }
        val sessionId = sentId?.takeIf { it.isNotBlank() && sessions.contains(it) }
            ?: newSession()

        val responses = JSONArray()
        for (msg in incoming) {
            val method = msg.optString("method")
            // notifications/* 是单向消息：没有 id，也不产生响应。
            if (!msg.has("id") || msg.isNull("id")) {
                Log.d(TAG, "notification: $method")
                continue
            }
            val id: Any = msg.get("id")
            val params = msg.optJSONObject("params") ?: JSONObject()
            responses.put(handleRequest(id, method, params))
        }

        // 全部是 notification → 202 Accepted，无 body（MCP 规范如此）。
        if (responses.length() == 0) {
            val r = response(Status.ACCEPTED)
            r.addHeader(HDR_SESSION_ID, sessionId)
            return r
        }

        val payload: Any = if (responses.length() == 1) responses.get(0) else responses
        Log.d(TAG, "reply ${responses.length()} msg(s) session=${sessionId.take(8)}")
        val res = json(Status.OK, payload.toString())
        res.addHeader(HDR_SESSION_ID, sessionId)
        return res
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val sid = header(session, HDR_SESSION_ID)
        if (!sid.isNullOrBlank() && sessions.remove(sid)) {
            Log.i(TAG, "session terminated: ${sid.take(8)}")
        }
        McpStatusTracker.setActiveSessions(sessions.size)
        return response(Status.NO_CONTENT)
    }

    // ------------------------------------------------------------- JSON-RPC

    private fun handleRequest(id: Any, method: String, params: JSONObject): JSONObject {
        val result: Any? = when (method) {
            "initialize" -> initializeResult(params)
            "ping" -> JSONObject()
            "tools/list" -> toolsListResult()
            "tools/call" -> callTool(params)
            else -> return rpcError(id, METHOD_NOT_FOUND, "method not found: $method")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        // 客户端说的版本我们支持就跟它走，不支持就报我们最新的（规范允许）。
        val negotiated = requested.takeIf { it in SUPPORTED_PROTOCOLS } ?: LATEST_PROTOCOL
        Log.i(TAG, "initialize: client=$requested → negotiated=$negotiated")
        return JSONObject()
            .put("protocolVersion", negotiated)
            // 只声明 tools：PalmClaw 开了 enforceStrictCapabilities，
            // 声明了却没实现的能力会让握手后的调用直接失败。
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", versionName))
            .put(
                "instructions",
                "先用 cloudlink_device_list 拿到 entity_id 与 domain（不要凭猜测构造），" +
                    "再用 cloudlink_device_control 控制；只查状态用 cloudlink_device_get_state。" +
                    "出错时结果文本里有 error_code，按它修正参数，而不是原样重试。",
            )
    }

    private fun toolsListResult(): JSONObject {
        val tools = JSONArray()
        for ((name, description, inputSchema) in DeviceToolSchema.MCP_TOOLS) {
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("inputSchema", JSONObject(inputSchema)),
            )
        }
        return JSONObject().put("tools", tools)
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val result = runBlocking {
                when (name) {
                    DeviceToolSchema.LIST -> core.listDevices(
                        args.optString("area", ""),
                        args.optString("domain", ""),
                    )

                    DeviceToolSchema.GET_STATE -> core.getState(args.optString("entity_id", ""))

                    DeviceToolSchema.CONTROL -> core.control(
                        args.optString("entity_id", ""),
                        args.optString("domain", ""),
                        args.optString("service", ""),
                        core.requireDataMap(
                            if (args.has("data") && !args.isNull("data")) args.opt("data") else null,
                        ),
                    )

                    else -> throw CapError(TOOL_NOT_FOUND, "unknown tool: $name")
                }
            }
            Log.d(TAG, "tools/call $name → ok")
            McpStatusTracker.recordCall(name, ok = true)
            toolResult(result.toString(), isError = false)
        } catch (e: CapError) {
            Log.w(TAG, "tools/call $name → ${e.code}")
            McpStatusTracker.recordCall(name, ok = false, errorCode = e.code)
            // 工具执行失败按 MCP 规范走 result + isError，而不是 JSON-RPC error：
            // 这是「设备层面」的失败，Agent 要能读到它并据此改参数重试。
            toolResult(
                JSONObject().put("error_code", e.code).put("message", e.message).toString(),
                isError = true,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "tools/call $name crashed", t)
            McpStatusTracker.recordCall(name, ok = false, errorCode = INTERNAL_ERROR_CODE)
            toolResult(
                JSONObject().put("error_code", INTERNAL_ERROR_CODE)
                    .put("message", t.message ?: t.javaClass.simpleName).toString(),
                isError = true,
            )
        }
    }

    private fun toolResult(text: String, isError: Boolean): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", isError)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    // ---------------------------------------------------------------- helpers

    private fun readBody(session: IHTTPSession): String {
        val len = header(session, "content-length")?.toIntOrNull()
        return if (len != null && len > 0) {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = session.inputStream.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else {
            session.inputStream.bufferedReader().use { it.readText() }
        }
    }

    /** NanoHTTPD 的 header key 大小写不保证，统一忽略大小写取。 */
    private fun header(session: IHTTPSession, name: String): String? =
        session.headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * 新会话。会话集合本身要有上界：PalmClaw 断线重连会不断 initialize，
     * 每次都发新 id，不清理的话这个集合只增不减（虽然只是几十字节的字符串）。
     */
    private fun newSession(): String {
        if (sessions.size >= MAX_SESSIONS) {
            sessions.firstOrNull()?.let { sessions.remove(it) }
        }
        return UUID.randomUUID().toString().also {
            sessions.add(it)
            McpStatusTracker.setActiveSessions(sessions.size)
        }
    }

    private fun response(status: Status): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, "")
        r.addHeader("Allow", "POST, DELETE")
        return r
    }

    private fun json(status: Status, body: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, body)
        r.addHeader("Allow", "POST, DELETE")
        return r
    }

    private fun json(status: Status, body: JSONObject): Response = json(status, body.toString())

    companion object {
        const val PORT = 8731
        const val HOST = "127.0.0.1"
        const val PATH = "/mcp"
        const val SERVER_NAME = "cloudlink"
        const val LATEST_PROTOCOL = "2025-11-25"

        /** 工具数量与名字：设置页展示用，必须跟着 [DeviceToolSchema.MCP_TOOLS] 走，不写死。 */
        val TOOL_COUNT: Int get() = DeviceToolSchema.MCP_TOOLS.size
        val TOOL_NAMES: String get() = DeviceToolSchema.MCP_TOOLS.joinToString(" / ") { it.first }

        private const val TAG = "CloudLinkMcp"
        private const val HDR_SESSION_ID = "Mcp-Session-Id"
        private const val MIME_JSON = "application/json"
        private const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
        private const val INTERNAL_ERROR_CODE = "INTERNAL_ERROR"
        private const val MAX_SESSIONS = 16

        /** SDK 0.10.0 能说的版本；协商时优先跟客户端走，否则给最新的。 */
        private val SUPPORTED_PROTOCOLS = setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        // JSON-RPC 2.0 标准错误码
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INTERNAL_JSONRPC_ERROR = -32603
        private const val SESSION_ERROR = -32001
    }
}
