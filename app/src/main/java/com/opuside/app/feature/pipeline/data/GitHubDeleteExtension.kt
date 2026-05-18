package com.opuside.app.feature.pipeline.data

import com.opuside.app.core.network.github.GitHubApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Extension для удаления файла через GitHub Contents API.
 * Реализована тут отдельно чтобы не лезть во внутренности GitHubApiClient.
 */
suspend fun GitHubApiClient.deleteFileExt(
    owner: String,
    repo: String,
    token: String,
    path: String,
    message: String,
    sha: String,
    branch: String
): Result<Unit> = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "OpusIDE-Pipeline/1.0")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
        }
        val body = JSONObject().apply {
            put("message", message)
            put("sha", sha)
            put("branch", branch)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code in 200..299) {
            Result.success(Unit)
        } else {
            val err = conn.errorStream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: "HTTP $code"
            Result.failure(Exception("GitHub DELETE $code: ${err.take(300)}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}