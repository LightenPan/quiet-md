package com.zhaoolee.notes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhaoolee.notes.library.MigrationReport
import com.zhaoolee.notes.sync.SyncPhase
import com.zhaoolee.notes.sync.SyncProgress
import com.zhaoolee.notes.sync.WebDavConfig
import com.zhaoolee.notes.ui.theme.ThemeStyle
import com.zhaoolee.notes.viewmodel.NoteViewModel
import com.zhaoolee.notes.viewmodel.NutstoreViewModel
import com.zhaoolee.notes.viewmodel.RemoteFolderBrowserState
import com.zhaoolee.notes.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier,
    settingsViewModel: SettingsViewModel,
    noteViewModel: NoteViewModel,
    nutstoreViewModel: NutstoreViewModel,
    onBack: () -> Unit
) {
    val isDarkMode by settingsViewModel.isDarkMode.collectAsState()
    val selectedTheme by settingsViewModel.selectedTheme.collectAsState()
    val footerText by settingsViewModel.footerText.collectAsState()
    val library by noteViewModel.ui.collectAsState()
    val sync by nutstoreViewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    var isEditingFooter by rememberSaveable { mutableStateOf(false) }
    var footerInput by rememberSaveable { mutableStateOf(footerText) }
    var showMigration by rememberSaveable { mutableStateOf(false) }
    var showDisconnect by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(sync.server) }
    var username by rememberSaveable { mutableStateOf(sync.username) }
    var remoteRoot by rememberSaveable { mutableStateOf(sync.remoteRoot) }
    var password by remember { mutableStateOf(sync.savedPassword) }
    var showPassword by remember { mutableStateOf(false) }
    LaunchedEffect(sync.savedPassword) { password = sync.savedPassword }
    val timeFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    Scaffold(
        modifier = modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionHeader("文件库")
            SettingsItem(
                Icons.Default.Folder,
                "位置",
                library.libraryPath.ifBlank { "正在读取…" }
            )
            SettingsItem(
                Icons.Default.Description,
                "便签",
                "${library.totalNotes} 篇 · 删除的文件保留在本机 .trash"
            )
            SettingsItem(
                Icons.Default.Restore,
                "旧便签迁移",
                migrationSummary(library.migrationReport),
                trailing = {
                    TextButton(onClick = { showMigration = true }) { Text("查看") }
                }
            )
            if (library.warnings.isNotEmpty()) {
                StatusText("${library.warnings.size} 个文件无法读取，详情见列表提示。", false)
            }
            if (library.error != null) StatusText(library.error.orEmpty(), true)

            Spacer(Modifier.height(16.dp))
            SectionHeader("坚果云同步")
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WebDAV 地址") },
                placeholder = { Text(WebDavConfig.DEFAULT_SERVER) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = settingsFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("账号") },
                placeholder = { Text("坚果云登录邮箱") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = settingsFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("第三方应用密码") },
                placeholder = {
                    Text(if (sync.passwordSaved) "留空沿用已保存密码" else "在坚果云安全选项中生成")
                },
                singleLine = true,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    AsteriskPassword
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                        )
                    }
                },
                colors = settingsFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = remoteRoot,
                onValueChange = { remoteRoot = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("远程文件夹") },
                placeholder = { Text(WebDavConfig.DEFAULT_REMOTE_ROOT) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        nutstoreViewModel.openFolderBrowser(server, username, remoteRoot, password)
                    }) {
                        Icon(Icons.Default.FolderOpen, "浏览坚果云目录", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = settingsFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        nutstoreViewModel.connectAndSync(server, username, remoteRoot, password)
                    },
                    enabled = !sync.busy
                ) {
                    Text(if (sync.configured) "保存并同步" else "连接并同步")
                }
                if (sync.configured) {
                    OutlinedButton(onClick = nutstoreViewModel::syncNow, enabled = !sync.busy) {
                        Text("立即同步")
                    }
                }
                if (sync.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            if (sync.configured) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDisconnect = true }, enabled = !sync.busy) {
                        Text("断开坚果云", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (sync.busy) {
                SyncProgressCard(sync.progress)
            }
            sync.error?.let { StatusText(it, !sync.paused) }
            if (!sync.busy && sync.error == null) {
                sync.summary?.let { SyncSummaryCard(it, sync.lastSyncAt, timeFormat) }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("外观")
            SettingsItem(Icons.Default.Brightness4, "深色模式", trailing = {
                Switch(
                    checked = isDarkMode,
                    onCheckedChange = settingsViewModel::setDarkMode,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                )
            })
            SectionHeader("主题风格")
            listOf(
                ThemeStyle.WARM_PAPER to "暖白纸感" to Color(0xFFFFF8F0),
                ThemeStyle.NIGHT_NOTE to "深夜便签" to Color(0xFF1A1A1A),
                ThemeStyle.APPLE_NOTES to "iPhone 备忘录" to Color(0xFFF2F2F7),
                ThemeStyle.BEAR_MINIMAL to "Bear 极简" to Color(0xFFFAFAFA),
                ThemeStyle.TELEGRAPH to "电报" to Color(0xFFFFFFFF)
            ).forEach { (pair, color) ->
                val (style, name) = pair
                ThemeOption(name, color, selectedTheme == style) {
                    settingsViewModel.setThemeStyle(style)
                }
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader("底部标识")
            SettingsItem(Icons.Default.Edit, "自定义标识", footerText, trailing = {
                IconButton(onClick = {
                    isEditingFooter = !isEditingFooter
                    if (!isEditingFooter) settingsViewModel.setFooterText(footerInput)
                }) {
                    Icon(Icons.Default.Check, "保存", tint = MaterialTheme.colorScheme.primary)
                }
            })
            if (isEditingFooter) {
                OutlinedTextField(
                    value = footerInput,
                    onValueChange = { footerInput = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp),
                    placeholder = { Text("输入底部标识文字") },
                    singleLine = true,
                    colors = settingsFieldColors()
                )
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader("关于")
            SettingsItem(
                Icons.Default.Info,
                "关于锤子笔记",
                "v1.0.0 · 文件优先 Android 客户端"
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    sync.initialChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = nutstoreViewModel::dismissInitialChoice,
            title = { Text("两边都已有便签") },
            text = {
                Text(
                    buildString {
                        append("本机有 ${choice.localFiles} 篇，坚果云有 ${choice.remoteFiles} 篇。")
                        when {
                            choice.conflicts > 0 && choice.unverified > 0 ->
                                append("指纹比对发现 ${choice.conflicts} 篇同名文件内容不同，另有 ${choice.unverified} 篇需要下载后确认；只有确实不同的文件会保留第二份。")
                            choice.conflicts > 0 ->
                                append("指纹比对发现 ${choice.conflicts} 篇同名文件内容不同，合并会保留两份，不会覆盖。")
                            choice.unverified > 0 ->
                                append("${choice.unverified} 篇同名文件需要下载后比对指纹；内容相同会跳过，不会生成额外副本。")
                            else ->
                                append("两边没有同名文件差异，合并会把两边内容放在一起。")
                        }
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = nutstoreViewModel::mergeBoth,
                    enabled = !sync.busy
                ) {
                    Text("合并两边")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = nutstoreViewModel::dismissInitialChoice,
                    enabled = !sync.busy
                ) {
                    Text("稍后")
                }
            }
        )
    }

    sync.browser?.let { browser ->
        RemoteFolderBrowserDialog(
            state = browser,
            onOpen = nutstoreViewModel::browseRemoteFolder,
            onUp = nutstoreViewModel::browseRemoteFolderUp,
            onRetry = { nutstoreViewModel.browseRemoteFolder(browser.path) },
            onSelect = nutstoreViewModel::chooseRemoteFolder,
            onDismiss = nutstoreViewModel::closeFolderBrowser
        )
    }

    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text("断开坚果云？") },
            text = { Text("会删除本机保存的应用密码和同步状态，不会删除本地或云端便签。") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnect = false
                    nutstoreViewModel.disconnect()
                }) {
                    Text("断开", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnect = false }) { Text("取消") }
            }
        )
    }

    if (showMigration) {
        MigrationDialog(
            report = library.migrationReport,
            onClose = { showMigration = false }
        )
    }
}

