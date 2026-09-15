package com.jianji.jizhang.ui.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.centsToYuan
// isTransfer / nonTransfers 是 data 包的顶层扩展，不 import 会「找不到成员」直接编译失败
// （本文件 aggregateByCategory 早就在用 isTransfer，此前一直缺这条 import）。
// 判断转账只允许走 isTransfer，别在导出里再写一遍 toAccountId.isNotBlank()。
import com.jianji.jizhang.data.isTransfer
import com.jianji.jizhang.data.monthRangeMillis
import com.jianji.jizhang.data.nonTransfers
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.text.Charsets

/* ---------------- 数据 → 文本 ---------------- */

private fun escapeCsv(field: String): String {
    val needsQuote = field.contains(',') || field.contains('"') ||
        field.contains('\n') || field.contains('\r')
    return if (needsQuote) "\"" + field.replace("\"", "\"\"") + "\"" else field
}

private fun csvRow(vararg fields: String): String =
    fields.joinToString(",") { escapeCsv(it) } + "\r\n"

/** 分类聚合：按金额降序，返回 (分类名, 分, 笔数)。转账不计入任何分类（恒 isExpense，需显式排除）。 */
private fun aggregateByCategory(
    list: List<TxWithCategory>,
    isExpense: Boolean,
): List<Triple<String, Long, Int>> {
    val amount = LinkedHashMap<String, Long>()
    val count = LinkedHashMap<String, Int>()
    for (item in list) {
        if (item.tx.isTransfer || item.tx.isExpense != isExpense) continue
        val name = item.category?.name ?: "未分类"
        amount[name] = (amount[name] ?: 0L) + item.tx.amountCents
        count[name] = (count[name] ?: 0) + 1
    }
    return amount.entries
        .sortedByDescending { it.value }
        .map { Triple(it.key, it.value, count[it.key] ?: 0) }
}

private fun percentOf(part: Long, total: Long): String =
    if (total <= 0L) "0.0%"
    else String.format(Locale.CHINA, "%.1f%%", part * 100.0 / total)

/**
 * 明细 CSV：一行流水一行。
 *
 * 「类型」列必须把转账显式写成「转账」：转账的 isExpense 恒 true，若照旧输出「支出」，
 * 这份文件被重新导入（BillParser）或被人在 Excel 里做透视时，转账会被当成消费，
 * 支出直接灌水。「转入账户」列让转账的两个方向都可读，文件才算把转账说清楚。
 */
private fun buildDetailCsv(
    list: List<TxWithCategory>,
    accountNames: Map<String, String>,
): String {
    val sb = StringBuilder()
    sb.append('﻿') // UTF-8 BOM：Excel 识别中文必需
    sb.append(csvRow("日期", "账户", "类型", "分类", "金额", "备注", "转入账户"))
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    for (item in list) {
        val tx = item.tx
        val typeText = when {
            tx.isTransfer -> "转账"
            tx.isExpense -> "支出"
            else -> "收入"
        }
        // 转账不占用任何分类（categoryId 恒空串），用「—」占位而不是「未分类」：
        // 后者是普通行「漏选分类」的真实状态，混在一起就没法在表格里筛出待补录的行。
        val categoryText = if (tx.isTransfer) "—" else (item.category?.name ?: "未分类")
        // 转入账户给名字不给 id（人读不懂 id）；查不到 = 该账户后来被删了，
        // 写明「已删除账户」，将来对不平账时这一行就是线索。非转账留空，别写「—」：
        // 空串才是「此列不适用」的默认形态，占位符会干扰按列筛选。
        val toAccountText = if (tx.isTransfer) {
            accountNames[tx.toAccountId] ?: "已删除账户"
        } else ""
        sb.append(csvRow(
            sdf.format(Date(tx.dateTime)),
            accountNames[tx.accountId] ?: "未选择",
            typeText,
            categoryText,
            String.format(Locale.CHINA, "%.2f", tx.amountCents / 100.0),
            tx.note,
            toAccountText,
        ))
    }
    return sb.toString()
}

/**
 * 统计 CSV：概览 + 分类聚合（笔数、占比），与明细表分开成两个文件，
 * 便于直接存档或喂给表格软件做透视。
 *
 * 收支口径 = [nonTransfers]：转账是左口袋进右口袋，进了收入/支出/结余，
 * 这份统计就全错了。分类聚合由 aggregateByCategory 内部显式排除转账。
 */
