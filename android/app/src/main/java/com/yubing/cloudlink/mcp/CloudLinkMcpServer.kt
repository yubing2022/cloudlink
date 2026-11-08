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
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CloudLink 的本地 MCP server —— 供同设备的 PalmClaw（端侧 Agent）调用。
 *
 * 协议：MCP **Streamable HTTP（2025-11-25）**，PalmClaw 的 AUTO 模式首选它。
 *
 * ⚠️ 这里**必须**支持 GET 的长连接 SSE 流（见 [handleGet]）。规范（2025-03-26 起）
 * 要求客户端在 initialize 之后开一个 GET 流接收服务端通知；官方 Kotlin SDK 正是
 * 这么实现的。之前这里对 GET 返回 405，理由是「客户端拿到 405 会回退 legacy SSE」——
 * **这个假设是错的**，实测会让 transport 在握手后失效，表现为「工具调用 30 秒超时、
 * 服务端一条 POST 日志都没有」。
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

    /**
     * 已发放的会话 id → 创建时间戳。MCP 要求 server 在 initialize 响应里下发，之后客户端回带。
     *
     * 为什么带时间戳：会话满了要淘汰**最老的**，不能随机挑一个。
     * 随机淘汰会把正在使用的会话干掉，客户端下一次请求就吃到 404，
     * 表现为「工具调用莫名失败/超时」——而这对客户端完全不可见，极难排查。
     */
    private val sessions: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /**
     * 打开中的 GET SSE 流：连接 → 输出流。
     *
     * 用 `IHTTPSession` 做键而不是 session id：同一个 MCP 会话理论上可以开
     * 多个 GET 流（规范允许），按连接记更准确。
     */
    private val sseStreams: ConcurrentHashMap<IHTTPSession, java.io.OutputStream> =
        ConcurrentHashMap()

    /**
     * SSE 保活线程。
     *
     * ## 重要：这里**不写任何内容**
     *
     * 规范允许服务端用 SSE 注释行（`: ping`）保活，但实测官方 Kotlin SDK
     * **不接受注释行**：客户端收到第一个 `: ping` 就把流关掉了 ——
     * 服务端这边表现为「写 pipe 失败」（`SSE stream closed by peer`），
     * 客户端那边表现为 transport 崩掉、后续工具调用超时。
     *
     * 也就是说，用注释行保活反而**害**了 transport。
     *
     * CloudLink 目前也没有需要主动推送的通知（工具结果都是 POST 的直接响应），
     * 所以最稳的做法是：**开着流但什么都不写**。连接保持，客户端的 transport
     * 保持健康，TCP 的空闲超时由客户端侧的 socketTimeoutMillis 管
     * （KratuAgent 那边把 SSE 的 socket timeout 设成了 null，即不主动断）。
     *
     * 单线程 + daemon：server 进程被回收时这个线程不该拦住退出。
     */
    private val sseKeepAlive: Thread = Thread({
        while (true) {
            try {
                Thread.sleep(SSE_KEEP_ALIVE_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            // 只做「连接还在吗」的探测，不写入任何字节 ——
            // 写注释行会让客户端断开（见上面的说明）。
            sseStreams.entries.removeIf { (httpSession, out) ->
                val alive = runCatching {
                    // flush 一个空写入，确认 pipe 还没被对端关闭
                    out.flush()
                    true
                }.getOrDefault(false)
                if (!alive) {
                    Log.d(TAG, "SSE stream closed by peer")
                    runCatching { out.close() }
                }
                !alive
            }
        }
    }, "cloudlink-sse-keepalive").apply {
        isDaemon = true
        start()
    }

    // ------------------------------------------------------------------ HTTP

    override fun serve(session: IHTTPSession): Response {
        val uri = (session.uri ?: "").substringBefore('?')
        val method = session.method?.name ?: "?"
        val sid = header(session, HDR_SESSION_ID)?.take(8) ?: "-"
        // 到达日志：用来区分「请求根本没到 server」和「到了但响应没回去」。
        // 之前只有 tools/call 级别的日志，客户端一超时，完全看不出请求走到哪一步。
        Log.d(TAG, "→ $method $uri session=$sid")
        val started = System.currentTimeMillis()
        return try {
            when {
                uri != PATH -> json(Status.NOT_FOUND, rpcError(null, METHOD_NOT_FOUND, "not found: $uri"))
                session.method == Method.POST -> handlePost(session)
                session.method == Method.DELETE -> handleDelete(session)
                session.method == Method.GET -> handleGet(session)
                else -> response(Status.METHOD_NOT_ALLOWED)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "serve failed: ${t.message}", t)
            json(Status.INTERNAL_ERROR, rpcError(null, INTERNAL_JSONRPC_ERROR, t.message ?: "internal error"))
        }.also {
            Log.d(TAG, "← $method $uri ${it.status} in ${System.currentTimeMillis() - started}ms")
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

        // 会话校验。
        //
        // initialize 不需要已有会话（它自己开一个）。
        // 其余请求必须带一个我们发出去的 Mcp-Session-Id。
        //
        // **带了一个我们不认识的 id 时不返回 404，而是原地重建一个会话继续处理**。
        // 原因：MCP 规范要求未知会话回 404，但客户端收到 404 会按规范回退到
        // legacy SSE —— 而这个 server 明确不做 SSE（GET 直接 405），
        // 于是整条链路以「工具调用莫名失败」收场，客户端还拿不到任何有用信息。
        // 宽容重建的代价只是丢一段会话状态（这里本来就没状态），
        // 换来的却是「会话被淘汰 / server 重启后自动恢复」。
        val sentId = header(session, HDR_SESSION_ID)
        val isInit = incoming.any { it.optString("method") == "initialize" }
        if (!isInit && sentId.isNullOrBlank()) {
            Log.w(TAG, "rejected: missing $HDR_SESSION_ID")
            return json(
                Status.NOT_FOUND,
                rpcError(null, SESSION_ERROR, "missing $HDR_SESSION_ID; send initialize first"),
            )
        }
        val sessionId = when {
            isInit -> newSession()
            sessions.containsKey(sentId) -> sentId!!
            else -> {
                Log.i(TAG, "unknown session ${sentId?.take(8)} → creating a new one")
                newSession()
            }
        }

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
        if (!sid.isNullOrBlank() && sessions.remove(sid) != null) {
            Log.i(TAG, "session terminated: ${sid.take(8)}")
        }
        McpStatusTracker.setActiveSessions(sessions.size)
        return response(Status.NO_CONTENT)
    }

    /**
     * GET —— **MCP Streamable HTTP 规范要求的长连接 SSE 流**。
     *
     * ## 为什么必须有（2026-11-08 真机事故）
     *
     * 规范（2025-03-26 起）：客户端在 `initialize` 之后**必须**开一个 GET 流，
     * 用来接收服务端主动推送的通知。官方 Kotlin SDK 的
     * `StreamableHttpClientTransport` 就是这么实现的。
     *
     * 之前这里对 GET 直接返回 405，理由是「客户端拿到 405 会回退 legacy SSE」。
     * **这个假设是错的**，实测后果是：
     *
     * | 场景 | 结果 |
     * |---|---|
     * | 握手后**立刻**调工具 | ✅ 339ms，工具调用成功 |
     * | 握手后**等 8 秒**再调 | ❌ transport 崩掉，进程 crash |
     * | App 里的实际表现 | 30 秒超时，**服务端一条 POST 日志都没有** |
     *
     * 405 让 SDK 判定「这个 server 不支持 Streamable HTTP」，transport 被标记为
     * 不可用；此时模型刚好还在推理（几秒），等它决定调工具时，transport 已经坏了，
     * 请求**根本发不出去** —— 所以服务端零日志、客户端只能等到超时。
     *
     * 「立刻调用能通、隔几秒就坏」这个反差，是定位到这里的决定性线索。
     *
     * ## 实现要点
     *
     * 规范允许服务端**只发注释行**（`: ping`）保活。CloudLink 目前没有需要主动
     * 推送的通知（工具结果都是 POST 的直接响应），所以这个流的作用就是：
     * ① 让客户端的 transport 保持健康；② 传输层保活探测。
     *
     * 流必须**一直开着**直到客户端断开，所以这里不能阻塞 serve 线程 ——
     * NanoHTTPD 每连接一线程，阻塞在这里等于占死一个线程。做法是先返回一个
     * chunked 响应（响应头立刻发出去），再由后台线程往里写保活注释。
     */
    private fun handleGet(session: IHTTPSession): Response {
        val sid = header(session, HDR_SESSION_ID)
        if (sid.isNullOrBlank() || !sessions.containsKey(sid)) {
            // 没有有效会话的 GET 一律 404：客户端会据此认为这个流不存在，
            // 而不是拿到一个「连上了但没用」的流。
            return json(Status.NOT_FOUND, rpcError(null, METHOD_NOT_FOUND, "unknown session"))
        }

        // NanoHTTPD 的 chunked 响应是基于 InputStream 的：它一边从流里读、
        // 一边按 chunk 发出去。用一根 pipe 把「后台保活线程」和「NanoHTTPD
        // 的读线程」接起来 —— 后者只管读，前者只管写。
        val pipeOut = java.io.PipedOutputStream()
        val pipeIn = java.io.PipedInputStream(pipeOut, SSE_PIPE_BUFFER_BYTES)
        sseStreams[session] = pipeOut

        val response = NanoHTTPD.newChunkedResponse(Status.OK, MIME_SSE, pipeIn)
        response.addHeader("Content-Type", MIME_SSE)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader(HDR_SESSION_ID, sid)
        Log.d(TAG, "GET stream opened session=${sid.take(8)}")
        return response
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
                // **CloudLink 的全部定制都写在这里** —— server 的 instructions 是
                // MCP 规范里「server 作者写给模型的权威说明」，随协议自然分发到任何
                // 客户端。Agent 侧不该出现任何 CloudLink 专用的提示词。
                //
                // 工具名一律引用常量，别在这里手写 —— 手写过一轮，改名就漂了。
                //
                // ⚠️ 长度预算：端侧提示词给本段 800 字符，超了会从中间截断
                // （clipMiddle），关键句必须排在靠前/靠后的位置。
                "问「有哪些设备/设备叫什么」「我家连了哪些 IoT 设备」「家里有什么智能家居」" +
                    "→ 直接调用 ${DeviceToolSchema.LIST}，" +
                    "它**不需要任何参数**，不要反问用户要区域或类型。" +
                    "它的返回是一份**清单**：每行一台设备，格式是「- 设备名（区域）」。" +
                    "回答用户时**把这几行原样逐行列出来**，每行一个，" +
                    "`-` 后面留一个空格，不要合并成一整段，也不要自己编设备。" +
                    "**device_name 只能照抄清单里的原名，一个字都不能改**：" +
                    "不要翻译、不要简写、不要合并两台设备（如把「床尾灯」写成「床灯」）。" +
                    "查某台设备现在的状态 → 调用 ${DeviceToolSchema.GET_STATE}，" +
                    "参数 device_name 填设备名（如 \"落地灯\"）。" +
                    "想知道这台设备有哪些实体 → 调用 ${DeviceToolSchema.ENTITIES}，" +
                    "参数 device_name 填设备名。" +
                    "控制设备 → 调用 ${DeviceToolSchema.CONTROL}，参数 device_name 填设备名、" +
                    "service 填 turn_on / turn_off / toggle。" +
                    "**你不需要 entity_id，也不需要 domain**：这四个工具都以设备名为入口，" +
                    "服务端会自己定位到正确的实体。" +
                    "同名设备会报错并列出候选，这时加上 area 参数区分即可。" +
                    "出错时结果文本里有 error_code，按它修正参数，而不是原样重试" +
                    "（INVALID_PARAMS 是类型/写法错，ENTITY_NOT_FOUND 是设备名不存在，" +
                    "或者有同名设备需要用 area 区分）。" +
                    "ENTITY_NOT_FOUND 的提示里会列出**最接近的设备名**，照抄其中一个重试即可。",
            )
    }

    private fun toolsListResult(): JSONObject {
        val tools = JSONArray()
        for ((name, description, inputSchema) in DeviceToolSchema.MCP_TOOLS) {
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("inputSchema", JSONObject(inputSchema))
                    // 声明只读/写语义：客户端据此决定工具调用超时后能不能安全重试。
                    .put("annotations", JSONObject(DeviceToolSchema.annotationsFor(name))),
            )
        }
        return JSONObject().put("tools", tools)
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            // 工具结果是**文本**（MCP 的 content[].text），不是 JSON 值。
            // 列表类工具刻意返回纯文本清单而非 JSON 数组 ——
            // 理由见 [McpDeviceView.deviceListText]。
            val result: String = runBlocking {
                when (name) {
                    DeviceToolSchema.LIST -> McpDeviceView.deviceListText(core.listDevices())

                    // 「有哪些设备」只列设备；「这台设备有什么」才钻到实体。
                    DeviceToolSchema.ENTITIES -> {
                        val device = core.resolveDevice(
                            core.requiredString(args, "device_name", "\"设备名\""),
                            core.optionalString(args, "area", "\"区域名\""),
                        )
                        McpDeviceView.entitiesText(device)
                    }

                    DeviceToolSchema.GET_STATE -> {
                        val device = core.resolveDevice(
                            core.requiredString(args, "device_name", "\"设备名\""),
                            core.optionalString(args, "area", "\"区域名\""),
                        )
                        val primary = McpDeviceView.pickPrimaryEntity(device)
                            ?: throw CapError(
                                ENTITY_NOT_FOUND,
                                "设备「${device.name}」下没有可查询的实体",
                            )
                        McpDeviceView.deviceStateView(
                            device,
                            primary,
                            core.getState(primary.entity_id),
                        ).toString()
                    }

                    // 控制：服务端自己挑「支持该 service 的实体」，
                    // 模型不需要给 entity_id，也不需要给 domain（domain 会不匹配）。
                    DeviceToolSchema.CONTROL -> {
                        val device = core.resolveDevice(
                            core.requiredString(args, "device_name", "\"设备名\""),
                            core.optionalString(args, "area", "\"区域名\""),
                        )
                        val service = core.requiredString(args, "service", "\"turn_on\"")
                        val data = core.requireDataMap(
                            if (args.has("data") && !args.isNull("data")) args.opt("data") else null,
                        )
                        // 给了 entity_name 就精确控制那一个；没给就按 service
                        // 反查它常见于哪些 domain，再挑主功能。
                        val entityName = core.optionalString(args, "entity_name", "\"实体名\"")
                        val target = if (entityName.isNotBlank()) {
                            McpDeviceView.pickEntityByShortName(device, entityName)
                                ?: throw CapError(
                                    ENTITY_NOT_FOUND,
                                    "设备「${device.name}」下没有名为「$entityName」的实体。" +
                                        "可用「查询设备实体」工具查看它有哪些实体",
                                )
                        } else {
                            McpDeviceView.pickControlEntity(
                                device,
                                service,
                                core.domainsSupportingService(service),
                            ) ?: throw CapError(
                                ENTITY_NOT_FOUND,
                                "设备「${device.name}」下没有可控制的实体（只有只读实体）",
                            )
                        }
                        val controlResult = core.control(
                            target.entity_id,
                            target.domain.lowercase(),
                            service,
                            data,
                        )
                        McpDeviceView.controlResultView(device, target, controlResult).toString()
                    }

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

    /**
     * 读请求体。
     *
     * **不能阻塞读到 EOF**：NanoHTTPD 是「每连接一线程」，而 MCP 客户端用的是
     * keep-alive 长连接 —— 在这种连接上 readText() 会一直等下去，
     * 把线程池（默认 50）慢慢耗光，之后**所有**请求都排队等超时。
     * 所以：只按 Content-Length 精确读；没有长度就按 chunked 解；
     * 两者都不是就当空 body，绝不阻塞。
     */
    private fun readBody(session: IHTTPSession): String {
        val len = header(session, "content-length")?.trim()?.toIntOrNull()
        if (len != null) {
            if (len <= 0) return ""
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = session.inputStream.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            return String(buf, 0, off, Charsets.UTF_8)
        }
        val chunked = header(session, "transfer-encoding")
            ?.contains("chunked", ignoreCase = true) == true
        return if (chunked) readChunkedBody(session) else ""
    }

    /** 最小 chunked 解码器：size 行 → 数据 → CRLF，size=0 结束。 */
    private fun readChunkedBody(session: IHTTPSession): String {
        val out = StringBuilder()
        val sizeLine = ByteArrayOutputStream()
        while (true) {
            sizeLine.reset()
            var b = session.inputStream.read()
            if (b < 0) break
            while (b >= 0 && b != '\n'.code) {
                if (b != '\r'.code) sizeLine.write(b)
                b = session.inputStream.read()
            }
            val size = sizeLine.toString().trim().substringBefore(';').toIntOrNull() ?: break
            if (size <= 0) break
            val buf = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = session.inputStream.read(buf, off, size - off)
                if (n < 0) break
                off += n
            }
            out.append(String(buf, 0, off, Charsets.UTF_8))
            session.inputStream.read() // chunk 后的 CRLF
            session.inputStream.read()
        }
        return out.toString()
    }

    /** NanoHTTPD 的 header key 大小写不保证，统一忽略大小写取。 */
    private fun header(session: IHTTPSession, name: String): String? =
        session.headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * 新会话。会话集合本身要有上界：PalmClaw 断线重连会不断 initialize，
     * 每次都发新 id，不清理的话这个集合只增不减（虽然只是几十字节的字符串）。
     *
     * 淘汰**最老的**而不是随机的：随机淘汰会干掉正在用的会话，
     * 客户端下一次请求就吃 404，而它根本不知道自己被淘汰了。
     */
    private fun newSession(): String {
        if (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value }?.key
            if (oldest != null) {
                sessions.remove(oldest)
                Log.i(TAG, "evicted oldest session ${oldest.take(8)} (cap=$MAX_SESSIONS)")
            }
        }
        val id = UUID.randomUUID().toString()
        sessions[id] = System.currentTimeMillis()
        McpStatusTracker.setActiveSessions(sessions.size)
        return id
    }

    private fun response(status: Status): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, "")
        r.addHeader("Allow", "POST, GET, DELETE")
        r.addHeader("Connection", "close")
        return r
    }

    private fun json(status: Status, body: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, body)
        r.addHeader("Allow", "POST, GET, DELETE")
        // **每次响应都关连接**。
        //
        // NanoHTTPD 在 socket 读超时后可能留下一条「僵尸连接」：socket 还开着，
        // 但已经不再处理请求。客户端（OkHttp 连接池，keep-alive 5 分钟）下一次
        // 复用它时，写入照样成功，却永远等不到响应 —— 表现为工具调用挂到超时。
        // 实测过一次：上一条调用成功，闲置几十秒后的下一次调用整整卡了 30s，
        // 而同一请求单独发是 200ms 返回。
        //
        // 回环上加一次 TCP 握手不到 1ms，换掉「偶发卡死」这笔账很划算。
        r.addHeader("Connection", "close")
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

        /** 与 [DeviceControlCore] 的同名错误码保持一致（那边是 private，这里各存一份）。 */
        private const val ENTITY_NOT_FOUND = "ENTITY_NOT_FOUND"
        private const val INTERNAL_ERROR_CODE = "INTERNAL_ERROR"

        /**
         * 会话上限。之前是 16 —— 客户端每次重连都发新 id，调试期间几次 initialize
         * 就把正在用的会话挤掉了，表现为随机的「工具调用失败」。128 足够，
         * 淘汰时按创建时间从最老的开始（见 [newSession]）。
         */
        private const val MAX_SESSIONS = 128

        /**
         * SSE 保活间隔。
         *
         * 15 秒是常见取值：足够短（中间的 NAT/代理不会判定空闲而断链），
         * 又足够长（一条 60 字节的注释流，1 小时才 1.4KB）。
         */
        private const val SSE_KEEP_ALIVE_INTERVAL_MS = 15_000L

        /** SSE 的 MIME 类型。 */
        private const val MIME_SSE = "text/event-stream"

        /**
         * SSE pipe 的缓冲区大小。
         *
         * 够大，让一次保活写入不会阻塞；又不会大到浪费内存 ——
         * 每个打开的流都有一根 pipe。
         */
        private const val SSE_PIPE_BUFFER_BYTES = 4096

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
