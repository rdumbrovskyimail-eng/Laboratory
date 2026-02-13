package com.opuside.app.core.ai

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 🤖 CLAUDE MODEL CONFIGURATION v7.0 (CACHE WITHOUT HISTORY + INPUT 1 TOKEN)
 * 
 * Pricing (из docs.anthropic.com):
 * - 5min cache write = 1.25× base input price
 * - Cache read (hit) = 0.1× base input price  
 * - TTL refreshes on each successful cache hit (free)
 * 
 * NEW: testInputTokenLimit = 1 для тестирования кеша
 * NEW: minCacheableTokens по документации Anthropic (1024/4096/2048)
 */
object ClaudeModelConfig {
    
    private const val TAG = "ClaudeModelConfig"
    
    const val ECO_OUTPUT_TOKENS = 8192
    const val CACHE_TTL_MS = 5 * 60 * 1000L
    
    enum class ClaudeModel(
        val modelId: String,
        val displayName: String,
        val description: String,
        val contextWindow: Int,
        val maxOutputTokens: Int,
        val testInputTokenLimit: Int = 1,
        val inputPricePerM: Double,
        val outputPricePerM: Double,
        val longInputPricePerM: Double,
        val longOutputPricePerM: Double,
        val cacheWritePricePerM: Double,
        val cacheReadPricePerM: Double,
        val minCacheableTokens: Int,
        val longContextThreshold: Int,
        val supportsLongContext1M: Boolean,
        val speedRating: Int,
        val emoji: String
    ) {
        OPUS_4_6(
            modelId = "claude-opus-4-6",
            displayName = "Opus 4.6",
            description = "Новейшая, лучшая для кодирования",
            contextWindow = 200_000,
            maxOutputTokens = 128_000,
            testInputTokenLimit = 1,
            inputPricePerM = 5.0,
            outputPricePerM = 25.0,
            longInputPricePerM = 10.0,
            longOutputPricePerM = 37.5,
            cacheWritePricePerM = 6.25,
            cacheReadPricePerM = 0.50,
            minCacheableTokens = 1024,
            longContextThreshold = 200_000,
            supportsLongContext1M = true,
            speedRating = 3,
            emoji = "🚀"
        ),
        
        OPUS_4_5(
            modelId = "claude-opus-4-5-20251101",
            displayName = "Opus 4.5",
            description = "Мощная и эффективная",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            testInputTokenLimit = 1,
            inputPricePerM = 5.0,
            outputPricePerM = 25.0,
            longInputPricePerM = 10.0,
            longOutputPricePerM = 37.5,
            cacheWritePricePerM = 6.25,
            cacheReadPricePerM = 0.50,
            minCacheableTokens = 1024,
            longContextThreshold = 200_000,
            supportsLongContext1M = false,
            speedRating = 3,
            emoji = "🔥"
        ),

        OPUS_4_1(
            modelId = "claude-opus-4-1-20250805",
            displayName = "Opus 4.1",
            description = "Специализированная для reasoning",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            testInputTokenLimit = 1,
            inputPricePerM = 15.0,
            outputPricePerM = 75.0,
            longInputPricePerM = 30.0,
            longOutputPricePerM = 112.5,
            cacheWritePricePerM = 18.75,
            cacheReadPricePerM = 1.50,
            minCacheableTokens = 1024,
            longContextThreshold = 200_000,
            supportsLongContext1M = false,
            speedRating = 2,
            emoji = "🧠"
        ),

        OPUS_4(
            modelId = "claude-opus-4-20250514",
            displayName = "Opus 4",
            description = "Оригинальная Opus 4",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            testInputTokenLimit = 1,
            inputPricePerM = 15.0,
            outputPricePerM = 75.0,
            longInputPricePerM = 30.0,
            longOutputPricePerM = 112.5,
            cacheWritePricePerM = 18.75,
            cacheReadPricePerM = 1.50,
            minCacheableTokens = 1024,
            longContextThreshold = 200_000,
            supportsLongContext1M = false,
            speedRating = 2,
            emoji = "💎"
        ),

        SONNET_4_5(
            modelId = "claude-sonnet-4-5-20250929",
            displayName = "Sonnet 4.5",
            description = "Умная и эффективная",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            testInputTokenLimit = 1,
            inputPricePerM = 3.0,
            outputPricePerM = 15.0,
            longInputPricePerM = 6.0,
            longOutputPricePerM = 22.5,
            cacheWritePricePerM = 3.75,
            cacheReadPricePerM = 0.30,
            minCacheableTokens = 1024,
            longContextThreshold = 200_000,
            supportsLongContext1M = true,
            speedRating = 5,
            emoji = "⚡"
        ),

        SONNET_4(
            modelId = "claude-sonnet-4-20250514",
            displayName = "Sonnet 4",
            description = "Сбалансированная рабочая лошадка",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            testInputTokenLimit = 1,
            inputPricePerM = 3.0,
            outputPricePerM = 15.0,
            longInputPricePerM = 6.0,
            longOutputPricePerM = 22.5,
            cacheWritePricePerM = 3.75,
            cacheReadPricePerM = 0.30,
            minCacheableTokens = 1024,
            longContextThreshold = 200_000,
            supportsLongContext1M = true,
            speedRating = 5,
            emoji = "✨"
        ),

        HAIKU_4_5(
            modelId = "claude-haiku-4-5-20251001",
            displayName = "Haiku 4.5",
            description = "Быстрая для ежедневных задач",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            testInputTokenLimit = 1,
            inputPricePerM = 1.0,
            outputPricePerM = 5.0,
            longInputPricePerM = 2.0,
            longOutputPricePerM = 7.5,
            cacheWritePricePerM = 1.25,
            cacheReadPricePerM = 0.10,
            minCacheableTokens = 4096,
            longContextThreshold = 200_000,
            supportsLongContext1M = false,
            speedRating = 8,
            emoji = "💨"
        ),

        HAIKU_3(
            modelId = "claude-3-haiku-20240307",
            displayName = "Haiku 3",
            description = "Самая быстрая и дешёвая (max 4K output)",
            contextWindow = 200_000,
            maxOutputTokens = 4_096,
            testInputTokenLimit = 1,
            inputPricePerM = 0.25,
            outputPricePerM = 1.25,
            longInputPricePerM = 0.25,
            longOutputPricePerM = 1.25,
            cacheWritePricePerM = 0.30,
            cacheReadPricePerM = 0.03,
            minCacheableTokens = 2048,
            longContextThreshold = 200_000,
            supportsLongContext1M = false,
            speedRating = 10,
            emoji = "🪶"
        );
        
        fun getEffectiveOutputTokens(ecoMode: Boolean): Int {
            return if (ecoMode) minOf(ECO_OUTPUT_TOKENS, maxOutputTokens) else maxOutputTokens
        }
        
        fun getMaxInputTokens(ecoMode: Boolean, useCacheTestMode: Boolean = false): Int {
            if (useCacheTestMode) return testInputTokenLimit
            return contextWindow - getEffectiveOutputTokens(ecoMode)
        }
        
        companion object {
            fun fromModelId(modelId: String): ClaudeModel? = entries.find { it.modelId == modelId }
            fun getAllModelIds(): List<String> = entries.map { it.modelId }
            fun getAllModelsWithNames(): List<Pair<String, String>> = entries.map { 
                it.modelId to "${it.emoji} ${it.displayName} — \$${it.inputPricePerM}/\$${it.outputPricePerM}"
            }
            fun getDefault(): ClaudeModel = OPUS_4_6
        }
        
        fun calculateCost(
            inputTokens: Int,
            outputTokens: Int,
            cachedReadTokens: Int = 0,
            cachedWriteTokens: Int = 0,
            usdToEur: Double = 0.92
        ): ModelCost {
            require(inputTokens >= 0) { "Input tokens cannot be negative" }
            require(outputTokens >= 0) { "Output tokens cannot be negative" }
            require(cachedReadTokens >= 0) { "Cache read tokens cannot be negative" }
            require(cachedWriteTokens >= 0) { "Cache write tokens cannot be negative" }
            require(usdToEur > 0) { "USD to EUR rate must be positive" }
            
            val isLongContext = inputTokens > longContextThreshold
            val actualInputPrice = if (isLongContext) longInputPricePerM else inputPricePerM
            val actualOutputPrice = if (isLongContext) longOutputPricePerM else outputPricePerM
            
            val regularInputTokens = (inputTokens - cachedReadTokens - cachedWriteTokens).coerceAtLeast(0)
            val regularInputCostUSD = (regularInputTokens / 1_000_000.0) * actualInputPrice
            val cacheWriteCostUSD = (cachedWriteTokens / 1_000_000.0) * cacheWritePricePerM
            val cacheReadCostUSD = (cachedReadTokens / 1_000_000.0) * cacheReadPricePerM
            val outputCostUSD = (outputTokens / 1_000_000.0) * actualOutputPrice
            
            val totalCostUSD = regularInputCostUSD + cacheWriteCostUSD + cacheReadCostUSD + outputCostUSD
            val totalCostEUR = totalCostUSD * usdToEur
            
            val withoutCacheCostUSD = if (cachedReadTokens > 0) (cachedReadTokens / 1_000_000.0) * actualInputPrice else 0.0
            val savingsUSD = withoutCacheCostUSD - cacheReadCostUSD
            
            return ModelCost(
                model = this,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedReadTokens = cachedReadTokens,
                cachedWriteTokens = cachedWriteTokens,
                regularInputTokens = regularInputTokens,
                isLongContext = isLongContext,
                regularInputCostUSD = regularInputCostUSD,
                cacheWriteCostUSD = cacheWriteCostUSD,
                cacheReadCostUSD = cacheReadCostUSD,
                outputCostUSD = outputCostUSD,
                totalCostUSD = totalCostUSD,
                totalCostEUR = totalCostEUR,
                cacheSavingsUSD = savingsUSD,
                cacheSavingsEUR = savingsUSD * usdToEur
            )
        }
    }
    
