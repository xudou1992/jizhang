package com.jianji.jizhang.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.util.Calendar

/**
 * 转账判据（本地副本）。
 * TODO(接线时换成 Ledger.kt 的 isTransfer)：该扩展属性随 toAccountId 在另一分支落地，
 * 提前引用会让本文件编译不过；行为等价 —— 转入账户非空即视为转账流水。
 * 命名与 StatsScreen.kt 里的同款判据错开：private 顶层声明只在本文件可见，
 * 但同名容易让人误以为是共享工具，改一处漏一处。
 */
private val TxWithCategory.isTransferRow: Boolean
    get() = tx.toAccountId.isNotBlank()

/**
 * 月度结余明细：最近 12 个月中「有数据」的月份，从新到旧。
 *
 * 这里曾经还有一张 `MonthlyTrendCard`（固定锚定当前月的近 6 个月柱状图），
 * 与 `StatsScreen` 里跟随月份选择器的 `TrendCard` 高度重叠，已删除。
 * 近 6 个月的趋势统一由 `StatsScreen.TrendCard` 负责。
 */
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
            // 剔除转账：转出一笔、转入一笔，不滤掉的话结余明细每笔转账都双计虚增
            if (t.isTransferRow) continue
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
        shape = MaterialTheme.shapes.large,
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
                            text = "支 ¥${centsToYuan(r.expense)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = expenseColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "收 ¥${centsToYuan(r.income)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = incomeColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "结余 ¥${centsToYuan(r.balance)}",
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
