package com.yubing.cloudlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yubing.cloudlink.mcp.ACTION_REFRESH_NOTIFICATION
import com.yubing.cloudlink.mcp.CloudLinkMcpService
import com.yubing.cloudlink.mcp.McpServiceStarter
import com.yubing.cloudlink.nav.AppNavGraph
import com.yubing.cloudlink.ui.theme.CloudLinkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Android 13+ 通知是运行时权限。这里申请它，系统才会弹出授权框；
     * 用户授予后，「设置 → 通知管理 → 允许通知」才能真正打开，前台常驻通知才会显示。
     * 没声明/没申请时，ColorOS 那个开关是灰的、点不动，通知被系统直接吞掉。
     */
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // 权限到位前 startForeground 那条通知被系统吞了，这里补发一次，
            // 让常驻通知真正显示出来（服务器其实已经在跑）。
            val intent = Intent(this, CloudLinkMcpService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 前台服务要显示常驻通知，Android 13+ 必须先拿到 POST_NOTIFICATIONS。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 前台启动时补一次，保证「用户打开过 App → MCP 端口一定在」。
        // 后台启动被系统拒绝的情况在这里被兜住。
        McpServiceStarter.start(this)
        setContent {
            CloudLinkTheme {
                AppNavGraph()
            }
        }
    }
}