    data class ModelCost(
        val model: ClaudeModel,
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedReadTokens: Int,
        val cachedWriteTokens: Int,
        val regularInputTokens: Int,
        val isLongContext: Boolean,
        val regularInputCostUSD: Double,
        val cacheWriteCostUSD: Double,
        val cacheReadCostUSD: Double,
        val outputCostUSD: Double,
        val totalCostUSD: Double,
        val totalCostEUR: Double,
        val cacheSavingsUSD: Double,
        val cacheSavingsEUR: Double
    ) {
        val totalTokens: Int = inputTokens + outputTokens
        val savingsPercentage: Double = if (cacheSavingsUSD > 0 && totalCostUSD > 0) {
            (cacheSavingsUSD / (totalCostUSD + cacheSavingsUSD)) * 100
        } else 0.0
        val costPerToken: Double = if (totalTokens > 0) totalCostUSD / totalTokens else 0.0
        val cacheEfficiency: Double = if (inputTokens > 0) (cachedReadTokens.toDouble() / inputTokens) * 100 else 0.0
        
        operator fun plus(other: ModelCost): ModelCost {
            require(model == other.model) { "Cannot combine costs from different models" }
            return ModelCost(
                model = model,
                inputTokens = inputTokens + other.inputTokens,
                outputTokens = outputTokens + other.outputTokens,
                cachedReadTokens = cachedReadTokens + other.cachedReadTokens,
                cachedWriteTokens = cachedWriteTokens + other.cachedWriteTokens,
                regularInputTokens = regularInputTokens + other.regularInputTokens,
                isLongContext = isLongContext || other.isLongContext,
                regularInputCostUSD = regularInputCostUSD + other.regularInputCostUSD,
                cacheWriteCostUSD = cacheWriteCostUSD + other.cacheWriteCostUSD,
                cacheReadCostUSD = cacheReadCostUSD + other.cacheReadCostUSD,
                outputCostUSD = outputCostUSD + other.outputCostUSD,
                totalCostUSD = totalCostUSD + other.totalCostUSD,
                totalCostEUR = totalCostEUR + other.totalCostEUR,
                cacheSavingsUSD = cacheSavingsUSD + other.cacheSavingsUSD,
                cacheSavingsEUR = cacheSavingsEUR + other.cacheSavingsEUR
            )
        }
        
        override fun toString(): String =
            "ModelCost(${model.displayName}, ${totalTokens}tok, \$${String.format("%.4f", totalCostUSD)}, " +
            "savings=${String.format("%.1f", savingsPercentage)}%)"
    }
    
