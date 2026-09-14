package com.jianji.jizhang.ui.bills

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
// `Modifier.weight` 是 RowScope/ColumnScope 的成员，不能 import。
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 金额恒为「分」，统一用千分手点格式，避免各处分散写。 */
private fun money(cents: Long): String = String.format(Locale.CHINA, "%,.2f", cents / 100.0)

/** 本月 0 点毫秒，用于「本月」筛选的边界。 */
private fun monthStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** 把两条带时间清零的日历按「天」求差（中国无夏令时，除法安全）。 */
private fun dayDiff(today: Calendar, other: Calendar): Int {
    val a = (today.clone() as Calendar).apply { clearTime() }
    val b = (other.clone() as Calendar).apply { clearTime() }
    return ((a.timeInMillis - b.timeInMillis) / 86_400_000L).toInt()
}

private fun Calendar.clearTime() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/** 今天 / 昨天 / 9月12日 周五。 */
private fun dayLabel(cal: Calendar, today: Calendar): String {
    return when (val d = dayDiff(today, cal)) {
        0 -> "今天"
        1 -> "昨天"
        else -> SimpleDateFormat("M月d日 E", Locale.CHINA).format(cal.time)
    }
}

/** 懒加载扁平列表的元素：月份头 / 天头 / 交易行。 */
private sealed interface BillItem {
    val key: String
    data class MonthHeader(
        override val key: String,
        val label: String,
        val expenseCents: Long,
        val incomeCents: Long,
    ) : BillItem

    data class DayHeader(
        override val key: String,
        val label: String,
        val expenseCents: Long,
        val incomeCents: Long,
    ) : BillItem

    data class Row(override val key: String, val tx: TxWithCategory) : BillItem
}

private enum class BillFilter { MONTH, ALL }

/**
 * 把已 DESC 排序的交易列表，按「月 → 天」两级分组，并预聚合每月/每日收支。
 * 两遍扫描：先聚合进 Map，再顺序展开成扁平列表，保证表头金额完整。
 */
private fun buildItems(list: List<TxWithCategory>, today: Calendar): List<BillItem> {
    val monthMeta = LinkedHashMap<Long, MonthAcc>()
    val dayMeta = LinkedHashMap<Long, DayAcc>()
    for (item in list) {
        val cal = Calendar.getInstance().apply { timeInMillis = item.tx.dateTime }
        val mKey = cal.get(Calendar.YEAR) * 100L + (cal.get(Calendar.MONTH) + 1)
        val dKey = mKey * 100 + cal.get(Calendar.DAY_OF_MONTH)
        val m = monthMeta.getOrPut(mKey) {
            MonthAcc(label = "${cal.get(Calendar.MONTH) + 1}月")
        }
        val d = dayMeta.getOrPut(dKey) {
            DayAcc(label = dayLabel(cal, today))
        }
        if (item.tx.isExpense) {
            m.expense += item.tx.amountCents
            d.expense += item.tx.amountCents
        } else {
            m.income += item.tx.amountCents
            d.income += item.tx.amountCents
        }
    }

    val out = ArrayList<BillItem>(list.size + monthMeta.size + dayMeta.size)
    var lastM = -1L
    var lastD = -1L
    for (item in list) {
        val cal = Calendar.getInstance().apply { timeInMillis = item.tx.dateTime }
        val mKey = cal.get(Calendar.YEAR) * 100L + (cal.get(Calendar.MONTH) + 1)
        val dKey = mKey * 100 + cal.get(Calendar.DAY_OF_MONTH)
        if (mKey != lastM) {
            val m = monthMeta.getValue(mKey)
            out += BillItem.MonthHeader("m_$mKey", m.label, m.expense, m.income)
            lastM = mKey
        }
        if (dKey != lastD) {
            val d = dayMeta.getValue(dKey)
            out += BillItem.DayHeader("d_$dKey", d.label, d.expense, d.income)
            lastD = dKey
        }
        out += BillItem.Row("r_${item.tx.id}", item)
    }
    return out
}

private class MonthAcc(var label: String) {
    var expense: Long = 0L
    var income: Long = 0L
}

