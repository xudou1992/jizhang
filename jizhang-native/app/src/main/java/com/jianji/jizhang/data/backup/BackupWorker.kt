package com.jianji.jizhang.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.jianji.jizhang.data.LedgerDb
import kotlinx.coroutines.flow.first
// Charsets.UTF_8 来自 kotlin.text（stdlib 自动可见），不要 import java.nio.charset。
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 每日自动备份到坚果云的 Worker。没有 Hilt，直接用 applicationContext 手动构造依赖。
 *
 * 每次运行写两份：根目录的「最新版」覆盖更新，以及 `auto/` 下带设备名与时间戳的归档
 * （只增不删）—— 手动备份走 `manual/`，两条线互不覆盖，也不会互相冲掉历史。
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): WorkResult {
        val app = applicationContext
        val settings = app.nutstoreSettingsFlow().first()
        if (!settings.enabled) return WorkResult.success()

        return runBackupNow(app, BackupOrigin.AUTO).fold(
            onSuccess = { WorkResult.success() },
            onFailure = {
                // 失败信息写回 DataStore，UI 能展示；重试上限 3 次。
                if (runAttemptCount < 3) WorkResult.retry() else WorkResult.failure()
            },
        )
    }

    companion object {
        const val WORK_NAME = "jianji_backup"
        private const val MAX_RETRY = 3
    }
}

/** 从设置里读出连接凭据，几个入口（备份/列表/恢复/删除）共用。 */
private data class NutstoreCreds(
    val email: String,
    val password: String,
    val dir: String,
    val fileName: String,
)

private suspend fun readCreds(context: Context): NutstoreCreds {
    val s = context.applicationContext.nutstoreSettingsFlow().first()
    val email = s.email.trim()
    val password = s.password
    require(email.isNotBlank() && password.isNotBlank()) { "请先在设置里填写坚果云账号和应用密码" }
    return NutstoreCreds(
        email = email,
        password = password,
        dir = s.remoteDir.trim('/').ifBlank { "jianji" },
        fileName = s.fileName.ifBlank { "jianji-backup.json" },
    )
}

/**
 * 纯逻辑的「立刻备份一次」：导出 JSON → 建目录 → 上传 → 写回 lastSyncAt/lastResult。
 * 不检查 enabled 开关（用户主动点按钮就应当执行），只要求账号和应用密码都已填。
 * UI 的「立即备份一次」和 Worker 共用它，避免两套实现。
 *
 * 远端落两份：
 * - `<dir>/<fileName>`：固定路径的「最新版」，**刻意覆盖**，作用是一键恢复最新数据。
 * - `<dir>/<origin.tag>/<设备>-<时间戳>.json`：归档，**只增不删**。自动与手工各占一个目录，
 *   互相不覆盖；文件名里带设备名，多台机器共用一个坚果云账号也不会认错。
 *
 * [archive] 传 false 时只刷新「最新版」，不写归档 —— 记账后的防抖备份走这条路，
 * 否则一天记几十笔就会在 auto/ 里堆几十份几乎一样的文件。
 */
