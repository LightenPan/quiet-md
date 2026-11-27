package com.zhaoolee.notes.sync

enum class SyncPhase {
    CONNECTING,
    SCANNING_REMOTE,
    SCANNING_LOCAL,
    COMPARING,
    DOWNLOADING,
    UPLOADING,
    DELETING,
    FINISHING,
    DONE
}

data class SyncProgress(
    val phase: SyncPhase,
    val localFiles: Int = 0,
    val remoteFiles: Int = 0,
    val done: Int = 0,
    val total: Int? = null,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val moved: Int = 0,
    val conflicts: Int = 0,
    val remoteDeleted: Int = 0,
    val localMovedToRecovery: Int = 0,
    val verified: Int = 0,
    val fingerprintSkipped: Int = 0,
    val warnings: Int = 0,
    val detail: String? = null
)