private class DayAcc(var label: String) {
    var expense: Long = 0L
    var income: Long = 0L
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillListScreen(
    all: List<TxWithCategory>,
    onAdd: () -> Unit,
    onOpenSearch: () -> Unit,
    onCopy: (TxWithCategory) -> Unit,
    onDelete: (String) -> Unit,
) {
    // 筛选状态 + 分组结果都缓存：600+ 条只在 all / filter 变化时重算
    var filter by remember { mutableStateOf(BillFilter.ALL) }
    val items = remember(all, filter) {
        val today = Calendar.getInstance()
        val scoped = if (filter == BillFilter.MONTH) {
            all.filter { it.tx.dateTime >= monthStartMillis() }
        } else {
            all
        }
        buildItems(scoped, today)
    }
    // 长按菜单里的「删除」先到这里，确认后才真删
    var pendingDelete by remember { mutableStateOf<TxWithCategory?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        // 标题行：左「账单」，右搜索 + 当前筛选范围说明
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "账单",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) {
                Icon(
                    JizhangIcons.Search,
                    contentDescription = "搜索账单",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (filter == BillFilter.MONTH) "本月" else "全部",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        // 本月 / 全部 切换
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = filter == BillFilter.MONTH,
                onClick = { filter = BillFilter.MONTH },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("本月") }
            SegmentedButton(
                selected = filter == BillFilter.ALL,
                onClick = { filter = BillFilter.ALL },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("全部") }
        }
        Spacer(Modifier.height(12.dp))

        Text(
            "长按账单可复制或删除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        if (items.isEmpty()) {
            EmptyBills(onAdd = onAdd, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is BillItem.MonthHeader -> MonthHeaderRow(item)
                        is BillItem.DayHeader -> DayHeaderRow(item)
                        is BillItem.Row -> TxRow(
                            item = item.tx,
                            onCopy = onCopy,
                            onRequestDelete = { pendingDelete = it },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    // 删除确认：本机立刻删，云端历史备份不受影响。
    pendingDelete?.let { victim ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这笔账单？") },
            text = {
                Text(
                    (if (victim.tx.isExpense) "支出" else "收入") + " ¥" + money(victim.tx.amountCents) +
                        "，删除后本机立即消失，云端历史备份不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(victim.tx.id)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 月份组头：9月 + 支出 ¥x / 收入 ¥y。 */
@Composable
private fun MonthHeaderRow(m: BillItem.MonthHeader) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            m.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        if (m.expenseCents > 0) {
            Text(
                "支出 ¥" + money(m.expenseCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.expense,
            )
        }
        if (m.incomeCents > 0) {
            if (m.expenseCents > 0) Spacer(Modifier.width(12.dp))
            Text(
                "收入 ¥" + money(m.incomeCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.income,
            )
        }
    }
}

/** 日期头：今天 / 昨天 / 9月12日 周五 + 当天合计。 */
@Composable
private fun DayHeaderRow(d: BillItem.DayHeader) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            d.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (d.expenseCents > 0) {
            Text(
                "支出 -" + money(d.expenseCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.expense,
            )
        }
        if (d.incomeCents > 0) {
            if (d.expenseCents > 0) Spacer(Modifier.width(8.dp))
            Text(
                "收入 +" + money(d.incomeCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.income,
            )
        }
    }
}

/** 单条记录行：与 HomeScreen.TxRow 风格一致；长按弹「复制一笔 / 删除」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TxRow(
    item: TxWithCategory,
    onCopy: (TxWithCategory) -> Unit,
    onRequestDelete: (TxWithCategory) -> Unit,
) {
    val tx = item.tx
    val cat = item.category
    val tint = if (tx.isExpense) JizhangTheme.colors.expense else JizhangTheme.colors.income
    // 无分类：灰底 + 「未」字（复用 HomeScreen 的未选中灰）
    val avatarBg = cat?.let { Color(it.color) } ?: Color(0xFF9CA0AB)
    val avatarText = cat?.name?.take(1) ?: "未"
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { },
                    onLongClick = { menuOpen = true },
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(avatarBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    avatarText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cat?.name ?: "未分类",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (tx.note.isNotBlank()) {
                    Text(
                        tx.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                (if (tx.isExpense) "-" else "+") + money(tx.amountCents),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = tint,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("复制一笔") },
                onClick = {
                    menuOpen = false
                    onCopy(item)
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    menuOpen = false
                    onRequestDelete(item)
                },
            )
        }
    }
}

/** 空状态（无数据或筛选后为空）：居中提示 + 记一笔按钮。 */
@Composable
private fun EmptyBills(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "还没有账单，点下面的 + 记一笔",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAdd,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(JizhangIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("记一笔", fontSize = 15.sp)
            }
        }
    }
}
