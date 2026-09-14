package com.jianji.jizhang.ui.manage

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.CategoryPalette
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
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
    onBack: () -> Unit,
) {
    // 弹窗状态：新增 / 编辑（持有被编辑账户）/ 删除二次确认（持有被删账户 id）
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

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
    // 总资产 = 所有账户（收入 − 支出）之和；无账单的账户贡献 0，不影响结果。
    val totalCents = remember(statsByAccount) {
        statsByAccount.values.sumOf { it.income - it.expense }
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
                        onEdit = { editing = a },
                        onDelete = { deletingId = a.id },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    // 新增弹窗
    if (showAdd) {
        AccountEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { name, color ->
                onAdd(name, color)
                showAdd = false
            },
        )
    }

    // 编辑弹窗
    editing?.let { acc ->
        AccountEditDialog(
            initial = acc,
            onDismiss = { editing = null },
            onConfirm = { name, color ->
                onUpdate(acc.copy(name = name, color = color))
                editing = null
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

/** 单账户卡：色圆（首字）+ 名称 + 编辑/删除；次行余额；末行收入·支出·笔数。 */
@Composable
private fun AccountCard(
    account: AccountEntity,
    stat: AccountStat?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val income = stat?.income ?: 0L
    val expense = stat?.expense ?: 0L
    val count = stat?.count ?: 0
    val balance = income - expense
    // 余额颜色：正数用收入绿，负数用支出红（在 @Composable 内取色，符合约束）。
    val balanceColor = if (balance >= 0) JizhangTheme.colors.income else JizhangTheme.colors.expense

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
                // 账户自身数据色圆，居中白字首字
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(account.color)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        account.name.firstOrNull()?.toString().orEmpty(),
                        color = Color.White,
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

            // 次行：余额（按正负取语义色）
            Text(
                "¥${yuan(balance)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = balanceColor,
            )

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

/** 新增/编辑弹窗：账户名 + 10 色板选色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditDialog(
    initial: AccountEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // 选中色用 Long 存储，与 AccountEntity.color 同型；默认取色板第一个
    var selectedColor by remember {
        mutableStateOf(initial?.color ?: CategoryPalette.first().value.toLong())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), selectedColor) },
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
                                val c = color.value.toLong()
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
