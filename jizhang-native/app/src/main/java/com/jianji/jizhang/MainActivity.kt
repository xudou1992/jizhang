package com.jianji.jizhang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.jizhang.ui.add.AddScreen
import com.jianji.jizhang.ui.add.TxPrefill
import com.jianji.jizhang.ui.backup.BackupScreen
import com.jianji.jizhang.ui.bills.BillListScreen
import com.jianji.jizhang.ui.detail.TxDetailScreen
import com.jianji.jizhang.ui.export.ExportScreen
import com.jianji.jizhang.ui.home.HomeScreen
import com.jianji.jizhang.ui.manage.AccountManageScreen
import com.jianji.jizhang.ui.manage.CategoryManageScreen
import com.jianji.jizhang.ui.search.SearchScreen
import com.jianji.jizhang.ui.settings.SettingsScreen
import com.jianji.jizhang.ui.shell.AppShell
import com.jianji.jizhang.ui.shell.AppTab
import com.jianji.jizhang.ui.stats.StatsScreen
import com.jianji.jizhang.ui.theme.HomeStyle
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.homeStyleFlow
import com.jianji.jizhang.ui.theme.setHomeStyle
import kotlinx.coroutines.launch

/** 盖在 Tab 之上的整屏页面，全部不占底部 Tab。 */
private enum class Overlay {
    NONE, ADD, BACKUP, EXPORT, SEARCH, DETAIL, CATEGORY_MANAGE, ACCOUNT_MANAGE,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val style by homeStyleFlow().collectAsState(initial = HomeStyle.WHITE)
            val scope = rememberCoroutineScope()
            val vm: LedgerViewModel = viewModel()

            var tab by remember { mutableStateOf(AppTab.HOME) }
            var overlay by remember { mutableStateOf(Overlay.NONE) }
            // 复制账单：跳「记一笔」并预填；普通记一笔为 null
            var addPrefill by remember { mutableStateOf<TxPrefill?>(null) }
            // 账单详情（编辑/删除），持有被查看的那笔
            var detailTx by remember { mutableStateOf<com.jianji.jizhang.data.TxWithCategory?>(null) }

            val all by vm.all.collectAsState()
            val categories by vm.categories.collectAsState()
            val accounts by vm.accounts.collectAsState()

            fun backToNone() {
                overlay = Overlay.NONE
                addPrefill = null
                detailTx = null
            }

            JizhangTheme {
                when (overlay) {
                    Overlay.ADD -> AddScreen(
                        categories = categories,
                        prefill = addPrefill,
                        onSave = { catId, isExpense, cents, note ->
                            vm.addTx(catId, isExpense, cents, note)
                            backToNone()
                        },
                        onClose = { backToNone() },
                    )

                    Overlay.BACKUP -> BackupScreen(onBack = { backToNone() })

                    Overlay.EXPORT -> ExportScreen(all = all, onBack = { backToNone() })

                    Overlay.SEARCH -> SearchScreen(
                        all = all,
                        categories = categories,
                        onOpenTx = { tx ->
                            detailTx = tx
                            overlay = Overlay.DETAIL
                        },
                        onBack = { backToNone() },
                    )

                    Overlay.DETAIL -> detailTx?.let { tx ->
                        TxDetailScreen(
                            item = tx,
                            categories = categories,
                            onSave = { catId, isExpense, cents, note, dateTime ->
                                vm.updateTx(tx.tx.id, catId, isExpense, cents, note, dateTime)
                                backToNone()
                            },
                            onDelete = {
                                vm.deleteTx(tx.tx.id)
                                backToNone()
                            },
                            onBack = { backToNone() },
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
                        onBack = { backToNone() },
                    )

                    Overlay.NONE -> AppShell(
                        selected = tab,
                        onSelect = { tab = it },
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
                                    onToggleStyle = {
                                        scope.launch {
                                            setHomeStyle(
                                                if (style == HomeStyle.WHITE) HomeStyle.BLUE else HomeStyle.WHITE,
                                            )
                                        }
                                    },
                                    onOpenTx = { tx ->
                                        detailTx = tx
                                        overlay = Overlay.DETAIL
                                    },
                                )

                                AppTab.BILLS -> BillListScreen(
                                    all = all,
                                    onAdd = {
                                        addPrefill = null
                                        overlay = Overlay.ADD
                                    },
                                    onOpenSearch = { overlay = Overlay.SEARCH },
                                    onCopy = { tx ->
                                        // 复制一笔：预填金额/收支/分类/备注，日期保存时取今天。
                                        addPrefill = TxPrefill(
                                            categoryId = tx.tx.categoryId.ifBlank {
                                                categories.firstOrNull()?.id
                                            },
                                            isExpense = tx.tx.isExpense,
                                            amountCents = tx.tx.amountCents,
                                            note = tx.tx.note,
                                        )
                                        overlay = Overlay.ADD
                                    },
                                    onDelete = { id -> vm.deleteTx(id) },
                                )

                                AppTab.STATS -> StatsScreen(
                                    all = all,
                                    categories = categories,
                                )

                                AppTab.SETTINGS -> SettingsScreen(
                                    style = style,
                                    onStyleChange = { next -> scope.launch { setHomeStyle(next) } },
                                    txCount = all.size,
                                    categoryCount = categories.size,
                                    accountCount = accounts.size,
                                    onOpenBackup = { overlay = Overlay.BACKUP },
                                    onOpenExport = { overlay = Overlay.EXPORT },
                                    onOpenCategories = { overlay = Overlay.CATEGORY_MANAGE },
                                    onOpenAccounts = { overlay = Overlay.ACCOUNT_MANAGE },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
