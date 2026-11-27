package com.zhaoolee.notes.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

data class LocalFileInfo(
    val size: Long,
    val sha256: String
)

data class LocalScan(
    val files: LinkedHashMap<String, LocalFileInfo> = linkedMapOf(),
    val dirs: LinkedHashSet<String> = linkedSetOf(),
    val warnings: MutableList<String> = mutableListOf(),
    val protectedPaths: LinkedHashSet<String> = linkedSetOf()
)

interface SyncFileSystem {
    fun scan(): LocalScan
    fun read(rel: String): ByteArray
    fun writeAtomic(rel: String, bytes: ByteArray)
    fun exists(rel: String): Boolean
    fun isFile(rel: String): Boolean
    fun isDirectory(rel: String): Boolean
    fun ensureDirectory(rel: String)
    fun moveFile(from: String, to: String)
    fun removeFile(rel: String)
    fun removeEmptyDirectory(rel: String)
    fun quarantine(rel: String): Boolean
}
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class JavaSyncFileSystem(
    private val root: File,
    private val recoveryRoot: File
) : SyncFileSystem {
    init {
        require(root.isDirectory || root.mkdirs()) { "同步的本地文件夹不存在：${root.path}" }
    }

    override fun scan(): LocalScan {
        val result = LocalScan()
        walk("", result)
        val seen = linkedSetOf<String>()
        for (rel in result.files.keys + result.dirs) {
            if (!seen.add(SyncPaths.pathKey(rel))) {
                throw SyncException("本机有仅大小写不同的同名条目，暂停同步：$rel")
            }
        }
        return result
    }

    private fun walk(relDir: String, result: LocalScan) {
        val dir = localFile(relDir)
        val children = dir.listFiles() ?: throw SyncException("无法读取文件夹：$relDir")
        for (child in children) {
            val name = child.name
            val rel = if (relDir.isEmpty()) name else "$relDir/$name"
            if (Files.isSymbolicLink(child.toPath())) {
                if (child.isDirectory) {
                    throw SyncException("$rel 是符号链接文件夹，暂停同步")
                }
                result.warnings.add("$rel 是符号链接，未同步")
                result.protectedPaths.add(SyncPaths.nfc(rel))
                continue
            }
            if (child.isDirectory) {
                if (name.startsWith('.') && !(relDir.isEmpty() && name == ".assets")) continue
                SyncPaths.requireRelative(rel)
                if (rel.startsWith(".assets/thumbs")) continue
                result.dirs.add(SyncPaths.nfc(rel))
                walk(rel, result)
                continue
            }
            if (!child.isFile || name.startsWith('.') || name.endsWith(".tmp") || name.endsWith(".sqlite-journal")) continue
            if (!SyncPaths.isSyncedFile(rel, name)) continue
            SyncPaths.requireRelative(rel)
            val size = child.length()
            if (size > SyncLimits.MAX_BYTES) {
                result.warnings.add("$rel 超过 100 MB，未同步")
                result.protectedPaths.add(SyncPaths.nfc(rel))
                continue
            }
            if (size > SyncLimits.WARN_BYTES) result.warnings.add("$rel 超过 50 MB，同步可能较慢")
            val hash = try {
                sha256(child)
            } catch (e: Exception) {
                result.warnings.add("$rel 读取失败：${e.message ?: e.javaClass.simpleName}")
                result.protectedPaths.add(SyncPaths.nfc(rel))
                continue
            }
            result.files[SyncPaths.nfc(rel)] = LocalFileInfo(size, hash)
        }
    }

    override fun read(rel: String): ByteArray {
        val file = localFile(rel)
        if (!file.isFile) throw SyncException("本机文件不存在：$rel")
        if (file.length() > SyncLimits.MAX_BYTES) throw SyncException("$rel 超过同步上限")
        return file.readBytes()
    }

    override fun writeAtomic(rel: String, bytes: ByteArray) {
        atomicWrite(localFile(rel), bytes)
    }

    override fun exists(rel: String): Boolean = localFile(rel).exists()
    override fun isFile(rel: String): Boolean = localFile(rel).isFile
    override fun isDirectory(rel: String): Boolean = localFile(rel).isDirectory

    override fun ensureDirectory(rel: String) {
        if (rel.isEmpty()) return
        SyncPaths.requireRelative(rel)
        val dir = localFile(rel)
        if (!dir.isDirectory && !dir.mkdirs()) throw SyncException("无法创建文件夹：$rel")
    }

    override fun moveFile(from: String, to: String) {
        val source = localFile(from)
        val target = localFile(to)
        target.parentFile?.mkdirs()
        if (target.exists()) throw SyncException("目标位置已有文件：$to")
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    override fun removeFile(rel: String) {
        val file = localFile(rel)
        if (file.isFile && !file.delete()) throw SyncException("无法删除文件：$rel")
    }

    override fun removeEmptyDirectory(rel: String) {
        if (rel.isEmpty()) return
        localFile(rel).delete()
    }

    override fun quarantine(rel: String): Boolean {
        val source = localFile(rel)
        if (!source.isFile) return false
        val target = uniqueRecoveryFile(rel)
        target.parentFile?.mkdirs()
        return try {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(source.toPath(), target.toPath())
            }
            true
        } catch (e: Exception) {
            throw SyncException("无法把 $rel 移到恢复区：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun uniqueRecoveryFile(rel: String): File {
        var candidate = File(recoveryRoot, rel.replace('/', File.separatorChar))
        if (!candidate.exists()) return candidate
        val parent = candidate.parentFile
        val name = candidate.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            candidate = File(parent, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun localFile(rel: String): File {
        if (rel.isEmpty()) return root.canonicalFile.apply { if (!isDirectory && !mkdirs()) throw SyncException("无法创建本地库") }
        val normalized = SyncPaths.requireRelative(rel)
        val rootPath = root.canonicalFile.toPath()
        val file = File(root, normalized.replace('/', File.separatorChar)).canonicalFile
        if (!file.toPath().startsWith(rootPath)) throw SyncException("路径越过了本地库：$rel")
        return file
    }
}

fun localConflictName(
    rel: String,
    deviceName: String,
    knownPaths: Set<String>,
    exists: (String) -> Boolean,
    now: java.time.ZonedDateTime
): String {
    val parent = SyncPaths.parentRel(rel)
    val ext = SyncPaths.extension(rel)
    val device = SyncPaths.truncateUtf8(SyncPaths.conflictComponent(deviceName), 40)
    val title = SyncPaths.truncateUtf8(SyncPaths.conflictComponent(SyncPaths.stem(rel)), 150)
    val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.ROOT))
    val base = "$title（冲突 · $device · $stamp）"
    val known = knownPaths.mapTo(linkedSetOf()) { SyncPaths.pathKey(it) }
    var n = 1
    while (true) {
        val suffix = if (n == 1) base else "$base ($n)"
        val name = if (ext.isEmpty()) suffix else "$suffix.$ext"
        val candidate = if (parent.isEmpty()) name else "$parent/$name"
        if (!exists(candidate) && SyncPaths.pathKey(candidate) !in known) return SyncPaths.nfc(candidate)
        n++
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

