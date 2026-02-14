package com.opuside.app.core.network.anthropic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.anthropic.model.Usage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RESILIENT STREAMING CLIENT v1.0
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Обёртка над ClaudeApiClient, которая переживает обрывы сети.
 */
@Singleton
class ResilientStreamingClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val claudeClient: ClaudeApiClient
) {
    companion object {
        private const val TAG = "ResilientStream"
        private const val MAX_RETRIES = 3              // ✅ ИСПРАВЛЕНИЕ #6: было 50, стало 3
        private const val INITIAL_BACKOFF_MS = 5000L   // ✅ ИСПРАВЛЕНО: было 2000, стало 5000
        private const val MAX_BACKOFF_MS = 10_000L     // ✅ ИСПРАВЛЕНО: было 60_000, стало 10_000
        private const val NETWORK_WAIT_TIMEOUT_MS = 2 * 60 * 1000L  // ✅ ИСПРАВЛЕНО: было 10 минут, стало 2
    }

    // ══════════════════════════════════════════════════════════════════
    // RESULT TYPES
    // ══════════════════════════════════════════════════════════════════

    sealed class ResilientResult {
        /** Стрим начался (или возобновлён после retry) */
        data class Started(val messageId: String, val isRetry: Boolean = false) : ResilientResult()

        /** Новая порция текста */
        data class Delta(val text: String, val accumulated: String) : ResilientResult()

        /** Стрим потерян, ждём сеть */
        data class WaitingForNetwork(
            val attempt: Int,
            val maxAttempts: Int,
            val accumulatedText: String,
            val accumulatedTokens: Int
        ) : ResilientResult()

        /** Сеть вернулась, делаем retry */
        data class Retrying(
            val attempt: Int,
            val maxAttempts: Int,
            val backoffMs: Long
        ) : ResilientResult()

        /** Tool Use (прокидка из базового клиента) */
        data class ToolUse(
            val textSoFar: String,
            val toolCalls: List<ToolCall>,
            val usage: Usage?
        ) : ResilientResult()

        /** Успешно завершено */
        data class Completed(
            val fullText: String,
            val usage: Usage?,
            val totalRetries: Int
        ) : ResilientResult()

        /** Фатальная ошибка (не связана с сетью) */
        data class Error(val exception: ClaudeApiException) : ResilientResult()
    }

    // ══════════════════════════════════════════════════════════════════
    // NETWORK MONITOR
    // ══════════════════════════════════════════════════════════════════

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Ждёт восстановления сети через callback.
     * Возвращает true если сеть появилась, false если таймаут.
     */
    private suspend fun waitForNetwork(timeoutMs: Long = NETWORK_WAIT_TIMEOUT_MS): Boolean {
        if (isNetworkAvailable()) return true

        return suspendCancellableCoroutine { cont ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

            var resolved = false
            var networkCallback: ConnectivityManager.NetworkCallback? = null
            
            val timeoutJob = CoroutineScope(Dispatchers.Default).launch {
                delay(timeoutMs)
                if (!resolved) {
                    resolved = true
                    networkCallback?.let {
                        try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
                    }
                    if (cont.isActive) cont.resume(false) {}
                }
            }

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!resolved) {
                        resolved = true
                        timeoutJob.cancel()
                        try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(true) {}
                    }
                }
            }

            cont.invokeOnCancellation {
                resolved = true
                timeoutJob.cancel()
                networkCallback?.let {
                    try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
                }
            }

            try {
                cm.registerNetworkCallback(request, networkCallback)
            } catch (e: Exception) {
                resolved = true
                timeoutJob.cancel()
                if (cont.isActive) cont.resume(false) {}
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // MAIN RESILIENT STREAM
    // ══════════════════════════════════════════════════════════════════

    fun streamWithRetry(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
        enableCaching: Boolean = false,
        tools: List<JsonObject>? = null,
        enableThinking: Boolean = false,
        thinkingBudget: Int = 40000,
        sendTools: Boolean = true,
        sendSystemPrompt: Boolean = true
    ): Flow<ResilientResult> = flow {
        var accumulatedText = StringBuilder()
        var totalRetries = 0
        var currentBackoff = INITIAL_BACKOFF_MS
        var totalUsage: Usage? = null
        var isRetry = false

        // Текущие messages — обновляются при retry
        var currentMessages = messages.toMutableList()
        var currentMaxTokens = maxTokens

        while (totalRetries <= MAX_RETRIES) {
            var streamCompleted = false
            var streamError: ClaudeApiException? = null
            var wasNetworkError = false
            var hadToolUse = false

            try {
                claudeClient.streamMessage(
                    model = model,
                    messages = currentMessages,
                    systemPrompt = systemPrompt,
                    maxTokens = currentMaxTokens,
                    temperature = temperature,
                    enableCaching = enableCaching,
                    tools = tools,
                    enableThinking = enableThinking,
                    thinkingBudget = thinkingBudget,
                    sendTools = sendTools,
                    sendSystemPrompt = sendSystemPrompt
                ).collect { result ->
                    when (result) {
                        is StreamingResult.Started -> {
                            emit(ResilientResult.Started(result.messageId, isRetry))
                        }

                        is StreamingResult.Delta -> {
                            accumulatedText.clear()
                            accumulatedText.append(result.accumulated)
                            emit(ResilientResult.Delta(result.text, result.accumulated))
                        }

                        is StreamingResult.ToolUse -> {
                            hadToolUse = true
                            // Tool Use прокидываем наверх — RepositoryAnalyzer обработает
                            emit(ResilientResult.ToolUse(
                                textSoFar = result.textSoFar,
                                toolCalls = result.toolCalls,
                                usage = result.usage
                            ))
                            // После tool use — стрим завершается, новый начнётся в tool loop
                            streamCompleted = true
                        }

                        is StreamingResult.Completed -> {
                            accumulatedText.clear()
                            accumulatedText.append(result.fullText)
                            totalUsage = mergeUsage(totalUsage, result.usage)
                            streamCompleted = true
                            emit(ResilientResult.Completed(
                                fullText = result.fullText,
                                usage = totalUsage,
                                totalRetries = totalRetries
                            ))
                        }

                        is StreamingResult.Error -> {
                            streamError = result.exception
                            wasNetworkError = isNetworkError(result.exception)
                        }

                        is StreamingResult.StopReason -> {
                            // Игнорируем — Completed придёт следом
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Не ловим cancel
            } catch (e: Exception) {
                Log.e(TAG, "Stream exception (retry=$totalRetries)", e)
                streamError = ClaudeApiException("network_error", e.message ?: "Connection lost", cause = e)
                wasNetworkError = true
            }

            // Стрим завершился успешно или tool use
            if (streamCompleted) break

            // ════════════════════════════════════════════════════════
            // ОБРАБОТКА ОШИБКИ
            // ════════════════════════════════════════════════════════

            if (streamError != null) {
                // Не-сетевые ошибки — не retry-able
                if (!wasNetworkError) {
                    emit(ResilientResult.Error(streamError!!))
                    break
                }

                // Tool Use + сетевая ошибка — слишком сложно для resume
                if (hadToolUse) {
                    emit(ResilientResult.Error(ClaudeApiException(
                        "network_error",
                        "Network lost during tool execution. Cannot resume safely. " +
                        "Accumulated text saved: ${accumulatedText.length} chars."
                    )))
                    break
                }

                totalRetries++
                if (totalRetries > MAX_RETRIES) {
                    emit(ResilientResult.Error(ClaudeApiException(
                        "max_retries",
                        "Max retries ($MAX_RETRIES) exceeded. " +
                        "Accumulated text: ${accumulatedText.length} chars."
                    )))
                    break
                }

                val accText = accumulatedText.toString()
                Log.w(TAG, "Network error. Retry #$totalRetries. Accumulated: ${accText.length} chars")

                // ════════════════════════════════════════════════════
                // ЖДЁМ СЕТЬ
                // ════════════════════════════════════════════════════

                emit(ResilientResult.WaitingForNetwork(
                    attempt = totalRetries,
                    maxAttempts = MAX_RETRIES,
                    accumulatedText = accText,
                    accumulatedTokens = accText.length / 4 // грубая оценка
                ))

                val networkRestored = waitForNetwork()
                if (!networkRestored) {
                    emit(ResilientResult.Error(ClaudeApiException(
                        "network_timeout",
                        "Network did not return within ${NETWORK_WAIT_TIMEOUT_MS / 60000} minutes. " +
                        "Accumulated: ${accText.length} chars."
                    )))
                    break
                }

                // ════════════════════════════════════════════════════
                // BACKOFF + RETRY
                // ════════════════════════════════════════════════════

                emit(ResilientResult.Retrying(
                    attempt = totalRetries,
                    maxAttempts = MAX_RETRIES,
                    backoffMs = currentBackoff
                ))

                delay(currentBackoff)
                currentBackoff = (currentBackoff * 2).coerceAtMost(MAX_BACKOFF_MS)

                // ════════════════════════════════════════════════════
                // ПОСТРОЕНИЕ RETRY REQUEST
                // ════════════════════════════════════════════════════

                if (accText.isNotBlank()) {
                    // Строим continuation request:
                    // 1. Оригинальные messages
                    // 2. + Assistant с уже полученным текстом
                    // 3. + User с просьбой продолжить
                    currentMessages = messages.toMutableList()
                    currentMessages.add(ClaudeMessage("assistant", accText))
                    currentMessages.add(ClaudeMessage("user",
                        "Соединение было потеряно. Продолжи ТОЧНО с того места где остановился. " +
                        "НЕ повторяй уже написанное. Просто продолжи."
                    ))

                    // Уменьшаем maxTokens на уже полученные
                    val estimatedOutputTokens = accText.length / 4
                    currentMaxTokens = (maxTokens - estimatedOutputTokens).coerceAtLeast(1000)
                } else {
                    // Ничего не получили — просто повтор
                    currentMessages = messages.toMutableList()
                    currentMaxTokens = maxTokens
                }

                isRetry = true
            }
        }
    }.flowOn(Dispatchers.IO)

    // ══════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════

    private fun isNetworkError(exception: ClaudeApiException): Boolean {
        // Сетевые ошибки — retry-able
        if (exception.type == "network_error") return true
        if (exception.type == "timeout") return true
        if (exception.type == "overloaded_error") return true
        // Rate limit — retry-able (с backoff)
        if (exception.isRateLimitError) return true
        // Всё остальное (auth, invalid request) — НЕ retry-able
        return false
    }

    private fun mergeUsage(a: Usage?, b: Usage?): Usage? {
        if (a == null) return b
        if (b == null) return a
        return Usage(
            inputTokens = a.inputTokens + b.inputTokens,
            outputTokens = a.outputTokens + b.outputTokens,
            cacheCreationInputTokens = (a.cacheCreationInputTokens ?: 0) + (b.cacheCreationInputTokens ?: 0),
            cacheReadInputTokens = (a.cacheReadInputTokens ?: 0) + (b.cacheReadInputTokens ?: 0)
        )
    }
}