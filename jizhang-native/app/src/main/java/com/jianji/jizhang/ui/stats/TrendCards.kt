package com.jianji.jizhang.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.util.Calendar
import java.util.Locale

/** 金额格式：分 → 元，两位、千分位、中文语境 */
private fun money(cents: Long): String = String.format(Locale.CHINA, "%,.2f", cents / 100.0)

/** 单月趋势聚合（近 6 个月柱状图用） */
private data class MonthTrend(
    val key: Int,
    val month: Int, // 1..12
    var expense: Long,
    var income: Long,
)

/** 近 6 个月收支趋势：分组柱状图 */
@Composable
fun MonthlyTrendCard(all: List<TxWithCategory>) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    val expenseColor = JizhangTheme.colors.expense
    val incomeColor = JizhangTheme.colors.income
    val axisColor = MaterialTheme.colorScheme.outline

    val months = remember(all) {
        val now = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val list = (0 until 6).map { off ->
            val c = now.clone() as Calendar
            c.add(Calendar.MONTH, off - 5)
            MonthTrend(
                key = c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1),
                month = c.get(Calendar.MONTH) + 1,
                expense = 0L,
                income = 0L,
            )
        }
        val byKey = list.associateBy { it.key }
        val c = Calendar.getInstance()
        for (t in all) {
            c.timeInMillis = t.tx.dateTime
            val key = c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1)
            val bucket = byKey[key] ?: continue
            if (t.tx.isExpense) bucket.expense += t.tx.amountCents
            else bucket.income += t.tx.amountCents
        }
        list
    }

    var maxVal = 0L
    for (m in months) {
        if (m.expense > maxVal) maxVal = m.expense
        if (m.income > maxVal) maxVal = m.income
    }
    if (maxVal == 0L) maxVal = 1L
    val hasData = months.any { it.expense != 0L || it.income != 0L }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "近 6 个月收支趋势",
                style = MaterialTheme.typography.titleMedium,
                color = onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(160.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (!hasData) {
                        val baseY = size.height
                        drawRoundRect(
                            color = axisColor,
                            topLeft = Offset(0f, baseY - 1.dp.toPx()),
                            size = Size(size.width, 1.dp.toPx()),
                            cornerRadius = CornerRadius(0f),
                        )
                        return@Canvas
                    }
                    val groupW = size.width / 6f
                    val barW = 14.dp.toPx()
                    val gap = 6.dp.toPx()
                    val baseY = size.height
                    months.forEachIndexed { i, m ->
                        val cx = groupW * (i + 0.5f)
                        val exH = size.height * (m.expense.toFloat() / maxVal)
                        val inH = size.height * (m.income.toFloat() / maxVal)
                        drawRoundRect(
                            color = expenseColor,
                            topLeft = Offset(cx - barW - gap / 2f, baseY - exH),
                            size = Size(barW, exH.coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                        drawRoundRect(
                            color = incomeColor,
                            topLeft = Offset(cx + gap / 2f, baseY - inH),
                            size = Size(barW, inH.coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    }
                }
                if (!hasData) {
                    Text(
                        text = "最近 6 个月还没有记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                months.forEach { m ->
                    Text(
                        text = "${m.month}月",
                        style = MaterialTheme.typography.bodySmall,
                        color = onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(expenseColor),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "支出",
                    style = MaterialTheme.typography.bodySmall,
                    color = onBackground,
                )
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(incomeColor),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "收入",
                    style = MaterialTheme.typography.bodySmall,
                    color = onBackground,
                )
            }
        }
    }
}

/** 月度结余明细：最近 12 个月中「有数据」的月份，从新到旧 */
@Composable
fun YearBarsCard(all: List<TxWithCategory>) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    val expenseColor = JizhangTheme.colors.expense
    val incomeColor = JizhangTheme.colors.income

    val rows = remember(all) {
        val now = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sums = LinkedHashMap<Int, MonthSum>()
        val c = Calendar.getInstance()
        for (off in 0..11) {
            val cc = now.clone() as Calendar
            cc.add(Calendar.MONTH, off - 11)
            sums[cc.get(Calendar.YEAR) * 100 + (cc.get(Calendar.MONTH) + 1)] =
                MonthSum(cc.get(Calendar.YEAR), cc.get(Calendar.MONTH) + 1, 0L, 0L)
        }
        for (t in all) {
            c.timeInMillis = t.tx.dateTime
            val key = c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1)
            val s = sums[key] ?: continue
            if (t.tx.isExpense) s.expense += t.tx.amountCents
            else s.income += t.tx.amountCents
        }
        sums.values
            .filter { it.expense > 0L || it.income > 0L }
            .map { m -> YearRow("${m.year}年${m.month}月", m.expense, m.income, m.income - m.expense) }
            .reversed()
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "月度结余明细",
                style = MaterialTheme.typography.titleMedium,
                color = onBackground,
            )
            if (rows.isEmpty()) {
                Text(
                    text = "还没有记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                rows.forEachIndexed { idx, r ->
                    if (idx > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = r.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "支 ¥${money(r.expense)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = expenseColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "收 ¥${money(r.income)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = incomeColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "结余 ¥${money(r.balance)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = onBackground,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 单月汇总（月度结余明细聚合用） */
private data class MonthSum(
    val year: Int,
    val month: Int,
    var expense: Long,
    var income: Long,
)

/** 已渲染的一行结余 */
private data class YearRow(
    val label: String,
    val expense: Long,
    val income: Long,
    val balance: Long,
)
