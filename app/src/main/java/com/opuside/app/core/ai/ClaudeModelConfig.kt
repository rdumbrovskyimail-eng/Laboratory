package com.opuside.app.core.ai

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 🤖 CLAUDE MODEL CONFIGURATION v5.0 (ALL 8 MODELS + ECO/MAX OUTPUT)
 * 
 * ✅ ОБНОВЛЕНО (2026-02-10):
 * - contextWindow: макс контекстное окно (input + output)
 * - maxOutputTokens: предел выходных токенов каждой модели
 * - ECO_OUTPUT_TOKENS: экономный режим (8192 для всех)
 * - getEffectiveOutputTokens(ecoMode): выбор между ECO и MAX
 * 
 * Лимиты по моделям (из docs.anthropic.com):
 * 1. Opus 4.6   — context: 200K (1M beta), max output: 128,000
 * 2. Opus 4.5   — context: 200K,           max output: 64,000
 * 3. Opus 4.1   — context: 200K,           max output: 64,000
 * 4. Opus 4     — context: 200K,           max output: 64,000
 * 5. Sonnet 4.5 — context: 200K (1M beta), max output: 64,000
 * 6. Sonnet 4   — context: 200K (1M beta), max output: 64,000
 * 7. Haiku 4.5  — context: 200K,           max output: 64,000
 * 8. Haiku 3    — context: 200K,           max output: 4,096
 */
object ClaudeModelConfig {
    
    private const val TAG = "ClaudeModelConfig"
    
    /** ECO mode: экономный лимит output для всех моделей */
    const val ECO_OUTPUT_TOKENS = 8192
    
