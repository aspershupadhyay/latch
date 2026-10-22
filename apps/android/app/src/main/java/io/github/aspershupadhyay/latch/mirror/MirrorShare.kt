// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Files from the phone to the computer watching its mirror (ADR-069): each
 * one streams from Android's content provider straight to the gateway's
 * `/v1/mirror/outbox`, with no copy on the phone; the page saves it.
 */
object MirrorShare {
    /** A file the owner picked or shared: its name and size as Android reports them (size -1 when unknown). */
    data class Picked(val uri: Uri, val name: String, val size: Long)

    private const val CHUNK = 256 * 1024

    fun describe(resolver: ContentResolver, uri: Uri): Picked? = runCatching {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        Picked(uri, safeName(name ?: uri.lastPathSegment ?: "file"), size)
    }.getOrNull()

    /** A name the computer can save: no folders or control characters, at most 200 characters. */
    fun safeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isISOControl()) '_' else it }.joinToString("").trim()
        val name = if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
        if (name.length <= 200) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 16 }
        return if (dot != null) name.take(200 - (name.length - dot)) + name.substring(dot) else name.take(200)
    }

    /**
     * Streams [file] to [gateway] with this phone's [token]. Calls [progress]
     * with bytes sent so far. Returns null when the computer has it, else why not.
     */
    fun upload(
        http: OkHttpClient,
        resolver: ContentResolver,
        gateway: String,
        token: String,
        file: Picked,
        progress: (Long) -> Unit,
    ): String? {
        val url = "$gateway/v1/mirror/outbox".toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("name", file.name)?.build()
            ?: return "the gateway address is not valid"
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(file.uri) ?: throw IOException("can't open the file")
                input.use {
                    val buffer = ByteArray(CHUNK)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        sent += n
                        if (sent - lastReport >= 1024 * 1024) {
                            lastReport = sent
                            progress(sent)
                        }
                    }
                    progress(sent)
                }
            }
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build()
        // Big files: no overall limit, only on a stalled connection.
        val client = http.newBuilder().writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    null
                } else {
                    val text = response.body.string()
                    Regex("\"error\"\\s*:\\s*\"([^\"]{1,300})\"").find(text)?.groupValues?.get(1) ?: "the gateway answered HTTP ${response.code}"
                }
            }
        } catch (e: IOException) {
            "the connection to your computer dropped (${e.javaClass.simpleName})"
        } catch (e: SecurityException) {
            "Android did not let Latch read that file"
        }
    }
}
