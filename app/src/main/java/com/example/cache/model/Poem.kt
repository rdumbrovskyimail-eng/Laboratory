package com.example.cache.model

data class Poem(
    val id: String,
    val title: String,
    val author: String,
    val text: String,
    val cachedAt: Long = System.currentTimeMillis()
)