package com.zhaoolee.notes.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhaoolee.notes.sync.InitialChoice
import com.zhaoolee.notes.sync.InitialPolicy
import com.zhaoolee.notes.sync.NutstoreSyncController
import com.zhaoolee.notes.sync.RemoteEntry
import com.zhaoolee.notes.sync.SyncOutcome
import com.zhaoolee.notes.sync.SyncPhase
import com.zhaoolee.notes.sync.SyncPausedException
import com.zhaoolee.notes.sync.SyncProgress
import com.zhaoolee.notes.sync.SyncReport
import com.zhaoolee.notes.sync.WebDavConfig
import com.zhaoolee.notes.sync.WebDavErrorKind
import com.zhaoolee.notes.sync.WebDavException
import com.zhaoolee.notes.work.NutstoreSyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NutstoreUiState(
    val server: String = WebDavConfig.DEFAULT_SERVER,
    val username: String = "",
    val remoteRoot: String = WebDavConfig.DEFAULT_REMOTE_ROOT,
    val configured: Boolean = false,
    val passwordSaved: Boolean = false,
    val savedPassword: String = "",
    val busy: Boolean = false,
    val initialChoice: InitialChoice? = null,
    val error: String? = null,
    val paused: Boolean = false,
    val summary: String? = null,
    val lastSyncAt: Long? = null,
    val progress: SyncProgress? = null,
    val browser: RemoteFolderBrowserState? = null
)

