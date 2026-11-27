package com.zhaoolee.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val content: String = "",
    val category: String = "默认",
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val images: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val footerText: String = "锤子笔记"
)