    enum class ClaudeModel(
        val modelId: String,
        val displayName: String,
        val description: String,
        val contextWindow: Int,
        val maxOutputTokens: Int,
        val inputPricePerM: Double,
        val outputPricePerM: Double,
        val longInputPricePerM: Double,
        val longOutputPricePerM: Double,
        val cachedInputPricePerM: Double,
        val longContextThreshold: Int,
        val supportsLongContext1M: Boolean,
        val speedRating: Int,
        val emoji: String
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // OPUS MODELS (most capable)
        // ═══════════════════════════════════════════════════════════════════
        
        OPUS_4_6(
            modelId = "claude-opus-4-6",
            displayName = "Opus 4.6",
            description = "Новейшая, лучшая для кодирования",
            contextWindow = 200_000,
            maxOutputTokens = 128_000,
            inputPricePerM = 5.0,
            outputPricePerM = 25.0,
            longInputPricePerM = 10.0,
            longOutputPricePerM = 37.5,
            cachedInputPricePerM = 0.50,
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
            inputPricePerM = 5.0,
            outputPricePerM = 25.0,
            longInputPricePerM = 10.0,
            longOutputPricePerM = 37.5,
            cachedInputPricePerM = 0.50,
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
            inputPricePerM = 15.0,
            outputPricePerM = 75.0,
            longInputPricePerM = 30.0,
            longOutputPricePerM = 112.5,
            cachedInputPricePerM = 1.50,
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
            inputPricePerM = 15.0,
            outputPricePerM = 75.0,
            longInputPricePerM = 30.0,
            longOutputPricePerM = 112.5,
            cachedInputPricePerM = 1.50,
            longContextThreshold = 200_000,
            supportsLongContext1M = false,
            speedRating = 2,
            emoji = "💎"
        ),

        // ═══════════════════════════════════════════════════════════════════
        // SONNET MODELS (balanced)
        // ═══════════════════════════════════════════════════════════════════
        
        SONNET_4_5(
            modelId = "claude-sonnet-4-5-20250929",
            displayName = "Sonnet 4.5",
            description = "Умная и эффективная",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            inputPricePerM = 3.0,
            outputPricePerM = 15.0,
            longInputPricePerM = 6.0,
            longOutputPricePerM = 22.5,
            cachedInputPricePerM = 0.30,
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
            inputPricePerM = 3.0,
            outputPricePerM = 15.0,
            longInputPricePerM = 6.0,
            longOutputPricePerM = 22.5,
            cachedInputPricePerM = 0.30,
            longContextThreshold = 200_000,
            supportsLongContext1M = true,
            speedRating = 5,
            emoji = "✨"
        ),

        // ═══════════════════════════════════════════════════════════════════
        // HAIKU MODELS (fastest & cheapest)
        // ═══════════════════════════════════════════════════════════════════
        
        HAIKU_4_5(
            modelId = "claude-haiku-4-5-20251001",
            displayName = "Haiku 4.5",
            description = "Быстрая для ежедневных задач",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            inputPricePerM = 1.0,
            outputPricePerM = 5.0,
            longInputPricePerM = 2.0,
            longOutputPricePerM = 7.5,
            cachedInputPricePerM = 0.10,
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
            inputPricePerM = 0.25,
            outputPricePerM = 1.25,
            longInputPricePerM = 0.25,
            longOutputPricePerM = 1.25,
            cachedInputPricePerM = 0.03,
            longContextThreshold = 200_000,
            supportsLongContext1M = false,
            speedRating = 10,
            emoji = "🪶"
        );
        
        /**
         * Получить эффективный лимит output токенов
         * ECO mode (true)  → 8192 (или maxOutputTokens если он меньше, как у Haiku 3)
         * MAX mode (false)  → maxOutputTokens модели
         */
        fun getEffectiveOutputTokens(ecoMode: Boolean): Int {
            return if (ecoMode) {
                minOf(ECO_OUTPUT_TOKENS, maxOutputTokens)
            } else {
                maxOutputTokens
            }
        }
        
        /**
         * Максимум входных токенов с учётом зарезервированного output
         */
        fun getMaxInputTokens(ecoMode: Boolean): Int {
            return contextWindow - getEffectiveOutputTokens(ecoMode)
        }
        
        companion object {
            fun fromModelId(modelId: String): ClaudeModel? {
                return entries.find { it.modelId == modelId }
            }
            
            fun getAllModelIds(): List<String> {
                return entries.map { it.modelId }
            }
            
            fun getAllModelsWithNames(): List<Pair<String, String>> {
                return entries.map { 
                    it.modelId to "${it.emoji} ${it.displayName} — \$${it.inputPricePerM}/\$${it.outputPricePerM}"
                }
            }
            
            fun getDefault(): ClaudeModel = OPUS_4_6
        }
        
        fun calculateCost(
            inputTokens: Int,
            outputTokens: Int,
            cachedInputTokens: Int = 0,
            usdToEur: Double = 0.92
        ): ModelCost {
            require(inputTokens >= 0) { "Input tokens cannot be negative: $inputTokens" }
            require(outputTokens >= 0) { "Output tokens cannot be negative: $outputTokens" }
            require(cachedInputTokens >= 0) { "Cached tokens cannot be negative: $cachedInputTokens" }
            require(cachedInputTokens <= inputTokens) { 
                "Cached tokens ($cachedInputTokens) cannot exceed input tokens ($inputTokens)" 
            }
            require(usdToEur > 0) { "USD to EUR rate must be positive: $usdToEur" }
            
            Log.d(TAG, "Calculating cost for ${this.displayName}: " +
                    "input=$inputTokens, output=$outputTokens, cached=$cachedInputTokens")
            
            val isLongContext = inputTokens > longContextThreshold
            
            val actualInputPrice = if (isLongContext) longInputPricePerM else inputPricePerM
            val actualOutputPrice = if (isLongContext) longOutputPricePerM else outputPricePerM
            
            val newInputTokens = inputTokens - cachedInputTokens
            
            val newInputCostUSD = (newInputTokens / 1_000_000.0) * actualInputPrice
            val cachedInputCostUSD = (cachedInputTokens / 1_000_000.0) * cachedInputPricePerM
            val outputCostUSD = (outputTokens / 1_000_000.0) * actualOutputPrice
            
            val totalCostUSD = newInputCostUSD + cachedInputCostUSD + outputCostUSD
            val totalCostEUR = totalCostUSD * usdToEur
            
            val savingsUSD = if (cachedInputTokens > 0) {
                (cachedInputTokens / 1_000_000.0) * (actualInputPrice - cachedInputPricePerM)
            } else 0.0
            
            val cost = ModelCost(
                model = this,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedInputTokens = cachedInputTokens,
                newInputTokens = newInputTokens,
                isLongContext = isLongContext,
                newInputCostUSD = newInputCostUSD,
                cachedInputCostUSD = cachedInputCostUSD,
                outputCostUSD = outputCostUSD,
                totalCostUSD = totalCostUSD,
                totalCostEUR = totalCostEUR,
                cacheSavingsUSD = savingsUSD,
                cacheSavingsEUR = savingsUSD * usdToEur
            )
            
            Log.d(TAG, "Cost calculated: $${String.format("%.4f", totalCostUSD)} " +
                    "(€${String.format("%.4f", totalCostEUR)}), " +
                    "savings: ${String.format("%.1f", cost.savingsPercentage)}%")
            
            return cost
        }
    }
    
