package com.jianji.jizhang.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Compose [Color] → 存库 `Long` 的唯一转换口。
 *
 * 库里（`CategoryEntity.color` / `AccountEntity.color`）存的是 **ARGB 的 32 位值、
 * 符号扩展成 Long**，也就是 `toArgb().toLong()`：不透明色（alpha = 0xFF）最高位为 1，
 * **存进去是负数**（`0xFFE5484D` → `-1750963`）。
 *
 * 这不是笔误。`assets/migration.json` 里那 608 笔种子、备份 JSON 的 `color` 字段、
 * `BackupCodec` 的 `color.toInt()` 用的全是这一种表示，必须继续统一。
 *
 * 读回来直接用 `Color(value)` 即可：该工厂只取低 32 位，正负两种写法渲染结果完全一样
 * —— 正因为看不出差别，才更不该「顺手」加上 `and 0xFFFFFFFFL` 把新写入变成正数：
 * 存量数据是负的，混两种表示会让「这个颜色有没有变」这类比较悄悄失真。
 *
 * **不要写 `color.value.toLong()`。** `Color` 是 value class，`value` 是**已打包**的
 * 64 位 ULong（等于 `argb shl 32`）；而 `Color(color: Long)` 工厂内部还会再左移 32 位：
 *
 * ```kotlin
 * public fun Color(color: Long): Color = Color((color shl 32).toULong())
 * ```
 *
 * 于是 `Color(x.value.toLong())` 实际是 `argb shl 64` —— 64 位里溢光，结果为 0，
 * 也就是全透明。表现为「新建分类/账户、或在色板里换个色之后，圆点和首字都看不见」。
 */
fun Color.toStoredLong(): Long = toArgb().toLong()