private fun buildStatsCsv(label: String, list: List<TxWithCategory>): String {
    val normal = list.nonTransfers()
    val transfers = list.filter { it.tx.isTransfer }
    val transferCents = transfers.sumOf { it.tx.amountCents }
    val expense = normal.sumOf { if (it.tx.isExpense) it.tx.amountCents else 0L }
    val income = normal.sumOf { if (!it.tx.isExpense) it.tx.amountCents else 0L }
    val yuan = { cents: Long -> String.format(Locale.CHINA, "%.2f", cents / 100.0) }
    val sb = StringBuilder()
    sb.append('﻿')
    sb.append(csvRow("范围", label))
    sb.append(csvRow("笔数", list.size.toString()))
    sb.append(csvRow("收入", yuan(income)))
    sb.append(csvRow("支出", yuan(expense)))
    sb.append(csvRow("结余", yuan(income - expense)))
    // 即使 0 笔也照写这一行：概览里的「笔数」含转账，这行交代差额去哪了，
    // 顺便声明「上面的收支合计不含转账」的口径 —— 0 也要说得出口。
    sb.append(csvRow("转账", "${transfers.size} 笔 ¥${yuan(transferCents)}"))
    sb.append("\r\n")
    sb.append(csvRow("类型", "分类", "金额", "笔数", "占比"))
    for ((isExpense, typeName, total) in listOf(
        Triple(true, "支出", expense),
        Triple(false, "收入", income),
    )) {
        for ((name, cents, n) in aggregateByCategory(list, isExpense)) {
            sb.append(csvRow(typeName, name, yuan(cents), n.toString(), percentOf(cents, total)))
        }
    }
    return sb.toString()
}

/** 复制报告：给微信/备忘录贴的纯文本摘要。口径与统计 CSV 一致（收支不含转账）。 */
private fun buildReportText(label: String, list: List<TxWithCategory>): String {
    val normal = list.nonTransfers()
    val transfers = list.filter { it.tx.isTransfer }
    val expense = normal.sumOf { if (it.tx.isExpense) it.tx.amountCents else 0L }
    val income = normal.sumOf { if (!it.tx.isExpense) it.tx.amountCents else 0L }
    val transferCents = transfers.sumOf { it.tx.amountCents }
    val sb = StringBuilder()
    sb.append("【简记】账单报告 · ").append(label).append('\n')
    sb.append("支出 ¥").append(centsToYuan(expense))
        .append(" ｜ 收入 ¥").append(centsToYuan(income))
        .append(" ｜ 结余 ¥").append(centsToYuan(income - expense))
        .append(" ｜ ").append(list.size).append(" 笔\n")
    // 转账单独一行摘要：混进上面的合计会虚增支出，完全不提又让对账的人
    // 找不到「左口袋倒右口袋」的那几笔去哪了。与统计 CSV 同理，0 笔也写明。
    sb.append("转账 ").append(transfers.size).append(" 笔 ¥").append(centsToYuan(transferCents))
        .append("（账户间互转，不计收支）\n")
    for ((isExpense, typeName) in listOf(true to "支出", false to "收入")) {
        val slices = aggregateByCategory(list, isExpense)
        if (slices.isEmpty()) continue
        val total = slices.sumOf { it.second }
        sb.append("\n").append(typeName).append("构成：\n")
        slices.forEachIndexed { i, (name, cents, n) ->
            sb.append(i + 1).append(". ").append(name)
                .append(" ¥").append(centsToYuan(cents))
                .append("（").append(percentOf(cents, total)).append(" · ").append(n).append(" 笔）\n")
        }
    }
    return sb.toString().trimEnd()
}

/* ---------------- 页面 ---------------- */

private enum class ExportScope { ALL, THIS_MONTH, PICK }

