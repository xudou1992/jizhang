package com.jianji.jizhang

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.jizhang.data.RemindScheduler
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.importing.toTxDraft
import com.jianji.jizhang.ui.add.AddScreen
import com.jianji.jizhang.ui.add.TxPrefill
import com.jianji.jizhang.ui.backup.BackupScreen
import com.jianji.jizhang.ui.bills.BillListScreen
import com.jianji.jizhang.ui.detail.TxDetailScreen
import com.jianji.jizhang.ui.export.ExportScreen
import com.jianji.jizhang.ui.home.HomeScreen
import com.jianji.jizhang.ui.import.ImportScreen
import com.jianji.jizhang.ui.manage.AccountManageScreen
import com.jianji.jizhang.ui.manage.CategoryManageScreen
import com.jianji.jizhang.ui.search.SearchScreen
import com.jianji.jizhang.ui.settings.SettingsScreen
import com.jianji.jizhang.ui.shell.AppShell
import com.jianji.jizhang.ui.shell.AppTab
import com.jianji.jizhang.ui.stats.StatsScreen
import com.jianji.jizhang.ui.theme.HomeStyle
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.ThemeMode
import com.jianji.jizhang.ui.theme.homeStyleFlow
import com.jianji.jizhang.ui.theme.pureBlackFlow
import com.jianji.jizhang.ui.theme.remindSettingsFlow
import com.jianji.jizhang.ui.theme.setHomeStyle
import com.jianji.jizhang.ui.theme.setPureBlack
import com.jianji.jizhang.ui.theme.themeModeFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 盖在 Tab 之上的整屏页面，全部不占底部 Tab。 */
private enum class Overlay {
    NONE, ADD, BACKUP, EXPORT, SEARCH, DETAIL, CATEGORY_MANAGE, ACCOUNT_MANAGE, IMPORT,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val style by homeStyleFlow().collectAsState(initial = HomeStyle.WHITE)
            val pureBlack by pureBlackFlow().collectAsState(initial = false)
            // 三态主题：设置页只落库，这里吃流真正换肤；SYSTEM 跟随系统。
            val themeMode by themeModeFlow().collectAsState(initial = ThemeMode.SYSTEM)
            val scope = rememberCoroutineScope()
            val vm: LedgerViewModel = viewModel()

            // tab/overlay 是枚举（Serializable），rememberSaveable 能直接存回 Bundle：
            // 进程被系统回收后回到原来的页面，而不是每次都被弹回首页。
            // addPrefill/detailTx 持复杂对象存不了，恢复后由下面的 activeOverlay 兜底。
            var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
            var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
            // 复制账单：跳「记一笔」并预填；普通记一笔为 null
            var addPrefill by remember { mutableStateOf<TxPrefill?>(null) }
            // 账单详情（编辑/删除），持有被查看的那笔
            var detailTx by remember { mutableStateOf<com.jianji.jizhang.data.TxWithCategory?>(null) }
            val activeOverlay =
                if (overlay == Overlay.DETAIL && detailTx == null) Overlay.NONE else overlay

            val snackbarHostState = remember { SnackbarHostState() }
            // 搜索词上提到这里：进详情返回后结果页还在（用户走查：原来一翻详情搜索全丢）。
            var searchQuery by rememberSaveable { mutableStateOf("") }
            // 最小的「来源记忆」：只有一处真正需要的返回栈 —— 搜索→详情→返回搜索。
            var detailReturn by remember { mutableStateOf(Overlay.NONE) }

            val all by vm.all.collectAsState()
            val categories by vm.categories.collectAsState()
            val accounts by vm.accounts.collectAsState()
            // 预算：首页预算卡片的数据源，增删走 vm.setBudget/removeBudget。
            val budgets by vm.budgets.collectAsState()

            // 「最近」默认值：all 已按时间倒序，第一条即最近一笔。
            val recentAccountId = all.firstOrNull()?.tx?.accountId
            val recentCategoryIds = remember(all) {
                all.asSequence()
                    .map { it.tx.categoryId }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(8)
                    .toList()
            }

            fun backToNone() {
                overlay = Overlay.NONE
                addPrefill = null
                detailTx = null
                detailReturn = Overlay.NONE
            }

            fun openDetail(item: com.jianji.jizhang.data.TxWithCategory, from: Overlay) {
                detailTx = item
                detailReturn = from
                overlay = Overlay.DETAIL
            }

            /** 离开详情：从搜索进来的回搜索结果（词还在），否则回 Tab。 */
            fun closeDetail() {
                detailTx = null
                if (detailReturn == Overlay.SEARCH) {
                    overlay = Overlay.SEARCH
                } else {
                    backToNone()
                }
            }

