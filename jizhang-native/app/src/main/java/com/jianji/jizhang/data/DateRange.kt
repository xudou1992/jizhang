package com.jianji.jizhang.data

import java.util.Calendar

/**
 * 「今天 / 本月」的时间边界。
 *
 * 此前 `monthStartMillis()` 在首页、账单页、导出页各写了一遍（三份一模一样的代码），
 * 而且三处都只有下界没有上界 —— `dateTime >= monthStart` 会把未来月份也算进「本月」。
 * 过去不能记未来日期所以没暴露，但一旦支持补记/改日期就会立刻出问题。
 *
 * 统一收在这里，并且**成对**提供上下界，调用方用 `in start..end` 就不会漏掉上界。
 */

/** 今天 0 点。 */
fun todayStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** 今天最后一毫秒。 */
fun todayEndMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_MONTH, 1)
    cal.add(Calendar.MILLISECOND, -1)
    return cal.timeInMillis
}

/** 本月 1 号 0 点。 */
fun monthStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** 本月最后一毫秒（下月 1 号 0 点减 1ms）。 */
fun monthEndMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.MONTH, 1)
    cal.add(Calendar.MILLISECOND, -1)
    return cal.timeInMillis
}

/** 任意年月的首/末毫秒（month 与 Calendar 一致，0 基）。导出「指定月份」用。 */
fun monthRangeMillis(year: Int, month: Int): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    cal.add(Calendar.MONTH, 1)
    cal.add(Calendar.MILLISECOND, -1)
    return start to cal.timeInMillis
}
