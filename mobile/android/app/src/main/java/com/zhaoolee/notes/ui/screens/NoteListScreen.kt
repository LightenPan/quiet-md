package com.zhaoolee.notes.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhaoolee.notes.library.LibraryNote
import com.zhaoolee.notes.viewmodel.NoteViewModel
import com.zhaoolee.notes.viewmodel.ViewMode
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    modifier: Modifier,
    viewModel: NoteViewModel,
    onNoteClick: (LibraryNote) -> Unit,
    onNewNote: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val ui by viewModel.ui.collectAsState()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val tree = remember(ui.folders) { buildFolderTree(ui.folders) }
    var expanded by remember(tree) {
        mutableStateOf(tree.filter { it.children.isNotEmpty() }.map { it.path }.toSet())
    }
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val searchActive = searchVisible || ui.query.isNotEmpty()

    LaunchedEffect(searchVisible) {
        if (searchVisible) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(drawerState.isOpen, ui.selectedFolder) {
        if (!drawerState.isOpen) return@LaunchedEffect
        val selected = ui.selectedFolder ?: return@LaunchedEffect
        val parts = selected.split('/')
        var prefix = ""
        val ancestors = mutableSetOf<String>()
        for (part in parts.dropLast(1)) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            ancestors.add(prefix)
        }
        if (ancestors.isNotEmpty()) expanded = expanded + ancestors
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            FolderTreeDrawer(
                tree = tree,
                folderCount = ui.folders.size,
                selected = ui.selectedFolder,
                favoritesActive = ui.viewMode == ViewMode.FAVORITES,
                expanded = expanded,
                onToggle = { path ->
                    expanded = if (path in expanded) expanded - path else expanded + path
                },
                onSelect = { folder ->
                    viewModel.browseFolder(folder)
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    "锤子笔记",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    when {
                                        ui.viewMode == ViewMode.FAVORITES -> "只看收藏"
                                        ui.selectedFolder != null -> ui.selectedFolder.orEmpty()
                                        else -> "全部便签"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                moreOpen = false
                                keyboard?.hide()
                                scope.launch {
                                    if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                }
                            }) {
                                Icon(
                                    if (drawerState.isOpen) Icons.Default.FolderOpen else Icons.Default.Folder,
                                    "文件夹目录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = {
                                    keyboard?.hide()
                                    moreOpen = true
                                }) {
                                    Icon(Icons.Default.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(
                                    expanded = moreOpen,
                                    onDismissRequest = { moreOpen = false },
                                    modifier = Modifier.width(210.dp),
                                    containerColor = MaterialTheme.colorScheme.surface
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("刷新") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        onClick = {
                                            moreOpen = false
                                            viewModel.refresh()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (ui.viewMode == ViewMode.FAVORITES) "显示全部" else "只看收藏") },
                                        leadingIcon = {
                                            Icon(
                                                if (ui.viewMode == ViewMode.FAVORITES) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                                null
                                            )
                                        },
                                        onClick = {
                                            moreOpen = false
                                            viewModel.setViewMode(
                                                if (ui.viewMode == ViewMode.FAVORITES) ViewMode.ALL else ViewMode.FAVORITES
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (searchActive) "关闭搜索" else "搜索") },
                                        leadingIcon = { Icon(Icons.Default.Search, null) },
                                        onClick = {
                                            moreOpen = false
                                            if (searchActive) {
                                                searchVisible = false
                                                viewModel.setQuery("")
                                                keyboard?.hide()
                                            } else {
                                                searchVisible = true
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("设置") },
                                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                                        onClick = {
                                            moreOpen = false
                                            onSettingsClick()
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("新建便签") },
                                        leadingIcon = { Icon(Icons.Default.Add, null) },
                                        onClick = {
                                            moreOpen = false
                                            onNewNote()
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                    AnimatedVisibility(visible = searchActive) {
                        TextField(
                            value = ui.query,
                            onValueChange = viewModel::setQuery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .focusRequester(searchFocus),
                            placeholder = {
                                Text("搜索标题、正文或文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = {
                                if (searchActive) {
                                    IconButton(onClick = {
                                        searchVisible = false
                                        viewModel.setQuery("")
                                        keyboard?.hide()
                                    }) {
                                        Icon(Icons.Default.Close, "关闭搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    ui.loading -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    ui.notes.isEmpty() -> {
                        EmptyLibrary(
                            favoritesOnly = ui.viewMode == ViewMode.FAVORITES,
                            searching = ui.query.isNotBlank(),
                            folder = ui.selectedFolder,
                            hasError = ui.error != null,
                            error = ui.error,
                            onRetry = viewModel::refresh,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (ui.error != null || ui.warnings.isNotEmpty()) {
                                item {
                                    StatusBanner(
                                        text = ui.error ?: "${ui.warnings.size} 个文件无法读取；其余便签仍可使用",
                                        isError = ui.error != null
                                    )
                                }
                            }
                            items(ui.notes, key = { it.relativePath }) { note ->
                                NoteCard(
                                    note = note,
                                    dateText = dateFormat.format(Date(note.modifiedAt)),
                                    onClick = { onNoteClick(note) },
                                    onFavorite = { viewModel.toggleFavorite(note) }
                                )
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
                if (ui.refreshing && !ui.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}

@Composable
private fun FolderTreeDrawer(
    tree: List<FolderNode>,
    folderCount: Int,
    selected: String?,
    favoritesActive: Boolean,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onSelect: (String?) -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp).fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerContentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    "文件夹",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$folderCount 个文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                FolderTreeRow(
                    label = "全部便签",
                    depth = 0,
                    selected = selected == null && !favoritesActive,
                    hasChildren = false,
                    expanded = false,
                    icon = Icons.Default.FolderOpen,
                    onToggle = {},
                    onClick = { onSelect(null) }
                )
                FolderTreeRows(
                    nodes = tree,
                    depth = 0,
                    selected = selected,
                    expanded = expanded,
                    onToggle = onToggle,
                    onSelect = onSelect
                )
                if (tree.isEmpty()) {
                    Text(
                        "还没有文件夹",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderTreeRows(
    nodes: List<FolderNode>,
    depth: Int,
    selected: String?,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onSelect: (String?) -> Unit
) {
    for (node in nodes) {
        val open = node.path in expanded
        FolderTreeRow(
            label = node.name,
            depth = depth,
            selected = selected == node.path,
            hasChildren = node.children.isNotEmpty(),
            expanded = open,
            icon = if (open) Icons.Default.FolderOpen else Icons.Default.Folder,
            onToggle = { onToggle(node.path) },
            onClick = { onSelect(node.path) }
        )
        if (open && node.children.isNotEmpty()) {
            FolderTreeRows(
                nodes = node.children,
                depth = depth + 1,
                selected = selected,
                expanded = expanded,
                onToggle = onToggle,
                onSelect = onSelect
            )
        }
    }
}

@Composable
private fun FolderTreeRow(
    label: String,
    depth: Int,
    selected: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    icon: ImageVector,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = (12 + depth * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasChildren) {
            Box(
                Modifier
                    .size(28.dp)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    if (expanded) "收起" else "展开",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(28.dp))
        }
        Icon(
            icon,
            null,
            Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class FolderNode(
    val name: String,
    val path: String,
    val children: MutableList<FolderNode> = mutableListOf()
)

private fun buildFolderTree(folders: List<String>): List<FolderNode> {
    val roots = mutableListOf<FolderNode>()
    val byPath = linkedMapOf<String, FolderNode>()
    for (folder in folders) {
        var parent = roots
        var prefix = ""
        for (part in folder.split('/')) {
            if (part.isEmpty()) continue
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val node = byPath.getOrPut(prefix) {
                FolderNode(part, prefix).also(parent::add)
            }
            parent = node.children
        }
    }
    val collator = Collator.getInstance(Locale.getDefault())
    fun sort(nodes: MutableList<FolderNode>) {
        nodes.sortWith { a, b -> collator.compare(a.name, b.name) }
        nodes.forEach { sort(it.children) }
    }
    sort(roots)
    return roots
}

@Composable
private fun NoteCard(
    note: LibraryNote,
    dateText: String,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    val preview = note.body.replace('\n', ' ').trim().take(160)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (note.pinned) {
                        Icon(
                            Icons.Default.PushPin,
                            "置顶",
                            Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (note.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        "收藏",
                        Modifier.size(18.dp),
                        tint = if (note.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (note.folder.isNotEmpty() || !note.editable) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.folder.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                note.folder,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (!note.editable) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "桌面端文档 · 只读",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (preview.isEmpty()) "空白便签" else preview,
                style = MaterialTheme.typography.bodyLarge,
                color = if (preview.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                dateText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBanner(text: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun EmptyLibrary(
    favoritesOnly: Boolean,
    searching: Boolean,
    folder: String?,
    hasError: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📝", fontSize = 56.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                hasError -> "文件库无法读取"
                searching -> "没有匹配的便签"
                favoritesOnly -> "没有收藏的便签"
                folder != null -> "此文件夹没有便签"
                else -> "没有便签"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (hasError) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        } else if (folder != null && !searching) {
            Spacer(Modifier.height(8.dp))
            Text(
                "打开左侧文件夹目录选择其他文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!searching && !favoritesOnly) {
            Spacer(Modifier.height(8.dp))
            Text(
                "点击右上角更多按钮创建第一篇 Markdown 便签",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
