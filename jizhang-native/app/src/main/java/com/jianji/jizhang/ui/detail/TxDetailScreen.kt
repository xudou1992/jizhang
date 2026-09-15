package com.jianji.jizhang.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxDraft
import com.jianji.jizhang.data.TxEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.isTransfer
import com.jianji.jizhang.data.parseYuanToCents
import com.jianji.jizhang.ui.components.AccountSelector
import com.jianji.jizhang.ui.components.DateField
import com.jianji.jizhang.ui.components.TxBillType
import com.jianji.jizhang.ui.components.TxBillTypeSelector
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.NeutralAvatar
import java.text.SimpleDateFormat
import java.util.Locale

/** 查看态用的完整日期时间。 */
private val dtFmt = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)

/** 复制文本用的纯日期。 */
private val dateFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

/** 账户 id → 名字；查不到说明账户已被删（转账行引用已删账户是允许的，不能白屏）。 */
private fun accountNameOrDeleted(accounts: List<AccountEntity>, id: String): String =
    accounts.firstOrNull { it.id == id }?.name ?: "已删除账户"

/** 转账行的人话描述：「转账 ¥35.50：微信 → 招商」，查看/删除确认/复制共用一份。 */
private fun transferLine(tx: TxEntity, accounts: List<AccountEntity>): String =
    "转账 ¥${centsToYuan(tx.amountCents)}：" +
        accountNameOrDeleted(accounts, tx.accountId) + " → " +
        accountNameOrDeleted(accounts, tx.toAccountId)

