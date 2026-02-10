package com.example.cache

import com.example.cache.model.Poem
import org.junit.Assert.*
import org.junit.Test

class PoemCacheTest {

    @Test
    fun `poem model is created correctly`() {
        val poem = CachedPoems.nightAtWindow

        assertEquals("night_at_window", poem.id)
        assertEquals("Тихо ночь к окну прижалась", poem.title)
        assertTrue(poem.text.contains("Город выдохнул — и спит"))
        assertTrue(poem.text.contains("Он сильнее, чем война"))
    }

    @Test
    fun `poem contains all 8 lines`() {
        val lines = CachedPoems.nightAtWindow.text
            .lines()
            .filter { it.isNotBlank() }

        assertEquals(8, lines.size)
    }

    @Test
    fun `poem data class equality works`() {
        val poem1 = Poem("1", "Title", "Author", "Text")
        val poem2 = Poem("1", "Title", "Author", "Text")
        assertEquals(poem1.copy(cachedAt = 0), poem2.copy(cachedAt = 0))
    }
}