private fun ymLabel(year: Int, month: Int): String =
    String.format(Locale.CHINA, "%d年%d月", year, month + 1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    all: List<TxWithCategory>,
    accounts: List<AccountEntity>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()
    var scope by remember { mutableStateOf(ExportScope.ALL) }
    var pick by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var resultText by remember { mutableStateOf("") }

    // 有数据的年月，倒序（新 → 旧）
    val monthOptions = remember(all) {
        val cal = Calendar.getInstance()
        all.map {
            cal.timeInMillis = it.tx.dateTime
            cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
        }.distinct().sortedWith(
            compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second }
        )
    }
    val effectivePick = pick ?: monthOptions.firstOrNull()
        ?: run {
            val now = Calendar.getInstance()
            now.get(Calendar.YEAR) to now.get(Calendar.MONTH)
        }

    val nowCal = Calendar.getInstance()
    val (startMs, endMs) = when (scope) {
        ExportScope.ALL -> Long.MIN_VALUE to Long.MAX_VALUE
        ExportScope.THIS_MONTH -> monthRangeMillis(
            nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH)
        )
        ExportScope.PICK -> monthRangeMillis(effectivePick.first, effectivePick.second)
    }
    val list = remember(all, startMs, endMs) {
        if (startMs == Long.MIN_VALUE) all
        else all.filter { it.tx.dateTime in startMs..endMs }
    }
    val scopeLabel = when (scope) {
        ExportScope.ALL -> "全部"
        ExportScope.THIS_MONTH -> "本月"
        ExportScope.PICK -> ymLabel(effectivePick.first, effectivePick.second)
    }
    val fileTag = when (scope) {
        ExportScope.ALL -> "all"
        ExportScope.THIS_MONTH -> "this-month"
        ExportScope.PICK -> String.format(
            Locale.CHINA, "%d%02d", effectivePick.first, effectivePick.second + 1
        )
    }

    // 顶部摘要与两份导出文件用同一口径（nonTransfers）：界面说一套、文件里另一套，
    // 用户按界面上的数字去核导出的表就会对不上。转账单独计数放在句尾。
    val normal = remember(list) { list.nonTransfers() }
    val expense = normal.sumOf { if (it.tx.isExpense) it.tx.amountCents else 0L }
    val income = normal.sumOf { if (!it.tx.isExpense) it.tx.amountCents else 0L }
    val transferCount = list.size - normal.size
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }

    val detailLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = buildDetailCsv(list, accountNames)
        ioScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    // 返回 null 必须当失败：否则用户看到「已导出」但文件是空的。
                    val os = context.contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("系统未提供可写的文件流")
                    os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                "已导出明细 ${list.size} 笔 · $scopeLabel"
            } catch (e: Exception) {
                "导出失败：${e.javaClass.simpleName}: ${e.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            resultText = msg
        }
    }
    val statsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = buildStatsCsv(scopeLabel, list)
        ioScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val os = context.contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("系统未提供可写的文件流")
                    os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                "已导出统计表 · $scopeLabel"
            } catch (e: Exception) {
                "导出失败：${e.javaClass.simpleName}: ${e.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            resultText = msg
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        // 顶栏：返回 + 标题。与搜索页同款（此前这里用的是 Material 的 ArrowBack，
        // 笔画粗细与自绘图标集不一致，是全局唯一一处混用）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    JizhangIcons.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                "导出数据",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(12.dp))

        // 范围切换：配色与全 App 的分段控件统一走语义令牌。
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val items = listOf(
                ExportScope.ALL to "全部",
                ExportScope.THIS_MONTH to "本月",
                ExportScope.PICK to "选择月份",
            )
            items.forEachIndexed { i, (s, label) ->
                SegmentedButton(
                    selected = scope == s,
                    onClick = { scope = s },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
                    colors = if (scope == s) {
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        SegmentedButtonDefaults.colors()
                    },
                ) {
                    Text(label)
                }
            }
        }

        // 「选择月份」时列出有数据的月份供点选
        if (scope == ExportScope.PICK) {
            Spacer(Modifier.height(10.dp))
            if (monthOptions.isEmpty()) {
                Text(
                    "还没有任何记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    monthOptions.forEach { (y, m) ->
                        val selected = y == effectivePick.first && m == effectivePick.second
                        AssistChip(
                            onClick = { pick = y to m },
                            label = { Text(ymLabel(y, m)) },
                            colors = if (selected) {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            } else {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors()
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "$scopeLabel · 共 ${list.size} 笔 · " +
                "支出 ¥${centsToYuan(expense)} · 收入 ¥${centsToYuan(income)} · " +
                "结余 ¥${centsToYuan(income - expense)} · 转账 $transferCount 笔",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(24.dp))

        Button(
            enabled = list.isNotEmpty(),
            onClick = {
                detailLauncher.launch("jianji-export-$fileTag.csv")
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = "导出明细 CSV",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            enabled = list.isNotEmpty(),
            onClick = {
                statsLauncher.launch("jianji-stats-$fileTag.csv")
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = "导出统计 CSV",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            enabled = list.isNotEmpty(),
            onClick = {
                val text = buildReportText(scopeLabel, list)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("简记报告", text))
                val msg = "已复制 $scopeLabel 报告（${list.size} 笔）"
                Toast.makeText(context, "报告已复制到剪贴板", Toast.LENGTH_SHORT).show()
                resultText = msg
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = "复制报告文本",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "CSV 已带 UTF-8 BOM，Excel 可直接打开；明细的类型列会标出「转账」并附「转入账户」列，" +
                "统计表收支合计不含转账、转账单独一行，分类聚合（金额、笔数、占比）同样只算真实收支。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (resultText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodySmall,
                color = if (resultText.startsWith("已") || resultText.startsWith("报告")) {
                    JizhangTheme.colors.income
                } else {
                    JizhangTheme.colors.expense
                }
            )
        }
    }
}
