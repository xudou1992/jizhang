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
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.ui.theme.CategoryPalette
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme

/**
 * 分类管理页。纯 UI + 回调，本页不写任何 IO / 数据库逻辑。
 * 根节点是 Column，无 Scaffold、无底部栏。
 */
@Composable
fun CategoryManageScreen(
    categories: List<CategoryEntity>,
    onAdd: (name: String, color: Long) -> Unit,
    onUpdate: (CategoryEntity) -> Unit,
    onDelete: (id: String, onResult: (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    // 弹窗状态：新增 / 编辑（持有被编辑分类）/ 删除二次确认（持有被删分类）
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    val context = LocalContext.current

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
                "分类管理",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    JizhangIcons.Plus,
                    contentDescription = "新建分类",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // 统计行：共 N 个分类（次文字色）
        if (categories.isNotEmpty()) {
            Text(
                "共 ${categories.size} 个分类",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // 空状态
        if (categories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        JizhangIcons.Empty,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有分类，点右上角 + 新建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // 列表：每行一个分类，行可点 = 编辑
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(categories, key = { it.id }) { c ->
                    CategoryRow(
                        category = c,
                        onEdit = { editing = c },
                        onDelete = { deleting = c },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                }
            }
        }
    }

    // 新增弹窗
    if (showAdd) {
        CategoryEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { name, color ->
                onAdd(name, color)
                showAdd = false
            },
        )
    }

    // 编辑弹窗
    editing?.let { cat ->
        CategoryEditDialog(
            initial = cat,
            onDismiss = { editing = null },
            onConfirm = { name, color ->
                onUpdate(cat.copy(name = name, color = color))
                editing = null
            },
        )
    }

    // 删除二次确认弹窗
    deleting?.let { cat ->
        DeleteConfirmDialog(
            category = cat,
            onDismiss = { deleting = null },
            onConfirm = {
                // onDelete 的回调可能从协程返回，直接用即可
                onDelete(cat.id) { ok ->
                    if (!ok) {
                        Toast.makeText(
                            context,
                            "该分类下还有账单，无法删除",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                deleting = null
            },
        )
    }
}

/** 单条分类行：色圆（首字）+ 名称 + 编辑/删除。整行可点触发编辑。 */
@Composable
private fun CategoryRow(
    category: CategoryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分类自身数据色圆，居中白字首字
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(category.color)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                category.name.firstOrNull()?.toString().orEmpty(),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            category.name,
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
}

/** 新增/编辑弹窗：分类名 + 10 色板选色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditDialog(
    initial: CategoryEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // 选中色用 Long 存储，与 CategoryEntity.color 同型；默认取色板第一个
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
        title = { Text(if (initial == null) "新建分类" else "编辑分类") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名") },
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
    category: CategoryEntity,
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
        title = { Text("删除分类") },
        text = {
            Text(
                "删除后不可恢复",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
