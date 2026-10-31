package com.yubing.cloudlink.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * MCP 服务的自启入口。
 *
 * 为什么必须有它：Android 12+ 禁止从后台启动前台服务，唯一还能稳定拉起 FGS 的
 * 后台场景就是系统广播（BOOT_COMPLETED 在豁免名单里）。没有这个 receiver 的话，
 * 手机重启后只要用户没打开过 CloudLink，PalmClaw 就永远连不上。
 *
 * 三个动作：
 *  - BOOT_COMPLETED：开机完成后拉起；
 *  - MY_PACKAGE_REPLACED：本 App 升级/重装后拉起（装完就能连，不用手动开一次）；
 *  - ACTION_MY_PACKAGE_REPLACED 之外还收 QUICKBOOT_POWERON，部分国产 ROM
 *    （荣耀/ColorOS 等）只发这个广播庆祝"快速开机"。
 *
 * 刻意不设 directBootAware：direct boot 阶段凭据加密存储（TokenStore、
 * DataStore）还不可用，这时启动服务只会拿到一个空登录态，宁可等解锁后再起。
 */
class McpBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in TRIGGERS) return
        Log.i(TAG, "received $action → starting MCP service")
        McpKeepAliveScheduler.schedule(context)
        McpServiceStarter.start(context)
    }

    private companion object {
        const val TAG = "CloudLinkMcp"
        val TRIGGERS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
