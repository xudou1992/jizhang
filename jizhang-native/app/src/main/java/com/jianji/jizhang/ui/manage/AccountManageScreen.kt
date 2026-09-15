package com.jianji.jizhang.ui.manage

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.balanceMap
import com.jianji.jizhang.data.parseYuanToCents
import com.jianji.jizhang.ui.theme.CategoryPalette
import com.jianji.jizhang.ui.theme.toStoredLong
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.readableOn
import java.util.Locale

/**
 * 账户管理页。纯 UI + 回调，本页不写任何 IO / 数据库逻辑。
 * 根节点是 Column，无 Scaffold、无底部栏。
 */
@Composable
fun AccountManageScreen(
    accounts: List<AccountEntity>,
    all: List<TxWithCategory>,
    onAdd: (name: String, color: Long) -> Unit,
    onUpdate: (AccountEntity) -> Unit,
    onDelete: (id: String, onResult: (Boolean) -> Unit) -> Unit,
    /**
     * 「调整当前余额…」：把账户余额调到 [targetCents]（分）。
     * ViewModel 会补一笔「余额调整」流水留痕而不是硬改数字。
     * 带默认值 → MainActivity 接线前本页照常编译。
     */
    onAdjustBalance: (accountId: String, targetCents: Long) -> Unit = { _, _ -> },
    /** 编辑账户时改初始余额（分）。同上，接线前兼容旧调用点。 */
    onInitialChange: (AccountEntity, Long) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    // 弹窗状态：新增 / 编辑（持有被编辑账户）/ 删除二次确认（持有被删账户 id）
    // / 调整余额（持有目标账户）
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var adjusting by remember { mutableStateOf<AccountEntity?>(null) }
    val context = LocalContext.current

    // 真实余额 = 初始余额 + 流水，口径只认 data/Ledger.kt 的 balanceMap 一份实现。
    // all 本来就含转账行 —— balanceMap 内部会做「转出扣、转入加」，这里绝不能再过滤，
    // 否则过滤掉转账后余额会把左口袋挪右口袋的钱算丢。
    val balanceByAccount = remember(all, accounts) { balanceMap(all, accounts) }

    // 预聚合：按账户分组后统计收入/支出/笔数，避免每帧遍历 608 条。
    // 这里只读数据，不碰 MaterialTheme。
    val statsByAccount = remember(all, accounts) {
        val grouped = all.groupBy { it.tx.accountId }
        grouped.mapValues { (_, txs) ->
            var income = 0L
            var expense = 0L
            for (t in txs) {
                if (t.tx.isExpense) expense += t.tx.amountCents else income += t.tx.amountCents
            }
            AccountStat(income = income, expense = expense, count = txs.size)
        }
    }
    // 总资产 = 各账户真实余额之和。旧口径「收入 − 支出」两头都错：
    // 不含初始余额，且转账只落在转出账户的「支出」上，转一笔总资产就凭空蒸发一笔。
    val totalCents = remember(balanceByAccount, accounts) {
        accounts.sumOf { balanceByAccount[it.id] ?: it.initialCents }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        // 顶栏：返回 + 标题 + 新建
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    JizhangIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                "账户管理",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    JizhangIcons.Plus,
                    contentDescription = "新建账户",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // 资产总览卡
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    "总资产",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "¥${yuan(totalCents)}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${accounts.size} 个账户",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 空状态 / 账户列表
        if (accounts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有账户",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(accounts, key = { it.id }) { a ->
                    AccountCard(
                        account = a,
                        stat = statsByAccount[a.id],
                        // 展示真实余额（初始 + 流水，含转账双向），不再是「收支差」。
                        balanceCents = balanceByAccount[a.id] ?: a.initialCents,
                        onEdit = { editing = a },
                        onDelete = { deletingId = a.id },
                        onAdjust = { adjusting = a },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    // 新增弹窗（没有历史流水，初始余额固定 0，想要非零去编辑里改）
    if (showAdd) {
        AccountEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { name, color, _ ->
                onAdd(name, color)
                showAdd = false
            },
        )
    }

    // 编辑弹窗：名称/颜色走 onUpdate（整行覆盖），初始余额单独走 onInitialChange
    // —— 让 ViewModel 用 setAccountInitial 的 copy 语义去改这一个字段。
    // ⚠️ 必须把「改名后」的实体传给 onInitialChange：updateAccount 是整行 @Update，
    // ViewModel 拿到的若是改名前的旧副本，第二次写库会把新名字无声覆盖回旧名字。
    editing?.let { acc ->
        AccountEditDialog(
            initial = acc,
            onDismiss = { editing = null },
            onConfirm = { name, color, initialCents ->
                val updated = acc.copy(name = name, color = color)
                onUpdate(updated)
                if (initialCents != acc.initialCents) onInitialChange(updated, initialCents)
                editing = null
            },
        )
    }

    // 调整当前余额：输入的是「核对后的真实余额」，差额由 ViewModel 补流水
    adjusting?.let { acc ->
        AdjustBalanceDialog(
            account = acc,
            currentCents = balanceByAccount[acc.id] ?: acc.initialCents,
            onDismiss = { adjusting = null },
            onConfirm = { targetCents ->
                onAdjustBalance(acc.id, targetCents)
                adjusting = null
            },
        )
    }

    // 删除二次确认弹窗
    deletingId?.let { id ->
        DeleteConfirmDialog(
            onDismiss = { deletingId = null },
            onConfirm = {
                // onDelete 的回调可能从协程返回，直接用即可
                onDelete(id) { ok ->
                    if (!ok) {
                        Toast.makeText(
                            context,
                            "该账户下还有账单，或这是最后一个账户，无法删除",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                deletingId = null
            },
        )
    }
}

/** 单账户卡：色圆（首字）+ 名称 + 编辑/删除；次行真实余额 + 调整入口；末行收入·支出·笔数。 */
@Composable
private fun AccountCard(
    account: AccountEntity,
    stat: AccountStat?,
    /** 初始余额 + 流水（含转账双向）得到的真实余额，由外层用 balanceMap 算好传入。 */
    balanceCents: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAdjust: () -> Unit,
) {
    val income = stat?.income ?: 0L
    val expense = stat?.expense ?: 0L
    val count = stat?.count ?: 0
    // 余额颜色：正数用收入绿，负数用支出红（信用卡/花呗类账户余额为负是常态）。
    val balanceColor = if (balanceCents >= 0) JizhangTheme.colors.income else JizhangTheme.colors.expense

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // 顶行：色圆 + 名称 + 编辑/删除
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 账户自身数据色圆：首字颜色按底色亮度选黑/白
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(account.color)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        account.name.firstOrNull()?.toString().orEmpty(),
                        color = readableOn(Color(account.color)),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        JizhangIcons.Edit,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        JizhangIcons.Trash,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 次行：真实余额 + 「调整当前余额…」入口。
            // 放余额旁边而不是藏进菜单：对账场景（核完银行卡 App 数字对不上）
            // 的下一步动作就该在错的那个数字旁边。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "¥${yuan(balanceCents)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = balanceColor,
                )
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onAdjust,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("调整当前余额…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(4.dp))

            // 末行小字：收入 · 支出 · 笔数
            Text(
                "收入 ¥${yuan(income)} · 支出 ¥${yuan(expense)} · ${count} 笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 新增/编辑弹窗：账户名 + 10 色板选色；编辑态额外一节「初始余额」。
 *
 * 初始余额只在编辑态出现：onAdd 的签名不带余额（改签名会波及所有调用点），
 * 新建的账户从 0 起步，想要非零基数建完立刻在编辑里填，一步不多绕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditDialog(
    initial: AccountEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Long, initialCents: Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // 选中色用 Long 存储，与 AccountEntity.color 同型（0xAARRGGBB）；默认取色板第一个。
    // 必须走 toStoredLong()：color.value.toLong() 是打包值，再交给 Color(Long) 会溢出成全透明。
    var selectedColor by remember {
        mutableStateOf(initial?.color ?: CategoryPalette.first().toStoredLong())
    }
    // 初始余额以「元」文本编辑，保存时 parseYuanToCents 换分 —— 库里永远是分，
    // 元只活在输入框（Money.kt 的唯一换算口径）。预填用带千分位的展示串也能解析回来。
    var initialText by remember {
        mutableStateOf(initial?.let { yuan(it.initialCents) } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                // 非法的初始余额文本（如 "1.2.3"）按 0 处理不如直接不让保存：
                // 静默归零会把用户真实的开户基数抹掉。
                enabled = name.isNotBlank() &&
                    (initial == null || initialText.isBlank() ||
                        initialText.replace(",", "").trim().toDoubleOrNull() != null),
                onClick = {
                    onConfirm(
                        name.trim(),
                        selectedColor,
                        if (initial == null) 0L else parseYuanToCents(initialText),
                    )
                },
            ) {
                Text("保存", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        },
        title = { Text(if (initial == null) "新建账户" else "编辑账户") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (initial != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = initialText,
                        onValueChange = { v ->
                            // 允许负号：信用卡/花呗的开户基数本来就是欠款。
                            initialText = v.filter { it.isDigit() || it == '.' || it == '-' }.take(12)
                        },
                        label = { Text("初始余额（元）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = {
                            Text("当前余额 = 初始余额 + 全部流水；开户前已有的存款/欠款填这里")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "颜色",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 10 色板分两行（每行 5 个）展示，选中的加描边并叠 Check
                Column {
                    CategoryPalette.chunked(5).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            rowColors.forEach { color ->
                                val c = color.toStoredLong()
                                val selected = c == selectedColor
                                Surface(
                                    shape = CircleShape,
                                    color = color,
                                    border = if (selected) {
                                        BorderStroke(
                                            2.dp,
                                            MaterialTheme.colorScheme.onBackground,
                                        )
                                    } else {
                                        null
                                    },
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable { selectedColor = c },
                                ) {
                                    if (selected) {
                                        Icon(
                                            JizhangIcons.Check,
                                            contentDescription = "已选",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * 「调整当前余额…」弹窗：输入核对后的实际余额（元）。
 *
 * 预填 App 当前算出来的余额（去掉千分位便于修改）。确认回调给的是**目标分**：
 * 差额、补流水都是 ViewModel.adjustBalanceTo 的事 —— 页面只管收集意图。
 */
@Composable
private fun AdjustBalanceDialog(
    account: AccountEntity,
    currentCents: Long,
    onDismiss: () -> Unit,
    onConfirm: (targetCents: Long) -> Unit,
) {
    // 预填不带千分位：带着逗号用户一改数字就混进非法字符，干脆给干净的。
    var text by remember(account.id) {
        mutableStateOf(String.format(Locale.CHINA, "%.2f", currentCents / 100.0))
    }
    val valid = text.replace(",", "").trim().toDoubleOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(parseYuanToCents(text)) }, enabled = valid) {
                Text("调整", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", fontWeight = FontWeight.Medium) }
        },
        title = { Text("调整「${account.name}」余额") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        // 允许负号：透支账户对出负余额是真实情况。
                        text = v.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }.take(14)
                    },
                    label = { Text("实际余额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                // 说清楚「调整」不是改数字而是补一笔流水：不写这句，
                // 稍后账单里冒出一笔「余额调整」会被当成 Bug/幽灵记录。
                Text(
                    "当前 ¥${yuan(currentCents)}。保存后将生成一笔「余额调整」记录留痕，可在账单里查看或删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** 删除二次确认弹窗。确认按钮文字「删除」且用支出红。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = JizhangTheme.colors.expense,
                ),
            ) {
                Text("删除", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        },
        title = { Text("删除账户") },
        text = {
            Text(
                "删除后不可恢复",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** 单账户聚合统计（收入/支出/笔数）。纯数据，无 UI。 */
private data class AccountStat(
    val income: Long,
    val expense: Long,
    val count: Int,
)

/** 分转元并加千分位。例：12345 -> "123.45"。 */
private fun yuan(cents: Long): String =
    String.format(Locale.CHINA, "%,.2f", cents / 100.0)
