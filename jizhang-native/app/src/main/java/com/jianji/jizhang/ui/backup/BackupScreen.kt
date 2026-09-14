package com.jianji.jizhang.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
// 注意：`Modifier.weight` 是 RowScope/ColumnScope 的成员，不能 import。
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.R
import com.jianji.jizhang.data.backup.BackupOrigin
import com.jianji.jizhang.data.backup.BackupScheduler
import com.jianji.jizhang.data.backup.CloudBackupEntry
import com.jianji.jizhang.data.backup.CloudBackupPreview
import com.jianji.jizhang.data.backup.LocalBackupFile
import com.jianji.jizhang.data.backup.NutstoreSettings
import com.jianji.jizhang.data.backup.deleteCloudBackup
import com.jianji.jizhang.data.backup.deleteLocalBackup
import com.jianji.jizhang.data.backup.deviceFromBackupName
import com.jianji.jizhang.data.backup.deviceName
import com.jianji.jizhang.data.backup.downloadCloudBackupByPath
import com.jianji.jizhang.data.backup.exportLocalBackup
import com.jianji.jizhang.data.backup.listCloudBackups
import com.jianji.jizhang.data.backup.listLocalBackups
import com.jianji.jizhang.data.backup.nutstoreSettingsFlow
import com.jianji.jizhang.data.backup.readLocalBackup
import com.jianji.jizhang.data.backup.readLocalBackupFromUri
import com.jianji.jizhang.data.backup.restoreFromJson
import com.jianji.jizhang.data.backup.runBackupNow
import com.jianji.jizhang.data.backup.updateNutstoreSettings
import com.jianji.jizhang.data.backup.writeLocalBackupToUri
import com.jianji.jizhang.ui.theme.JizhangIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** 两个 Tab：本地备份（本机 JSON 文件）/ 云端备份（坚果云 WebDAV）。 */
private enum class BackupTab(val label: String, val icon: ImageVector) {
    LOCAL("本地备份", JizhangIcons.Doc),
    CLOUD("云端备份", JizhangIcons.Cloud),
}

/** 来源标签配色：手工=品牌蓝，自动=青绿，留底=琥珀。 */
private val ORIGIN_COLORS = mapOf(
    BackupOrigin.MANUAL to Color(0xFF2A5AE8),
    BackupOrigin.AUTO to Color(0xFF12A594),
    BackupOrigin.SAFETY to Color(0xFFCC7A00),
)

