package com.jianji.jizhang.ui.add

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxDraft
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.parseYuanToCents
import com.jianji.jizhang.ui.components.AccountSelector
import com.jianji.jizhang.ui.components.DateField
import com.jianji.jizhang.ui.components.TxBillType
import com.jianji.jizhang.ui.components.TxBillTypeSelector
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.readableOn

/**
 * 复制账单带来的预填内容：金额/收支/分类/账户/备注。
 * 日期不预填 —— 复制昨天的外卖是记在今天。
 *
 * [toAccountId] 非空串 = 复制的是一笔转账：进页面直接落在「转账」段并带上转入账户。
 * 默认空串 —— 旧的复制调用点（普通收支）一行都不用改。
 */
data class TxPrefill(
    val categoryId: String?,
    val accountId: String?,
    val isExpense: Boolean,
    val amountCents: Long,
    val note: String,
    val toAccountId: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    prefill: TxPrefill? = null,
    /** 最近一笔用的账户 id（普通记一笔时的默认落点，不再永远掉到第一个账户）。 */
    recentAccountId: String? = null,
    /** 最近 90 天用过的分类 id（新的在前）：网格前置 + 默认预选第一个。 */
    recentCategoryIds: List<String> = emptyList(),
    onSave: (TxDraft) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // 进页即聚焦金额框、弹键盘：记账的第一动作就是敲数字，原来每次都要先点一下输入框。
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }
    // 表单全部走 rememberSaveable：旋转/字体切换/进程回收后草稿还在，
    // 记一半被系统杀掉不再等于白记（1.0.5 审计 P0）。
    // 类型用枚举而不是 isExpense 布尔：第三段「转账」塞不进两态，
    // 而且 (isExpense, isTransfer) 两个布尔能拼出「收入型转账」这种非法态。
    var type by rememberSaveable {
        mutableStateOf(
            when {
                prefill?.toAccountId?.isNotBlank() == true -> TxBillType.TRANSFER
                prefill?.isExpense == false -> TxBillType.INCOME
                else -> TxBillType.EXPENSE
            },
        )
    }
    // 转账的「转入」账户。只存用户选的，落库口径与转出一样交给校验分支。
    var pickedToAccountId by rememberSaveable { mutableStateOf(prefill?.toAccountId ?: "") }
    var amountText by rememberSaveable {
        mutableStateOf(
            prefill?.let {
                val yuan = it.amountCents / 100
                val fen = it.amountCents % 100
                "$yuan." + fen.toString().padStart(2, '0')
            } ?: "",
        )
    }
    var note by rememberSaveable { mutableStateOf(prefill?.note ?: "") }
    // 默认预选最近用过的分类：把「记一笔」从 5+ 步压到「金额→保存」两步。
    // 选错了看得见、改一下就在眼前；不熟的人第一次也会被 recentCategoryIds 里
    // 不存在的分类挡住（下面兜底校验 id 仍在列表中）。
    var categoryId by rememberSaveable {
        mutableStateOf(prefill?.categoryId ?: recentCategoryIds.firstOrNull())
    }
    // 账户可能异步才加载出来（StateFlow 初始值是空表），所以这里存「用户选的」，
    // 真正的落库值在下面用 effectiveAccountId 兜底。默认优先「最近一笔的账户」。
    var pickedAccountId by rememberSaveable {
        mutableStateOf(prefill?.accountId ?: recentAccountId ?: "")
    }
    var dateTime by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }

    val cents = parseYuanToCents(amountText)
    val isTransfer = type == TxBillType.TRANSFER
    // 转账落库恒为「支出」语义（钱从转出账户流出），ViewModel 也会再规范化一次；
    // 这里只是保证预览色与草稿口径一致。
    val isExpense = type != TxBillType.INCOME
    val accent = when (type) {
        TxBillType.EXPENSE -> JizhangTheme.colors.expense
        TxBillType.INCOME -> JizhangTheme.colors.income
        // 转账既不是花掉也不是赚到，红/绿都是错的语义 —— 走中性 Transfer。
        TxBillType.TRANSFER -> JizhangTheme.colors.transfer
    }
    // 预置的「最近分类」可能已被删除：用之前校验 id 仍在列表里，防止挂到幽灵分类。
    val validCategoryId = categoryId?.takeIf { id -> categories.any { it.id == id } }
    // 转账没有分类可校验（分类网格整段隐藏），换成「转入已选」；
    // 只有一个账户时根本转不了账，也算在这条缺失提示里。
    val requiredChoiceOk = if (isTransfer) {
        accounts.size > 1 && pickedToAccountId.isNotBlank()
    } else {
        validCategoryId != null
    }
    val canSave = cents > 0 && requiredChoiceOk

    val effectiveAccountId = pickedAccountId.ifBlank { accounts.firstOrNull()?.id ?: "" }
    // 常用分类前置（新的在前），其余保持用户配置的 orderNum 顺序。
    val displayCategories = remember(categories, recentCategoryIds) {
        if (recentCategoryIds.isEmpty()) categories
        else categories.sortedBy { c ->
            recentCategoryIds.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                JizhangIcons.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
            )
            Spacer(Modifier.weight(1f))
            Text("记一笔", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(16.dp))

        TxBillTypeSelector(selected = type, onSelect = { type = it })
        Spacer(Modifier.height(20.dp))

        Text("金额", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "¥ " + (amountText.ifBlank { "0.00" }),
            style = MaterialTheme.typography.headlineSmall,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = amountText,
            onValueChange = { v ->
                val cleaned = v.filter { it.isDigit() || it == '.' }
                val parts = cleaned.split('.')
                amountText = (
                    if (parts.size <= 1) cleaned
                    else parts[0] + "." + parts.drop(1).joinToString("").take(2)
                    ).take(12)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(amountFocus),
            placeholder = { Text("0.00") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Spacer(Modifier.height(20.dp))

        // 转账不是消费也不是进账，不该占用任何分类：整段网格隐藏，
        // 落库时 categoryId 恒空串（见下面 onSave）。
        if (!isTransfer) {
            Text("分类", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(((displayCategories.size + 3) / 4 * 84).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(displayCategories.size) { i ->
                    val c = displayCategories[i]
                    val selected = validCategoryId == c.id
                    Column(
                        modifier = Modifier.clickable { categoryId = c.id },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val bg = if (selected) MaterialTheme.colorScheme.primary else Color(c.color)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .clickable { categoryId = c.id },
                            contentAlignment = Alignment.Center,
                        ) {
                            // 首字对比度走亮度判断：黄/橙分类配白字原来只有 2:1。
                            Text(
                                c.name.take(1),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = readableOn(bg),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            c.name,
                            fontSize = 11.sp,
                            maxLines = 1,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 账户：只有一个账户时不显示（没有可选项，纯噪音）。
        // 转账语义下这一行是「钱从哪出去」，标题跟着语境走，别让用户猜。
        if (accounts.size > 1) {
            Text(
                if (isTransfer) "转出" else "账户",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AccountSelector(
                accounts = accounts,
                selectedId = effectiveAccountId,
                onSelect = { pickedAccountId = it },
            )
            if (isTransfer) {
                // 转入候选排除转出账户本身：A→A 会让余额先减后加原地抵消，
                // 留下一笔永远对不上账的流水（ViewModel 也会拦，但别让选项长在脸上）。
                Spacer(Modifier.height(16.dp))
                Text("转入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                AccountSelector(
                    accounts = accounts.filter { it.id != effectiveAccountId },
                    selectedId = pickedToAccountId,
                    onSelect = { pickedToAccountId = it },
                    // 排除转出后可能只剩 1 个候选，必选项不能被隐藏规则吞掉。
                    showSingleOption = true,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("日期", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        DateField(dateTime = dateTime, onChange = { dateTime = it })
        // 快捷片：记「昨天那笔」是最常见补记场景，原来要点开日历翻两步。
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { dateTime = System.currentTimeMillis() },
                label = { Text("今天") },
                colors = AssistChipDefaults.assistChipColors(),
            )
            AssistChip(
                onClick = { dateTime = System.currentTimeMillis() - DAY_MS },
                label = { Text("昨天") },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("备注（可选）") },
            singleLine = true,
        )
        Spacer(Modifier.height(20.dp))
        // 校验不再静默：灰按钮旁边说清楚差什么（原来是吞了非法字符、无声不能保存）。
        // 三种缺失各一句：金额 / 分类（普通收支）/ 转入账户（转账），别拿「请选择分类」
        // 去要求一张根本没有分类网格的转账表单。
        if (!canSave) {
            val missing = buildList {
                if (cents <= 0L) add("请输入金额")
                if (isTransfer) {
                    if (accounts.size <= 1) add("转账需要至少两个账户")
                    else if (pickedToAccountId.isBlank()) add("请选择转入账户")
                } else if (validCategoryId == null) add("请选择分类")
            }
            Text(
                missing.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                if (canSave) {
                    if (isTransfer && pickedToAccountId == effectiveAccountId) {
                        // 能走到这里说明「先选了转入 B，又把转出改成 B」——
                        // B 已从候选里消失，但选中值还是它。当场 Toast 出声，
                        // 静默吞掉点击只会让人以为 App 卡了。
                        Toast.makeText(
                            context,
                            "转出的账户和转入的账户不能相同",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@Button
                    }
                    onSave(
                        TxDraft(
                            // 转账无分类：categoryId 空串，ViewModel 会按转账规范化落库。
                            categoryId = if (isTransfer) "" else validCategoryId!!,
                            accountId = effectiveAccountId,
                            isExpense = isExpense,
                            amountCents = cents,
                            note = note,
                            dateTime = dateTime,
                            toAccountId = if (isTransfer) pickedToAccountId else "",
                        ),
                    )
                    // 连续记账：保存只清金额，分类/账户/转入/收支/日期留在原位。
                    // 连记同类型的多笔（午饭+饮料+打车）从每笔 5 步降到每笔 2 步。
                    amountText = ""
                    note = ""
                    Toast.makeText(
                        context,
                        "已${if (isTransfer) "转出" else "记一笔"} ¥${centsToYuan(cents)}，可继续记",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("保存", fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 昨天快捷片用。中国大陆无夏令时，24 小时差即正确的「昨天同一时刻」。 */
private const val DAY_MS = 24 * 60 * 60 * 1000L
