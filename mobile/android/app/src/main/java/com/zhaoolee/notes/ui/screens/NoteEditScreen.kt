package com.zhaoolee.notes.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhaoolee.notes.viewmodel.NoteViewModel
import com.zhaoolee.notes.viewmodel.SaveState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    modifier: Modifier,
    viewModel: NoteViewModel,
    footerText: String,
    onMoved: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.editor.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showReload by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }
    var moveFolder by rememberSaveable { mutableStateOf("") }

    val editor = state
    if (editor == null || (editor.loading && editor.note == null)) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    fun leaveEditor() {
        focusManager.clearFocus()
        viewModel.normalizeTitleOnBlur()
        onBack()
    }

    fun closeMoveDialog() {
        if (!editor.moving) {
            showMove = false
            viewModel.clearMoveError()
        }
    }

    Scaffold(
        modifier = modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (editor.readOnly) "只读文档" else "编辑便签",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        editor.note?.let {
                            Text(
                                dateFormat.format(Date(it.modifiedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::leaveEditor) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    editor.note?.let { note ->
                        IconButton(onClick = { viewModel.togglePinned(note) }) {
                            Icon(
                                Icons.Default.PushPin,
                                "置顶",
                                tint = if (note.pinned) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        IconButton(onClick = { viewModel.toggleFavorite(note) }) {
                            Icon(
                                if (note.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                "收藏",
                                tint = if (note.favorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    IconButton(onClick = {
                        shareNote(context, editor.title, editor.body)
                    }) {
                        Icon(
                            Icons.Default.Share,
                            "分享",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showDelete = true },
                        enabled = editor.note?.editable == true
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
            if (editor.error != null) {
                SaveErrorBanner(
                    text = editor.error,
                    conflict = editor.conflict,
                    canRetry = !editor.conflict,
                    onRetry = viewModel::retrySave,
                    onReload = { showReload = true },
                    onCopy = {
                        copyToClipboard(context, editor.title, editor.body)
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            if (editor.readOnly) {
                Text(
                    "这是桌面端的富文档或画板文件。Android 首期只读打开，请在桌面端 Quiet.md 中编辑。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "文件夹 · ${editor.note?.folder.orEmpty().ifEmpty { "根目录" }}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(
                    onClick = {
                        moveFolder = editor.note?.folder.orEmpty()
                        viewModel.clearMoveError()
                        showMove = true
                    },
                    enabled = !editor.readOnly && !editor.moving,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("移动")
                }
            }
            OutlinedTextField(
                value = editor.title,
                onValueChange = viewModel::updateTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (!it.isFocused) viewModel.normalizeTitleOnBlur()
                    },
                readOnly = editor.readOnly,
                singleLine = true,
                placeholder = { Text("标题") },
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            OutlinedTextField(
                value = editor.body,
                onValueChange = viewModel::updateBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp),
                readOnly = editor.readOnly,
                placeholder = {
                    Text(
                        if (editor.readOnly) "只读内容" else "开始记录，支持 Markdown…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 26.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (editor.saveState) {
                        SaveState.SAVED -> "已保存"
                        SaveState.DIRTY -> "未保存"
                        SaveState.SAVING -> "正在保存…"
                        SaveState.FAILED -> "保存失败"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (editor.saveState == SaveState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    footerText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这篇便签？") },
            text = { Text("文件会移到本机回收站，不会立即永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.deleteCurrent(onBack)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }

    if (showReload) {
        AlertDialog(
            onDismissRequest = { showReload = false },
            title = { Text("放弃当前草稿并重新载入？") },
            text = { Text("磁盘上的版本会替换当前编辑内容。建议先复制正文。") },
            confirmButton = {
                TextButton(onClick = {
                    showReload = false
                    viewModel.reloadCurrent()
                }) {
                    Text("重新载入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReload = false }) { Text("取消") }
            }
        )
    }

    if (showMove) {
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = ::closeMoveDialog,
            title = { Text("移动到文件夹") },
            text = {
                Column {
                    Text(
                        "输入文件夹路径。留空表示根目录；不存在的多级文件夹会自动创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = moveFolder,
                        onValueChange = { moveFolder = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !editor.moving,
                        label = { Text("文件夹路径") },
                        placeholder = { Text("例如：工作/2026") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                        })
                    )
                    editor.moveError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.moveCurrent(moveFolder) { path ->
                            showMove = false
                            onMoved(path)
                        }
                    },
                    enabled = !editor.moving
                ) {
                    if (editor.moving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("移动")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = ::closeMoveDialog, enabled = !editor.moving) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SaveErrorBanner(
    text: String,
    conflict: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
            .padding(12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (canRetry) TextButton(onClick = onRetry) { Text("重试") }
            if (conflict) TextButton(onClick = onReload) { Text("重新载入") }
            TextButton(onClick = onCopy) { Text("复制正文") }
        }
    }
}

private fun shareNote(context: Context, title: String, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, buildString {
            append(title)
            if (body.isNotBlank()) append("\n\n").append(body)
        })
    }
    context.startActivity(Intent.createChooser(intent, "分享便签"))
}

private fun copyToClipboard(context: Context, title: String, body: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(title, body))
    Toast.makeText(context, "正文已复制", Toast.LENGTH_SHORT).show()
}
