package com.zhaoolee.notes.sync

import com.zhaoolee.notes.library.LibraryRootLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NutstoreSyncEngine(
    private val options: SyncOptions,
    private val fileSystem: SyncFileSystem = JavaSyncFileSystem(
        options.localRoot,
        File(options.stateDir, "removed")
    ),
    private val stateStore: SyncStateStore = SyncStateStore(options.stateDir),
    private val onProgress: ((SyncProgress) -> Unit)? = null
) {
    private val stateMutex = locks.computeIfAbsent(options.stateDir.absoluteFile.path) { Mutex() }
    private var state = SyncState()
    private var stateDirty = false
    private var loadWarning: String? = null

    // NoteLibrary takes only the root lock, so always take root before state.
    suspend fun sync(api: WebDavApi, policy: InitialPolicy = InitialPolicy.ASK): SyncOutcome =
        LibraryRootLock.mutexFor(options.localRoot).withLock {
            stateMutex.withLock {
                val loaded = stateStore.load()
                state = loaded.state
                loadWarning = loaded.damagedPath?.let {
                    "坚果云同步状态已损坏，旧文件保留在 ${it.path}"
                }
                withContext(Dispatchers.IO) {
                    try {
                        syncLocked(api, policy)
                    } catch (e: Exception) {
                        if (stateDirty) runCatching { stateStore.save(state) }
                        throw e
                    }
                }
            }
        }

    private suspend fun syncLocked(api: WebDavApi, policy: InitialPolicy): SyncOutcome {
        val report = SyncReport()
        emit(report, SyncPhase.CONNECTING)
        validateConfig()
        val persisted = if (state.scanComplete) null else state.initialPolicy?.let {
            runCatching { InitialPolicy.valueOf(it) }.getOrNull()
        }
        val resuming = !state.scanComplete && persisted != null
        if (!state.scanComplete && persisted == null && policy == InitialPolicy.MERGE_BOTH) {
            state = state.copy(initialPolicy = policy.name)
            stateDirty = true
            stateStore.save(state)
            stateDirty = false
        }
        val effectivePolicy = persisted ?: policy
        api.ensureRoot()
        emit(report, SyncPhase.SCANNING_REMOTE)
        val remote = scanRemote(api)
        emit(report, SyncPhase.SCANNING_LOCAL, remoteFiles = remote.files.size)
        val local = fileSystem.scan()
        report.protectedPaths.addAll(local.protectedPaths)
        report.protectedPaths.addAll(remote.protectedPaths)
        loadWarning?.let { report.warnings.add(it); loadWarning = null }
        report.warnings.addAll(remote.warnings)
        report.warnings.addAll(local.warnings)
        emit(report, SyncPhase.COMPARING, local.files.size, remote.files.size)
        preflightPaths(remote, local)

        if (state.scanComplete && remote.files.isEmpty() && remote.protectedPaths.isEmpty() &&
            state.files.isNotEmpty() && local.files.isNotEmpty()
        ) {
            throw SyncException("坚果云远程文件夹突然为空；为保护本地文件已暂停同步。如果确实要清空，请先备份本地文件，再重新扫描")
        }

        if (!state.scanComplete) {
            val outcome = initialSync(api, remote, local, effectivePolicy, resuming, report)
            if (outcome != null) {
                if (outcome is SyncOutcome.Synced) {
                    emit(report, SyncPhase.FINISHING, local.files.size, remote.files.size)
                }
                stateStore.save(state)
                stateDirty = false
                if (outcome is SyncOutcome.Synced) {
                    emit(report, SyncPhase.DONE, local.files.size, remote.files.size)
                }
                return outcome
            }
            state = state.copy(scanComplete = true, initialPolicy = null)
            stateDirty = true
            emit(report, SyncPhase.FINISHING, local.files.size, remote.files.size)
            stateStore.save(state)
            stateDirty = false
            emit(report, SyncPhase.DONE, local.files.size, remote.files.size)
            return SyncOutcome.Synced(report)
        }

        reconcile(api, remote, local, report)
        val after = fileSystem.scan()
        report.warnings.addAll(after.warnings)
        pushLocal(api, after, report)
        emit(report, SyncPhase.FINISHING, after.files.size, remote.files.size)
        stateStore.save(state)
        stateDirty = false
        emit(report, SyncPhase.DONE, after.files.size, remote.files.size)
        return SyncOutcome.Synced(report)
    }

    private fun emit(
        report: SyncReport,
        phase: SyncPhase,
        localFiles: Int = 0,
        remoteFiles: Int = 0,
        done: Int = 0,
        total: Int? = null,
        detail: String? = null
    ) {
        onProgress?.invoke(
            SyncProgress(
                phase = phase,
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                done = done,
                total = total,
                uploaded = report.uploaded,
                downloaded = report.downloaded,
                moved = report.moved,
                conflicts = report.conflicts,
                remoteDeleted = report.remoteDeleted,
                localMovedToRecovery = report.localMovedToRecovery,
                verified = report.verified,
                fingerprintSkipped = report.fingerprintSkipped,
                warnings = report.warnings.size,
                detail = detail
            )
        )
    }

    private fun validateConfig() {
        val config = options.config
        if (state.server.isNotEmpty() &&
            (state.server != config.server ||
                state.username != config.username ||
                state.remoteRoot != config.remoteRoot)
        ) {
            throw SyncException("坚果云账号或远程文件夹已经改变；请先重置这个文件夹的坚果云同步")
        }
        if (state.server != config.server || state.username != config.username || state.remoteRoot != config.remoteRoot) {
            state = state.copy(
                server = config.server,
                username = config.username,
                remoteRoot = config.remoteRoot
            )
            stateDirty = true
        }
    }

    private suspend fun initialSync(
        api: WebDavApi,
        remote: RemoteTree,
        local: LocalScan,
        policy: InitialPolicy,
        resuming: Boolean,
        report: SyncReport
    ): SyncOutcome? {
        val remoteHas = remote.files.isNotEmpty()
        val localHas = local.files.isNotEmpty()
        var selectedPolicy = policy
        if (remoteHas && localHas && policy == InitialPolicy.ASK && !resuming) {
            val comparison = compareInitial(remote, local)
            if (comparison.overlap > 0 && comparison.conflicts == 0) {
                selectedPolicy = InitialPolicy.MERGE_BOTH
                if (state.initialPolicy == null) {
                    state = state.copy(initialPolicy = selectedPolicy.name)
                    stateDirty = true
                    stateStore.save(state)
                    stateDirty = false
                }
            } else {
                return SyncOutcome.NeedsInitialChoice(
                    InitialChoice(local.files.size, remote.files.size, comparison.conflicts, comparison.unverified)
                )
            }
        }
        if (selectedPolicy == InitialPolicy.UPLOAD_LOCAL && remoteHas && !resuming) {
            throw SyncException("坚果云远程文件夹已有文件；若要保留两边，请选择合并两边")
        }
        if (selectedPolicy == InitialPolicy.DOWNLOAD_REMOTE && localHas && !resuming) {
            throw SyncException("本地文件夹已有文件；若要保留两边，请选择合并两边")
        }

        val actual = when {
            selectedPolicy != InitialPolicy.ASK -> selectedPolicy
            remoteHas && !localHas -> InitialPolicy.DOWNLOAD_REMOTE
            !remoteHas && localHas -> InitialPolicy.UPLOAD_LOCAL
            else -> null
        }
        if (actual != null && state.initialPolicy == null) {
            state = state.copy(initialPolicy = actual.name)
            stateDirty = true
            stateStore.save(state)
            stateDirty = false
        }
        if (state.folders != remote.dirs) {
            state = state.copy(folders = LinkedHashSet(remote.dirs))
            stateDirty = true
        }
        if (remoteHas && localHas && selectedPolicy == InitialPolicy.MERGE_BOTH) {
            val ordered = remote.files.entries.sortedBy { if (local.files.containsKey(it.key)) 1 else 0 }
            val total = ordered.size
            for ((index, item) in ordered.withIndex()) {
                val (rel, entry) = item
                try {
                    if (alreadySynced(rel, entry, local, report)) continue
                    if (rel in report.protectedPaths) {
                        report.warnings.add("$rel 本机文件无法安全读取或超过上限，合并时未覆盖")
                        continue
                    }
                    if ((entry.size ?: 0) > SyncLimits.MAX_BYTES) {
                        report.warnings.add("$rel 超过 100 MB，未下载")
                        continue
                    }
                    emit(report, SyncPhase.DOWNLOADING, local.files.size, remote.files.size, index, total, rel)
                    val bytes = api.get(rel)
                    val remoteSha = sha256(bytes)
                    if (!fileSystem.isFile(rel)) {
                        download(entry, rel, bytes, report)
                        continue
                    }
                    val ours = fileSystem.read(rel)
                    val oursSha = sha256(ours)
                    if (oursSha == remoteSha) {
                        remember(rel, entry, remoteSha)
                        report.verified++
                        continue
                    }
                    writeRemoteConflict(api, rel, bytes, local.files.keys, report)
                    state.files[rel] = SyncFileState(
                        etag = entry.etag,
                        size = entry.size,
                        modified = entry.modified,
                        sha256 = oursSha,
                        pendingUpload = true
                    )
                    stateDirty = true
                    report.changedPaths.add(rel)
                } finally {
                    saveProgress(index + 1)
                }
            }
            val after = fileSystem.scan()
            report.warnings.addAll(after.warnings)
            pushLocal(api, after, report)
            state = state.copy(scanComplete = true, initialPolicy = null)
            stateDirty = true
            stateStore.save(state)
            stateDirty = false
            return SyncOutcome.Synced(report)
        }

        if (remoteHas && (!localHas || selectedPolicy == InitialPolicy.DOWNLOAD_REMOTE)) {
            val ordered = remote.files.entries.sortedBy { if (local.files.containsKey(it.key)) 1 else 0 }
            val total = ordered.size
            for ((index, item) in ordered.withIndex()) {
                val (rel, entry) = item
                try {
                    if (alreadySynced(rel, entry, local, report)) continue
                    if (rel in report.protectedPaths) {
                        report.warnings.add("$rel 本机文件无法安全读取或超过上限，未下载覆盖")
                        continue
                    }
                    if ((entry.size ?: 0) > SyncLimits.MAX_BYTES) {
                        report.warnings.add("$rel 超过 100 MB，未下载")
                        continue
                    }
                    emit(report, SyncPhase.DOWNLOADING, local.files.size, remote.files.size, index, total, rel)
                    download(entry, rel, api.get(rel), report)
                } finally {
                    saveProgress(index + 1)
                }
            }
        }
        if (localHas && (!remoteHas || selectedPolicy == InitialPolicy.UPLOAD_LOCAL)) {
            val sorted = local.files.keys.sorted()
            for ((index, rel) in sorted.withIndex()) {
                try {
                    if (alreadyUploaded(rel, local, remote, report)) continue
                    if (rel in report.protectedPaths) {
                        report.warnings.add("$rel 无法安全同步，未上传覆盖")
                        continue
                    }
                    emit(report, SyncPhase.UPLOADING, local.files.size, remote.files.size, index, sorted.size, rel)
                    uploadNew(api, rel, fileSystem.read(rel), report)
                } finally {
                    saveProgress(index + 1)
                }
            }
        }
        state = state.copy(scanComplete = true, initialPolicy = null)
        stateDirty = true
        return null
    }

    private suspend fun reconcile(
        api: WebDavApi,
        remote: RemoteTree,
        local: LocalScan,
        report: SyncReport
    ) {
        val oldDirs = state.folders.toSet()
        val oldPaths = state.files.keys.toSet()
        val movedTargets = linkedSetOf<String>()
        for ((old, new) in remoteMoves(remote, local)) {
            val info = local.files[old] ?: continue
            val oldState = state.files[old] ?: continue
            if (!fileSystem.isFile(old) || fileSystem.exists(new) || info.sha256 != oldState.sha256) continue
            fileSystem.moveFile(old, new)
            state.files.remove(old)
            stateDirty = true
            val entry = remote.files[new] ?: continue
            state.files[new] = SyncFileState(
                etag = entry.etag,
                size = entry.size,
                modified = entry.modified,
                sha256 = oldState.sha256
            )
            stateDirty = true
            movedTargets.add(new)
            report.moved++
            report.changedPaths.add(old)
            report.changedPaths.add(new)
        }
        if (state.folders != remote.dirs) {
            state = state.copy(folders = LinkedHashSet(remote.dirs))
            stateDirty = true
        }

        val remoteTotal = remote.files.size
        for ((index, item) in remote.files.entries.withIndex()) {
            val (rel, entry) = item
            try {
                emit(report, SyncPhase.COMPARING, local.files.size, remote.files.size, index, remoteTotal, rel)
                if (rel in movedTargets) continue
                applyRemote(api, rel, entry, local, report)
            } finally {
                saveProgress(index + 1)
            }
        }

        val removed = oldPaths.filter { it !in remote.files && state.files.containsKey(it) }
        for ((index, rel) in removed.withIndex()) {
            try {
                emit(report, SyncPhase.DELETING, local.files.size, remote.files.size, index, removed.size, rel)
                if (rel in remote.files || !state.files.containsKey(rel)) continue
                dropRemote(rel, report)
            } finally {
                saveProgress(index + 1)
            }
        }
        val obsoleteDirs = oldDirs - state.folders
        obsoleteDirs.sortedByDescending { it.count { ch -> ch == '/' } }.forEach {
            fileSystem.removeEmptyDirectory(it)
        }
    }

    private suspend fun applyRemote(
        api: WebDavApi,
        rel: String,
        entry: RemoteEntry,
        local: LocalScan,
        report: SyncReport
    ) {
        if (entry.size != null && entry.size > SyncLimits.MAX_BYTES) {
            report.warnings.add("$rel 超过 100 MB，未下载")
            report.protectedPaths.add(rel)
            return
        }
        if (rel in report.protectedPaths) {
            report.warnings.add("$rel 本机文件无法安全读取或超过上限，本轮未同步")
            return
        }
        val old = state.files[rel]
        if (old != null && !remoteChanged(old, entry)) {
            if (old.pendingUpload) {
                val fingerprint = fingerprintMatch(rel, entry, local.files[rel])
                if (fingerprint != null) {
                    remember(rel, entry, fingerprint)
                    report.fingerprintSkipped++
                    return
                }
                if (local.files.containsKey(rel)) return
            } else {
                if (local.files[rel]?.sha256 != old.sha256) {
                    val fingerprint = fingerprintMatch(rel, entry, local.files[rel])
                    if (fingerprint != null) {
                        remember(rel, entry, fingerprint)
                        report.fingerprintSkipped++
                        return
                    }
                }
                remember(rel, entry, old.sha256)
                return
            }
        }
        val fingerprint = fingerprintMatch(rel, entry, local.files[rel]) ?: run {
            val bytes = api.get(rel)
            val remoteSha = sha256(bytes)
            if (!fileSystem.isFile(rel)) {
                download(entry, rel, bytes, report)
                return
            }
            val ours = fileSystem.read(rel)
            val oursSha = sha256(ours)
            if (oursSha == remoteSha) {
                remember(rel, entry, remoteSha)
                report.verified++
                return
            }
            if (old == null) {
                writeRemoteConflict(api, rel, bytes, local.files.keys, report)
                remember(rel, entry, remoteSha)
                report.changedPaths.add(rel)
                return
            }
            if (oursSha == old.sha256 && !old.pendingUpload) {
                download(entry, rel, bytes, report)
                return
            }
            report.warnings.add("$rel 在两处都有修改；保留本机版本，远端版本另存为冲突副本")
            writeRemoteConflict(api, rel, bytes, local.files.keys, report)
            remember(rel, entry, remoteSha)
            report.changedPaths.add(rel)
            return
        }
        remember(rel, entry, fingerprint)
        report.fingerprintSkipped++
    }

    private fun download(entry: RemoteEntry, rel: String, bytes: ByteArray, report: SyncReport) {
        fileSystem.writeAtomic(rel, bytes)
        val hash = sha256(bytes)
        remember(rel, entry, hash)
        report.downloaded++
        report.changedPaths.add(rel)
    }

    private suspend fun writeRemoteConflict(
        api: WebDavApi,
        rel: String,
        bytes: ByteArray,
        localPaths: Set<String>,
        report: SyncReport
    ) {
        val known = linkedSetOf<String>().apply {
            addAll(state.files.keys)
            addAll(localPaths)
            addAll(report.changedPaths)
        }
        val conflict = localConflictName(
            rel,
            options.deviceName,
            known,
            fileSystem::exists,
            ZonedDateTime.now(options.clock)
        )
        fileSystem.writeAtomic(conflict, bytes)
        try {
            uploadNew(api, conflict, bytes, report)
        } catch (e: Exception) {
            report.warnings.add("$conflict 只保存在本机：${e.message ?: e.javaClass.simpleName}")
        }
        report.conflicts++
        report.changedPaths.add(conflict)
    }

    private fun dropRemote(rel: String, report: SyncReport) {
        val old = state.files.remove(rel) ?: return
        stateDirty = true
        if (!fileSystem.isFile(rel)) return
        val bytes = try {
            fileSystem.read(rel)
        } catch (e: Exception) {
            report.warnings.add("$rel 在坚果云被删除，但本机文件读取失败：${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (sha256(bytes) != old.sha256) {
            report.warnings.add("$rel 在坚果云被删除，但本机有修改；保留本机版本并重新上传")
            return
        }
        if (fileSystem.quarantine(rel)) {
            report.localMovedToRecovery++
            report.changedPaths.add(rel)
        }
    }

    private suspend fun pushLocal(api: WebDavApi, local: LocalScan, report: SyncReport) {
        val handled = linkedSetOf<String>()
        val missingStates = state.files
            .filterKeys { !local.files.containsKey(it) }
            .entries
            .groupBy { it.value.sha256 }
            .filter { (hash, entries) -> hash.isNotEmpty() && entries.size == 1 }
            .mapValues { it.value.single() }
        val newLocal = local.files.keys
            .filter { !state.files.containsKey(it) && it !in report.protectedPaths }
            .groupBy { local.files.getValue(it).sha256 }
        for ((hash, moved) in missingStates) {
            val candidates = newLocal[hash]?.filter { it !in handled } ?: continue
            if (candidates.size != 1) continue
            val old = moved.key
            val new = candidates.single()
            ensureRemoteDir(api, SyncPaths.parentRel(new))
            api.move(old, new, false)
            state.files.remove(old)
            stateDirty = true
            state.files[new] = SyncFileState(
                etag = moved.value.etag,
                size = local.files[new]?.size,
                modified = moved.value.modified,
                sha256 = hash
            )
            stateDirty = true
            handled.add(new)
            report.moved++
            report.changedPaths.add(old)
            report.changedPaths.add(new)
        }

        val sorted = local.files.keys.sorted()
        for ((index, rel) in sorted.withIndex()) {
            try {
                if (state.files.containsKey(rel) || rel in handled) continue
                if (rel in report.protectedPaths) {
                    report.warnings.add("$rel 本轮受保护，未上传")
                    continue
                }
                if (state.files.keys.any { SyncPaths.pathKey(it) == SyncPaths.pathKey(rel) }) {
                    throw SyncException("本机有仅大小写不同的同名文件，暂停同步：$rel")
                }
                emit(report, SyncPhase.UPLOADING, local.files.size, state.files.size, index, sorted.size, rel)
                uploadNew(api, rel, fileSystem.read(rel), report)
            } finally {
                saveProgress(index + 1)
            }
        }

        val existing = state.files.entries.toList()
        for ((index, item) in existing.withIndex()) {
            val (rel, old) = item
            try {
                val current = local.files[rel] ?: continue
                if (rel in handled || (current.sha256 == old.sha256 && !old.pendingUpload)) continue
                if (rel in report.protectedPaths) {
                    report.warnings.add("$rel 本轮受保护，未上传")
                    continue
                }
                emit(report, SyncPhase.UPLOADING, local.files.size, state.files.size, index, existing.size, rel)
                uploadExisting(api, rel, report)
            } finally {
                saveProgress(index + 1)
            }
        }

        val deleting = state.files.keys.toList()
        for ((index, rel) in deleting.withIndex()) {
            try {
                if (local.files.containsKey(rel) || rel in handled) continue
                if (rel in report.protectedPaths) {
                    report.warnings.add("$rel 本机文件无法安全读取或超过上限，未从坚果云删除")
                    continue
                }
                emit(report, SyncPhase.DELETING, local.files.size, state.files.size, index, deleting.size, rel)
                val stateBefore = state.files[rel] ?: continue
                api.remove(rel, false, stateBefore.etag)
                state.files.remove(rel)
                stateDirty = true
                report.remoteDeleted++
                report.changedPaths.add(rel)
            } finally {
                saveProgress(index + 1)
            }
        }
    }

    private suspend fun uploadExisting(api: WebDavApi, rel: String, report: SyncReport) {
        val bytes = fileSystem.read(rel)
        val hash = sha256(bytes)
        val expected = state.files[rel]?.etag ?: ""
        val entry = api.put(rel, bytes, expected)
        remember(rel, entry, hash)
        report.uploaded++
        report.changedPaths.add(rel)
    }

    private suspend fun uploadNew(api: WebDavApi, rel: String, bytes: ByteArray, report: SyncReport) {
        ensureRemoteDir(api, SyncPaths.parentRel(rel))
        val entry = api.put(rel, bytes, null)
        remember(rel, entry, sha256(bytes))
        report.uploaded++
        report.changedPaths.add(rel)
    }

    private suspend fun ensureRemoteDir(api: WebDavApi, rel: String) {
        val normalized = SyncPaths.nfc(rel)
        if (normalized.isEmpty() || normalized in state.folders) return
        ensureRemoteDir(api, SyncPaths.parentRel(normalized))
        api.createDir(normalized)
        state.folders.add(normalized)
        stateDirty = true
    }

    private fun remember(rel: String, entry: RemoteEntry, hash: String) {
        val next = SyncFileState(
            etag = entry.etag,
            size = entry.size,
            modified = entry.modified,
            sha256 = hash
        )
        val key = SyncPaths.nfc(rel)
        if (state.files[key] != next) {
            state.files[key] = next
            stateDirty = true
        }
    }

    private fun compareInitial(remote: RemoteTree, local: LocalScan): InitialComparison {
        var overlap = 0
        var conflicts = 0
        var unverified = 0
        for ((rel, entry) in remote.files) {
            val info = local.files[rel] ?: continue
            overlap++
            val known = state.files[rel]
            val remoteUnchanged = known != null && !remoteChanged(known, entry)
            if (known != null && remoteUnchanged && !known.pendingUpload &&
                known.sha256.isNotEmpty() && known.sha256 == info.sha256
            ) {
                continue
            }
            if (entry.size != null && entry.size != info.size) {
                conflicts++
                continue
            }
            if (known != null && remoteUnchanged && !known.pendingUpload && known.sha256.isNotEmpty()) {
                conflicts++
                continue
            }
            if (known?.pendingUpload == true) {
                if (fingerprintMatch(rel, entry, info) != null) continue
                unverified++
                continue
            }
            if (etagFingerprint(entry.etag) == null) {
                unverified++
                continue
            }
            if (fingerprintMatch(rel, entry, info) != null) continue
            unverified++
        }
        return InitialComparison(overlap, conflicts, unverified)
    }

    private data class InitialComparison(
        val overlap: Int,
        val conflicts: Int,
        val unverified: Int
    )

    private fun alreadySynced(rel: String, entry: RemoteEntry, local: LocalScan, report: SyncReport): Boolean {
        val known = state.files[rel]
        if (known != null && !remoteChanged(known, entry)) {
            if (known.pendingUpload) {
                val fingerprint = fingerprintMatch(rel, entry, local.files[rel])
                if (fingerprint != null) {
                    remember(rel, entry, fingerprint)
                    report.fingerprintSkipped++
                    return true
                }
                return local.files.containsKey(rel)
            }
            if (known.sha256.isNotEmpty() && local.files[rel]?.sha256 == known.sha256) return true
        }
        val fingerprint = fingerprintMatch(rel, entry, local.files[rel]) ?: return false
        remember(rel, entry, fingerprint)
        report.fingerprintSkipped++
        return true
    }

    private fun alreadyUploaded(rel: String, local: LocalScan, remote: RemoteTree, report: SyncReport): Boolean {
        val entry = remote.files[rel] ?: return false
        val known = state.files[rel]
        if (known != null && !known.pendingUpload && !remoteChanged(known, entry) &&
            local.files[rel]?.sha256 == known.sha256
        ) {
            return true
        }
        val fingerprint = fingerprintMatch(rel, entry, local.files[rel]) ?: return false
        remember(rel, entry, fingerprint)
        report.fingerprintSkipped++
        return true
    }

    private fun fingerprintMatch(rel: String, entry: RemoteEntry, info: LocalFileInfo?): String? {
        if (info == null) return null
        if (entry.size != null && entry.size != info.size) return null
        val hash = etagFingerprint(entry.etag) ?: return null
        if (hash.length == 64) return info.sha256.takeIf { it == hash }
        val algorithm = when (hash.length) {
            32 -> "MD5"
            40 -> "SHA-1"
            else -> return null
        }
        val bytes = runCatching { fileSystem.read(rel) }.getOrNull() ?: return null
        val digest = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
        return info.sha256.takeIf { digest == hash }
    }

    private fun etagFingerprint(etag: String?): String? {
        if (etag.isNullOrBlank()) return null
        return ETAG_HASH.find(etag)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    }

    private fun saveProgress(processed: Int) {
        if (!stateDirty) return
        if (processed <= 0 || processed % STATE_SAVE_BATCH != 0) return
        stateStore.save(state)
        stateDirty = false
    }

    private fun remoteMoves(remote: RemoteTree, local: LocalScan): Map<String, String> {
        val oldBySignature = linkedMapOf<String, MutableList<String>>()
        val newBySignature = linkedMapOf<String, MutableList<String>>()
        for ((rel, old) in state.files) {
            if (remote.files.containsKey(rel)) continue
            val current = local.files[rel] ?: continue
            if (current.sha256 != old.sha256) continue
            oldBySignature.getOrPut(remoteSignature(old.etag, old.size, old.modified)) { mutableListOf() }.add(rel)
        }
        for ((rel, entry) in remote.files) {
            if (state.files.containsKey(rel)) continue
            newBySignature.getOrPut(remoteSignature(entry.etag, entry.size, entry.modified)) { mutableListOf() }.add(rel)
        }
        val out = linkedMapOf<String, String>()
        for ((signature, olds) in oldBySignature) {
            if (olds.size != 1) continue
            val news = newBySignature[signature] ?: continue
            if (news.size == 1) out[olds.single()] = news.single()
        }
        return out
    }

    private fun remoteChanged(old: SyncFileState, entry: RemoteEntry): Boolean =
        if (old.etag != null && entry.etag != null) {
            old.etag != entry.etag
        } else {
            old.size != entry.size || old.modified != entry.modified
        }

    private fun remoteSignature(etag: String?, size: Long?, modified: String?): String =
        etag ?: "$size:$modified"

    private fun preflightPaths(remote: RemoteTree, local: LocalScan) {
        val localByKey = linkedMapOf<String, Pair<String, Boolean>>()
        for ((rel, isDir) in
            local.files.keys.map { it to false } +
            local.dirs.map { it to true } +
            local.protectedPaths.map { it to false }
        ) {
            val key = SyncPaths.pathKey(rel)
            val previous = localByKey.putIfAbsent(key, rel to isDir)
            if (previous != null && previous != (rel to isDir)) {
                throw SyncException("本机有同名文件与文件夹冲突，暂停同步：$rel")
            }
        }
        for ((rel, isDir) in
            remote.files.keys.map { it to false } +
            remote.dirs.map { it to true } +
            remote.protectedPaths.map { it to false }
        ) {
            val existing = localByKey[SyncPaths.pathKey(rel)] ?: continue
            if (existing.first != rel) {
                throw SyncException("本机与坚果云有仅大小写不同的同名条目，暂停同步：${existing.first} / $rel")
            }
            if (existing.second != isDir) {
                throw SyncException("本机与坚果云的同名条目类型不同，暂停同步：$rel")
            }
        }
    }

    private suspend fun scanRemote(api: WebDavApi): RemoteTree {
        val raw = linkedMapOf<String, RemoteEntry>()
        for (entry in api.scan()) {
            val rel = SyncPaths.nfc(entry.rel)
            if (rel.isEmpty()) continue
            if (!SyncPaths.safeRelative(rel)) throw SyncException("坚果云有无法在本机使用的名称：$rel")
            if (raw.put(rel, entry.copy(rel = rel)) != null) throw SyncException("坚果云返回了重复路径：$rel")
        }
        val tree = RemoteTree()
        val keys = linkedSetOf<String>()
        for ((rel, entry) in raw.entries.sortedBy { it.key.count { ch -> ch == '/' } }) {
            if (!eligibleRelative(rel)) continue
            if (!keys.add(SyncPaths.pathKey(rel))) {
                throw SyncException("坚果云有仅大小写不同的同名条目：$rel")
            }
            if (entry.isDir) {
                if (!SyncPaths.isContainerDir(rel)) continue
                tree.dirs.add(rel)
            } else if (SyncPaths.isSyncedFile(rel, SyncPaths.baseName(rel))) {
                if ((entry.size ?: 0) > SyncLimits.MAX_BYTES) tree.protectedPaths.add(rel)
                tree.files[rel] = entry
            }
        }
        if (raw.size >= 750) tree.warnings.add("坚果云单次目录结果达到 750 项上限，已改用逐层扫描")
        return tree
    }

    private fun eligibleRelative(rel: String): Boolean {
        val parts = rel.split('/')
        val dirs = parts.dropLast(1)
        for ((index, dir) in dirs.withIndex()) {
            if (!dir.startsWith('.')) continue
            if (index == 0 && dir == ".assets") continue
            return false
        }
        if (rel.startsWith(".assets/thumbs/")) return false
        return true
    }

    private data class RemoteTree(
        val dirs: LinkedHashSet<String> = linkedSetOf(),
        val files: LinkedHashMap<String, RemoteEntry> = linkedMapOf(),
        val warnings: MutableList<String> = mutableListOf(),
        val protectedPaths: LinkedHashSet<String> = linkedSetOf()
    )

    private companion object {
        const val STATE_SAVE_BATCH = 20
        val ETAG_HASH = Regex("(?i)(?:^|[^0-9a-z])([0-9a-f]{64}|[0-9a-f]{40}|[0-9a-f]{32})(?![0-9a-z])")
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
