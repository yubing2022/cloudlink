package com.yubing.cloudlink.mcp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 启动本地 MCP 服务的唯一入口。
 *
 * 两处调用：App 进程起来时（[com.yubing.cloudlink.CloudLinkApp]）与
 * 用户打开界面时（[com.yubing.cloudlink.MainActivity]）。
 *
 * 为什么两处都调：Android 12+ 禁止从后台启动前台服务，Application.onCreate
 * 如果是被 broadcast 之类拉起的，那次 startForegroundService 会被系统拒绝
 * （ForegroundServiceStartNotAllowedException）。用户打开界面时补一次，
 * 就能保证「打开过 App → 端口一定在」。
 *
 * 启动失败只记日志，不抛：MCP server 是附加能力，不能因为它让 App 起不来。
 */
object McpServiceStarter {

    fun start(context: Context) {
        // 顺带把周期自愈挂上：它是幂等的，重复调用不会多排一个 job。
        McpKeepAliveScheduler.schedule(context)
        try {
            val intent = Intent(context, CloudLinkMcpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot start MCP service (will retry on next UI open)", t)
        }
    }

    private const val TAG = "CloudLinkMcp"
}