/** 组装可复制的账单文本，例如「2026年9月14日 餐饮 -35.50 午饭」。 */
private fun buildCopyText(item: TxWithCategory, accounts: List<AccountEntity>): String {
    val tx = item.tx
    val date = dateFmt.format(tx.dateTime)
    // 转账不是消费也不是收入：「-35.50 + 分类」的模板两头都是错的语义，单独走 A → B 样式。
    val base = if (tx.isTransfer) {
        "$date ${transferLine(tx, accounts)}"
    } else {
        val catName = item.category?.name ?: "未分类"
        val sign = if (tx.isExpense) "-" else "+"
        "$date $catName $sign${centsToYuan(tx.amountCents)}"
    }
    return if (tx.note.isNotBlank()) "$base ${tx.note}" else base
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxDetailScreen(
    item: TxWithCategory,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    onSave: (TxDraft) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    // 编辑态用单一开关控制；进入编辑态即带着原值
    var editing by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    // 以 tx.id 为 key 初始化，切换账单或重新进入编辑都带原值。
    // 类型三段枚举与 AddScreen 同源：转账行（isExpense=true + toAccountId 非空）
    // 若只读 isExpense 会被误判成「支出」，编辑一保存就把转账改成了普通消费。
    var type by remember(item.tx.id) {
        mutableStateOf(
            when {
                item.tx.isTransfer -> TxBillType.TRANSFER
                item.tx.isExpense -> TxBillType.EXPENSE
                else -> TxBillType.INCOME
            },
        )
    }
    var toAccountId by remember(item.tx.id) { mutableStateOf(item.tx.toAccountId) }
    var amountText by remember(item.tx.id) {
        mutableStateOf(String.format(Locale.CHINA, "%.2f", item.tx.amountCents / 100.0))
    }
    var note by remember(item.tx.id) { mutableStateOf(item.tx.note) }
    var categoryId by remember(item.tx.id) { mutableStateOf(item.tx.categoryId) }
    var accountId by remember(item.tx.id) { mutableStateOf(item.tx.accountId) }
    var dateTime by remember(item.tx.id) { mutableStateOf(item.tx.dateTime) }

    // 先把颜色提到 composable 作用域，避免在非 composable lambda 里读主题
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JizhangTheme.colors
    // 转账用中性色：红「支出」绿「收入」对一笔左口袋进右口袋的钱都是错误语义。
    val accent = when {
        item.tx.isTransfer -> semantic.transfer
        item.tx.isExpense -> semantic.expense
        else -> semantic.income
    }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        // 顶栏：返回 / 标题 / 编辑 + 删除
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                JizhangIcons.ArrowBack,
                contentDescription = "返回",
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "账单详情",
                style = MaterialTheme.typography.titleLarge,
                color = colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Row {
                Icon(
                    JizhangIcons.Edit,
                    contentDescription = "编辑",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { editing = !editing },
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    JizhangIcons.Trash,
                    contentDescription = "删除",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { showDelete = true },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        if (!editing) {
            ViewMode(
                item = item,
                accent = accent,
                accounts = accounts,
                onEdit = { editing = true },
                onCopy = {
                    val text = buildCopyText(item, accounts)
                    val cm = context.getSystemService(ClipboardManager::class.java) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("账单", text))
                    Toast.makeText(context, "账单已复制到剪贴板", Toast.LENGTH_SHORT).show()
                },
            )
        } else {
            EditMode(
                type = type,
                amountText = amountText,
                note = note,
                categoryId = categoryId,
                categories = categories,
                accounts = accounts,
                accountId = accountId,
                toAccountId = toAccountId,
                dateTime = dateTime,
                onType = { type = it },
                onAmountText = { amountText = it },
                onNote = { note = it },
                onCategory = { categoryId = it },
                onAccount = { accountId = it },
                onToAccount = { toAccountId = it },
                onDate = { dateTime = it },
                onSave = {
                    val cents = parseYuanToCents(amountText)
                    val transfer = type == TxBillType.TRANSFER
                    when {
                        cents <= 0 -> Unit
                        // 转账必选转入；转出改成了当前转入值时当场出声（选项已排除，
                        // 这种相等只能来自「先选 B 再把转出改成 B」的顺序问题）。
                        transfer && toAccountId.isBlank() -> Unit
                        transfer && toAccountId == accountId -> Toast.makeText(
                            context,
                            "转出的账户和转入的账户不能相同",
                            Toast.LENGTH_SHORT,
                        ).show()
                        else -> {
                            onSave(
                                TxDraft(
                                    // 转账无分类；从转账切回普通收支时若原 categoryId
                                    // 是空串，就保持「未分类」，与旧编辑行为一致。
                                    categoryId = if (transfer) "" else categoryId,
                                    accountId = accountId,
                                    // 转账恒 isExpense（ViewModel 再规范化一次，口径一致）。
                                    isExpense = type != TxBillType.INCOME,
                                    amountCents = cents,
                                    note = note,
                                    dateTime = dateTime,
                                    toAccountId = if (transfer) toAccountId else "",
                                ),
                            )
                            editing = false
                        }
                    }
                },
            )
        }
    }

    // 删除二次确认
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条账单？") },
            text = {
                // 与复制文本同一套描述：转账若走「未分类 -xx」模板，
                // 用户会以为删的是一笔消费，实际删掉的是两边账户的差额来源。
                Text(
                    buildCopyText(item, accounts),
                    color = colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                }) { Text("删除", color = semantic.expense) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 查看态：大金额 + 信息卡片 + 两个按钮。 */
@Composable
private fun ViewMode(
    item: TxWithCategory,
    accent: Color,
    accounts: List<AccountEntity>,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val cat = item.category
    val tx = item.tx
    val isTransfer = tx.isTransfer
    // 只有多账户时才值得占一行展示。
    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.name
        ?.takeIf { accounts.size > 1 }
    // 转账必有两个账户（哪怕已被删），这行永远值得展示；查不到的名字显示「已删除账户」。
    val transferFlowText = if (isTransfer) {
        accountNameOrDeleted(accounts, tx.accountId) + " → " + accountNameOrDeleted(accounts, tx.toAccountId)
    } else {
        ""
    }

    // 大号金额（displaySmall）。转账既不是花掉也不是赚到，「→」前缀代替 +/-。
    Text(
        (when {
            isTransfer -> "→"
            tx.isExpense -> "-"
            else -> "+"
        }) + centsToYuan(tx.amountCents),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Medium,
        color = accent,
    )
    Spacer(Modifier.height(16.dp))

    // 信息卡片
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(JizhangTheme.colors.card)
            .padding(16.dp),
    ) {
        // 转账行 categoryId 恒空串，硬画只会多一行「未分类」的假分类。
        if (!isTransfer) {
            InfoRow(label = "分类") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(cat?.let { Color(it.color) } ?: NeutralAvatar),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        cat?.name ?: "未分类",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onBackground,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = colorScheme.outline,
            )
        }
        if (isTransfer) {
            InfoRow(label = "账户") {
                Text(
                    transferFlowText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onBackground,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = colorScheme.outline,
            )
        } else if (accountName != null) {
            InfoRow(label = "账户") {
                Text(
                    accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onBackground,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = colorScheme.outline,
            )
        }
        InfoRow(label = "日期时间") {
            Text(
                dtFmt.format(tx.dateTime),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onBackground,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = colorScheme.outline,
        )
        InfoRow(label = "备注") {
            Text(
                if (tx.note.isNotBlank()) tx.note else "无",
                style = MaterialTheme.typography.bodyMedium,
                color = if (tx.note.isNotBlank()) colorScheme.onBackground else colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(20.dp))

    Button(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
    ) {
        Text("编辑", fontSize = 16.sp)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onCopy,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Text("复制账单文本", fontSize = 16.sp, color = colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
}

/** 一行标签 + 内容的通用排布。 */
@Composable
private fun InfoRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 编辑态：三段类型切换 + 金额 + （非转账）分类网格 + 转出/转入账户 + 日期 + 备注 + 保存。 */
@Composable
private fun EditMode(
    type: TxBillType,
    amountText: String,
    note: String,
    categoryId: String?,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    accountId: String,
    toAccountId: String,
    dateTime: Long,
    onType: (TxBillType) -> Unit,
    onAmountText: (String) -> Unit,
    onNote: (String) -> Unit,
    onCategory: (String) -> Unit,
    onAccount: (String) -> Unit,
    onToAccount: (String) -> Unit,
    onDate: (Long) -> Unit,
    onSave: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isTransfer = type == TxBillType.TRANSFER
    val cents = parseYuanToCents(amountText)
    // 转账的必填是「转入已选」而不是「分类已选」——隐藏网格后若仍按分类卡按钮，
    // 一笔转账永远保存不了。
    val requiredChoiceOk = if (isTransfer) toAccountId.isNotBlank() else categoryId != null
    val canSave = cents > 0 && requiredChoiceOk

    // 三段切换：与 AddScreen 共用一个组件，两处表单不会再长得不一样
    TxBillTypeSelector(selected = type, onSelect = onType)
    Spacer(Modifier.height(20.dp))

    Text("金额", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = amountText,
        onValueChange = { v ->
            // 只留数字与一个小数点，小数最多 2 位
            val cleaned = v.filter { it.isDigit() || it == '.' }
            val parts = cleaned.split('.')
            onAmountText(
                (
                    if (parts.size <= 1) cleaned
                    else parts[0] + "." + parts.drop(1).joinToString("").take(2)
                    ).take(12),
            )
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("0.00") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
    Spacer(Modifier.height(20.dp))

    // 转账无分类：编辑成转账时整段网格隐藏，保存路径也会把 categoryId 写成空串。
    if (!isTransfer) {
        Text("分类", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .height(((categories.size + 3) / 4 * 84).dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            items(categories) { c ->
                val selected = categoryId == c.id
                Column(
                    modifier = Modifier.clickable { onCategory(c.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(c.color))
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, colorScheme.primary, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onCategory(c.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.name.take(1),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        c.name,
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    // 账户：只有一个账户时不显示。转账语境下这是「转出」，下面再挂一行「转入」。
    if (accounts.size > 1) {
        Text(
            if (isTransfer) "转出" else "账户",
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        AccountSelector(
            accounts = accounts,
            selectedId = accountId,
            onSelect = onAccount,
        )
        if (isTransfer) {
            // 候选排除转出账户：A→A 的转账会留下两边互相抵消的幽灵流水。
            // 排除后可能只剩 1 个候选，它仍是必选项，不能被单账户隐藏规则吞掉。
            Spacer(Modifier.height(16.dp))
            Text("转入", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            AccountSelector(
                accounts = accounts.filter { it.id != accountId },
                selectedId = toAccountId,
                onSelect = onToAccount,
                showSingleOption = true,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    Text("日期", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    DateField(dateTime = dateTime, onChange = onDate)
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = note,
        onValueChange = onNote,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("备注（可选）") },
        singleLine = true,
    )
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onSave,
        enabled = canSave,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
    ) {
        Text("保存", fontSize = 16.sp)
    }
    Spacer(Modifier.height(24.dp))
}
