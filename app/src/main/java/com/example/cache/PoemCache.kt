package com.example.cache

import android.content.Context
import android.content.SharedPreferences
import com.example.cache.model.Poem
import org.json.JSONObject

/**
 * Two-level poem cache:
 * Level 1 — in-memory (ConcurrentHashMap)
 * Level 2 — persistent (SharedPreferences)
 */
object PoemCache {

    private const val PREFS_NAME = "poem_cache"
    private const val KEY_PREFIX = "poem_"

    // ── Level 1: In-memory cache ────────────────────────────────────
    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, Poem>()

    // ── Public API ──────────────────────────────────────────────────

    /** Сохранить стих в оба уровня кэша */
    fun put(context: Context, poem: Poem) {
        memoryCache[poem.id] = poem
        prefs(context).edit().putString(KEY_PREFIX + poem.id, poem.toJson()).apply()
    }

    /** Получить стих: сначала из памяти, потом из диска */
    fun get(context: Context, id: String): Poem? {
        // L1
        memoryCache[id]?.let { return it }

        // L2
        val json = prefs(context).getString(KEY_PREFIX + id, null) ?: return null
        val poem = Poem.fromJson(json)
        // promote to L1
        if (poem != null) memoryCache[id] = poem
        return poem
    }

    /** Удалить стих из обоих уровней */
    fun evict(context: Context, id: String) {
        memoryCache.remove(id)
        prefs(context).edit().remove(KEY_PREFIX + id).apply()
    }

    /** Очистить весь кэш */
    fun clear(context: Context) {
        memoryCache.clear()
        prefs(context).edit().clear().apply()
    }

    /** Проверить наличие в кэше */
    fun contains(context: Context, id: String): Boolean {
        return memoryCache.containsKey(id) ||
                prefs(context).contains(KEY_PREFIX + id)
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Poem.toJson(): String = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("text", text)
        put("cachedAt", cachedAt)
    }.toString()

    private fun Poem.Companion.fromJson(json: String): Poem? = try {
        val obj = JSONObject(json)
        Poem(
            id = obj.getString("id"),
            title = obj.getString("title"),
            author = obj.getString("author"),
            text = obj.getString("text"),
            cachedAt = obj.getLong("cachedAt")
        )
    } catch (e: Exception) {
        null
    }
}

// Нужен companion object для extension-функции fromJson
private val Poem.Companion: PoemCompanion get() = PoemCompanion
private object PoemCompanion {
    fun fromJson(json: String): Poem? = try {
        val obj = JSONObject(json)
        Poem(
            id = obj.getString("id"),
            title = obj.getString("title"),
            author = obj.getString("author"),
            text = obj.getString("text"),
            cachedAt = obj.getLong("cachedAt")
        )
    } catch (e: Exception) {
        null
    }
}