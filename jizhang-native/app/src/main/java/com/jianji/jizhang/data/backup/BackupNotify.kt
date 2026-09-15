package com.jianji.jizhang.data.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 备份状态的本地通知。
 *
 * 只发两类，绝不发成功通知（每天弹一条会被用户关掉整个渠道）：
 * - 失败/熔断：自动备份传不上去或暴跌被拦 —— 用户不打开 App 也必须看到，
 *   否则密码失效几个月后丢机就是全丢。
 * - 长期未备份：留给每日 Worker 自己判断。
 *
 * Android 13+ 需要 POST_NOTIFICATIONS 运行时授权；未授权时静默跳过，
 * 结果仍然写进 DataStore 的 lastResult，备份页照样可见。
 */
object BackupNotify {
    const val CHANNEL_ID = "jianji_backup_status"
    private const val NOTI_ID = 1001

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "备份状态",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "自动备份失败或数据异常时的提醒"
                    },
                )
            }
        }
    }

    /** 发一条备份警示通知；权限没给就算了，不闪退也不强弹授权。 */
    fun warn(context: Context, text: String) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(app)
        val notification: Notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("简记备份提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTI_ID, notification) }
    }
}
