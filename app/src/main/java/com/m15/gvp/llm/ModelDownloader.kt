package com.m15.gvp.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads a LiteRT `.task` model to app-private storage with progress reporting. Writes to a
 * `.part` temp file and atomically renames on success so a half-finished download is never mistaken
 * for a usable model. Follows HTTP→HTTPS and CDN redirects (Hugging Face `resolve` URLs 302 to a CDN).
 */
object ModelDownloader {

    private const val TAG = "GVP.LLM"

    /**
     * @param authToken optional Hugging Face access token for gated models (e.g. Gemma). Sent as a
     *   Bearer header only to `huggingface.co`; it is intentionally dropped on the redirect to the
     *   pre-signed CDN host, which both rejects and doesn't need it.
     * @param onProgress invoked with (downloadedBytes, totalBytes); totalBytes is -1 if unknown.
     * @return Result.success(modelFile) or Result.failure(error).
     */
    suspend fun download(
        url: String,
        dest: File,
        authToken: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        runCatching {
            tmp.delete()
            var current = URL(url)
            var conn: HttpURLConnection
            var redirects = 0
            // Manually follow redirects so we can cross http/https and hop to the HF CDN host.
            while (true) {
                conn = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "GVP/0.1")
                    // Authorize gated (Gemma) downloads, but only against HF itself — the CDN it
                    // redirects to uses a signed URL and 400s if an Authorization header is present.
                    if (!authToken.isNullOrBlank() && current.host.endsWith("huggingface.co")) {
                        setRequestProperty("Authorization", "Bearer $authToken")
                    }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: error("redirect with no Location")
                    conn.disconnect()
                    if (++redirects > 5) error("too many redirects")
                    current = URL(current, loc)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    val msg = "HTTP $code ${conn.responseMessage}"
                    conn.disconnect()
                    // 401/403 typically means the model is auth-gated (needs a Hugging Face token).
                    error(msg)
                }
                break
            }

            val total = conn.contentLengthLong
            Log.i(TAG, "downloading model: $url -> ${dest.name} (${total / 1_000_000} MB)")

            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive() // cancel-aware
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        // Throttle progress callbacks to ~every 2 MB to avoid UI churn.
                        if (downloaded - lastReport >= 2_000_000 || (total in 1..downloaded)) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            if (!tmp.renameTo(dest)) {
                // renameTo can fail across filesystems; fall back to copy.
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "model download complete: ${dest.absolutePath} (${dest.length()} bytes)")
            dest
        }.onFailure {
            Log.e(TAG, "model download failed", it)
            runCatching { tmp.delete() }
        }
    }
}