package com.jianji.jizhang.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 界面偏好（主题类 + 记账提醒），与首页风格分开存。
 *
 * DataStore 名称 `ui` —— 不能和 HomeStyle 的 `settings`、坚果云的 `backup`、
 * 种子的 `seed` 重名，同名同进程会崩。后加的三态主题、提醒开关、提醒快照
 * 全部寄居在这一个 `ui` store 上，**不要再为它们新建 store**。
 *
 * 这里最早只有「纯黑（AMOLED）」一项：`Theme.kt` 里的 `trueBlack` 参数与
 * `Color.kt` 的 `PureBlack` 早就写好了，但一直没有任何入口去开它，等于死代码。
 */
private val Context.uiDataStore by preferencesDataStore(name = "ui")

private val KEY_PURE_BLACK = booleanPreferencesKey("pure_black")

/** 是否开启纯黑背景。深色模式下才生效（浅色模式传了也会被 [JizhangTheme] 忽略）。 */
fun Context.pureBlackFlow(): Flow<Boolean> =
    uiDataStore.data.map { it[KEY_PURE_BLACK] == true }

suspend fun Context.setPureBlack(enabled: Boolean) {
    uiDataStore.edit { it[KEY_PURE_BLACK] = enabled }
}

/* ---------------- 三态主题 ---------------- */

/**
 * 主题模式。SYSTEM 跟随系统；LIGHT/DARK 强制。
 *
 * 放 UiPrefs 而不是 Theme.kt：它本质是「持久化的用户偏好」，
 * Theme.kt 只是消费方。存法与 home_style 一致——写枚举名字符串，
 * 读不到/脏值一律回落到 SYSTEM（老用户没这个键，升级后行为不变）。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun from(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

fun Context.themeModeFlow(): Flow<ThemeMode> =
    uiDataStore.data.map { ThemeMode.from(it[KEY_THEME_MODE]) }

suspend fun Context.setThemeMode(mode: ThemeMode) {
    uiDataStore.edit { it[KEY_THEME_MODE] = mode.name }
}

/* ---------------- 每日记账提醒 ---------------- */

/** 默认提醒整点。21 点：晚饭后、睡前，记账意愿和记忆都还在的时段。 */
const val DEFAULT_REMIND_HOUR = 21

/** 提醒设置快照（给设置页回显 + Worker 读取，一次 flow 全拿，避免两个流各自跳变）。 */
data class RemindSettings(
    val enabled: Boolean = false,
    val hour: Int = DEFAULT_REMIND_HOUR,
)

private val KEY_REMIND_ENABLED = booleanPreferencesKey("remind_enabled")
private val KEY_REMIND_HOUR = intPreferencesKey("remind_hour")

fun Context.remindSettingsFlow(): Flow<RemindSettings> =
    uiDataStore.data.map {
        RemindSettings(
            enabled = it[KEY_REMIND_ENABLED] == true,
            hour = it[KEY_REMIND_HOUR] ?: DEFAULT_REMIND_HOUR,
        )
    }

suspend fun Context.setRemindSettings(enabled: Boolean, hour: Int) {
    uiDataStore.edit {
        it[KEY_REMIND_ENABLED] = enabled
        it[KEY_REMIND_HOUR] = hour.coerceIn(0, 23)
    }
}

/* ---------------- 提醒用的「上次账单总数」快照 ---------------- */

/**
 * RemindWorker 的判据缓存：上一次到点检查时库里的账单总数。
 * null = 从没检查过（首次启用）。
 *
 * 为什么存在这里而不是别处：Ledger.kt 没有（本次也不允许加）「今日 0 点后有
 * 新增」的日期查询，Worker 只能拿 txCount() 总数和上次快照对比做近似判断；
 * 这个键纯属提醒功能，跟着提醒设置放同一个 `ui` store，不新建 store。
 */
private val KEY_REMIND_LAST_TX_TOTAL = intPreferencesKey("remind_last_tx_total")

suspend fun Context.readRemindLastTxTotal(): Int? =
    uiDataStore.data.first()[KEY_REMIND_LAST_TX_TOTAL]

suspend fun Context.writeRemindLastTxTotal(total: Int) {
    uiDataStore.edit { it[KEY_REMIND_LAST_TX_TOTAL] = total }
}