suspend fun runBackupNow(
    context: Context,
    origin: BackupOrigin = BackupOrigin.MANUAL,
    archive: Boolean = true,
): Result<Unit> {
    val app = context.applicationContext
    return runCatching {
        val settings = app.nutstoreSettingsFlow().first()
        val c = readCreds(app)

        // ---- 暴跌熔断：坏快照禁止自动覆盖云端「最新版」 ----
        // 场景：Room 迁移事故/清库后记了一笔新账，3 分钟防抖就把近乎空的库
        // 推上云，覆盖掉唯一的好备份 —— 双重丢数据。自动来源且笔数较上次
        // 成功上传时骤减一半以上（或归零），直接拒传并发通知。
        // 手工备份是明确的用户意图，不受熔断限制。
        val txCount = LedgerDb.get(app).dao().txCount()
        if (origin == BackupOrigin.AUTO && settings.lastTxCount > 0 &&
            (txCount == 0 || txCount * 2 < settings.lastTxCount)
        ) {
            val msg = "自动备份已熔断：账单数从 ${settings.lastTxCount} 骤减到 $txCount，" +
                "为防坏数据未覆盖云端最新版。若确实是有意删减，请手动备份一次确认。"
            app.updateNutstoreSettings(lastResult = msg)
            BackupNotify.warn(app, msg)
            return@runCatching
        }

        val json = BackupCodec.export(app)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val device = deviceName()

        val client = NutstoreClient(c.email, c.password)
        try {
            client.makeDir(c.dir).getOrThrow()
            // 最新版：固定路径，故意覆盖
            client.putFile("${c.dir}/${c.fileName}", bytes).getOrThrow()
            if (archive) {
                // 归档：按来源分目录 + 文件名带设备与时间戳，永不覆盖
                val archiveDir = "${c.dir}/${origin.tag}"
                client.makeDir(archiveDir).getOrThrow()
                client.putFile("$archiveDir/${cloudArchiveFileName()}", bytes).getOrThrow()
            }
        } finally {
            client.close()
        }

        val now = System.currentTimeMillis()
        app.updateNutstoreSettings(
            lastSyncAt = now,
            lastTxCount = txCount,
            lastResult = buildString {
                append("成功：")
                append(origin.label)
                if (archive) append("归档") else append("同步")
                append(" $device（${bytes.size / 1024}KB · $txCount 笔）")
            },
        )
    }.onFailure {
        // 失败时也把结果写回，方便 UI 展示（runCatching 已捕获异常）；
        // 自动来源额外发通知 —— 密码失效这类问题必须让用户在 App 外也能察觉。
        val reason = it.message ?: it.javaClass.simpleName
        app.updateNutstoreSettings(lastResult = "失败：$reason")
        if (origin == BackupOrigin.AUTO) {
            BackupNotify.warn(app, "自动备份失败：$reason。云端备份可能已过期，请尽快在设置→备份里处理。")
        }
    }
}

/** 云端备份的预览信息：下载后先给用户看，确认了才写库。 */
data class CloudBackupPreview(
    val jsonText: String,
    val sizeKB: Int,
    val accountCount: Int,
    val categoryCount: Int,
    val txCount: Int,
)

/** 云端备份列表条目（给「选择备份恢复」弹窗展示）。 */
data class CloudBackupEntry(
    val path: String,
    val name: String,
    val sizeKB: Int,
    val lastModified: Long,
    val isLatest: Boolean,
    /** 这份是自动跑的、手点的，还是恢复前的留底。 */
    val origin: BackupOrigin,
)

/**
 * 列出云端所有可恢复的备份，按时间倒序。
 *
 * 扫三个位置：根目录（最新版 + 历史遗留的老文件名）、`auto/`、`manual/`。
 * 目录不存在（404）由 [NutstoreClient.listFiles] 归一成空列表，不会报错。
 */
suspend fun listCloudBackups(context: Context): Result<List<CloudBackupEntry>> {
    val app = context.applicationContext
    return runCatching {
        val c = readCreds(app)
        val client = NutstoreClient(c.email, c.password)
        try {
            val groups = buildList {
                add(null to client.listFiles(c.dir).getOrThrow())
                BackupOrigin.entries.forEach { o ->
                    add(o to client.listFiles("${c.dir}/${o.tag}").getOrThrow())
                }
            }
            groups.flatMap { (origin, files) ->
                files.filter { it.name.endsWith(".json", ignoreCase = true) }.map { f ->
                    CloudBackupEntry(
                        path = f.path,
                        name = f.name,
                        sizeKB = (f.sizeBytes / 1024).toInt(),
                        lastModified = f.lastModified,
                        isLatest = f.name.equals(c.fileName, ignoreCase = true),
                        origin = origin ?: BackupOrigin.fromTag(f.name),
                    )
                }
            }.sortedByDescending { it.lastModified }
        } finally {
            client.close()
        }
    }
}

