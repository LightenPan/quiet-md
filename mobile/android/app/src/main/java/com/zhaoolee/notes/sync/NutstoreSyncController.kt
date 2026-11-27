package com.zhaoolee.notes.sync

import android.content.Context
import android.os.Build
import com.google.gson.GsonBuilder
import java.io.File
import java.time.Clock

class NutstoreSyncController(
    context: Context,
    private val libraryRoot: File = File(context.filesDir, "Quiet.md"),
    private val deviceName: String = defaultDeviceName(),
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val appContext = context.applicationContext
    private val stateDir = File(appContext.noBackupFilesDir, "nutstore")
    private val credentials = AndroidNutstoreCredentialVault(appContext)
    private val connections = ConnectionStore(File(stateDir, "connection.json"))

    fun currentConnection(): WebDavConfig? = connections.load()

    fun hasPassword(): Boolean = currentConnection()?.let { hasPassword(it) } ?: false

    fun hasPassword(config: WebDavConfig): Boolean =
        runCatching { credentials.load(config) }.getOrNull() != null

    fun connect(config: WebDavConfig, password: String) {
        val normalized = WebDavConfig.create(config.server, config.username, config.remoteRoot)
        val previous = connections.load()
        if (previous != null && previous != normalized) resetState()
        connections.save(normalized)
        credentials.save(normalized, password)
    }

    suspend fun sync(
        config: WebDavConfig,
        password: String,
        policy: InitialPolicy = InitialPolicy.ASK
    ): SyncOutcome {
        connect(config, password)
        return sync(policy)
    }

    suspend fun sync(policy: InitialPolicy = InitialPolicy.ASK): SyncOutcome {
        val config = connections.load()
            ?: throw SyncException("请先填写坚果云连接信息")
        val password = credentials.load(config)
            ?: throw SyncException("请重新输入坚果云第三方应用密码")
        if (!libraryRoot.isDirectory && !libraryRoot.mkdirs()) {
            throw SyncException("无法创建本地文件库：${libraryRoot.path}")
        }
        val api = NutstoreWebDavClient(config, password)
        val engine = NutstoreSyncEngine(
            SyncOptions(libraryRoot, stateDir, config, deviceName, clock),
            stateStore = SyncStateStore(stateDir)
        )
        return engine.sync(api, policy)
    }

    fun disconnect() {
        connections.load()?.let(credentials::delete)
        resetState()
    }

    fun resetState() {
        val state = SyncStateStore(stateDir).stateFile
        if (!state.isFile) return
        stateDir.mkdirs()
        val reset = File(stateDir, "state.json.reset-${System.currentTimeMillis()}")
        if (!state.renameTo(reset)) {
            throw SyncException("无法重置坚果云同步状态：${state.path}")
        }
    }

}

private fun defaultDeviceName(): String =
    Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Android"

private class ConnectionStore(private val file: File) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): WebDavConfig? {
        if (!file.isFile) return null
        return try {
            val config = gson.fromJson(file.readText(Charsets.UTF_8), WebDavConfig::class.java)
                ?: return null
            WebDavConfig.create(config.server, config.username, config.remoteRoot)
        } catch (_: Exception) {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            null
        }
    }

    fun save(config: WebDavConfig) {
        atomicWrite(file, gson.toJson(config).toByteArray(Charsets.UTF_8))
    }
}
