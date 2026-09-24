package com.kidsafe.beacon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal Telegram Bot API client. All calls run on the IO dispatcher and
 * return true on HTTP 2xx. No third-party server is involved — requests go
 * straight to Telegram's free public API.
 */
class TelegramClient(private val token: String, private val chatId: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(method: String) = "https://api.telegram.org/bot$token/$method"

    /**
     * Long-polls for new commands. Blocks up to [timeoutSec] on Telegram's side.
     * Returns the raw JSON response body, or null on failure.
     */
    suspend fun getUpdates(offset: Long, timeoutSec: Int = 50): String? =
        withContext(Dispatchers.IO) {
            val u = url("getUpdates") + "?timeout=$timeoutSec&offset=$offset&allowed_updates=%5B%22message%22%5D"
            try {
                client.newCall(Request.Builder().url(u).get().build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (e: Exception) {
                null
            }
        }

    suspend fun sendMessage(text: String): Boolean = withContext(Dispatchers.IO) {        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("text", text)
            .build()
        execute(Request.Builder().url(url("sendMessage")).post(body).build())
    }

    suspend fun sendLocation(lat: Double, lon: Double): Boolean = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("latitude", lat.toString())
            .addFormDataPart("longitude", lon.toString())
            .build()
        execute(Request.Builder().url(url("sendLocation")).post(body).build())
    }

    suspend fun sendPhoto(file: File, caption: String = ""): Boolean = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "photo", file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
            .build()
        execute(Request.Builder().url(url("sendPhoto")).post(body).build())
    }

    suspend fun sendAudio(file: File, caption: String = ""): Boolean = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "audio", file.name,
                file.asRequestBody("audio/mp4".toMediaType())
            )
            .build()
        execute(Request.Builder().url(url("sendAudio")).post(body).build())
    }

    private fun execute(request: Request): Boolean =
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
}