    data class ModelCost(
        val model: ClaudeModel,
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int,
        val newInputTokens: Int,
        val isLongContext: Boolean,
        val newInputCostUSD: Double,
        val cachedInputCostUSD: Double,
        val outputCostUSD: Double,
        val totalCostUSD: Double,
        val totalCostEUR: Double,
        val cacheSavingsUSD: Double,
        val cacheSavingsEUR: Double
    ) {
        val totalTokens: Int = inputTokens + outputTokens
        
        val savingsPercentage: Double = if (inputTokens > 0 && cacheSavingsUSD > 0) {
            (cacheSavingsUSD / (totalCostUSD + cacheSavingsUSD)) * 100
        } else 0.0
        
        val costPerToken: Double = if (totalTokens > 0) {
            totalCostUSD / totalTokens
        } else 0.0
        
        val cacheEfficiency: Double = if (inputTokens > 0) {
            (cachedInputTokens.toDouble() / inputTokens) * 100
        } else 0.0
        
        operator fun plus(other: ModelCost): ModelCost {
            require(model == other.model) { "Cannot combine costs from different models" }
            
            return model.calculateCost(
                inputTokens = inputTokens + other.inputTokens,
                outputTokens = outputTokens + other.outputTokens,
                cachedInputTokens = cachedInputTokens + other.cachedInputTokens
            )
        }
        
        override fun toString(): String = buildString {
            append("ModelCost(")
            append("model=${model.displayName}, ")
            append("tokens=$totalTokens, ")
            append("cost=$${String.format("%.4f", totalCostUSD)}, ")
            append("savings=${String.format("%.1f", savingsPercentage)}%")
            append(")")
        }
    }
    
    data class ChatSession(
        val sessionId: String,
        val model: ClaudeModel,
        val startTime: Instant,
        var endTime: Instant? = null,
        var totalInputTokens: Int = 0,
        var totalOutputTokens: Int = 0,
        var totalCachedInputTokens: Int = 0,
        var messageCount: Int = 0,
        var isActive: Boolean = true
    ) {
        var cacheHitRate: Double = 0.0
            private set
        
        var averageCostPerMessage: Double = 0.0
            private set
        
        var averageTokensPerMessage: Int = 0
            private set
        
        private var _cachedCost: ModelCost? = null
        
        val duration: Long
            get() = (endTime ?: Instant.now()).epochSecond - startTime.epochSecond
        
        val durationFormatted: String
            get() {
                val seconds = duration
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                return when {
                    hours > 0 -> "${hours}ч ${minutes}м"
                    minutes > 0 -> "${minutes}м ${secs}с"
                    else -> "${secs}с"
                }
            }
        
        val startTimeFormatted: String
            get() = formatInstant(startTime)
        
        val endTimeFormatted: String?
            get() = endTime?.let { formatInstant(it) }
        
        val currentCost: ModelCost
            get() = _cachedCost ?: model.calculateCost(
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                cachedInputTokens = totalCachedInputTokens
            ).also { _cachedCost = it }
        
        val isApproachingLongContext: Boolean
            get() = totalInputTokens > (model.longContextThreshold * 0.8)
        
        val isLongContext: Boolean
            get() = totalInputTokens > model.longContextThreshold
        
        val remainingTokensBeforeLongContext: Int
            get() = (model.longContextThreshold - totalInputTokens).coerceAtLeast(0)
        
        @Synchronized
        fun addMessage(inputTokens: Int, outputTokens: Int, cachedInputTokens: Int = 0) {
            require(inputTokens >= 0) { "Input tokens cannot be negative" }
            require(outputTokens >= 0) { "Output tokens cannot be negative" }
            require(cachedInputTokens >= 0) { "Cached tokens cannot be negative" }
            
            totalInputTokens += inputTokens
            totalOutputTokens += outputTokens
            totalCachedInputTokens += cachedInputTokens
            messageCount++
            
            _cachedCost = null
            
            updateMetrics()
            
            Log.d(TAG, "Message added to session $sessionId: " +
                    "input=$inputTokens, output=$outputTokens, cached=$cachedInputTokens, " +
                    "total messages=$messageCount")
        }
        
        @Synchronized
        fun end() {
            isActive = false
            endTime = Instant.now()
            
            Log.i(TAG, "Session $sessionId ended: " +
                    "duration=$durationFormatted, messages=$messageCount, " +
                    "cost=${currentCost.totalCostEUR}€")
        }
        
        private fun updateMetrics() {
            cacheHitRate = if (totalInputTokens > 0) {
                (totalCachedInputTokens.toDouble() / totalInputTokens) * 100
            } else 0.0
            
            averageCostPerMessage = if (messageCount > 0) {
                currentCost.totalCostEUR / messageCount
            } else 0.0
            
            averageTokensPerMessage = if (messageCount > 0) {
                (totalInputTokens + totalOutputTokens) / messageCount
            } else 0
        }
        
        private fun formatInstant(instant: Instant): String {
            val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault())
            return formatter.format(instant)
        }
        
