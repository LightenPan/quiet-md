package com.zhaoolee.notes.library

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

object LibraryRootLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun mutexFor(root: File): Mutex {
        val absolute = root.toPath().toAbsolutePath().normalize()
        val canonical = runCatching { absolute.toFile().canonicalFile.toPath() }.getOrDefault(absolute)
        return locks.computeIfAbsent(canonical.toString()) { Mutex() }
    }
}
