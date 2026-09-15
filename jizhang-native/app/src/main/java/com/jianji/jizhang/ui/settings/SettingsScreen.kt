package com.jianji.jizhang.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.BuildConfig
import com.jianji.jizhang.data.RemindScheduler
import com.jianji.jizhang.data.backup.nutstoreSettingsFlow
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.HomeStyle
import com.jianji.jizhang.ui.theme.RemindSettings
import com.jianji.jizhang.ui.theme.ThemeMode
import com.jianji.jizhang.ui.theme.remindSettingsFlow
import com.jianji.jizhang.ui.theme.setRemindSettings
import com.jianji.jizhang.ui.theme.setThemeMode
import com.jianji.jizhang.ui.theme.themeModeFlow
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 原有的风格/纯黑保持「纯展示 + 回调、IO 在外层」的写法；
 * 本次新增的三态主题、每日提醒、备份概览则**在本页自读写 DataStore**：
 * 它们的接线点在 MainActivity（禁改，由主控并行处理），如果也走新增回调参数，
 * 页面在接线完成前就是断的；自带 IO 让新功能独立生效，也不与主控抢文件。
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    style: HomeStyle,
    onStyleChange: (HomeStyle) -> Unit,
    pureBlack: Boolean,
    onPureBlackChange: (Boolean) -> Unit,
    txCount: Int,
    categoryCount: Int,
    accountCount: Int,
    onOpenBackup: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenAccounts: () -> Unit,
    /** 「导入账单」入口（1.1.0）；默认空实现，主控接 Overlay.IMPORT。 */
    onOpenImport: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 页面加载即回显当前设置（流常驻，外部改动也会刷新到这里）。
    val themeMode by context.themeModeFlow().collectAsState(initial = ThemeMode.SYSTEM)
    val remind by context.remindSettingsFlow().collectAsState(initial = RemindSettings())
    // 备份概览：只读坚果云 store（"backup"），本页绝不写它。
    // lastSyncAt/lastTxCount 只在备份成功时写入，直接当「最后成功备份」展示。
    val backup by context.nutstoreSettingsFlow().collectAsState(initial = null)

    /** 保存提醒设置并立刻重排任务（关 = 取消唯一任务，开/改点 = 重建到新整点）。 */
    fun applyReminder(enabled: Boolean, hour: Int) {
        scope.launch {
            context.setRemindSettings(enabled, hour)
            RemindScheduler.schedule(context, enabled, hour)
        }
    }

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
            // 三态主题：本页只负责落库，真正换肤要等主控把 themeModeFlow 接进
            // MainActivity 的 JizhangTheme(themeMode = ...)。在此之前切换只存不生效。
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "主题模式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { scope.launch { context.setThemeMode(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = i,
                                count = ThemeMode.entries.size,
                            ),
                        ) { Text(mode.label) }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
            // 纯黑（AMOLED）：Theme.kt 里的 trueBlack 与 Color.kt 的 PureBlack 早就写好了，
            // 但一直没有任何入口去开它，等于死代码。这里把它接上。
            SettingsRow(
                title = "纯黑背景（AMOLED）",
                subtitle = "深色模式下页面与卡片压到纯黑，更省电",
                trailing = {
                    Switch(
                        checked = pureBlack,
                        onCheckedChange = onPureBlackChange,
                    )
                },
                onClick = { onPureBlackChange(!pureBlack) },
            )
        }

        // 提醒组：开关与整点都「保存即调度」（见 applyReminder），
        // 页面加载时状态已从 remindSettingsFlow 回显。
        SettingsGroup("提醒") {
            SettingsRow(
                title = "每日记账提醒",
                subtitle = "到点还没记账时发一条通知；大概时间（±15~20 分钟，见下）",
                trailing = {
                    Switch(
                        checked = remind.enabled,
                        onCheckedChange = { applyReminder(it, remind.hour) },
                    )
                },
                onClick = { applyReminder(!remind.enabled, remind.hour) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "提醒时间",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                // 只放 18-23 点：晚间是记账高峰时段，再给一堆凌晨选项只会打扰人。
                // WorkManager 的周期任务不保证精确到点，只能做到"±15~20 分钟级"——
                // 这是有意的取舍：精确闹钟要 SCHEDULE_EXACT_ALARM/电池优化白名单的
                // 授权引导，对一个记账提醒不值当（详见 RemindScheduler 注释）。
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (18..23).forEach { h ->
                        FilterChip(
                            selected = remind.hour == h,
                            onClick = { applyReminder(remind.enabled, h) },
                            label = { Text("%02d:00".format(h)) },
                        )
                    }
                }
            }
        }

        // 数据组
        SettingsGroup("数据") {
            SettingsRow(title = "账单记录", value = "$txCount 条")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            // 备份概览：只读展示，无点击无写入；点「数据备份」进去才有完整状态。
            SettingsRow(
                title = "最后成功备份",
                value = backup?.takeIf { it.lastSyncAt > 0L }?.let {
                    "${relativeTimeLabel(it.lastSyncAt)}（${it.lastTxCount} 笔）"
                } ?: "尚未备份",
            )
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
                subtitle = "多账户与总资产，记账时可选择账户",
                value = "$accountCount 个",
                leading = {
                    Icon(
                        JizhangIcons.Wallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            SettingsRow(
                title = "导入账单",
                subtitle = "从微信 / 支付宝 / 简记导出的账单导入",
                trailing = {
                    Icon(
                        JizhangIcons.ChevronRight,
                        contentDescription = "前往导入账单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenImport,
            )
        }

        // 关于组
        SettingsGroup("关于") {
            SettingsRow(title = "简记", value = "版本 ${BuildConfig.VERSION_NAME}")
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

/**
 * 中文相对时间。不用 DateUtils.getRelativeTimeSpanString：
 * 它在英文 locale 下吐 "3 hours ago"，和本机全中文的界面打架。
 */
private fun relativeTimeLabel(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        else -> "${diff / 86_400_000L} 天前"
    }
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
