package com.jianji.jizhang.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.HomeStyle

/**
 * 设置页。纯展示 + 回调，本页不写任何 IO / 数据库 / DataStore 逻辑。
 * 外层 AppShell 已提供 Scaffold 与底部导航，这里只画内容。
 */
@Composable
fun SettingsScreen(
    style: HomeStyle,
    onStyleChange: (HomeStyle) -> Unit,
    txCount: Int,
    categoryCount: Int,
    accountCount: Int,
    onOpenBackup: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenAccounts: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        // 顶部标题，与 HomeScreen 的 MonthHeader 同级观感
        Text("设置", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))

        // 外观组
        SettingsGroup("外观") {
            // 当前首页风格概览
            SettingsRow(
                title = "首页风格",
                value = style.label,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            // 可选项：遍历所有风格
            HomeStyle.entries.forEach { it ->
                SettingsRow(
                    title = it.label,
                    subtitle = styleDesc(it),
                    leading = {
                        RadioButton(
                            selected = it == style,
                            onClick = { onStyleChange(it) },
                        )
                    },
                    onClick = { onStyleChange(it) },
                )
            }
        }

        // 数据组
        SettingsGroup("数据") {
            SettingsRow(title = "账单记录", value = "$txCount 条")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            SettingsRow(
                title = "分类管理",
                subtitle = "新增 / 改名 / 换色 / 删除",
                value = "$categoryCount 个",
                trailing = {
                    Icon(
                        JizhangIcons.ChevronRight,
                        contentDescription = "分类管理",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenCategories,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            SettingsRow(
                title = "账户管理",
                subtitle = "多账户与总资产",
                value = "$accountCount 个",
                trailing = {
                    Icon(
                        JizhangIcons.ChevronRight,
                        contentDescription = "账户管理",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenAccounts,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            SettingsRow(
                title = "数据备份",
                subtitle = "本地导出 / 导入 + 坚果云每日自动备份",
                trailing = {
                    Icon(
                        JizhangIcons.ChevronRight,
                        contentDescription = "前往数据备份",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenBackup,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            SettingsRow(
                title = "导出数据",
                subtitle = "导出 CSV，可用 Excel 打开",
                trailing = {
                    Icon(
                        JizhangIcons.ChevronRight,
                        contentDescription = "前往导出数据",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenExport,
            )
        }

        // 关于组
        SettingsGroup("关于") {
            SettingsRow(title = "简记", value = "版本 1.0.0")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    "所有数据只保存在本机，不上传任何服务器（除非你开启自动备份）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** 首页风格副标题描述。 */
private fun styleDesc(style: HomeStyle): String = when (style) {
    HomeStyle.WHITE -> "白底清朗，金额一目了然"
    HomeStyle.BLUE -> "品牌蓝顶，视觉更有层次"
}

/** 分组卡片：标题 + 圆角 surface 容器。 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(content = content)
        }
    }
}

/** 通用设置行。可带左侧图标、右侧值/图标，整行可点击。 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 8.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}