    data class ChatSession(
        val sessionId: String,
        val model: ClaudeModel,
        val startTime: Instant,
        var endTime: Instant? = null,
        var totalInputTokens: Int = 0,
        var totalOutputTokens: Int = 0,
        var totalCachedReadTokens: Int = 0,
        var totalCachedWriteTokens: Int = 0,
        var messageCount: Int = 0,
        var isActive: Boolean = true
    ) {
        var cacheHitRate: Double = 0.0; private set
        var averageCostPerMessage: Double = 0.0; private set
        var averageTokensPerMessage: Int = 0; private set
        private var _cachedCost: ModelCost? = null
        
        val duration: Long get() = (endTime ?: Instant.now()).epochSecond - startTime.epochSecond
        val durationFormatted: String get() {
            val s = duration; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return when { h > 0 -> "${h}ч ${m}м"; m > 0 -> "${m}м ${sec}с"; else -> "${sec}с" }
        }
        
        private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())
        val startTimeFormatted: String get() = formatter.format(startTime)
        val endTimeFormatted: String? get() = endTime?.let { formatter.format(it) }
        
        val currentCost: ModelCost get() = _cachedCost ?: model.calculateCost(
            totalInputTokens, totalOutputTokens, totalCachedReadTokens, totalCachedWriteTokens
        ).also { _cachedCost = it }
        