data class RemoteFolderBrowserState(
    val path: String = "",
    val folders: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class NutstoreViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = NutstoreSyncController(application)
    private val prefs = application.getSharedPreferences("nutstore-status", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(NutstoreUiState())
    val state: StateFlow<NutstoreUiState> = _state.asStateFlow()
    private var browserAuth: Pair<WebDavConfig, String>? = null
    private var browserRequest = 0

    init {
        val connection = controller.currentConnection()
        val saved = connection?.let {
            runCatching { controller.savedPassword(it) }.getOrNull()
        }.orEmpty()
        _state.value = NutstoreUiState(
            server = connection?.server ?: WebDavConfig.DEFAULT_SERVER,
            username = connection?.username.orEmpty(),
            remoteRoot = connection?.remoteRoot ?: WebDavConfig.DEFAULT_REMOTE_ROOT,
            configured = connection != null,
            passwordSaved = saved.isNotEmpty(),
            savedPassword = saved,
            summary = prefs.getString(KEY_SUMMARY, null),
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 }
        )
    }

    fun connectAndSync(server: String, username: String, remoteRoot: String, password: String) {
        if (_state.value.busy) return
        val config = try {
            require(username.trim().isNotEmpty()) { "请填写坚果云账号邮箱" }
            WebDavConfig.create(server, username, remoteRoot)
        } catch (e: Exception) {
            _state.update { it.copy(error = message(e)) }
            return
        }
        val current = controller.currentConnection()
        val reuseSaved = password.isBlank() &&
            current == config &&
            runCatching { controller.hasPassword(config) }.getOrDefault(false)
        if (!reuseSaved && password.isBlank()) {
            _state.update { it.copy(error = "请填写坚果云第三方应用密码") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    error = null,
                    paused = false,
                    initialChoice = null,
                    progress = SyncProgress(SyncPhase.CONNECTING),
                    server = config.server,
                    username = config.username,
                    remoteRoot = config.remoteRoot
                )
            }
            runSync { onProgress ->
                if (reuseSaved) {
                    controller.sync(InitialPolicy.ASK, onProgress = onProgress)
                } else {
                    controller.sync(config, password, InitialPolicy.ASK, onProgress = onProgress)
                }
            }
        }
    }

    fun openFolderBrowser(server: String, username: String, remoteRoot: String, password: String) {
        val config = try {
            WebDavConfig.create(server, username, remoteRoot)
        } catch (e: Exception) {
            _state.update { it.copy(error = message(e), paused = false) }
            return
        }
        val saved = controller.savedPassword(config)
        val actualPassword = password.ifBlank { saved.orEmpty() }
        if (actualPassword.isBlank()) {
            _state.update { it.copy(error = "请先填写坚果云第三方应用密码", paused = false) }
            return
        }
        browserAuth = config to actualPassword
        val start = config.remoteRoot.substringBeforeLast('/', "")
        _state.update { it.copy(browser = RemoteFolderBrowserState(path = start, loading = true), error = null) }
        browseRemoteFolder(start)
    }

    fun browseRemoteFolder(path: String) {
        val auth = browserAuth ?: return
        val request = ++browserRequest
        _state.update {
            it.copy(browser = (it.browser ?: RemoteFolderBrowserState()).copy(path = path, loading = true, error = null))
        }
        viewModelScope.launch {
            try {
                val folders = controller.listRemoteFolders(auth.first, auth.second, path).filter { it.isDir }
                if (request != browserRequest) return@launch
                _state.update {
                    it.copy(browser = it.browser?.copy(folders = folders, loading = false))
                }
            } catch (e: Exception) {
                if (request != browserRequest) return@launch
                _state.update {
                    it.copy(browser = it.browser?.copy(loading = false, error = message(e)))
                }
            }
        }
    }

    fun browseRemoteFolderUp() {
        val path = _state.value.browser?.path ?: return
        browseRemoteFolder(path.substringBeforeLast('/', ""))
    }

    fun chooseRemoteFolder() {
        val path = _state.value.browser?.path.orEmpty()
        if (path.isBlank()) return
        browserAuth = null
        browserRequest++
        _state.update { it.copy(remoteRoot = path, browser = null) }
    }

    fun closeFolderBrowser() {
        browserAuth = null
        browserRequest++
        _state.update { it.copy(browser = null) }
    }

    fun syncNow() {
        if (_state.value.busy) return
        val connection = controller.currentConnection()
        if (connection == null) {
            _state.update { it.copy(error = "请先连接坚果云", paused = false) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    error = null,
                    paused = false,
                    initialChoice = null,
                    progress = SyncProgress(SyncPhase.CONNECTING)
                )
            }
            runSync { onProgress -> controller.sync(InitialPolicy.ASK, onProgress = onProgress) }
        }
    }

    fun mergeBoth() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    error = null,
                    paused = false,
                    initialChoice = null,
                    progress = SyncProgress(SyncPhase.CONNECTING)
                )
            }
            runSync(clearChoiceOnSuccess = true) { onProgress ->
                controller.sync(InitialPolicy.MERGE_BOTH, onProgress = onProgress)
            }
        }
    }

    fun dismissInitialChoice() {
        _state.update { it.copy(initialChoice = null) }
    }

    fun disconnect() {
        if (_state.value.busy) return
        browserAuth = null
        browserRequest++
        runCatching { controller.disconnect() }
            .onFailure { e -> _state.update { it.copy(error = message(e)) } }
        runCatching { NutstoreSyncScheduler.cancel(getApplication()) }
        prefs.edit().clear().apply()
        _state.value = NutstoreUiState(
            error = _state.value.error,
            server = WebDavConfig.DEFAULT_SERVER
        )
    }

    private suspend fun runSync(
        clearChoiceOnSuccess: Boolean = false,
        action: suspend ((SyncProgress) -> Unit) -> SyncOutcome
    ) {
        try {
            val onProgress: (SyncProgress) -> Unit = { progress ->
                _state.update { it.copy(progress = progress) }
            }
            when (val outcome = action(onProgress)) {
                is SyncOutcome.NeedsInitialChoice -> {
                    val connection = controller.currentConnection()
                    val saved = savedPassword()
                    _state.update {
                        it.copy(
                            busy = false,
                            configured = connection != null,
                            passwordSaved = saved.isNotEmpty(),
                            savedPassword = saved,
                            initialChoice = outcome.choice,
                            error = null,
                            paused = false,
                            progress = null
                        )
                    }
                }
                is SyncOutcome.Synced -> {
                    val now = System.currentTimeMillis()
                    val summary = summary(outcome.report)
                    prefs.edit()
                        .putLong(KEY_LAST_SYNC, now)
                        .putString(KEY_SUMMARY, summary)
                        .apply()
                    val connection = controller.currentConnection()
                    val saved = savedPassword()
                    _state.update {
                        it.copy(
                            busy = false,
                            configured = connection != null,
                            passwordSaved = saved.isNotEmpty(),
                            savedPassword = saved,
                            initialChoice = if (clearChoiceOnSuccess) null else it.initialChoice,
                            error = null,
                            paused = false,
                            summary = summary,
                            lastSyncAt = now,
                            progress = null
                        )
                    }
                    NutstoreSyncScheduler.schedule(getApplication())
                }
            }
        } catch (e: Exception) {
            val paused = e is SyncPausedException ||
                (e is WebDavException && e.kind == WebDavErrorKind.RATE_LIMITED)
            if (paused) runCatching { NutstoreSyncScheduler.schedule(getApplication()) }
            val connection = runCatching { controller.currentConnection() }.getOrNull()
            val saved = savedPassword()
            _state.update {
                it.copy(
                    busy = false,
                    configured = connection != null,
                    passwordSaved = saved.isNotEmpty(),
                    savedPassword = saved,
                    error = message(e),
                    paused = paused,
                    progress = null
                )
            }
        }
    }

    private fun savedPassword(): String =
        controller.currentConnection()?.let { connection ->
            runCatching { controller.savedPassword(connection) }.getOrNull()
        }.orEmpty()

    private fun summary(report: SyncReport): String {
        val changed = report.uploaded + report.downloaded + report.moved +
            report.conflicts + report.remoteDeleted + report.localMovedToRecovery
        if (changed == 0 && report.verified == 0 && report.fingerprintSkipped == 0 && report.warnings.isEmpty()) {
            return "没有需要同步的更改"
        }
        return buildList {
            if (report.uploaded > 0) add("上传 ${report.uploaded}")
            if (report.downloaded > 0) add("下载 ${report.downloaded}")
            if (report.moved > 0) add("移动 ${report.moved}")
            if (report.conflicts > 0) add("冲突副本 ${report.conflicts}")
            if (report.remoteDeleted > 0) add("删除远端 ${report.remoteDeleted}")
            if (report.localMovedToRecovery > 0) add("移入恢复区 ${report.localMovedToRecovery}")
            if (report.verified > 0) add("比对 ${report.verified}")
            if (report.fingerprintSkipped > 0) add("指纹跳过 ${report.fingerprintSkipped}")
            if (report.warnings.isNotEmpty()) add("${report.warnings.size} 条警告")
        }.joinToString(" · ")
    }

    private fun message(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private companion object {
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_SUMMARY = "last_summary"
    }
}
