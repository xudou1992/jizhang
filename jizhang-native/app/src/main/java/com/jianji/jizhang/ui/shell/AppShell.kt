package com.jianji.jizhang.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.ui.theme.JizhangIcons
import androidx.compose.ui.unit.sp

/** 四个主 Tab，顺序即底栏顺序。「记一笔」的 + 按钮插在中间，它不是一个 Tab。 */
enum class AppTab(val label: String) {
    HOME("首页"),
    BILLS("账单"),
    STATS("统计"),
    SETTINGS("设置"),
}

/**
 * 应用外壳：Scaffold + 底部导航。四个页面都只画内容，导航统一在这里。
 *
 * `contentWindowInsets` 置 0：让每个页面自己决定要不要 `statusBarsPadding()`。
 * 首页的「品牌蓝顶」方案要顶栏一直延伸到状态栏底下，如果由外层统一加内边距，
 * 蓝色块上面会露出一条白边。
 */
@Composable
fun AppShell(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    onAdd: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { JizhangBottomBar(selected, onSelect, onAdd) },
        content = content,
    )
}

@Composable
private fun JizhangBottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(AppTab.HOME, JizhangIcons.Home, selected, onSelect, Modifier.weight(1f))
            NavItem(AppTab.BILLS, JizhangIcons.Bill, selected, onSelect, Modifier.weight(1f))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        JizhangIcons.Plus,
                        contentDescription = "记一笔",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            NavItem(AppTab.STATS, JizhangIcons.Stats, selected, onSelect, Modifier.weight(1f))
            NavItem(AppTab.SETTINGS, JizhangIcons.Settings, selected, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(
    tab: AppTab,
    icon: ImageVector,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = selected == tab
    val color = if (active) MaterialTheme.colorScheme.primary else Color(0xFF9CA0AB)
    Column(
        modifier = modifier.clickable { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = tab.label, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(tab.label, fontSize = 10.sp, color = color)
    }
}