/**
 * 数据备份页。无 Scaffold、无底部栏，根节点是 Column。
 *
 * 结构对齐旧版「数据备份」：Tab（本地/云端）→ 导出/导入 → 备份文件列表（含空态）。
 * 旧版只有本地这一条路；云端 Tab 里保留了坚果云自动备份与云端列表。
 *
 * 备份一律带「设备名 + 来源 + 时间戳」：自动跑的进 auto/，手点的进 manual/，
 * 互不覆盖；凭据区默认收起来，只露脱敏账号。
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by context.nutstoreSettingsFlow().collectAsState(initial = NutstoreSettings())

    var tab by remember { mutableStateOf(BackupTab.LOCAL) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }

    // —— 本地 ——
    var localFiles by remember { mutableStateOf<List<LocalBackupFile>>(emptyList()) }
    var pendingPreview by remember { mutableStateOf<CloudBackupPreview?>(null) }
    var pendingPreviewLabel by remember { mutableStateOf("") }
    var pendingDeleteLocal by remember { mutableStateOf<LocalBackupFile?>(null) }
    var pendingSavePath by remember { mutableStateOf<String?>(null) }

    // —— 云端 ——
    var cloudList by remember { mutableStateOf<List<CloudBackupEntry>?>(null) }
    var cloudLoading by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteCloud by remember { mutableStateOf<CloudBackupEntry?>(null) }

    // 账号密码默认收起来：页面上只显示脱敏账号，要改才展开输入框。
    var editingCreds by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    // 账号输入框的本地态：DataStore 是异步流，直接绑它会跳字。
    var email by remember { mutableStateOf(settings.email) }
    var password by remember { mutableStateOf(settings.password) }

    // DataStore 首次读出来后同步一次，避免输入框停在空值。
    LaunchedEffect(settings.email, settings.password) {
        if (email.isBlank()) email = settings.email
        if (password.isBlank()) password = settings.password
    }

    fun refreshLocal() {
        scope.launch {
            localFiles = withContext(Dispatchers.IO) { listLocalBackups(context) }
        }
    }

    fun fetchCloud() {
        cloudLoading = true
        cloudError = null
        scope.launch {
            listCloudBackups(context).fold(
                onSuccess = { cloudList = it },
                onFailure = { cloudError = it.message ?: "未知错误" },
            )
            cloudLoading = false
        }
    }

    /** 恢复（本地文件/云端文件共用）：预览已确认，这里才写库。 */
    fun doRestore(p: CloudBackupPreview) {
        val json = p.jsonText
        pendingPreview = null
        restoring = true
        message = null
        scope.launch {
            val r = restoreFromJson(context, json)
            message = r.fold(
                onSuccess = { "恢复成功：共写入 $it 条记录" },
                onFailure = { "恢复失败：${it.message ?: "未知错误"}" },
            )
            restoring = false
            localFiles = withContext(Dispatchers.IO) { listLocalBackups(context) }
            if (tab == BackupTab.CLOUD) fetchCloud()
        }
    }

    // 导入数据：系统文件选择器（SAF），免存储权限。放宽 MIME 是因为不少文件管理器
    // 把 .json 报成 application/octet-stream。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            busy = true
            message = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { readLocalBackupFromUri(context, uri) }
                r.fold(
                    onSuccess = {
                        pendingPreview = it
                        pendingPreviewLabel = "所选文件"
                    },
                    onFailure = { message = "读取失败：${it.message ?: "这不是简记的备份文件"}" },
                )
                busy = false
            }
        }
    }

    // 另存到其他位置（SAF CreateDocument）。
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val src = pendingSavePath
        pendingSavePath = null
        if (uri != null && src != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { writeLocalBackupToUri(context, uri, src) }
                message = r.fold(
                    onSuccess = { "已另存到所选位置" },
                    onFailure = { "另存失败：${it.message ?: "未知错误"}" },
                )
            }
        }
    }

    LaunchedEffect(Unit) { refreshLocal() }
    LaunchedEffect(tab) { if (tab == BackupTab.CLOUD) fetchCloud() }
    // 提示条自己消失，避免旧消息一直挂在页面上。
    LaunchedEffect(message) {
        if (message != null) {
            delay(4500)
            message = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BackupHeader(
            onBack = onBack,
            subtitle = if (tab == BackupTab.LOCAL) {
                "共 ${localFiles.size} 份本机备份"
            } else {
                "坚果云 · ${deviceName()}"
            },
        )
        BackupTabs(selected = tab, onSelect = { tab = it })
        message?.let { MessageBar(it) }

        Box(Modifier.weight(1f)) {
            when (tab) {
                BackupTab.LOCAL -> LocalBackupContent(
                    files = localFiles,
                    busy = busy || restoring,
                    onExport = {
                        busy = true
                        message = null
                        scope.launch {
                            val r = exportLocalBackup(context, BackupOrigin.MANUAL)
                            message = r.fold(
                                onSuccess = { "已生成备份：${it.name}（${it.sizeKB} KB）" },
                                onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                            )
                            busy = false
                            localFiles = withContext(Dispatchers.IO) { listLocalBackups(context) }
                        }
                    },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onRestore = { f ->
                        message = null
                        scope.launch {
                            withContext(Dispatchers.IO) { readLocalBackup(f.path) }.fold(
                                onSuccess = {
                                    pendingPreview = it
                                    pendingPreviewLabel = f.name
                                },
                                onFailure = { message = "读不了这份备份：${it.message ?: "文件已损坏"}" },
                            )
                        }
                    },
                    onSaveAs = { f ->
                        pendingSavePath = f.path
                        saveLauncher.launch(f.name)
                    },
                    onDelete = { pendingDeleteLocal = it },
                )

                BackupTab.CLOUD -> CloudBackupContent(
                    settings = settings,
                    email = email,
                    password = password,
                    editingCreds = editingCreds,
                    showPassword = showPassword,
                    onToggleEdit = { editingCreds = !editingCreds },
                    onToggleShowPassword = { showPassword = !showPassword },
                    onEmail = {
                        email = it
                        scope.launch { context.updateNutstoreSettings(email = it) }
                    },
                    onPassword = {
                        password = it
                        scope.launch { context.updateNutstoreSettings(password = it) }
                    },
                    onToggle = { on ->
                        scope.launch {
                            context.updateNutstoreSettings(enabled = on)
                            BackupScheduler.schedule(context, on)
                        }
                    },
                    busy = busy || restoring,
                    entries = cloudList,
                    loading = cloudLoading,
                    error = cloudError,
                    onBackupNow = {
                        busy = true
                        message = null
                        scope.launch {
                            val r = runBackupNow(context, BackupOrigin.MANUAL)
                            message = r.fold(
                                onSuccess = { "已备份到坚果云：手工归档 + 最新版" },
                                onFailure = { "备份失败：${it.message ?: "未知错误"}" },
                            )
                            busy = false
                            fetchCloud()
                        }
                    },
                    onRefresh = { fetchCloud() },
                    onRestore = { e ->
                        restoring = true
                        message = null
                        scope.launch {
                            val r = downloadCloudBackupByPath(context, e.path)
                            restoring = false
                            r.fold(
                                onSuccess = {
                                    pendingPreview = it
                                    pendingPreviewLabel = e.name
                                },
                                onFailure = { message = "读取备份失败：${it.message ?: "未知错误"}" },
                            )
                        }
                    },
                    onDelete = { pendingDeleteCloud = it },
                )
            }
        }
    }

    // —— 确认恢复：先给笔数/大小，确认了才写库 ——
    pendingPreview?.let { p ->
        val label = pendingPreviewLabel
        AlertDialog(
            onDismissRequest = { pendingPreview = null },
            title = { Text("确认恢复这份备份？") },
            text = {
                Text(
                    "$label\n\n" +
                        "${p.txCount} 笔账单 · ${p.categoryCount} 个分类 · ${p.accountCount} 个账户" +
                        "（${p.sizeKB} KB）\n\n" +
                        "恢复规则：按 id 合并，备份里同名的记录覆盖本机，本机多出的记录保留。" +
                        "恢复前会自动把本机现状留底一份，恢复错了能找回。",
                )
            },
            confirmButton = {
                TextButton(onClick = { doRestore(p) }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreview = null }) { Text("取消") }
            },
        )
    }

    // —— 删除本机备份 ——
    pendingDeleteLocal?.let { f ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLocal = null },
            title = { Text("删除这份备份？") },
            text = { Text("${f.origin.label}备份（${f.sizeKB} KB）删掉后本机不再保留，当前账单不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val path = f.path
                    pendingDeleteLocal = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { deleteLocalBackup(path) }
                        message = r.fold(
                            onSuccess = { "已删除本机备份" },
                            onFailure = { "删除失败：${it.message ?: "未知错误"}" },
                        )
                        localFiles = withContext(Dispatchers.IO) { listLocalBackups(context) }
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLocal = null }) { Text("取消") }
            },
        )
    }

    // —— 删除云端备份 ——
    pendingDeleteCloud?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCloud = null },
            title = { Text("删除这份云端备份？") },
            text = { Text("${e.origin.label}备份（${e.sizeKB} KB）删掉后云端不再保留，本机账单不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val path = e.path
                    pendingDeleteCloud = null
                    scope.launch {
                        deleteCloudBackup(context, path).fold(
                            onSuccess = {
                                message = "已删除云端备份"
                                fetchCloud()
                            },
                            onFailure = { message = "删除失败：${it.message ?: "未知错误"}" },
                        )
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCloud = null }) { Text("取消") }
            },
        )
    }
}