@Composable
private fun SyncProgressCard(progress: SyncProgress?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                syncPhaseTitle(progress?.phase),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (progress?.total != null && progress.total > 0) {
                Text(
                    "${progress.done}/${progress.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val fraction = progress?.total?.takeIf { it > 0 }?.let { total ->
            (progress.done.toFloat() / total).coerceIn(0f, 1f)
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
        }
        val counts = syncProgressCounts(progress)
        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                counts,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        progress?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Spacer(Modifier.height(4.dp))
            Text(
                "正在处理 $detail",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SyncSummaryCard(summary: String, lastSyncAt: Long?, timeFormat: SimpleDateFormat) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Cloud, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                "同步摘要",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        lastSyncAt?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "上次同步 ${timeFormat.format(Date(it))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun syncPhaseTitle(phase: SyncPhase?): String = when (phase) {
    SyncPhase.CONNECTING -> "正在连接坚果云…"
    SyncPhase.SCANNING_REMOTE -> "正在扫描坚果云目录…"
    SyncPhase.SCANNING_LOCAL -> "正在扫描本机文件…"
    SyncPhase.COMPARING -> "正在比较两边的改动…"
    SyncPhase.DOWNLOADING -> "正在下载坚果云文件…"
    SyncPhase.UPLOADING -> "正在上传本机文件…"
    SyncPhase.DELETING -> "正在清理远端文件…"
    SyncPhase.FINISHING -> "正在保存同步状态…"
    SyncPhase.DONE -> "同步完成"
    null -> "正在连接坚果云…"
}

private fun syncProgressCounts(progress: SyncProgress?): String {
    if (progress == null) return ""
    return buildList {
        if (progress.localFiles > 0 && progress.remoteFiles > 0) {
            add("本机 ${progress.localFiles} · 坚果云 ${progress.remoteFiles}")
        } else if (progress.localFiles > 0) {
            add("本机 ${progress.localFiles}")
        } else if (progress.remoteFiles > 0) {
            add("坚果云 ${progress.remoteFiles}")
        }
        if (progress.uploaded > 0) add("上传 ${progress.uploaded}")
        if (progress.downloaded > 0) add("下载 ${progress.downloaded}")
        if (progress.moved > 0) add("移动 ${progress.moved}")
        if (progress.conflicts > 0) add("冲突 ${progress.conflicts}")
        if (progress.remoteDeleted > 0) add("删除远端 ${progress.remoteDeleted}")
        if (progress.verified > 0) add("比对 ${progress.verified}")
        if (progress.fingerprintSkipped > 0) add("指纹跳过 ${progress.fingerprintSkipped}")
        if (progress.warnings > 0) add("警告 ${progress.warnings}")
    }.joinToString(" · ")
}

@Composable
private fun RemoteFolderBrowserDialog(
    state: RemoteFolderBrowserState,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onRetry: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit
) {
    val current = if (state.path.isEmpty()) "我的坚果云" else "我的坚果云 / ${state.path}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择坚果云文件夹") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onUp, enabled = state.path.isNotEmpty() && !state.loading) {
                        Icon(Icons.Default.ArrowUpward, "上一级")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "当前位置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            current,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                Text(
                    "点文件夹进入下一级；选择后仍需保存并同步。",
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                when {
                    state.error != null -> Column {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    state.folders.isEmpty() && !state.loading -> Text(
                        "这个文件夹里没有子文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(state.folders, key = { it.rel }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !state.loading) { onOpen(folder.rel) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    folder.rel.substringAfterLast('/'),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSelect,
                enabled = state.path.isNotEmpty() && !state.loading
            ) {
                Text("选择此文件夹")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MigrationDialog(report: MigrationReport?, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("旧便签迁移") },
        text = {
            Column {
                if (report == null) {
                    Text("尚未生成迁移报告。")
                } else {
                    Text(
                        "已迁移 ${report.migrated} 篇，跳过 ${report.skipped} 篇，失败 ${report.failed} 篇。" +
                            if (report.completed) " 迁移已完成。" else " 下次启动会重试失败项。"
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("原 Room 数据库保持不变：${if (report.databaseUnchanged) "是" else "否"}")
                    report.backupPath?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("备份：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (report.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("警告：${report.warnings.joinToString("；")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (report.errors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "失败：" + report.errors.joinToString("；") { "${it.id}: ${it.message}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}

private fun migrationSummary(report: MigrationReport?): String {
    if (report == null) return "未运行"
    return when {
        report.failed > 0 -> "迁移 ${report.migrated} 篇，${report.failed} 篇失败"
        report.completed && report.migrated == 0 -> "已检查，没有需要迁移的便签"
        report.completed -> "已迁移 ${report.migrated} 篇"
        else -> "迁移未完成"
    }
}

@Composable
private fun StatusText(text: String, isError: Boolean) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent
)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ThemeOption(name: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Default.Check, "已选", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private val AsteriskPassword = VisualTransformation { text ->
    TransformedText(
        AnnotatedString("*".repeat(text.text.length)),
        OffsetMapping.Identity
    )
}
