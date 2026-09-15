package com.jianji.jizhang.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 颜色 ↔ 存库 Long 的回归测试。
 *
 * 库里（`CategoryEntity.color` / `AccountEntity.color`）与迁移包、备份 JSON 一律存
 * **0xAARRGGBB**，转换只有一个出口：[toStoredLong]。
 */
class ColorCodecTest {

    @Test
    fun 落库值是ARGB符号扩展成Long() {
        listOf(0xFFE5484DL, 0xFF128A5BL, 0x80FF0000L).forEach { argb ->
            val stored = Color(argb).toStoredLong()
            // 低 32 位必须原样保住（含 alpha），不能只留 RGB
            assertEquals("位模式被改动", argb, stored and 0xFFFFFFFFL)
            // 表示必须唯一：符号扩展，而不是写成 0xAARRGGBB 的正数。
            // 这条看着像吹毛求疵，其实是真踩过：migration.json 里 608 笔种子的 color
            // 全是负数，一旦有人「顺手」给 toStoredLong 加上 `and 0xFFFFFFFF`，
            // 新写入会变正数而存量仍是负数 —— 渲染看不出差别，颜色比较却会失真。
            assertEquals("表示不唯一", argb.toInt().toLong(), stored)
        }
    }

    @Test
    fun 落库值能被读回原色() {
        listOf(0xFFE5484DL, 0xFF2A5AE8L, 0xFF6E56CFL, 0xFF8A6A4EL).forEach { argb ->
            // 走真实出口 toStoredLong()，别在这里手写 toArgb().toLong()：
            // 手写等于把实现抄一遍，实现改了测试却不会红。
            val stored = Color(argb).toStoredLong()
            assertEquals(argb.toInt(), Color(stored).toArgb())
        }
    }

    @Test
    fun 分类色板每个颜色都能原样往返() {
        CategoryPalette.forEach { c ->
            val stored = c.toStoredLong()
            assertEquals(c.toArgb(), Color(stored).toArgb())
        }
    }

    /**
     * 反例固化：`Color.value` 是**已打包**的 64 位 ULong（等于 `argb shl 32`），
     * 而 `Color(color: Long)` 工厂内部还会再左移 32 位 —— 于是 `Color(value.toLong())`
     * 等于 `argb shl 64`，64 位里溢光变成 0，也就是全透明。
     *
     * 这正是「新建分类 / 新建账户、或在色板里点一下换色之后，圆点和首字都看不见」的根因。
     * 把它写成用例，是为了让后来者一眼看到这条路的坑，而不是靠注释。
     */
    @Test
    fun 打包值直接喂回Color会溢出成全透明() {
        val packed = Color(0xFFE5484DL).value.toLong()
        assertEquals(0f, Color(packed).alpha, 0f)
    }
}
