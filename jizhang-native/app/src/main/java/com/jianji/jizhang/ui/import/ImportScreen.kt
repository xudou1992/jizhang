package com.jianji.jizhang.ui.import

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.importing.BillFormat
import com.jianji.jizhang.data.importing.BillParser
import com.jianji.jizhang.data.importing.ImportedTx
import com.jianji.jizhang.data.importing.ImportedTxDraft
import com.jianji.jizhang.data.importing.toDrafts
import com.jianji.jizhang.ui.components.AccountSelector
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 账单导入页：微信 / 支付宝 / 简记自己导出的 CSV → 简记的账本。
 *
 * 界面节奏是「选来源 → 选文件 → 预览 → 导入」，压在两屏状态里，
 * 中间没有任何一步直接写库：所有落库都通过 [onImport] 交回调用方。
 * 为什么这样切：解析与写库解耦后，这一页可以单独测、单独换（将来加
 * 「按对方账户分流」「重复检测」都不用动 ViewModel），而批量写库的时机、
 * 事务、自动备份全归 ViewModel 管 —— 与它逐笔记账的 addTx 职责分得干净。
 *
 * 视觉照抄 ExportScreen：顶栏自绘返回 + 标题、16dp 横向留白、52dp 高按钮、
 * 语义色走 JizhangTheme.colors（支出红 / 收入绿），不自造颜色。
 */

/** 只有两屏：① 选来源/选文件，② 预览。中间态用 busy/error 变量表达，不再加枚举分支。 */
private enum class ImportStep { SOURCE, PREVIEW }

/** 一次解析的全部产物。[charsetName] 参与展示：读错编码是这类功能最常见的故障，得让用户看见。 */
private class ParsedBill(
    val chosen: BillFormat,
    val detected: BillFormat,
    val charsetName: String,
    val rows: List<ImportedTx>,
)

/** 预览最多列这么多条：再多一屏也看不完，还白白拖慢重组（导入本身不受此限制）。 */
private const val PREVIEW_LIMIT = 20

/** 预览的一行：账单原文（给对方/分类/备注用）配它换算好的草稿（给金额用）。成对出现才不会错位。 */
private typealias PreviewEntry = Pair<ImportedTx, ImportedTxDraft>

