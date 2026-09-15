package com.jianji.jizhang.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jianji.jizhang.ui.theme.readRemindLastTxTotal
import com.jianji.jizhang.ui.theme.remindSettingsFlow
import com.jianji.jizhang.ui.theme.writeRemindLastTxTotal
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 每日记账提醒 Worker。
 *
 * 「今天记过账就不要弹」的判据说明：
 * 理想查询是 `SELECT COUNT(*) FROM transactions WHERE dateTime >= 今日0点`，
 * 但那要在 Ledger.kt 的 DAO 上加方法 —— 本次任务禁改该文件（其他 Agent 负责）。
 * 退而求其次用 lastTxTotal 快照方案：每次到点记下账单总数，下次对比，
 * 总数涨了 ≈ 上次检查以来用户记过账 → 静默。误差与代价都在注释里写明：
 *  - 窗口是「距上次检查（约 24h 前）」而非严格「今日 0 点后」；
 *  - 「当天记一笔又删一笔」总数不变，会误弹一次（宁误弹不漏弹，提醒本非强承诺）；
 *  - 从云端恢复会让总数跳涨，最坏情况漏弹一天。
 * TODO(主控)：Ledger.kt 放开后加 `txCountSince(dayStartMillis)`，
 * 用真实日期查询替换这里的快照启发式（改动只在 doWork 三行内）。
 */
class RemindWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val cfg = app.remindSettingsFlow().first()
        // 开关关了还跑到这里 = 取消和入队的竞态，直接静默退出。
        if (!cfg.enabled) return Result.success()

        val total = LedgerDb.get(app).dao().txCount()
        val lastTotal = app.readRemindLastTxTotal()
        app.writeRemindLastTxTotal(total)

        when {
            // 首次启用没有基线：这一轮只建档不弹 —— 拿不准用户今天记没记，
            // 误弹比漏弹更讨嫌（用户可能因此关掉整个通知渠道）。
            lastTotal == null -> return Result.success()
            // 上次检查以来有新账 = 用户今天（大概率）记过了，不打扰。
            total > lastTotal -> return Result.success()
        }
        notifyNotRecorded(app)
        return Result.success()
    }

    private fun notifyNotRecorded(context: Context) {
        // Android 13+ 没给 POST_NOTIFICATIONS 就静默跳过，不强弹授权、不闪退。
        // （授权引导在 MainActivity 启动时已有，这里只读参考 BackupNotify 的写法。）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val text = "今天还没记账，30 秒记一笔"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("简记")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        // 点通知打开 App 首页。用 launch intent 而不是硬编码 MainActivity 组件：
        // 不依赖 MainActivity 的内部结构（该文件本次禁改），将来换启动页也不用动这里。
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { li ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    li,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTI_ID, builder.build()) }
    }

    companion object {
        const val WORK_NAME = "jianji_remind"
        const val CHANNEL_ID = "jianji_remind"
        /** 别撞 BackupNotify 的 1001；通知 id 是进程级命名空间。 */
        private const val NOTI_ID = 2001

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "记账提醒",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        ).apply {
                            description = "到点还没记账时的一天一次提醒"
                        },
                    )
                }
            }
        }
    }
}

/**
 * 每日提醒调度。参照 BackupScheduler 的手写依赖风格（无 Hilt）。
 */
object RemindScheduler {
    /**
     * 保存即调度：enabled=false 取消唯一任务；true 则按目标整点重建。
     *
     * 时间精度是有意取舍：WorkManager 不保证精确到点 —— 首跑落在
     * [initialDelay, initialDelay+flex] 内，之后每个 24h 周期在末尾 flex 里跑，
     * 且省电优化可能进一步推迟。即实际是 **±15~20 分钟级** 的"大概 21 点"。
     * 不用 exact alarm（setExactAndAllowWhileIdle）：那要引导用户授予
     * SCHEDULE_EXACT_ALARM / 电池优化白名单，为一个记账提醒不值当。
     *
     * 用 ExistingPeriodicWorkPolicy.UPDATE：每次保存都重建，锚点回到
     * "下一个目标整点"；代价是改一次设置周期时钟就重新起算，可接受。
     */
    fun schedule(context: Context, enabled: Boolean, hour: Int) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(RemindWorker.WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<RemindWorker>(1, TimeUnit.DAYS, 20, TimeUnit.MINUTES)
            .setInitialDelay(millisUntilNextOccurrence(hour), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(
            RemindWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** 粗算到下一个 `hour:00:00` 的毫秒数；今天的点已过就顺延到明天。 */
    private fun millisUntilNextOccurrence(hour: Int): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - now
    }
}
