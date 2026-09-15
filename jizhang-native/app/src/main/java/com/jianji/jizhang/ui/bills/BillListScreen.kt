package com.jianji.jizhang.ui.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
// `Modifier.weight` 是 RowScope/ColumnScope 的成员，不能 import。
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.isTransfer
import com.jianji.jizhang.data.monthEndMillis
import com.jianji.jizhang.data.monthStartMillis
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.NeutralAvatar
import com.jianji.jizhang.ui.theme.readableOn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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

/** 「yyMM 数字键」→「2026年8月」，跳月按钮与月份弹层共用。 */
private fun formatYm(ymKey: Long): String = "${ymKey / 100}年${ymKey % 100}月"

/** 懒加载扁平列表的元素：月份头 / 天头 / 交易行。 */
private sealed interface BillItem {
    val key: String
    data class MonthHeader(
        override val key: String,
        /** year*100+month 编码，用于「锚点月」显示与跳月定位（label 只有「9月」，跨年会歧义）。 */
        val ymKey: Long,
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
        if (item.tx.isTransfer) {
            // 转账既非支出也非收入（恒 isExpense=true 只是存储约定），不计入月/日小计，
            // 否则「本月支出」会把还款/互转也累进去，虚高一倍。
            m.transfer += item.tx.amountCents
            d.transfer += item.tx.amountCents
        } else if (item.tx.isExpense) {
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
            out += BillItem.MonthHeader("m_$mKey", mKey, m.label, m.expense, m.income)
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
    var transfer: Long = 0L
}

private class DayAcc(var label: String) {
    var expense: Long = 0L
    var income: Long = 0L
    var transfer: Long = 0L
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BillListScreen(
    all: List<TxWithCategory>,
    onAdd: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTx: (TxWithCategory) -> Unit,
    onCopy: (TxWithCategory) -> Unit,
    onDelete: (String) -> Unit,
    /**
     * 批量删除。主控在 ViewModel 里逐条做撤销快照后再回调删除，
     * UI 只负责收集 id 并调用；保留默认值避免调用点来不及接线。
     * 注意：[onDelete]（单条）目前在本屏没有触发入口了 —— 长按已改为一键多选，
     * 单条删除请走详情页；参数保留只为兼容主控既有调用点。
     */
    onDeleteMany: (List<String>) -> Unit = {},
) {
    // 筛选状态 + 分组结果都缓存：600+ 条只在 all / filter 变化时重算
    var filter by remember { mutableStateOf(BillFilter.ALL) }
    // remember 的 key 里带上「今天日期字符串」：跨零点后的第一次重组里 todayKey 变化，
    // 「今天/昨天」标签与「本月」上下界才会按新的一天重算（原来只 key all/filter，会挂一整天旧标签）。
    val todayKey = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Calendar.getInstance().time)
    val items = remember(all, filter, todayKey) {
        val today = Calendar.getInstance()
        val scoped = if (filter == BillFilter.MONTH) {
            // 上下界都要有：只判 >= 会把未来月份也算进「本月」（补记功能一上线就会踩到）。
            all.filter { it.tx.dateTime in monthStartMillis()..monthEndMillis() }
        } else {
            all
        }
        buildItems(scoped, today)
    }

    // ---- 批量选择 ----
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    // 当前筛选结果里的全部行 id：「全选」的目标，也是清理失效选中的依据。
    val rowIds = remember(items) { items.filterIsInstance<BillItem.Row>().map { it.tx.tx.id } }
    // 数据被别处删掉 / 切换筛选后，剔除已经不在列表里的选中项，避免「已选 N 项」虚高。
    LaunchedEffect(rowIds) {
        selectedIds.retainAll(rowIds.toSet())
        if (selectedIds.isEmpty()) selectionMode = false
    }
    // 长按行的「复制一笔」入口已让位给多选；onCopy 仍由主控接线，这里不再调用。
    // （onCopy / onDelete 参数保留是为了不破坏 public 签名，见函数注释。）

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 每个「年月的第一条月份头」在扁平列表里的下标：跳月时 scrollToItem 用。
    val monthIndex = remember(items) {
        LinkedHashMap<Long, Int>().apply {
            items.forEachIndexed { i, it ->
                if (it is BillItem.MonthHeader) putIfAbsent(it.ymKey, i)
            }
        }
    }
    // 锚点月 = 当前第一条可见行往上最近的月份头。粘性天头会把月份头推下屏，
    // 月上下文就靠顶栏这颗按钮兜住（滚动时实时跟随）。
    val anchorYm by remember(items) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull() ?: return@derivedStateOf null
            for (i in first.index downTo 0) {
                val it = items.getOrNull(i)
                if (it is BillItem.MonthHeader) return@derivedStateOf it.ymKey
            }
            null
        }
    }

