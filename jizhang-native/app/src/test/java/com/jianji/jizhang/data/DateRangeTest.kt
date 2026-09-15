package com.jianji.jizhang.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 「今天 / 本月」边界的回归测试。
 *
 * 这组函数原先在首页、账单页、导出页各写了一份，且**只有下界没有上界** ——
 * `dateTime >= monthStart` 会把未来月份也算进「本月」。支持补记之后立刻会出问题，
 * 所以这里专门盯住「上下界成对、且把当前时刻包住」。
 */
class DateRangeTest {

    @Test
    fun 本月上下界包住当前时刻() {
        val now = System.currentTimeMillis()
        assertTrue(monthStartMillis() <= now)
        assertTrue(now <= monthEndMillis())
        assertTrue(monthStartMillis() < monthEndMillis())
    }

    @Test
    fun 本月起点是一号零点整() {
        val cal = Calendar.getInstance().apply { timeInMillis = monthStartMillis() }
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun 本月终点是下月一号前的一毫秒() {
        // 期望的年月必须从「当前时刻」独立算出来。
        //
        // 这里原来只断言「终点 +1ms 是某个 1 号 0 点」，太松 —— 变异测试证明过：
        // 把 monthEndMillis() 的 add(MONTH, 1) 改成 add(MONTH, 2)，终点落到下下月 1 号，
        // 5 个用例**全部照样绿**。跨年写错（12 月不减不加年）同理抓不住。
        val now = Calendar.getInstance()
        val expectYear =
            if (now.get(Calendar.MONTH) == Calendar.DECEMBER) now.get(Calendar.YEAR) + 1
            else now.get(Calendar.YEAR)
        val expectMonth = (now.get(Calendar.MONTH) + 1) % 12

        val cal = Calendar.getInstance().apply {
            timeInMillis = monthEndMillis()
            add(Calendar.MILLISECOND, 1)
        }
        assertEquals("终点+1ms 落到了错误的年份", expectYear, cal.get(Calendar.YEAR))
        assertEquals("终点+1ms 落到了错误的月份", expectMonth, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun 今天上下界跨度是一天减一毫秒() {
        val start = todayStartMillis()
        val end = todayEndMillis()
        val now = System.currentTimeMillis()
        assertTrue(start <= now)
        assertTrue(now <= end)
        // 中国无夏令时，固定 24 小时
        assertEquals(86_400_000L - 1L, end - start)
    }

    @Test
    fun 今天落在本月区间之内() {
        assertTrue(todayStartMillis() >= monthStartMillis())
        assertTrue(todayEndMillis() <= monthEndMillis())
    }
}
