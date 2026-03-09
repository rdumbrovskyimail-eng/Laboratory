package com.opuside.app.feature.scratch.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scratch_records")
data class ScratchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,       // авто-имя: "Запись 1", "Запись 2" ...
    val content: String,     // весь текст из поля
    val createdAt: Long = System.currentTimeMillis()
)
