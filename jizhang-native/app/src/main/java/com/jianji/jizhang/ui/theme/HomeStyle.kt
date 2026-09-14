package com.jianji.jizhang.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 首页风格。用户拍板：两版都要，做成可切换。
 * 令牌纪律不变：颜色一律来自 theme，不在这里写死业务色。
 */
enum class HomeStyle(val label: String) {
    WHITE("白底清朗"),
    BLUE("品牌蓝顶");

    companion object {
        fun from(name: String?): HomeStyle = entries.firstOrNull { it.name == name } ?: WHITE
    }
}

private val Context.jizhangDataStore by preferencesDataStore(name = "settings")
private val KEY_HOME_STYLE = stringPreferencesKey("home_style")

fun Context.homeStyleFlow(): Flow<HomeStyle> =
    jizhangDataStore.data.map { HomeStyle.from(it[KEY_HOME_STYLE]) }

suspend fun Context.setHomeStyle(style: HomeStyle) {
    jizhangDataStore.edit { it[KEY_HOME_STYLE] = style.name }
}
