package com.zhaoolee.notes.sync

import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID

const val SYNC_STATE_VERSION = 1

data class SyncFileState(
    val etag: String? = null,
    val size: Long? = null,
    val modified: String? = null,
    val sha256: String = ""
)

data class SyncState(
    val v: Int = SYNC_STATE_VERSION,
    val server: String = "",
    val username: String = "",
    val remoteRoot: String = "",
    val scanComplete: Boolean = false,
    val files: LinkedHashMap<String, SyncFileState> = linkedMapOf(),
    val folders: LinkedHashSet<String> = linkedSetOf()
) {
    fun referencedHashes(): Set<String> = files.values.mapTo(linkedSetOf()) { it.sha256 }.filterTo(linkedSetOf()) { it.isNotEmpty() }
}

data class LoadedSyncState(
    val state: SyncState,
    val damagedPath: File? = null
)

class SyncStateStore(private val dir: File) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    val stateFile: File get() = File(dir, "state.json")

    fun load(): LoadedSyncState {
        if (!stateFile.isFile) return LoadedSyncState(SyncState())
        val parsed = try {
            gson.fromJson(stateFile.readText(Charsets.UTF_8), SyncState::class.java)
        } catch (e: Exception) {
            dir.mkdirs()
            val damaged = File(dir, "state.json.corrupt-${System.currentTimeMillis()}")
            runCatching { stateFile.renameTo(damaged) }
            return LoadedSyncState(SyncState(), damaged)
        }
        if (parsed == null) return LoadedSyncState(SyncState())
        if (parsed.v > SYNC_STATE_VERSION) {
            throw SyncException("坚果云同步状态由更新版本的 Quiet.md 创建")
        }
        return LoadedSyncState(parsed.copy(v = SYNC_STATE_VERSION))
    }

    fun save(state: SyncState) {
        dir.mkdirs()
        val bytes = gson.toJson(state.copy(v = SYNC_STATE_VERSION)).toByteArray(Charsets.UTF_8)
        atomicWrite(stateFile, bytes)
    }
}

internal fun atomicWrite(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile ?: target.absoluteFile.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SyncOptions(
    val localRoot: File,
    val stateDir: File,
    val config: WebDavConfig,
    val deviceName: String,
    val clock: Clock = Clock.systemDefaultZone()
)

enum class InitialPolicy {
    ASK,
    UPLOAD_LOCAL,
    DOWNLOAD_REMOTE,
    MERGE_BOTH
}

data class InitialChoice(
    val localFiles: Int,
    val remoteFiles: Int
)

data class SyncReport(
    var uploaded: Int = 0,
    var downloaded: Int = 0,
    var moved: Int = 0,
    var conflicts: Int = 0,
    var remoteDeleted: Int = 0,
    var localMovedToRecovery: Int = 0,
    val changedPaths: LinkedHashSet<String> = linkedSetOf(),
    val warnings: MutableList<String> = mutableListOf(),
    val protectedPaths: LinkedHashSet<String> = linkedSetOf()
)

sealed class SyncOutcome {
    data class Synced(val report: SyncReport) : SyncOutcome()
    data class NeedsInitialChoice(val choice: InitialChoice) : SyncOutcome()
}