    var monthPickerOpen by remember { mutableStateOf(false) }
    var confirmDeleteMany by remember { mutableStateOf(false) }

    // 选择模式下系统返回键先退出多选，而不是直接退出账单页。
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds.clear()
    }

    val allSelected = rowIds.isNotEmpty() && selectedIds.size == rowIds.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        if (selectionMode) {
            // 多选工具条：替换普通顶栏，聚焦「选了多少 / 全选 / 删」。
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    selectionMode = false
                    selectedIds.clear()
                }) {
                    Icon(
                        JizhangIcons.Close,
                        contentDescription = "退出选择",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "已选 ${selectedIds.size} 项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (allSelected) {
                        selectedIds.clear()
                    } else {
                        selectedIds.clear()
                        selectedIds.addAll(rowIds)
                    }
                }) {
                    Text(if (allSelected) "取消全选" else "全选")
                }
                // 选中恰好一条时提供「复制一笔」—— 长按菜单让位给多选后，
                // 这是复制入口的唯一回归路径（预填金额/分类/账户，日期取今天）。
                if (selectedIds.size == 1) {
                    TextButton(onClick = {
                        val only = items.filterIsInstance<BillItem.Row>()
                            .firstOrNull { it.tx.tx.id == selectedIds.first() }
                        if (only != null) {
                            selectionMode = false
                            selectedIds.clear()
                            onCopy(only.tx)
                        }
                    }) { Text("复制一笔") }
                }
                TextButton(onClick = { confirmDeleteMany = true }) {
                    Icon(
                        JizhangIcons.Trash,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
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
            // 快速跳月：只显示当前锚点月；「本月」筛选下只可能有一个月份，按钮无意义就藏掉。
            if (monthIndex.size > 1 && anchorYm != null) {
                TextButton(
                    onClick = { monthPickerOpen = true },
                    modifier = Modifier.padding(top = 6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        JizhangIcons.Calendar,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatYm(anchorYm!!),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.width(2.dp))
                    // 自绘图标集没有向下的 V，把 ChevronRight 顺时针转 90° 代用。
                    Icon(
                        JizhangIcons.ChevronRight,
                        contentDescription = "跳转月份",
                        modifier = Modifier.size(14.dp).rotate(90f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "点按查看详情，长按进入多选",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))

        if (items.isEmpty()) {
            EmptyBills(onAdd = onAdd, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
            ) {
                // 逐条展开（items() 本身就是 forEach）：月/天组头要走 stickyHeader，
                // 不能在 items() 的内容 lambda 里条件生成。
                for (element in items) {
                    when (element) {
                        // 两级粘性：天头上滚到顶会把月份头顶出屏（LazyColumn 粘性不堆叠），
                        // 月上下文由顶栏「锚点月」按钮接管。组头必须自绘不透明背景，否则内容穿透。
                        is BillItem.MonthHeader -> stickyHeader(
                            key = element.key,
                            contentType = "month_header",
                        ) {
                            MonthHeaderRow(element)
                        }

                        is BillItem.DayHeader -> stickyHeader(
                            key = element.key,
                            contentType = "day_header",
                        ) {
                            DayHeaderRow(element)
                        }

                        is BillItem.Row -> item(
                            key = element.key,
                            contentType = "bill_row",
                        ) {
                            val row = element.tx
                            val id = row.tx.id
                            TxRow(
                                item = row,
                                selectionMode = selectionMode,
                                selected = id in selectedIds,
                                onOpen = { onOpenTx(row) },
                                onToggle = {
                                    if (!selectedIds.remove(id)) selectedIds.add(id)
                                },
                                onEnterSelect = {
                                    selectionMode = true
                                    if (id !in selectedIds) selectedIds.add(id)
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    // 跳月弹层：所有有数据的月份，新→旧；选中后把列表瞬时定位到该月组头
    // （scrollToItem 而非 animate：跨年可能隔着几百条，动画滚动会晃一眼）。
    if (monthPickerOpen) {
        val months = monthIndex.keys.sortedDescending()
        AlertDialog(
            onDismissRequest = { monthPickerOpen = false },
            title = { Text("跳转到月份") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    months.forEach { ym ->
                        Text(
                            formatYm(ym),
                            style = MaterialTheme.typography.bodyLarge,
                            // 当前锚点月用主色标出来，一眼知道「现在看到哪儿了」。
                            color = if (ym == anchorYm) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    monthPickerOpen = false
                                    monthIndex[ym]?.let { idx ->
                                        scope.launch { listState.scrollToItem(idx) }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    // 批量删除确认：与单条删除同款文案；确认即调 onDeleteMany 并退出多选，
    // 撤销反馈由主控在 ViewModel 侧统一弹（UI 不猜删除结果）。
    if (confirmDeleteMany) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            title = { Text("删除这 $count 笔账单？") },
            text = {
                Text("删除后本机立即消失，云端历史备份不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.toList()
                    confirmDeleteMany = false
                    selectionMode = false
                    selectedIds.clear()
                    onDeleteMany(ids)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteMany = false }) { Text("取消") }
            },
        )
    }
}

/** 月份组头（粘性）：9月 + 支出 ¥x / 收入 ¥y。背景必须不透明，滚动内容才不透底。 */
@Composable
private fun MonthHeaderRow(m: BillItem.MonthHeader) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
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
                "支出 ¥" + centsToYuan(m.expenseCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.expense,
            )
        }
        if (m.incomeCents > 0) {
            if (m.expenseCents > 0) Spacer(Modifier.width(12.dp))
            Text(
                "收入 ¥" + centsToYuan(m.incomeCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.income,
            )
        }
    }
}

/** 日期头（粘性）：今天 / 昨天 / 9月12日 周五 + 当天合计。 */
@Composable
private fun DayHeaderRow(d: BillItem.DayHeader) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
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
                "支出 ¥" + centsToYuan(d.expenseCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.expense,
            )
        }
        if (d.incomeCents > 0) {
            if (d.expenseCents > 0) Spacer(Modifier.width(12.dp))
            Text(
                "收入 ¥" + centsToYuan(d.incomeCents),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = JizhangTheme.colors.income,
            )
        }
    }
}

/**
 * 单条记录行：点按进详情；长按进入多选模式并选中本条。
 * 原先的长按下拉菜单（复制/删除）已让位给多选：复制走详情页，删除支持批量。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TxRow(
    item: TxWithCategory,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onEnterSelect: () -> Unit,
) {
    val tx = item.tx
    val cat = item.category
    val transfer = tx.isTransfer
    val tint = when {
        transfer -> JizhangTheme.colors.transfer
        tx.isExpense -> JizhangTheme.colors.expense
        else -> JizhangTheme.colors.income
    }
    // 无分类：中性灰底 + 「未」字，走 NeutralAvatar 令牌；转账用「转」+ 中性底
    val avatarBg = if (transfer) NeutralAvatar else cat?.let { Color(it.color) } ?: NeutralAvatar
    val avatarText = if (transfer) "转" else cat?.name?.take(1) ?: "未"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                // 多选模式里点按只切换勾选，不再进详情 —— 防止「想取消勾选却点开详情」。
                onClick = { if (selectionMode) onToggle() else onOpen() },
                onLongClick = onEnterSelect,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
            )
            Spacer(Modifier.width(8.dp))
        }
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
                color = readableOn(avatarBg),
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (transfer) "转账" else (cat?.name ?: "未分类"),
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
            (if (transfer) "→ " else if (tx.isExpense) "-" else "+") + centsToYuan(tx.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
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