        fun getDetailedStats(): String = buildString {
            appendLine("📊 Session Statistics")
            appendLine()
            appendLine("**Session ID:** ${sessionId.take(8)}...")
            appendLine("**Model:** ${model.displayName} ${model.emoji}")
            appendLine("**Context:** ${"%,d".format(model.contextWindow)} tok")
            appendLine("**Max Output:** ${"%,d".format(model.maxOutputTokens)} tok")
            appendLine("**Started:** $startTimeFormatted")
            if (endTime != null) {
                appendLine("**Ended:** $endTimeFormatted")
            }
            appendLine("**Duration:** $durationFormatted")
            appendLine()
            appendLine("**Messages:** $messageCount")
            appendLine("**Total Tokens:** ${"%,d".format(totalInputTokens + totalOutputTokens)}")
            appendLine("**Input Tokens:** ${"%,d".format(totalInputTokens)}")
            appendLine("**Output Tokens:** ${"%,d".format(totalOutputTokens)}")
            appendLine("**Cached Tokens:** ${"%,d".format(totalCachedInputTokens)}")
            appendLine()
            appendLine("**Cache Hit Rate:** ${String.format("%.1f", cacheHitRate)}%")
            appendLine("**Avg Tokens/Msg:** ${"%,d".format(averageTokensPerMessage)}")
            appendLine("**Avg Cost/Msg:** €${String.format("%.4f", averageCostPerMessage)}")
            appendLine()
            appendLine("**Total Cost:** €${String.format("%.4f", currentCost.totalCostEUR)}")
            if (currentCost.savingsPercentage > 0) {
                appendLine("**Savings:** ${String.format("%.1f", currentCost.savingsPercentage)}% " +
                        "(€${String.format("%.4f", currentCost.cacheSavingsEUR)})")
            }
        }
    }
    
    object SessionManager {
        private const val TAG = "SessionManager"
        private val sessions = ConcurrentHashMap<String, ChatSession>()
        
        fun createSession(sessionId: String, model: ClaudeModel): ChatSession {
            return sessions.getOrPut(sessionId) {
                ChatSession(
                    sessionId = sessionId,
                    model = model,
                    startTime = Instant.now()
                ).also {
                    Log.i(TAG, "Created new session: $sessionId with model ${model.displayName}")
                }
            }
        }
        
        fun getSession(sessionId: String): ChatSession? = sessions[sessionId]
        
        fun endSession(sessionId: String): ChatSession? {
            val session = sessions.remove(sessionId)
            session?.end()
            if (session != null) Log.i(TAG, "Ended session: $sessionId")
            return session
        }
        
        fun getAllActiveSessions(): List<ChatSession> = sessions.values.filter { it.isActive }
        
        fun getAllSessions(): List<ChatSession> = sessions.values.toList()
        
        fun shouldStartNewSession(sessionId: String): Boolean {
            val session = sessions[sessionId] ?: return false
            return session.isApproachingLongContext
        }
        
        fun cleanupOldSessions(maxAge: Duration = Duration.ofDays(1)): Int {
            val now = Instant.now()
            var cleaned = 0
            
            sessions.values.toList().forEach { session ->
                if (!session.isActive) {
                    val endTime = session.endTime ?: now
                    val age = Duration.between(endTime, now)
                    if (age > maxAge) {
                        sessions.remove(session.sessionId)
                        cleaned++
                    }
                } else {
                    val age = Duration.between(session.startTime, now)
                    if (age > Duration.ofHours(24)) {
                        session.end()
                        sessions.remove(session.sessionId)
                        cleaned++
                    }
                }
            }
            
            if (cleaned > 0) Log.i(TAG, "Cleaned up $cleaned old sessions")
            return cleaned
        }
        
        fun getTotalCost(): Map<ClaudeModel, ModelCost>? {
            val sessionList = sessions.values.toList()
            if (sessionList.isEmpty()) return null
            
            return sessionList
                .groupBy { it.model }
                .mapValues { (_, modelSessions) ->
                    modelSessions.map { it.currentCost }.reduce { acc, cost -> acc + cost }
                }
        }
        
        fun clear() {
            val count = sessions.size
            sessions.clear()
            Log.i(TAG, "Cleared all sessions: $count")
        }
    }
}