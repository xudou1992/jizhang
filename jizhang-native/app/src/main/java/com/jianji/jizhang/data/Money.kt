package com.jianji.jizhang.data

import java.util.Locale
import kotlin.math.roundToLong

/**
 * 金额换算：界面上的「元」 ↔ 数据库里的「分」。
 *
 * 库里一律存「分」的 Long（见 [TxEntity.amountCents]），元只存在于输入框、展示层
 * 和备份 JSON，两者的转换必须只有一处实现 —— 曾经 AddScreen 用 `.toLong()`、
 * TxDetailScreen 与 BackupCodec 用 `.roundToLong()`，同一个 App 三条路径两种结果。
 *
 * **必须四舍五入，不能截断。** 浮点乘法自带误差：`1.15 * 100` 得到
 * `114.99999999999999`，`.toLong()` 截断后存 114 分。穷举 0.01~99.99 的两位小数，
 * 有 573 个会因此少记 1 分（1.15→1.14、2.05→2.04、0.29→0.28…）。
 */

/** 元（Double，来自备份 JSON 等）→ 分。 */
fun yuanToCents(yuan: Double): Long = (yuan * 100).roundToLong()

/** 元（输入框里的文本）→ 分。容忍千分位逗号与首尾空白；解析不了按 0 处理。 */
fun parseYuanToCents(text: String): Long =
    text.trim()
        .replace(",", "")
        .toDoubleOrNull()
        ?.let(::yuanToCents)
        ?: 0L

/** 分 → 元的展示文本，两位小数、千分位、中文语境。 */
fun centsToYuan(cents: Long): String =
    String.format(Locale.CHINA, "%,.2f", cents / 100.0)
