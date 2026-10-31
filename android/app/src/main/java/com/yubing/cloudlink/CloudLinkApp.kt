package com.yubing.cloudlink

import android.app.Application
import com.yubing.cloudlink.mcp.McpServiceStarter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CloudLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 本地 MCP server（供 PalmClaw 等本机 Agent 调用）随进程起。
        // 可能被系统以后台启动为由拒绝，所以 MainActivity 会再补一次。
        McpServiceStarter.start(this)
    }
}
