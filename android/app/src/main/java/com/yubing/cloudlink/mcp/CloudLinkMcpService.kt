package com.yubing.cloudlink.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yubing.cloudlink.R
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import javax.inject.Inject

/**
 * 承载本地 MCP server 的前台服务。
 *
 * 为什么是前台服务：CloudLink 进程被系统回收时，进程内起的 HTTP server 一样会没，
 * PalmClaw 那边只会看到连接断开。前台服务至少能保证 App 退到后台后进程仍在。
 * （PalmClaw 自带 McpConnectionRetry，短暂断服能自愈，但能常驻就别让它断。）
 *
 * 端口与绑定地址在 [CloudLinkMcpServer] 里写死为 127.0.0.1:8731 —— 只服务本机的 Agent。
 */
@AndroidEntryPoint
class CloudLinkMcpService : Service() {

    @Inject lateinit var core: DeviceControlCore

    private var server: CloudLinkMcpServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("正在启动…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            val s = CloudLinkMcpServer(core, versionName())
            try {
                s.start()
                server = s
                Log.i(TAG, "MCP server listening on ${CloudLinkMcpServer.HOST}:${CloudLinkMcpServer.PORT}${CloudLinkMcpServer.PATH}")
                notify("已在 127.0.0.1:${CloudLinkMcpServer.PORT} 提供 3 个设备工具")
            } catch (e: IOException) {
                // 端口被占用时不能让 App 崩，也不能假装成功 —— 通知里说清状态。
                server = null
                Log.w(TAG, "failed to bind ${CloudLinkMcpServer.PORT}", e)
                notify("端口 ${CloudLinkMcpServer.PORT} 被占用，MCP server 未启动")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        Log.i(TAG, "MCP server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- helpers

    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private fun notification(text: String): Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CloudLink 本地 MCP 服务")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 状态变化直接改通知文案：这是用户唯一能看到「端口在不在」的地方。 */
    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本地 MCP 服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "在 127.0.0.1:8731 提供设备控制工具，供本机 Agent 应用调用" },
        )
    }

    private companion object {
        const val TAG = "CloudLinkMcp"
        const val CHANNEL_ID = "cloudlink_mcp"
        const val NOTIFICATION_ID = 8731
    }
}
