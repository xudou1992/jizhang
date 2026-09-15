package com.jianji.jizhang.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 金额换算的回归测试。
 *
 * 这一组用例的价值在于：`data/Money.kt` 里那三个函数是**唯一**的元↔分通道，
 * 一旦有人把 `roundToLong()` 改回 `toLong()`、或者绕过它自己写 `(x * 100).toLong()`，
 * 下面的穷举用例会立刻红。
 */
class MoneyTest {

    /**
     * 穷举 0.01 ~ 99.99 的全部两位小数（9999 个），逐个必须原样还原成「分」。
     *
     * 这是历史上真出过的事故：AddScreen 用 `.toLong()` 截断，浮点乘法误差
     * （`1.15 * 100 == 114.99999999999999`）导致其中 **573 个**少记 1 分 ——
     * 1.15 存成 1.14、2.05 存成 2.04、0.29 存成 0.28。
     * 穷举一遍比只测几个样例可靠得多。
     */
    @Test
    fun 全部两位小数都能原样还原成分() {
        var wrong = 0
        val samples = StringBuilder()
        for (cents in 1..9999) {
            val text = String.format(Locale.US, "%.2f", cents / 100.0)
            val back = parseYuanToCents(text)
            if (back != cents.toLong()) {
                wrong++
                if (samples.length < 150) samples.append("$text→$back ")
            }
        }
        assertEquals("有金额被记错，样例：$samples", 0, wrong)
    }

    @Test
    fun 已知会截断的边界值() {
        assertEquals(115L, parseYuanToCents("1.15"))
        assertEquals(205L, parseYuanToCents("2.05"))
        assertEquals(29L, parseYuanToCents("0.29"))
        assertEquals(226L, parseYuanToCents("2.26"))
        assertEquals(115L, yuanToCents(1.15))
    }

    @Test
    fun 容忍千分位逗号与首尾空白() {
        assertEquals(123456L, parseYuanToCents(" 1,234.56 "))
        assertEquals(100000000L, parseYuanToCents("1,000,000"))
    }

    @Test
    fun 解析不了按零处理() {
        assertEquals(0L, parseYuanToCents(""))
        assertEquals(0L, parseYuanToCents("   "))
        assertEquals(0L, parseYuanToCents("abc"))
        assertEquals(0L, parseYuanToCents("."))
        assertEquals(0L, parseYuanToCents("1.2.3"))
    }

    @Test
    fun 分转元的展示文本() {
        assertEquals("0.00", centsToYuan(0L))
        assertEquals("1.15", centsToYuan(115L))
        assertEquals("1,234.56", centsToYuan(123456L))
        assertEquals("-12.30", centsToYuan(-1230L))
    }

    @Test
    fun 元分往返不丢精度() {
        for (cents in listOf(1L, 7L, 29L, 115L, 999L, 123456L, 99999999L)) {
            assertEquals(cents, parseYuanToCents(centsToYuan(cents).replace(",", "")))
        }
    }
}