/**
 * 下载云端最新备份并解析出预览信息，不写库。
 * 云端 404 视为「还没备份过」，报错提示而不是崩溃。
 */
suspend fun downloadCloudBackup(context: Context): Result<CloudBackupPreview> {
    val app = context.applicationContext
    return runCatching {
        val c = readCreds(app)
        val client = NutstoreClient(c.email, c.password)
        try {
            val bytes = client.getFile("${c.dir}/${c.fileName}").getOrThrow()
                ?: error("云端还没有备份文件（/${c.dir}/${c.fileName}），先备份一次")
            val dto = BackupCodec.parse(bytes.toString(Charsets.UTF_8))
            CloudBackupPreview(
                jsonText = bytes.toString(Charsets.UTF_8),
                sizeKB = bytes.size / 1024,
                accountCount = dto.accounts.size,
                categoryCount = dto.categories.size,
                txCount = dto.transactions.size,
            )
        } finally {
            client.close()
        }
    }
}

/** 下载指定路径的云端备份并解析预览（不写库）。列表弹窗里点「恢复」走这里。 */
suspend fun downloadCloudBackupByPath(context: Context, remotePath: String): Result<CloudBackupPreview> {
    val app = context.applicationContext
    return runCatching {
        val c = readCreds(app)
        val client = NutstoreClient(c.email, c.password)
        try {
            val bytes = client.getFile(remotePath).getOrThrow()
                ?: error("云端文件不存在：$remotePath")
            val dto = BackupCodec.parse(bytes.toString(Charsets.UTF_8))
            CloudBackupPreview(
                jsonText = bytes.toString(Charsets.UTF_8),
                sizeKB = bytes.size / 1024,
                accountCount = dto.accounts.size,
                categoryCount = dto.categories.size,
                txCount = dto.transactions.size,
            )
        } finally {
            client.close()
        }
    }
}

/** 删除云端某一份备份（列表弹窗里的垃圾桶）。 */
suspend fun deleteCloudBackup(context: Context, remotePath: String): Result<Unit> {
    val app = context.applicationContext
    return runCatching {
        val c = readCreds(app)
        val client = NutstoreClient(c.email, c.password)
        try {
            client.deleteFile(remotePath).getOrThrow()
        } finally {
            client.close()
        }
    }
}

/**
 * 用已下载的 JSON 恢复：先把本机现状导出留底，再按 id 合并入库。
 * 合并语义：云端同名 id 覆盖本机，本机多出的记录不动 —— 适合换机 / 误删找回。
 */
suspend fun restoreFromJson(context: Context, jsonText: String): Result<Int> {
    val app = context.applicationContext
    return runCatching {
        // 恢复前把本机现状留底：万一恢复错了，还能从留底找回来。
        val safety = BackupCodec.export(app)
        saveSafetyCopy(app, safety)

        val count = BackupCodec.restore(app, jsonText)
        app.updateNutstoreSettings(lastResult = "恢复成功：$count 条（恢复前本机数据已留底）")
        count
    }.onFailure {
        app.updateNutstoreSettings(lastResult = "恢复失败：${it.message ?: "未知错误"}")
    }
}

/**
 * 留底写到 Android/data/<包名>/files/backups/，卸载 App 才会消失。
 *
 * 文件名同样带设备名与时间戳，来源标记为 `safety` —— 在恢复列表里一眼能和手工/自动备份区分。
 */
private fun saveSafetyCopy(context: Context, jsonText: String) {
    val dir = File(context.getExternalFilesDir(null), "backups")
    if (!dir.exists()) dir.mkdirs()
    val base = localBackupFileName(BackupOrigin.SAFETY).removeSuffix(".json")
    var file = File(dir, "$base.json")
    var n = 1
    while (file.exists()) {
        file = File(dir, "$base-${n++}.json")
    }
    file.writeText(jsonText, Charsets.UTF_8)
}

/** 自动备份调度：开关一变就增删唯一周期任务。 */
object BackupScheduler {
    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(BackupWorker.WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
