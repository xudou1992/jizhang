package com.jianji.jizhang.ui.add

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme

/** 复制账单带来的预填内容：金额/收支/分类/备注。日期不预填，保存时取当前时间（复制昨天的外卖记在今天）。 */
data class TxPrefill(
    val categoryId: String?,
    val isExpense: Boolean,
    val amountCents: Long,
    val note: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(
    categories: List<CategoryEntity>,
    prefill: TxPrefill? = null,
    onSave: (categoryId: String, isExpense: Boolean, amountCents: Long, note: String) -> Unit,
    onClose: () -> Unit,
) {
    var isExpense by remember { mutableStateOf(prefill?.isExpense ?: true) }
    var amountText by remember {
        mutableStateOf(
            prefill?.let {
                val yuan = it.amountCents / 100
                val fen = it.amountCents % 100
                "$yuan." + fen.toString().padStart(2, '0')
            } ?: "",
        )
    }
    var note by remember { mutableStateOf(prefill?.note ?: "") }
    var categoryId by remember { mutableStateOf(prefill?.categoryId) }

    val cents = amountText.replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0L
    val accent = if (isExpense) JizhangTheme.colors.expense else JizhangTheme.colors.income
    val canSave = cents > 0 && categoryId != null

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

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isExpense,
                onClick = { isExpense = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(0xFFFCEBEB),
                    activeContentColor = JizhangTheme.colors.expense,
                ),
            ) { Text("支出") }
            SegmentedButton(
                selected = !isExpense,
                onClick = { isExpense = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(0xFFEAF3EE),
                    activeContentColor = JizhangTheme.colors.income,
                ),
            ) { Text("收入") }
        }
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
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("0.00") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Spacer(Modifier.height(20.dp))

        Text("分类", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .height(((categories.size / 4 + 1) * 84).dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            items(categories.size) { i ->
                val c = categories[i]
                val selected = categoryId == c.id
                Column(
                    modifier = Modifier.clickable { categoryId = c.id },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color(c.color))
                            .clickable { categoryId = c.id },
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
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("备注（可选）") },
            singleLine = true,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (canSave) {
                    onSave(categoryId!!, isExpense, cents, note)
                }
            },
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("保存", fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}