/* ---------------------------------- 头部 / Tab ---------------------------------- */

@Composable
private fun BackupHeader(onBack: () -> Unit, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 10.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                JizhangIcons.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "数据备份",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 胶囊分段控件：选中项蓝底白字，比下划线 Tab 更有分量。 */
@Composable
private fun BackupTabs(selected: BackupTab, onSelect: (BackupTab) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(4.dp)) {
            BackupTab.entries.forEach { t ->
                val active = t == selected
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                        )
                        .clickable { onSelect(t) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        t.icon,
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        t.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/* ---------------------------------- 本地备份 ---------------------------------- */

@Composable
private fun LocalBackupContent(
    files: List<LocalBackupFile>,
    busy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRestore: (LocalBackupFile) -> Unit,
    onSaveAs: (LocalBackupFile) -> Unit,
    onDelete: (LocalBackupFile) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onExport,
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(JizhangIcons.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("导出数据", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    JizhangIcons.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "导入数据",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        HintRow(
            "文件名自带设备名与来源：手点的记 manual、定时任务记 auto、" +
                "恢复前的留底记 safety，永不互相覆盖。",
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("备份文件列表", trailingText = if (files.isEmpty()) null else "共 ${files.size} 项")
        Spacer(Modifier.height(8.dp))

        if (files.isEmpty()) {
            EmptyFilesHint()
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    files.forEachIndexed { index, f ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                modifier = Modifier.padding(start = 66.dp),
                            )
                        }
                        LocalFileRow(
                            file = f,
                            enabled = !busy,
                            onRestore = { onRestore(f) },
                            onSaveAs = { onSaveAs(f) },
                            onDelete = { onDelete(f) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "本机备份放在应用自己的目录里，卸载 App 会一起删掉。" +
                "要长期留着的，用行尾菜单里的「另存到其他位置」放到网盘或电脑上。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EmptyFilesHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_empty_backup),
            contentDescription = null,
            modifier = Modifier.size(150.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "还没有备份",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "点上面的「导出数据」生成第一份，文件名会带上本机型号和时间。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalFileRow(
    file: LocalBackupFile,
    enabled: Boolean,
    onRestore: () -> Unit,
    onSaveAs: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(JizhangIcons.Doc, tint = ORIGIN_COLORS[file.origin] ?: MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${file.origin.label}备份",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                deviceFromBackupName(file.name)?.let { dev ->
                    Spacer(Modifier.width(6.dp))
                    DeviceChip(dev)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatStamp(file.lastModified)} · ${file.sizeKB} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) { Text("恢复") }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) {
                Icon(
                    JizhangIcons.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("另存到其他位置") },
                    onClick = {
                        menu = false
                        onSaveAs()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/* ---------------------------------- 云端备份 ---------------------------------- */

@Composable
private fun CloudBackupContent(
    settings: NutstoreSettings,
    email: String,
    password: String,
    editingCreds: Boolean,
    showPassword: Boolean,
    onToggleEdit: () -> Unit,
    onToggleShowPassword: () -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    busy: Boolean,
    entries: List<CloudBackupEntry>?,
    loading: Boolean,
    error: String?,
    onBackupNow: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (CloudBackupEntry) -> Unit,
    onDelete: (CloudBackupEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "每日自动备份",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "每天自动上传一次；记一笔后也会自动补一次。自动备份进 auto/，不碰手工的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = onToggle)
            }
        }

        Spacer(Modifier.height(12.dp))
        CredentialsCard(
            email = email,
            password = password,
            editing = editingCreds,
            showPassword = showPassword,
            onToggleEdit = onToggleEdit,
            onToggleShowPassword = onToggleShowPassword,
            onEmail = onEmail,
            onPassword = onPassword,
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onBackupNow,
            enabled = email.isNotBlank() && password.isNotBlank() && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(JizhangIcons.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("立即备份一次", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "上次备份：${formatStamp(settings.lastSyncAt, never = "从未备份")}" +
                if (settings.lastResult.isBlank()) "" else "　·　${settings.lastResult}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(22.dp))
        SectionTitle(
            "云端备份列表",
            trailingText = if (loading) null else "共 ${entries?.size ?: 0} 份",
            onRefresh = if (loading) null else onRefresh,
        )
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp))
            }

            error != null -> Text(
                "读取失败：$error\n\n先在上面点「立即备份一次」，或者检查账号和应用密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            entries.isNullOrEmpty() -> Text(
                "云端还没有备份，先点上面的「立即备份一次」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            else -> {
                val latest = entries.firstOrNull { it.isLatest }
                val archives = entries.filterNot { it.isLatest }

                latest?.let { e ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CloudEntryRow(
                            entry = e,
                            enabled = !busy,
                            onRestore = { onRestore(e) },
                            onDelete = { onDelete(e) },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                BackupOrigin.entries.forEach { origin ->
                    val group = archives.filter { it.origin == origin }
                    if (group.isEmpty()) return@forEach
                    GroupLabel(origin.label, group.size)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            group.forEachIndexed { index, e ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                        modifier = Modifier.padding(start = 66.dp),
                                    )
                                }
                                CloudEntryRow(
                                    entry = e,
                                    enabled = !busy,
                                    onRestore = { onRestore(e) },
                                    onDelete = { onDelete(e) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "坚果云 /jianji 目录：latest 是最新一份（会被覆盖），" +
                "auto/ 与 manual/ 下是只增不删的归档，文件名带设备名与时间戳。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 凭据卡片。默认**收起**，只显示脱敏账号与「已保存」的密码 ——
 * 备份页是会被别人瞄到的页面，明文摆在那儿不合适。
 */
@Composable
private fun CredentialsCard(
    email: String,
    password: String,
    editing: Boolean,
    showPassword: Boolean,
    onToggleEdit: () -> Unit,
    onToggleShowPassword: () -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
) {
    val configured = email.isNotBlank() && password.isNotBlank()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    JizhangIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "坚果云账号",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleEdit) {
                    Text(if (editing) "收起" else if (configured) "修改" else "填写")
                }
            }

            if (!editing) {
                Spacer(Modifier.height(6.dp))
                Text(
                    maskEmail(email),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (password.isBlank()) "尚未填写应用密码" else "应用密码 ${"•".repeat(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                FieldLabel("账号（邮箱）")
                OutlinedTextField(
                    value = email,
                    onValueChange = onEmail,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("your@email.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(14.dp))
                FieldLabel("应用密码")
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassword,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("坚果云「应用密码」，不是登录密码") },
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = onToggleShowPassword) {
                            Icon(
                                if (showPassword) JizhangIcons.EyeOff else JizhangIcons.Eye,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "怎么拿应用密码：坚果云网页版 → 账户信息 → 安全选项 → 添加应用密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CloudEntryRow(
    entry: CloudBackupEntry,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(
            if (entry.isLatest) JizhangIcons.Cloud else JizhangIcons.CloudUpload,
            tint = ORIGIN_COLORS[entry.origin] ?: MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (entry.isLatest) "最新版" else "${entry.origin.label}备份",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                deviceFromBackupName(entry.name)?.let { dev ->
                    Spacer(Modifier.width(6.dp))
                    DeviceChip(dev)
                }
                if (entry.isLatest) {
                    Spacer(Modifier.width(6.dp))
                    Tag("最新")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatStamp(entry.lastModified)} · ${entry.sizeKB} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) { Text("恢复") }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                JizhangIcons.Trash,
                contentDescription = "删除这份备份",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/* ---------------------------------- 小组件 ---------------------------------- */

@Composable
private fun FileBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 设备名小标签：`Xiaomi-2201122C`。 */
@Composable
private fun DeviceChip(device: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            JizhangIcons.Device,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            device,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** 分组小标题：`自动备份 · 3 份`。 */
@Composable
private fun GroupLabel(label: String, count: Int) {
    Text(
        "$label · $count 份",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** 列表区的标题行：标题 + 右侧计数 / 刷新按钮。 */
@Composable
private fun SectionTitle(
    title: String,
    trailingText: String? = null,
    onRefresh: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (trailingText != null) {
            Text(
                trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRefresh != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRefresh) {
                Icon(
                    JizhangIcons.Rotate,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一行浅色说明文字，带左侧竖条，比纯段落更像「提示」而不是「正文」。 */
@Composable
private fun HintRow(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 顶部提示条（导出成功 / 恢复失败之类），几秒后自动消失。 */
@Composable
private fun MessageBar(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/* ---------------------------------- 格式化 ---------------------------------- */

/** 时间戳格式化；0/负值按 [never] 展示。 */
private fun formatStamp(epochMs: Long, never: String = "时间未知"): String {
    if (epochMs <= 0L) return never
    return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(epochMs)
}

/**
 * 账号脱敏：`504546466@qq.com` → `50•••@qq.com`。
 * 备份页是可能被别人瞄到的页面，默认状态不摆明文。
 */
private fun maskEmail(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return "未填写"
    val at = s.indexOf('@')
    if (at <= 0) {
        return if (s.length <= 2) "••" else s.take(2) + "•".repeat(minOf(s.length - 2, 4))
    }
    val name = s.substring(0, at)
    return name.take(minOf(2, name.length)) + "•••" + s.substring(at)
}
