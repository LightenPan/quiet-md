package com.zhaoolee.notes.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.Collator
import java.text.SimpleDateFormat
import java.time.Clock
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NoteLibrary(
    val root: File,
    val metadataFile: File,
    private val clock: Clock = Clock.systemUTC()
) {
    private val rootLock: Mutex by lazy { LibraryRootLock.mutexFor(root) }
    private val metadata = LibraryMetadata(metadataFile, clock)
    private val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
        strength = Collator.PRIMARY
    }

    init {
        root.mkdirs()
        metadataFile.parentFile?.mkdirs()
    }

    suspend fun list(query: String = "", folder: String? = null): List<LibraryNote> =
        snapshot(query, folder).notes

    suspend fun snapshot(query: String = "", folder: String? = null): LibrarySnapshot =
        withContext(Dispatchers.IO) {
            rootLock.withLock {
                val notes = ArrayList<LibraryNote>()
                val warnings = ArrayList<LibraryWarning>()
                val files = ArrayList<File>()
                walkFiles(root, files, warnings)
                val present = LinkedHashSet<String>()
                for (file in files) {
                    val relative = relativePath(file)
                    present.add(relative)
                    try {
                        notes.add(readNote(file, relative))
                    } catch (e: Exception) {
                        warnings.add(LibraryWarning(relative, e.message ?: e.javaClass.simpleName))
                    }
                }
                metadata.prune(present)
                metadata.saveIfDirty()
                val needle = query.trim()
                val filtered = notes.filter { note ->
                    (folder == null || note.folder == folder) &&
                        (needle.isEmpty() || note.title.contains(needle, ignoreCase = true) ||
                            note.body.contains(needle, ignoreCase = true) ||
                            note.folder.contains(needle, ignoreCase = true))
                }.sortedWith(noteComparator)
                LibrarySnapshot(filtered, warnings)
            }
        }

    suspend fun get(relativePath: String): LibraryNote? = withContext(Dispatchers.IO) {
        rootLock.withLock {
            val relative = PathRules.validateRelativePath(relativePath)
            val file = checkedFile(relative)
            if (!file.isFile) return@withLock null
            try {
                val note = readNote(file, relative)
                metadata.saveIfDirty()
                note
            } catch (e: NoteTooLargeException) {
                throw e
            } catch (e: Exception) {
                throw IOException("无法读取便签：$relative", e)
            }
        }
    }

    suspend fun create(
        title: String,
        folder: String = "",
        body: String = "",
        createdAt: Long = clock.millis()
    ): LibraryNote = withContext(Dispatchers.IO) {
        rootLock.withLock {
            val cleanFolder = PathRules.normalizeFolderInput(folder)
            val cleanTitle = PathRules.sanitizeTitle(title)
            val relative = uniqueRelative(cleanFolder, cleanTitle, "md", ignore = null)
            val file = checkedFile(relative)
            file.parentFile?.mkdirs()
            AtomicFiles.writeText(file, body)
            val entry = metadata.entry(relative, createdAt.coerceAtLeast(0))
            entry.createdAt = createdAt.coerceAtLeast(0)
            entry.lastSeenAt = clock.millis()
            metadata.saveIfDirty()
            readNote(file, relative)
        }
    }

    suspend fun save(note: LibraryNote, newTitle: String = note.title): LibraryNote =
        withContext(Dispatchers.IO) {
            rootLock.withLock {
                require(note.editable) { "这篇文档不能在这里编辑：${note.relativePath}" }
                val oldRelative = PathRules.validateRelativePath(note.relativePath)
                require(PathRules.isEditable(oldRelative.substringAfterLast('/'))) {
                    "只有 Markdown 便签可以编辑：$oldRelative"
                }
                val oldFile = checkedFile(oldRelative)
                if (!oldFile.isFile) throw NoteNotFoundException(oldRelative)
                val currentHash = AtomicFiles.sha256(oldFile)
                if (note.contentHash.isNotEmpty() && note.contentHash != currentHash) {
                    throw NoteConflictException(oldRelative, "文件已在其他位置被修改：$oldRelative")
                }
                val cleanTitle = PathRules.sanitizeTitle(newTitle)
                val cleanFolder = PathRules.normalizeFolderInput(note.folder)
                val targetRelative = uniqueRelative(cleanFolder, cleanTitle, "md", ignore = oldFile)
                val target = checkedFile(targetRelative)
                AtomicFiles.writeText(target, note.body)
                if (targetRelative != oldRelative && oldFile.exists() &&
                    !Files.isSameFile(target.toPath(), oldFile.toPath())
                ) {
                    oldFile.delete()
                }
                if (targetRelative != oldRelative) metadata.rename(oldRelative, targetRelative)
                metadata.entry(targetRelative, note.createdAt)
                metadata.saveIfDirty()
                readNote(target, targetRelative)
            }
        }

    suspend fun move(relativePath: String, newFolder: String): LibraryNote =
        withContext(Dispatchers.IO) {
            rootLock.withLock {
                val oldRelative = PathRules.validateRelativePath(relativePath)
                val oldFile = checkedFile(oldRelative)
                if (!oldFile.isFile) throw NoteNotFoundException(oldRelative)
                val note = readNote(oldFile, oldRelative)
                require(note.editable) { "这篇文档不能在这里移动：$oldRelative" }
                val folder = PathRules.normalizeFolderInput(newFolder)
                if (folder == note.folder) return@withLock note
                val targetRelative = uniqueRelative(
                    folder,
                    PathRules.titleOf(oldFile.name),
                    "md",
                    ignore = oldFile
                )
                val target = checkedFile(targetRelative)
                target.parentFile?.mkdirs()
                AtomicFiles.move(oldFile, target)
                metadata.rename(oldRelative, targetRelative)
                metadata.saveIfDirty()
                readNote(target, targetRelative)
            }
        }

    suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        rootLock.withLock {
            val relative = PathRules.validateRelativePath(relativePath)
            val file = checkedFile(relative)
            if (!file.isFile) throw NoteNotFoundException(relative)
            require(PathRules.isEditable(file.name)) { "这篇文档不能在这里删除：$relative" }
            val trash = File(root, ".trash").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date(clock.millis()))
            var target = File(trash, "${stamp}_${file.name}")
            var n = 2
            while (target.exists()) {
                target = File(trash, "${stamp}_${file.nameWithoutExtension} ($n).${file.extension}")
                n++
            }
            AtomicFiles.move(file, target)
            metadata.remove(relative)
            metadata.saveIfDirty()
        }
    }

    suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        rootLock.withLock {
            val result = ArrayList<String>()
            collectFolders(root, "", result)
            result.sortedWith { a, b -> collator.compare(a, b) }
        }
    }

    suspend fun setFavorite(relativePath: String, favorite: Boolean) =
        updateMetadata(relativePath) { it.favorite = favorite }

    suspend fun setPinned(relativePath: String, pinned: Boolean) =
        updateMetadata(relativePath) { it.pinned = pinned }

    private suspend fun updateMetadata(relativePath: String, change: (MetadataEntry) -> Unit) =
        withContext(Dispatchers.IO) {
            rootLock.withLock {
                val relative = PathRules.validateRelativePath(relativePath)
                val file = checkedFile(relative)
                if (!file.isFile) throw NoteNotFoundException(relative)
                val entry = metadata.entry(relative, file.lastModified())
                change(entry)
                metadata.saveIfDirty()
            }
        }

    private fun readNote(file: File, relative: String): LibraryNote {
        if (file.length() > AtomicFiles.MAX_BYTES) {
            throw NoteTooLargeException(relative, file.length())
        }
        val editable = PathRules.isEditable(file.name)
        val body = when (PathRules.extension(file.name)) {
            "sqlite" -> ""
            "md", "markdown", "mdown", "xml", "excalidraw" -> AtomicFiles.readText(file)
            else -> throw IOException("不支持的文档类型：$relative")
        }
        val hash = if (editable) AtomicFiles.sha256(body.toByteArray(Charsets.UTF_8)) else AtomicFiles.sha256(file)
        val entry = metadata.entry(relative, file.lastModified())
        return LibraryNote(
            relativePath = relative,
            title = PathRules.titleOf(file.name),
            body = body,
            folder = PathRules.folderOf(relative),
            modifiedAt = file.lastModified(),
            createdAt = entry.createdAt,
            pinned = entry.pinned,
            favorite = entry.favorite,
            editable = editable,
            contentHash = hash
        )
    }

    private fun walkFiles(
        dir: File,
        out: MutableList<File>,
        warnings: MutableList<LibraryWarning>
    ) {
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (Files.isSymbolicLink(file.toPath())) {
                warnings.add(LibraryWarning(relativePath(file), "跳过符号链接"))
                continue
            }
            if (file.isDirectory) {
                if (file.name.startsWith('.')) continue
                walkFiles(file, out, warnings)
            } else if (file.isFile && !file.name.startsWith('.') && PathRules.isListed(file.name)) {
                out.add(file)
            }
        }
    }

    private fun collectFolders(dir: File, prefix: String, out: MutableList<String>) {
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (!file.isDirectory || file.name.startsWith('.') || Files.isSymbolicLink(file.toPath())) {
                continue
            }
            val relative = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
            out.add(PathRules.nfc(relative))
            collectFolders(file, relative, out)
        }
    }

    private fun uniqueRelative(
        folder: String,
        title: String,
        extension: String,
        ignore: File?
    ): String {
        val parent = if (folder.isEmpty()) root else checkedFile(folder)
        parent.mkdirs()
        val existing = parent.listFiles()
            ?.filter { ignore == null || it.absoluteFile != ignore.absoluteFile }
            ?.mapTo(HashSet()) { PathRules.nfc(it.name).lowercase(Locale.ROOT) }
            ?: HashSet()
        var suffix = 1
        while (true) {
            val base = if (suffix == 1) title else {
                val extra = " $suffix"
                PathRules.truncateUtf8(
                    PathRules.truncateCodePoints(title, PathRules.MAX_TITLE_CHARS - extra.length),
                    PathRules.MAX_COMPONENT_BYTES - extra.toByteArray(Charsets.UTF_8).size
                ).trimEnd('.', ' ') + extra
            }
            val name = "$base.$extension"
            if (PathRules.nfc(name).lowercase(Locale.ROOT) !in existing) {
                return if (folder.isEmpty()) name else "$folder/$name"
            }
            suffix++
        }
    }

    private fun resolve(relative: String): File {
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path.startsWith(rootPath)) { "便签路径越界：$relative" }
        return file
    }

    private fun checkedFile(relative: String): File {
        val normalized = if (relative.isEmpty()) "" else PathRules.validateRelativePath(relative)
        var current = root.toPath()
        for (part in normalized.split('/').filter { it.isNotEmpty() }) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "便签路径包含符号链接：$relative" }
        }
        return resolve(normalized)
    }

    private fun relativePath(file: File): String {
        val value = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
        return PathRules.nfc(value)
    }

    private val noteComparator = Comparator<LibraryNote> { a, b ->
        when {
            a.pinned != b.pinned -> if (a.pinned) -1 else 1
            titleBucket(a.title) != titleBucket(b.title) -> titleBucket(a.title) - titleBucket(b.title)
            a.modifiedAt != b.modifiedAt -> b.modifiedAt.compareTo(a.modifiedAt)
            else -> compareTitles(a.title, b.title)
        }
    }

    private fun compareTitles(a: String, b: String): Int {
        val an = leadingNumber(a)
        val bn = leadingNumber(b)
        if (an != null && bn != null) {
            val numeric = an.compareTo(bn)
            if (numeric != 0) return numeric
        }
        return collator.compare(a, b).let { if (it != 0) it else a.compareTo(b) }
    }

    private fun leadingNumber(value: String): java.math.BigInteger? {
        val end = value.indexOfFirst { !it.isDigit() }.let { if (it < 0) value.length else it }
        if (end == 0) return null
        return value.substring(0, end).toBigIntegerOrNull()
    }

    private fun titleBucket(title: String): Int {
        val first = title.firstOrNull() ?: return 3
        return when {
            first.isDigit() -> 0
            first in 'A'..'Z' || first in 'a'..'z' -> 1
            first.code < 128 -> 2
            else -> 3
        }
    }

    companion object {
        fun forContext(context: android.content.Context): NoteLibrary = NoteLibrary(
            File(context.filesDir, "Quiet.md"),
            File(context.noBackupFilesDir, "library-meta.json")
        )
    }
}
