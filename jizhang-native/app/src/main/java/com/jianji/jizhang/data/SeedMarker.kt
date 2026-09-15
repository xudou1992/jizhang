package com.jianji.jizhang.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * 「内置种子数据是否已经灌过」的标记。
 *
 * 单独用一个 DataStore（名字 `seed`）：**不能**和 HomeStyle 的 `settings`、
 * NutstoreConfig 的 `backup` 重名，同名同进程会崩。
 *
 * 这个标记必须与「transactions 表里有多少行」解耦。用行数当判据的话，
 * 用户把账单全删光（或恢复到一份空备份）之后重启，行数又变回 0，
 * 608 笔旧账会自己长回来 —— 用户明确删掉的数据不该复活。
 */
private val Context.seedDataStore by preferencesDataStore(name = "seed")

private val KEY_SEEDED_V1 = booleanPreferencesKey("seeded_v1")

/** 是否已经灌过种子。只读，不产生副作用。 */
suspend fun Context.isSeeded(): Boolean =
    seedDataStore.data.first()[KEY_SEEDED_V1] == true

/**
 * 落标记。只在种子**全部写库成功之后**调用 —— 中途抛异常就不落，
 * 下次启动会重试，而不是留下一个「标记了但没灌」的空库。
 */
suspend fun Context.markSeeded() {
    seedDataStore.edit { it[KEY_SEEDED_V1] = true }
}
