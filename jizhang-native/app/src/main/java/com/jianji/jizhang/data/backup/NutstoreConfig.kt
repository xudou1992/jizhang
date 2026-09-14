package com.jianji.jizhang.data.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 坚果云备份配置（持久化在 DataStore）。
 *
 * DataStore 名称用 "backup" —— 千万别和 HomeStyle.kt 的 "settings" 重名，
 * 同名同进程会崩。
 *
 * 默认账号：504546466@qq.com
 * 应用密码：a562wi5cv4e2ba2g（坚果云应用名称「记账撤消授权」，授权日期 2026-03-02）
 * 这些信息在 APK 里是明文，只用于你自己的设备，不要外发 APK。
 *
 * remoteDir 默认 "/jianji"（纯 ASCII）：坚果云 WebDAV 对中文目录名的 URL 编码
 * 容易踩坑，用 ASCII 目录最稳，避免上传路径 404。
 */
data class NutstoreSettings(
    val email: String = "504546466@qq.com",
    val password: String = "a562wi5cv4e2ba2g",
    val enabled: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastResult: String = "",
    val remoteDir: String = "/jianji",
    val fileName: String = "jianji-backup.json",
)

private val Context.nutstoreDataStore by preferencesDataStore(name = "backup")

private val KEY_EMAIL = stringPreferencesKey("nutstore_email")
private val KEY_PASSWORD = stringPreferencesKey("nutstore_password")
private val KEY_ENABLED = booleanPreferencesKey("nutstore_enabled")
private val KEY_LAST_SYNC = longPreferencesKey("nutstore_last_sync_at")
private val KEY_LAST_RESULT = stringPreferencesKey("nutstore_last_result")
private val KEY_REMOTE_DIR = stringPreferencesKey("nutstore_remote_dir")
private val KEY_FILE_NAME = stringPreferencesKey("nutstore_file_name")

/** 当前备份配置的只读流。 */
fun Context.nutstoreSettingsFlow(): Flow<NutstoreSettings> =
    nutstoreDataStore.data.map { prefs ->
        NutstoreSettings(
            email = prefs[KEY_EMAIL].orEmpty(),
            password = prefs[KEY_PASSWORD].orEmpty(),
            enabled = prefs[KEY_ENABLED] == true,
            lastSyncAt = prefs[KEY_LAST_SYNC] ?: 0L,
            lastResult = prefs[KEY_LAST_RESULT].orEmpty(),
            remoteDir = prefs[KEY_REMOTE_DIR] ?: "/jianji",
            fileName = prefs[KEY_FILE_NAME] ?: "jianji-backup.json",
        )
    }

/**
 * 局部更新配置。给 null 的字段沿用当前值，只写传了的字段。
 * 这样 Worker / UI 都能只改 lastResult 而不动账号密码。
 */
suspend fun Context.updateNutstoreSettings(
    email: String? = null,
    password: String? = null,
    enabled: Boolean? = null,
    remoteDir: String? = null,
    fileName: String? = null,
    lastSyncAt: Long? = null,
    lastResult: String? = null,
) {
    val cur = nutstoreSettingsFlow().first()
    val next = cur.copy(
        email = email ?: cur.email,
        password = password ?: cur.password,
        enabled = enabled ?: cur.enabled,
        remoteDir = remoteDir ?: cur.remoteDir,
        fileName = fileName ?: cur.fileName,
        lastSyncAt = lastSyncAt ?: cur.lastSyncAt,
        lastResult = lastResult ?: cur.lastResult,
    )
    nutstoreDataStore.edit { prefs ->
        prefs[KEY_EMAIL] = next.email
        prefs[KEY_PASSWORD] = next.password
        prefs[KEY_ENABLED] = next.enabled
        prefs[KEY_LAST_SYNC] = next.lastSyncAt
        prefs[KEY_LAST_RESULT] = next.lastResult
        prefs[KEY_REMOTE_DIR] = next.remoteDir
        prefs[KEY_FILE_NAME] = next.fileName
    }
}
