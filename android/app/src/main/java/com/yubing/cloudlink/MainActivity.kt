package com.yubing.cloudlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yubing.cloudlink.mcp.McpServiceStarter
import com.yubing.cloudlink.nav.AppNavGraph
import com.yubing.cloudlink.ui.theme.CloudLinkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