        val isApproachingLongContext: Boolean get() = totalInputTokens > (model.longContextThreshold * 0.8)
        val isLongContext: Boolean get() = totalInputTokens > model.longContextThreshold
        val remainingTokensBeforeLongContext: Int get() = (model.longContextThreshold - totalInputTokens).coerceAtLeast(0)
        
        @Synchronized
        fun addMessage(inputTokens: Int, outputTokens: Int, cachedReadTokens: Int = 0, cachedWriteTokens: Int = 0) {
            totalInputTokens += inputTokens
            totalOutputTokens += outputTokens
            totalCachedReadTokens += cachedReadTokens
            totalCachedWriteTokens += cachedWriteTokens
            messageCount++
            _cachedCost = null
            updateMetrics()
        }
        
        @Synchronized
        fun end() {
            isActive = false
            endTime = Instant.now()
        }
        
        private fun updateMetrics() {
            cacheHitRate = if (totalInputTokens > 0) (totalCachedReadTokens.toDouble() / totalInputTokens) * 100 else 0.0
            averageCostPerMessage = if (messageCount > 0) currentCost.totalCostEUR / messageCount else 0.0
            averageTokensPerMessage = if (messageCount > 0) (totalInputTokens + totalOutputTokens) / messageCount else 0
        }
        
        fun getDetailedStats(): String = buildString {
            appendLine("📊 Session Statistics")
            appendLine()
            appendLine("Model: ${model.displayName} ${model.emoji}")
            appendLine("Duration: $durationFormatted")
            appendLine("Messages: $messageCount")
            appendLine("Total Tokens: ${"%,d".format(totalInputTokens + totalOutputTokens)}")
            appendLine("  Input: ${"%,d".format(totalInputTokens)}")
            appendLine("  Output: ${"%,d".format(totalOutputTokens)}")
            appendLine("  Cache Read: ${"%,d".format(totalCachedReadTokens)}")
            appendLine("  Cache Write: ${"%,d".format(totalCachedWriteTokens)}")
            appendLine()
            appendLine("Cache Hit Rate: ${String.format("%.1f", cacheHitRate)}%")
            appendLine("Avg Cost/Msg: €${String.format("%.4f", averageCostPerMessage)}")
            appendLine("Total Cost: €${String.format("%.4f", currentCost.totalCostEUR)}")
            if (currentCost.savingsPercentage > 0) {
                appendLine("Savings: ${String.format("%.1f", currentCost.savingsPercentage)}% " +
                    "(€${String.format("%.4f", currentCost.cacheSavingsEUR)})")
            }
        }
    }
    
    object SessionManager {
        private const val TAG = "SessionManager"
        private val sessions = ConcurrentHashMap<String, ChatSession>()
        
        fun createSession(sessionId: String, model: ClaudeModel): ChatSession =
            sessions.getOrPut(sessionId) {
                ChatSession(sessionId = sessionId, model = model, startTime = Instant.now()).also {
                    Log.i(TAG, "Created: $sessionId [${model.displayName}]")
                }
            }
        
        fun getSession(sessionId: String): ChatSession? = sessions[sessionId]
        
        fun endSession(sessionId: String): ChatSession? {
            val session = sessions.remove(sessionId)
            session?.end()
            return session
        }
        
        fun getAllActiveSessions(): List<ChatSession> = sessions.values.filter { it.isActive }
        fun getAllSessions(): List<ChatSession> = sessions.values.toList()
        fun shouldStartNewSession(sessionId: String): Boolean = sessions[sessionId]?.isApproachingLongContext == true
        
        fun cleanupOldSessions(maxAge: Duration = Duration.ofDays(1)): Int {
            val now = Instant.now()
            var cleaned = 0
            sessions.values.toList().forEach { session ->
                val shouldClean = if (!session.isActive) {
                    Duration.between(session.endTime ?: now, now) > maxAge
                } else {
                    Duration.between(session.startTime, now) > Duration.ofHours(24)
                }
                if (shouldClean) {
                    if (session.isActive) session.end()
                    sessions.remove(session.sessionId)
                    cleaned++
                }
            }
            if (cleaned > 0) Log.i(TAG, "Cleaned $cleaned old sessions")
            return cleaned
        }
        
        fun getTotalCost(): Map<ClaudeModel, ModelCost>? {
            val list = sessions.values.toList()
            if (list.isEmpty()) return null
            return list.groupBy { it.model }.mapValues { (_, s) -> s.map { it.currentCost }.reduce { a, b -> a + b } }
        }
        
        fun clear() {
            val count = sessions.size
            sessions.clear()
            Log.i(TAG, "Cleared $count sessions")
        }
    }
}