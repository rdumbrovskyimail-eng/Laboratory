package com.opuside.app.feature.pipeline.data

import com.opuside.app.core.security.SecureSettingsDataStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ротация двух Gemini-ключей пайплайна. При 429/quota вызывается burnAndRotate(),
 * который помечает ключ как "сгоревший" на 5 минут и переключается на второй.
 * Когда оба в cooldown — currentKey() вернёт null, задача станет DEFERRED.
 */
@Singleton
class PipelineKeyRotator @Inject constructor(
    private val secureSettings: SecureSettingsDataStore
) {
    private val burnedUntil = ConcurrentHashMap<Int, Long>()
    private val COOLDOWN_MS = 5 * 60_000L

    private suspend fun loadKeys(): List<String> = listOf(
        secureSettings.pipelineKeyA.first(),
        secureSettings.pipelineKeyB.first()
    )

    /** Возвращает (apiKey, indexAB) текущего рабочего ключа или null. */
    suspend fun currentKey(): Pair<String, Int>? {
        val keys = loadKeys()
        val active = secureSettings.pipelineActiveKeyIndex.first().coerceIn(0, 1)
        val now = System.currentTimeMillis()
        if (keys[active].isNotBlank() && (burnedUntil[active] ?: 0L) < now) {
            return keys[active] to active
        }
        val other = 1 - active
        if (keys[other].isNotBlank() && (burnedUntil[other] ?: 0L) < now) {
            secureSettings.setPipelineActiveKeyIndex(other)
            return keys[other] to other
        }
        return null
    }

    /** Помечает currentIndex как burned на 5 минут, пытается переключиться на другой. */
    suspend fun burnAndRotate(currentIndex: Int): Pair<String, Int>? {
        burnedUntil[currentIndex] = System.currentTimeMillis() + COOLDOWN_MS
        val other = 1 - currentIndex
        val keys = loadKeys()
        val now = System.currentTimeMillis()
        if (keys[other].isNotBlank() && (burnedUntil[other] ?: 0L) < now) {
            secureSettings.setPipelineActiveKeyIndex(other)
            return keys[other] to other
        }
        return null
    }

    fun isBurned(index: Int): Boolean =
        (burnedUntil[index] ?: 0L) > System.currentTimeMillis()

    fun cooldownRemainingMs(index: Int): Long =
        ((burnedUntil[index] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
}