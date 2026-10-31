package com.yubing.cloudlink.mcp

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MCP 服务的周期自愈。
 *
 * 先说清它能修什么、修不了什么：
 *  - **能修**：进程还活着，但 MCP 服务没起来（server 被停了、bind 失败过、
 *    前台服务被系统拆掉）。这种 Job 直接重新拉起即可。
 *  - **修不了**：整个进程已经被杀。Android 12+ 从 JobService 后台启动前台服务
 *    属于被禁场景，会抛 ForegroundServiceStartNotAllowedException；国产 ROM 还
 *    可能连周期 Job 都不给跑。这两类只能靠 [McpBootReceiver] 和用户打开界面补。
 *
 * 所以它只是第二道防线，不是保活银弹 —— 真正决定"会不会被回收"的是电池优化
 * 白名单（见设置页）。
 */
class McpKeepAliveJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d(TAG, "keep-alive tick")
        // 探测要连 socket，不能阻塞 JobService 主线程，所以异步做，做完再收工。
        jobScope.launch {
            val alive = McpProbe.isListening(CloudLinkMcpServer.HOST, CloudLinkMcpServer.PORT)
            if (!alive) {
                Log.w(TAG, "MCP port not listening → attempting restart")
                McpServiceStarter.start(this@McpKeepAliveJob)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private val jobScope = CoroutineScope(Dispatchers.Default)

    private companion object {
        const val TAG = "CloudLinkMcp"
    }

}

/**
 * 周期 Job 的调度。幂等：已经有同样的 job 就不重复调度。
 */
object McpKeepAliveScheduler {

    private const val JOB_ID = 8731
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return

        val component = ComponentName(context, McpKeepAliveJob::class.java)
        val info = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(PERIOD_MS)
            .setPersisted(true)
            .setRequiresDeviceIdle(false)
            .build()
        val result = scheduler.schedule(info)
        Log.i(TAG, "keep-alive job scheduled: ${result == JobScheduler.RESULT_SUCCESS}")
    }

    private const val TAG = "CloudLinkMcp"
}
