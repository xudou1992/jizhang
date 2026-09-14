package com.jianji.jizhang.data.backup

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份来源。文件名/远端目录里都带它，一眼看出这份是自动跑出来的还是手点的。
 *
 * [tag] 只含 ASCII 小写字母，用于文件名与远端目录名 —— 远端走 WebDAV，
 * ASCII 最稳（中文段虽然客户端会 URL 编码，但不值得冒险）。
 */
enum class BackupOrigin(val tag: String, val label: String) {
    /** 用户点「导出数据」/「立即备份一次」。 */
    MANUAL("manual", "手工"),

    /** 定时任务或记账后自动触发。 */
    AUTO("auto", "自动"),

    /** 恢复前自动留底的现状快照，用于恢复错了能找回。 */
    SAFETY("safety", "恢复留底"),
    ;

    companion object {
        /**
         * 从文件名/远端路径猜来源；认不出就当手工（老备份没有任何标记）。
         * 先判「留底」再判「自动」—— 老本机留底文件名是 `恢复前留底-…`，不含 safety。
         */
        fun fromTag(raw: String?): BackupOrigin {
            val t = raw.orEmpty().lowercase()
            return when {
                t.contains("留底") || t.contains(SAFETY.tag) -> SAFETY
                t.contains("自动") || t.contains(AUTO.tag) -> AUTO
                else -> MANUAL
            }
        }
    }
}

private const val TS_PATTERN = "yyyyMMdd-HHmmss"

/** 归档时间戳：`20260914-201530`（本地时区）。 */
fun backupTimestamp(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat(TS_PATTERN, Locale.CHINA).format(Date(now))

/**
 * 设备名片段，用于文件名里区分是哪台机器备的。
 *
 * `Build.MODEL` 常见形态是纯型号（`2201122C`）或带品牌（`M2012K11AC`），
 * 单看型号认不出机型，所以拼上品牌。结果必须能安全落进文件名与 URL：
 * 去掉一切非 `[A-Za-z0-9._-]`、合并重复分隔符、限长 24。
 */
fun deviceName(): String {
    val brand = Build.BRAND.orEmpty().ifBlank { Build.MANUFACTURER.orEmpty() }
    val model = Build.MODEL.orEmpty()
    val raw = if (model.lowercase().startsWith(brand.lowercase())) model else "$brand $model"
    val safe = raw.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-')
        .trim('.')
        .take(24)
        .trimEnd('-', '.')
    // 极端情况（模拟器/空值）兜底，保证永远有东西可拼
    return safe.ifBlank { "device" }
}

/** 本机本地备份文件名：`简记-<设备>-<来源>-<时间戳>.json`。 */
fun localBackupFileName(
    origin: BackupOrigin,
    now: Long = System.currentTimeMillis(),
): String = "简记-${deviceName()}-${origin.tag}-${backupTimestamp(now)}.json"

/**
 * 云端归档文件名：`<设备>-<时间戳>.json`（所在目录已经区分了来源）。
 */
fun cloudArchiveFileName(
    now: Long = System.currentTimeMillis(),
): String = "${deviceName()}-${backupTimestamp(now)}.json"

/** 本地命名：`简记-<设备>-<来源>-<时间戳>[-n].json`。 */
private val LOCAL_NAME_RE = Regex("^简记-(.+?)-(manual|auto|safety)-(\\d{8}-\\d{6})(?:-\\d+)?\\.json$")

/** 云端归档命名：`<设备>-<时间戳>[-n].json`。 */
private val CLOUD_NAME_RE = Regex("^(.+?)-(\\d{8}-\\d{6})(?:-\\d+)?\\.json$")

/**
 * 从备份文件名里把设备名抠出来，列表里就能显示「这份是哪台机器备的」。
 * 老命名（`记账备份-…` / `jianji-backup.json`）或用户另存后改过名的，返回 null。
 */
fun deviceFromBackupName(name: String): String? =
    LOCAL_NAME_RE.find(name)?.groupValues?.get(1)
        ?: CLOUD_NAME_RE.find(name)?.groupValues?.get(1)