            // 删除 + 撤销：VM 回调交出被删的那笔，Snackbar 给 5 秒反悔窗口。
            fun deleteTxWithUndo(id: String) {
                vm.deleteTx(id) { deleted ->
                    scope.launch {
                        val outcome = snackbarHostState.showSnackbar(
                            message = "已删除一笔 ¥${centsToYuan(deleted.amountCents)}",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Long,
                        )
                        if (outcome == SnackbarResult.ActionPerformed) vm.undoDeleteTx(deleted)
                    }
                }
            }

            // Android 13+ 通知授权（备份失败提醒用）。只在首次进入时申请一次。
            val askNotifications = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* 拒了就拒了：warn() 自己会静默降级，结果仍写备份页 */ }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // 提醒兜底重排：WorkManager 周期任务可能被系统清掉，每次启动按已存设置重锚定一次。
                val remind = remindSettingsFlow().first()
                RemindScheduler.schedule(this@MainActivity, remind.enabled, remind.hour)
            }

            // trueBlack 只在深色模式下生效（浅色时 JizhangTheme 会忽略它）。
            JizhangTheme(trueBlack = pureBlack, themeMode = themeMode) {
                // 全屏 overlay 一律拦截系统返回：回上层页面，而不是退出 App 丢现场。
                // DETAIL 特殊：它可能来自搜索，系统返回也要回搜索结果。
                if (activeOverlay != Overlay.NONE) {
                    BackHandler(onBack = {
                        if (activeOverlay == Overlay.DETAIL) closeDetail() else backToNone()
                    })
                }
                // overlay 页（ADD/SEARCH/DETAIL…）不在 AppShell 里，拿不到它的 Snackbar；
                // 这层顶置宿主保证「从搜索进详情删除」也能看到撤销条。
                Box(Modifier.fillMaxSize()) {
                when (activeOverlay) {
                    Overlay.ADD -> AddScreen(
                        categories = categories,
                        accounts = accounts,
                        prefill = addPrefill,
                        recentAccountId = recentAccountId,
                        recentCategoryIds = recentCategoryIds,
                        // 保存不再关页：AddScreen 内部只清金额、留在原表单连续记，
                        // 关页交给左上角 × 或系统返回（BackHandler 已接管）。
                        onSave = { draft -> vm.addTx(draft) },
                        onClose = { backToNone() },
                    )

                    Overlay.BACKUP -> BackupScreen(onBack = { backToNone() })

                    Overlay.EXPORT -> ExportScreen(
                        all = all,
                        accounts = accounts,
                        onBack = { backToNone() },
                    )

                    // 导入账单：解析出的草稿用 toTxDraft() 带上预览页选的账户后落库。
                    Overlay.IMPORT -> ImportScreen(
                        onBack = { backToNone() },
                        accounts = accounts,
                        categories = categories,
                        onImport = { drafts -> vm.importTxs(drafts.map { it.toTxDraft() }) },
                    )

                    Overlay.SEARCH -> SearchScreen(
                        all = all,
                        categories = categories,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onOpenTx = { tx -> openDetail(tx, Overlay.SEARCH) },
                        onBack = {
                            // 主动退出搜索页才清词；进详情返回走 closeDetail 保留。
                            searchQuery = ""
                            backToNone()
                        },
                    )

                    Overlay.DETAIL -> detailTx?.let { tx ->
                        TxDetailScreen(
                            item = tx,
                            categories = categories,
                            accounts = accounts,
                            onSave = { draft ->
                                vm.updateTx(tx.tx.id, draft)
                                closeDetail()
                            },
                            onDelete = {
                                deleteTxWithUndo(tx.tx.id)
                                closeDetail()
                            },
                            onBack = { closeDetail() },
                        )
                    }

                    Overlay.CATEGORY_MANAGE -> CategoryManageScreen(
                        categories = categories,
                        onAdd = { name, color -> vm.addCategory(name, color) },
                        onUpdate = { vm.updateCategory(it) },
                        onDelete = { id, onResult -> vm.deleteCategory(id, onResult) },
                        onBack = { backToNone() },
                    )

                    Overlay.ACCOUNT_MANAGE -> AccountManageScreen(
                        accounts = accounts,
                        all = all,
                        onAdd = { name, color -> vm.addAccount(name, color) },
                        onUpdate = { vm.updateAccount(it) },
                        onDelete = { id, onResult -> vm.deleteAccount(id, onResult) },
                        // 余额管理直连 VM：调余额补一笔「余额调整」流水留痕，改初始值直接落库。
                        onAdjustBalance = vm::adjustBalanceTo,
                        onInitialChange = vm::setAccountInitial,
                        onBack = { backToNone() },
                    )

                    Overlay.NONE -> AppShell(
                        selected = tab,
                        onSelect = { tab = it },
                        snackbarHostState = snackbarHostState,
                        onAdd = {
                            addPrefill = null
                            overlay = Overlay.ADD
                        },
                    ) { inner ->
                        // 各页面自己处理状态栏内边距；这里只让出底部导航占掉的高度。
                        Box(Modifier.padding(inner)) {
                            when (tab) {
                                AppTab.HOME -> HomeScreen(
                                    style = style,
                                    all = all,
                                    categories = categories,
                                    // 预算三参直连 VM：签名与 setBudget/removeBudget 逐位一致。
                                    budgets = budgets,
                                    onSetBudget = vm::setBudget,
                                    onRemoveBudget = vm::removeBudget,
                                    onToggleStyle = {
                                        scope.launch {
                                            setHomeStyle(
                                                if (style == HomeStyle.WHITE) HomeStyle.BLUE else HomeStyle.WHITE,
                                            )
                                        }
                                    },
                                    onOpenTx = { tx ->
                                        openDetail(tx, Overlay.NONE)
                                    },
                                )

                                AppTab.BILLS -> BillListScreen(
                                    all = all,
                                    onAdd = {
                                        addPrefill = null
                                        overlay = Overlay.ADD
                                    },
                                    onOpenSearch = { overlay = Overlay.SEARCH },
                                    // 点一条账单进详情 —— 与首页一致。此前这里是断的：
                                    // 首页能点开，账单 tab 点了没反应，只能长按。
                                    onOpenTx = { tx ->
                                        openDetail(tx, Overlay.NONE)
                                    },
                                    onCopy = { tx ->
                                        // 复制一笔：预填金额/收支/分类/账户/备注，日期取今天。
                                        addPrefill = TxPrefill(
                                            categoryId = tx.tx.categoryId.ifBlank {
                                                categories.firstOrNull()?.id
                                            },
                                            accountId = tx.tx.accountId,
                                            isExpense = tx.tx.isExpense,
                                            amountCents = tx.tx.amountCents,
                                            note = tx.tx.note,
                                            // 转账也要能复制对：带上转入账户，否则副本会掉成普通支出。
                                            toAccountId = tx.tx.toAccountId,
                                        )
                                        overlay = Overlay.ADD
                                    },
                                    onDelete = { id -> deleteTxWithUndo(id) },
                                    // 批量删除 + 撤销：与单条同一套路，条数进文案，撤销整批还原。
                                    onDeleteMany = { ids ->
                                        vm.deleteTxMany(ids) { deleted ->
                                            scope.launch {
                                                val outcome = snackbarHostState.showSnackbar(
                                                    message = "已删除 ${deleted.size} 笔",
                                                    actionLabel = "撤销",
                                                    duration = SnackbarDuration.Long,
                                                )
                                                if (outcome == SnackbarResult.ActionPerformed) {
                                                    vm.undoDeleteMany(deleted)
                                                }
                                            }
                                        }
                                    },
                                )

                                AppTab.STATS -> StatsScreen(
                                    all = all,
                                    categories = categories,
                                )

                                AppTab.SETTINGS -> SettingsScreen(
                                    style = style,
                                    onStyleChange = { next -> scope.launch { setHomeStyle(next) } },
                                    pureBlack = pureBlack,
                                    onPureBlackChange = { on -> scope.launch { setPureBlack(on) } },
                                    txCount = all.size,
                                    categoryCount = categories.size,
                                    accountCount = accounts.size,
                                    onOpenBackup = { overlay = Overlay.BACKUP },
                                    onOpenExport = { overlay = Overlay.EXPORT },
                                    onOpenCategories = { overlay = Overlay.CATEGORY_MANAGE },
                                    onOpenAccounts = { overlay = Overlay.ACCOUNT_MANAGE },
                                    // 导入账单入口：接上刚加的 Overlay.IMPORT。
                                    onOpenImport = { overlay = Overlay.IMPORT },
                                )
                            }
                        }
                    }
                }
                // NONE 时的 Snackbar 由 AppShell 自带宿主；这里只给 overlay 页兜一层，
                // 让「从搜索/详情里删除」的撤销条也显示得出来（同一个 hostState）。
                if (activeOverlay != Overlay.NONE) {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    )
                }
                }
            }
        }
    }
}
