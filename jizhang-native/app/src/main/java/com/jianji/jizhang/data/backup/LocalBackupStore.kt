package com.jianji.jizhang.data.backup

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 本地备份：JSON 落在 `Android/data/<包名>/files/backups/`，卸载 App 才会消失。
 *
 * 这里不碰 SAF 权限 —— 应用私有外部目录读写不需要任何权限声明，
 * 「另存到手机其他位置」才走系统文件选择器（SAF），同样免权限。
 */
data class LocalBackupFile(
    val path: String,
    val name: String,
    val sizeKB: Int,
    val lastModified: Long,
    /** 来源（手工 / 自动 / 恢复留底），从文件名解析。 */
    val origin: BackupOrigin,
)

/** 备份目录；不存在则建。 */
private fun backupDir(context: Context): File {
    val dir = File(context.getExternalFilesDir(null), "backups")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

/** 列出本机全部备份（.json），按修改时间倒序 —— 最新的排最上面。 */
fun listLocalBackups(context: Context): List<LocalBackupFile> =
    backupDir(context).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
        ?.map {
            LocalBackupFile(
                path = it.absolutePath,
                name = it.name,
                sizeKB = (it.length() / 1024).toInt(),
                lastModified = it.lastModified(),
                origin = BackupOrigin.fromTag(it.name),
            )
        }
        ?.sortedByDescending { it.lastModified }
        .orEmpty()

/**
 * 导出数据：把当前三张表导成 JSON 写进备份目录。
 *
 * 文件名 = `简记-<设备名>-<来源>-<yyyyMMdd-HHmmss>.json`，把「哪台机器 / 自动还是手点 /
 * 什么时候」三件事全部编进文件名 —— 多设备共用一份网盘时不会互相认错，
 * 恢复列表里也一眼看得出该挑哪份。
 *
 * 同一秒内重复导出会自动加 `-2/-3`，**任何情况下都不覆盖已有备份**。
 */
suspend fun exportLocalBackup(
    context: Context,
    origin: BackupOrigin = BackupOrigin.MANUAL,
): Result<LocalBackupFile> {
    val app = context.applicationContext
    return runCatching {
        val json = BackupCodec.export(app)
        val dir = backupDir(app)
        val base = localBackupFileName(origin).removeSuffix(".json")
        var file = File(dir, "$base.json")
        var n = 1
        while (file.exists()) {
            file = File(dir, "$base-${n++}.json")
        }
        file.writeText(json, Charsets.UTF_8)
        LocalBackupFile(
            path = file.absolutePath,
            name = file.name,
            sizeKB = (file.length() / 1024).toInt(),
            lastModified = file.lastModified(),
            origin = origin,
        )
    }
}

/** 读出某个本机备份并解析成预览（只解析、不写库）。 */
fun readLocalBackup(path: String): Result<CloudBackupPreview> =
    runCatching { previewOf(File(path).readText(Charsets.UTF_8)) }

/** 从系统文件选择器选中的文件读取并解析预览（用于「导入数据」）。 */
fun readLocalBackupFromUri(context: Context, uri: Uri): Result<CloudBackupPreview> = runCatching {
    val text = context.contentResolver.openInputStream(uri)?.use {
        it.readBytes().toString(Charsets.UTF_8)
    } ?: error("打不开所选文件")
    previewOf(text)
}

/** 把某份本机备份另存到用户选的位置（SAF 写入，免存储权限）。 */
fun writeLocalBackupToUri(context: Context, uri: Uri, path: String): Result<Unit> = runCatching {
    val bytes = File(path).readBytes()
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: error("写不进所选位置")
}

/** 删除某份本机备份。 */
fun deleteLocalBackup(path: String): Result<Unit> =
    runCatching { if (File(path).exists() && !File(path).delete()) error("删除失败") }

/** 解析 JSON 并统计条目，供「确认恢复」弹窗预览。 */
private fun previewOf(text: String): CloudBackupPreview {
    val dto = BackupCodec.parse(text)
    return CloudBackupPreview(
        jsonText = text,
        sizeKB = text.toByteArray(Charsets.UTF_8).size / 1024,
        accountCount = dto.accounts.size,
        categoryCount = dto.categories.size,
        txCount = dto.transactions.size,
    )
}