@Composable
fun ImportScreen(
    onBack: () -> Unit,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onImport: (List<ImportedTxDraft>) -> Unit = {},
) {
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()

    var step by remember { mutableStateOf(ImportStep.SOURCE) }
    var source by remember { mutableStateOf(BillFormat.WECHAT) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ParsedBill?>(null) }
    var targetAccount by remember { mutableStateOf(accounts.firstOrNull()?.id ?: "") }
    /** >= 0 = 已经导入过：按钮据此禁用，免得连点两下把同一个文件灌两遍。 */
    var importedCount by remember { mutableStateOf(-1) }

    // accounts 来自 StateFlow，首帧很可能是空表；等它到了要补默认账户，
    // 否则 AccountSelector 一个都渲染不出来、导入时 accountId 还是空串。
    LaunchedEffect(accounts, targetAccount) {
        if (accounts.none { it.id == targetAccount }) {
            targetAccount = accounts.firstOrNull()?.id ?: ""
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // 用户按「取消」时 uri 为 null：这不是错误，不该弹错误卡。
        if (uri == null) return@rememberLauncherForActivityResult
        val chosen = source
        busy = true
        error = null
        resultText = ""
        importedCount = -1
        ioScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("系统未提供可读的文件流")
                    val bytes = stream.use { it.readBytes() }
                    val parser = BillParser(bytes)
                    ParsedBill(
                        chosen = chosen,
                        detected = parser.detected,
                        charsetName = parser.usedCharset.name(),
                        rows = parser.parse(chosen).getOrThrow(),
                    )
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { bill ->
                    parsed = bill
                    step = ImportStep.PREVIEW
                },
                onFailure = { e ->
                    parsed = null
                    step = ImportStep.SOURCE
                    error = describeError(e)
                },
            )
        }
    }

    val rows = parsed?.rows ?: emptyList()
    // 预览与待导入必须来自同一份配对：drafts 会滤掉金额为 0 的行，
    // 若各自按下标取用，中间少一行就会让后面每条显示金额都错位。
    val entries = remember(rows) {
        rows.zip(rows.toDrafts()) { row, draft -> row to draft }
            .filter { entry -> entry.second.amountCents > 0L }
    }
    val dropped = rows.size - entries.size
    val expenseCents = entries.sumOf { entry -> if (entry.second.expense) entry.second.amountCents else 0L }
    val incomeCents = entries.sumOf { entry -> if (entry.second.expense) 0L else entry.second.amountCents }
    // 重名分类保留 orderNum 更靠前的那个（传入的 categories 已按 orderNum 升序）。
    val categoryIdsByName = remember(categories) {
        val map = HashMap<String, String>(categories.size * 2)
        for (c in categories) map.putIfAbsent(c.name, c.id)
        map
    }

    /** 真正交给调用方的那一步。账户为空时直接报错，不静默提交。 */
    fun commit() {
        if (targetAccount.isBlank()) {
            error = "还没有可用的账户：请先在「设置 → 账户管理」建一个账户再导入。"
            return
        }
        val resolved = entries.map { entry ->
            entry.second.copy(
                categoryId = categoryIdsByName[entry.second.categoryName] ?: "",
                // 账户选择的结果必须随草稿一起交出去，否则调用方只能瞎猜，
                // 用户在预览页挑的账户就等于白挑。
                accountId = targetAccount,
            )
        }
        val unmatched = resolved.count { it.categoryId.isBlank() }
        onImport(resolved)
        importedCount = resolved.size
        Toast.makeText(context, "已导入 ${resolved.size} 笔", Toast.LENGTH_SHORT).show()
        resultText = if (unmatched > 0) {
            "已导入 ${resolved.size} 笔，其中 $unmatched 笔的分类在简记里不存在，" +
                "先记成「未分类」，之后可在账单列表里逐笔改。"
        } else {
            "已导入 ${resolved.size} 笔，全部落在所选账户。"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(4.dp))
        ImportHeader(onBack = onBack)
        Spacer(Modifier.height(12.dp))

        SourceChips(selected = source, enabled = !busy && step == ImportStep.SOURCE) {
            source = it
        }

        if (step == ImportStep.SOURCE) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = sourceGuidance(source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = !busy,
                onClick = {
                    error = null
                    // 只写 text/* 会让不少文件管理器把 .csv 报成 octet-stream、整个列表变空，
                    // 所以照备份页的做法放宽；真选错了格式由解析器给出可读错误。
                    openLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (busy) "正在读取…" else "选择账单文件",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "解析全程在本地完成，不上传任何数据。导入前会先预览，确认后才写库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PreviewPage(
                bill = parsed,
                entries = entries,
                totalRows = rows.size,
                dropped = dropped,
                expenseCents = expenseCents,
                incomeCents = incomeCents,
                accounts = accounts,
                targetAccount = targetAccount,
                importedCount = importedCount,
                onPickAccount = { targetAccount = it },
                onImport = { commit() },
                onPickAgain = {
                    parsed = null
                    step = ImportStep.SOURCE
                    error = null
                    resultText = ""
                    importedCount = -1
                },
            )
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            ErrorCard(it)
        }

        if (resultText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodySmall,
                color = if (resultText.startsWith("已")) {
                    JizhangTheme.colors.income
                } else {
                    JizhangTheme.colors.expense
                },
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/* ---------------- 预览屏 ---------------- */

@Composable
private fun PreviewPage(
    bill: ParsedBill?,
    entries: List<PreviewEntry>,
    totalRows: Int,
    dropped: Int,
    expenseCents: Long,
    incomeCents: Long,
    accounts: List<AccountEntity>,
    targetAccount: String,
    importedCount: Int,
    onPickAccount: (String) -> Unit,
    onImport: () -> Unit,
    onPickAgain: () -> Unit,
) {
    // 自动识别徽章：识别结果优先于用户选的来源。两者不一致时必须写清楚按哪个解的，
    // 否则「我明明选的微信，怎么少了两列」根本无从解释。
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormatBadge(bill?.detected ?: BillFormat.UNKNOWN)
        Text(
            text = "按 ${bill?.charsetName ?: "?"} 解码",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (bill != null && bill.detected != bill.chosen && bill.detected != BillFormat.UNKNOWN) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "你选的来源是「${bill.chosen.label}」，但表头更像「${bill.detected.label}」账单，" +
                "已按识别结果解析。",
            style = MaterialTheme.typography.bodySmall,
            color = JizhangTheme.colors.transfer,
        )
    }

    Spacer(Modifier.height(12.dp))
    Text(
        text = "待导入 ${entries.size} 笔 · 支出 ¥${centsToYuan(expenseCents)} · " +
            "收入 ¥${centsToYuan(incomeCents)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    if (dropped > 0) {
        Text(
            text = "另有 $dropped 行金额为 0 或读不出数字，不会写入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    Text(
        text = "导入到账户",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (accounts.isEmpty()) {
        ErrorCard("还没有任何账户：请先在「设置 → 账户管理」新建一个账户，再回来导入。")
    } else {
        // AccountSelector 在只有一个账户时会自己隐藏，所以账户名要另外写一行，
        // 否则用户看不见这些账要落到哪里。
        Text(
            text = accounts.firstOrNull { it.id == targetAccount }?.name ?: "未选择",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        AccountSelector(
            accounts = accounts,
            selectedId = targetAccount,
            onSelect = onPickAccount,
        )
    }

    Spacer(Modifier.height(16.dp))
    val preview = entries.take(PREVIEW_LIMIT)
    Text(
        text = if (entries.size > PREVIEW_LIMIT) {
            "预览前 $PREVIEW_LIMIT 条 · 共 ${entries.size} 条"
        } else {
            "预览"
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (preview.isEmpty()) {
                Text(
                    text = if (totalRows > 0) {
                        "这些记录全部被剔除规则挡掉了（金额为 0），没有可写入的一条。"
                    } else {
                        "文件里没有任何数据行。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            preview.forEachIndexed { index, entry ->
                PreviewRow(entry = entry, index = index)
                if (index < preview.size - 1) Hairline()
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Button(
        // 没账户、没数据、已经导过 —— 都不许点。空账户会让 ViewModel 直接拒绝写入，
        // 用户点了毫无反应比明确报错更糟。
        enabled = importedCount < 0 && entries.isNotEmpty() && targetAccount.isNotBlank(),
        onClick = onImport,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            text = if (importedCount >= 0) "已导入 $importedCount 笔" else "导入 ${entries.size} 笔",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onPickAgain,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(text = "重新选择文件", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 一行预览：左侧「时间 + 对方 + 分类/备注」，右侧带方向符号的金额。
 * 支出红、收入绿，与账单列表的观感一致。
 */
@Composable
private fun PreviewRow(entry: PreviewEntry, index: Int) {
    val (item, draft) = entry
    val color = if (item.expense) JizhangTheme.colors.expense else JizhangTheme.colors.income
    val timeFmt = remember { SimpleDateFormat("MM月d日 HH:mm", Locale.CHINA) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号：核对笔数时不用自己数，导错了要删也知道删哪条
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = timeFmt.format(Date(item.millis)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.counterparty.ifBlank { item.categoryHint.ifBlank { "（无交易对方）" } },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 分类线索与备注都放第二行：预览时最常见的问题是「分类会不会被记错」
            val tail = listOf(item.categoryHint, item.note)
                .filter { part -> part.isNotBlank() }
                .distinct()
                .joinToString(" · ")
            if (tail.isNotEmpty()) {
                Text(
                    text = tail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = (if (item.expense) "-" else "+") + centsToYuan(draft.amountCents),
            // 金额必须等宽数字（主题的 display/headline 档都是这么做的），
            // 否则右列的「1,200.00」和「21.00」会对不齐。TextStyle 才有这个属性，
            // Text() 上没有，所以要 copy 一份。
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/* ---------------- 通用件 ---------------- */

/** 顶栏：与导出页/搜索页同一套（自绘返回箭头，不混用 Material 图标）。 */
@Composable
private fun ImportHeader(onBack: () -> Unit) {
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
            "导入账单",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SourceChips(
    selected: BillFormat,
    enabled: Boolean,
    onSelect: (BillFormat) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(BillFormat.WECHAT, BillFormat.ALIPAY, BillFormat.JIZHANG).forEach { format ->
            val isOn = format == selected
            AssistChip(
                enabled = enabled,
                onClick = { onSelect(format) },
                label = { Text(format.label) },
                colors = if (isOn) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
            )
        }
    }
}

@Composable
private fun FormatBadge(format: BillFormat) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = "自动识别：${format.label}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 错误卡：文案自己说明「哪儿不行 + 为什么 + 下一步」。 */
@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    JizhangIcons.Empty,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "没能导入",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** 1dp 分隔线：material3 1.2.1 还没有 HorizontalDivider，直接画。 */
@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/* ---------------- 文案与错误 ---------------- */

private fun sourceGuidance(source: BillFormat): String = when (source) {
    BillFormat.WECHAT ->
        "微信：我 → 服务 → 钱包 → 账单 → 右上角「…」→ 常见问题 → 下载账单。" +
            "账单会以邮件发来，附件是 zip —— 先在手机上解压（解压密码在邮件正文里）再选进来。"
    BillFormat.ALIPAY ->
        "支付宝：我的 → 账单 → 右上角「…」→ 下载账单 → 用于个人对账，选 CSV 并发送到邮箱。" +
            "老版本导出是 GBK 编码，这一页会自动判别，不用手工转码。"
    BillFormat.JIZHANG ->
        "简记：用「导出数据」生成的明细 CSV（表头为 日期,账户,类型,分类,金额,备注）。" +
            "转账行会被跳过 —— 当前导出格式里没有转入账户这一列，硬导入只会变成一笔支出。"
    BillFormat.UNKNOWN -> ""
}

/** 解析器的失败信息本来就是给人看的整句话，直接照用；其他异常才补类名，方便回报。 */
private fun describeError(e: Throwable): String = when (e) {
    is IllegalArgumentException -> e.message ?: "这份文件读不出账单。"
    else -> "读取失败：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}"
}
