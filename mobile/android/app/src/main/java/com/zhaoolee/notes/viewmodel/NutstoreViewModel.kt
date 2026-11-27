package com.zhaoolee.notes.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhaoolee.notes.sync.InitialChoice
import com.zhaoolee.notes.sync.InitialPolicy
import com.zhaoolee.notes.sync.NutstoreSyncController
import com.zhaoolee.notes.sync.SyncOutcome
import com.zhaoolee.notes.sync.SyncReport
import com.zhaoolee.notes.sync.WebDavConfig
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
    val busy: Boolean = false,
    val initialChoice: InitialChoice? = null,
    val error: String? = null,
    val summary: String? = null,
    val lastSyncAt: Long? = null
)

class NutstoreViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = NutstoreSyncController(application)
    private val prefs = application.getSharedPreferences("nutstore-status", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(NutstoreUiState())
    val state: StateFlow<NutstoreUiState> = _state.asStateFlow()

    init {
        val connection = controller.currentConnection()
        val saved = connection?.let {
            runCatching { controller.hasPassword(it) }.getOrDefault(false)
        } ?: false
        _state.value = NutstoreUiState(
            server = connection?.server ?: WebDavConfig.DEFAULT_SERVER,
            username = connection?.username.orEmpty(),
            remoteRoot = connection?.remoteRoot ?: WebDavConfig.DEFAULT_REMOTE_ROOT,
            configured = connection != null,
            passwordSaved = saved,
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
                    initialChoice = null,
                    server = config.server,
                    username = config.username,
                    remoteRoot = config.remoteRoot
                )
            }
            runSync {
                if (reuseSaved) {
                    controller.sync(InitialPolicy.ASK)
                } else {
                    controller.sync(config, password, InitialPolicy.ASK)
                }
            }
        }
    }

    fun syncNow() {
        if (_state.value.busy) return
        val connection = controller.currentConnection()
        if (connection == null) {
            _state.update { it.copy(error = "请先连接坚果云") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, initialChoice = null) }
            runSync { controller.sync(InitialPolicy.ASK) }
        }
    }

    fun mergeBoth() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runSync(clearChoiceOnSuccess = true) {
                controller.sync(InitialPolicy.MERGE_BOTH)
            }
        }
    }

    fun dismissInitialChoice() {
        _state.update { it.copy(initialChoice = null) }
    }

    fun disconnect() {
        if (_state.value.busy) return
        runCatching { controller.disconnect() }
            .onFailure { e -> _state.update { it.copy(error = message(e)) } }
        runCatching { NutstoreSyncScheduler.cancel(getApplication()) }
        prefs.edit().clear().apply()
        _state.value = NutstoreUiState(
            error = _state.value.error,
            server = WebDavConfig.DEFAULT_SERVER
        )
    }

    private suspend fun runSync(clearChoiceOnSuccess: Boolean = false, action: suspend () -> SyncOutcome) {
        try {
            when (val outcome = action()) {
                is SyncOutcome.NeedsInitialChoice -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            configured = controller.currentConnection() != null,
                            passwordSaved = controller.currentConnection()?.let(controller::hasPassword) == true,
                            initialChoice = outcome.choice,
                            error = null
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
                    _state.update {
                        it.copy(
                            busy = false,
                            configured = connection != null,
                            passwordSaved = connection?.let(controller::hasPassword) == true,
                            initialChoice = if (clearChoiceOnSuccess) null else it.initialChoice,
                            error = null,
                            summary = summary,
                            lastSyncAt = now
                        )
                    }
                    NutstoreSyncScheduler.schedule(getApplication())
                }
            }
        } catch (e: Exception) {
            val connection = runCatching { controller.currentConnection() }.getOrNull()
            val saved = connection?.let { config ->
                runCatching { controller.hasPassword(config) }.getOrDefault(false)
            } ?: false
            _state.update {
                it.copy(
                    busy = false,
                    configured = connection != null,
                    passwordSaved = saved,
                    error = message(e)
                )
            }
        }
    }

    private fun summary(report: SyncReport): String = buildString {
        append("上传 ${report.uploaded} · 下载 ${report.downloaded}")
        if (report.moved > 0) append(" · 移动 ${report.moved}")
        if (report.conflicts > 0) append(" · 冲突副本 ${report.conflicts}")
        if (report.remoteDeleted > 0) append(" · 删除远端 ${report.remoteDeleted}")
        if (report.localMovedToRecovery > 0) append(" · 移入恢复区 ${report.localMovedToRecovery}")
        if (report.warnings.isNotEmpty()) append(" · ${report.warnings.size} 条警告")
    }

    private fun message(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private companion object {
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_SUMMARY = "last_summary"
    }
}
