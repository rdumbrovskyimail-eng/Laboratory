package com.example.cache

import android.content.Context
import com.example.cache.model.Poem

/**
 * Предзагруженные стихи — кэшируются при первом обращении.
 */
object CachedPoems {

    private const val NIGHT_POEM_ID = "night_at_window"

    /** Стих «Тихо ночь к окну прижалась» */
    val nightAtWindow = Poem(
        id = NIGHT_POEM_ID,
        title = "Тихо ночь к окну прижалась",
        author = "Неизвестный автор",
        text = """
            |Тихо ночь к окну прижалась,
            |Город выдохнул — и спит.
            |Мысли ходят без сигналов,
            |Сердце честно говорит.
            |
            |Даже если мир качнётся
            |И дорога вдруг темна —
            |Свет внутри тебя найдётся.
            |Он сильнее, чем война.
        """.trimMargin()
    )

    /**
     * Закэшировать стих в persistent storage.
     * Вызывайте, например, в Application.onCreate() или при первом запуске.
     */
    fun warmUp(context: Context) {
        if (!PoemCache.contains(context, NIGHT_POEM_ID)) {
            PoemCache.put(context, nightAtWindow)
        }
    }

    /**
     * Получить стих из кэша (memory → disk → fallback to hardcoded).
     */
    fun getNightPoem(context: Context): Poem {
        return PoemCache.get(context, NIGHT_POEM_ID) ?: nightAtWindow.also {
            // re-cache if evicted
            PoemCache.put(context, it)
        }
    }
}