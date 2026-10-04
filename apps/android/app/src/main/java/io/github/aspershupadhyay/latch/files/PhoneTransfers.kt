package io.github.aspershupadhyay.latch.files

import android.os.Environment
import android.os.StatFs
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.FileItem
import io.github.aspershupadhyay.latch.protocol.FileLink
import io.github.aspershupadhyay.latch.protocol.FileTransfer
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Whole-file transfers by link (protocol 1.7, ADR-027). The phone streams the
 * bytes itself, so a file of a gigabyte or more never passes through a
 * command frame and the gateway's function limits do not apply. Bytes are
 * copied as they are: nothing is re-encoded or compressed.
 *
 * What travels and is stored is AES-256-CTR ciphertext under the link's key,
 * made for that one transfer; the key only ever exists on the phone, the
 * owner's gateway, and the AI's computer, never at the storage. A download is
 * written to a hidden pending file and becomes visible only when its SHA-256
 * matches; otherwise it is deleted. Links are followed only to the owner's
 * gateway and to the storage hosts the protocol names, never through
 * redirects. File contents are never logged.
 */
/** Where transfers read and write files: [PhoneFiles] on the phone. */
interface TransferFiles {
    /** A file being written: hidden until committed. */
    interface Target

    fun begin(location: io.github.aspershupadhyay.latch.protocol.FileLocation, folderId: String?, subfolder: String?, name: String, mime: String?, overwrite: Boolean): Target
    fun output(target: Target): java.io.OutputStream
    fun commit(target: Target, size: Long): FileItem
    fun abort(target: Target)
    fun openRead(id: String): Triple<FileItem, Long?, InputStream>
}

class PhoneTransfers(
    private val files: TransferFiles,
    http: OkHttpClient,
    /** The paired gateway's address, e.g. https://latch-gateway.vercel.app. */
    private val gatewayUrl: () -> String?,
) {
    /** What the owner sees while a file moves: notification and cursor label. */
    data class Progress(val name: String, val download: Boolean, val doneBytes: Long, val totalBytes: Long?)

    private class Transfer(
        val id: String,
        val download: Boolean,
        @Volatile var name: String,
        @Volatile var total: Long?,
    ) {
        val done = AtomicLong(0)
        @Volatile var item: FileItem? = null
        @Volatile var sha256: String? = null
        @Volatile var error: ProtocolException? = null
        @Volatile var finished = false
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val random = SecureRandom()
    private val _progress = MutableStateFlow<List<Progress>>(emptyList())
    /** Transfers still moving, for the notification and the cursor. */
    val progress: StateFlow<List<Progress>> = _progress.asStateFlow()

    /** Long transfers: no overall deadline, only a stall timeout; links are never redirected elsewhere. */
    private val client = http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(code, message)

    /** Starts a download; answers once it is done, or after [quickMs] with its progress. */
    suspend fun fetch(command: Command.FetchFile, quickMs: Long = QUICK_MS): FileTransfer {
        checkLink(command.link, upload = false)
        command.size?.let(::checkSpace)
        val t = Transfer(newId(), download = true, name = command.fileName, total = command.size)
        transfers[t.id] = t
        t.job = scope.launch { run(t) { download(t, command) } }
        return wait(t, quickMs)
    }

    /** Starts an upload of a phone file; answers like [fetch]. */
    suspend fun push(command: Command.PushFile, quickMs: Long = QUICK_MS): FileTransfer {
        checkLink(command.link, upload = true)
        val (item, size, input) = files.openRead(command.id)
        if (size != null && size > command.maxBytes) {
            input.close()
            fail(ErrorCode.INVALID_REQUEST, "the file is larger than this gateway's link limit")
        }
        val t = Transfer(newId(), download = false, name = item.name, total = size)
        t.item = item
        transfers[t.id] = t
        t.job = scope.launch { run(t) { upload(t, command, input, size) } }
        return wait(t, quickMs)
    }

    /** Progress of a transfer of this session, waiting up to [waitMs] for it to finish. */
    suspend fun status(id: String, waitMs: Long, cancel: Boolean): FileTransfer {
        val t = transfers[id] ?: fail(ErrorCode.TARGET_NOT_FOUND, "no transfer with that id in this session")
        if (cancel && !t.finished) {
            t.error = ProtocolException(ErrorCode.CANCELLED, "the transfer was stopped")
            t.job?.cancel()
        }
        return wait(t, waitMs)
    }

    /** Session end or Stop: every transfer stops and its partial file is removed. */
    fun cancelAll() {
        for (t in transfers.values) {
            if (!t.finished) t.error = ProtocolException(ErrorCode.CANCELLED, "the session ended")
            t.job?.cancel()
        }
        transfers.clear()
        publish()
    }

    private suspend fun wait(t: Transfer, waitMs: Long): FileTransfer {
        if (!t.finished && waitMs > 0) withTimeoutOrNull(waitMs) { t.job?.join() }
        t.error?.let { throw it }
        return FileTransfer(
            id = t.id,
            state = if (t.finished) "done" else "running",
            doneBytes = t.done.get(),
            totalBytes = t.total,
            item = t.item?.takeIf { t.finished },
            sha256 = t.sha256?.takeIf { t.finished && !t.download },
        )
    }

    private suspend fun run(t: Transfer, block: suspend () -> Unit) {
        publish()
        try {
            block()
            t.finished = true
        } catch (e: CancellationException) {
            if (t.error == null) t.error = ProtocolException(ErrorCode.CANCELLED, "the transfer was stopped")
            t.finished = true
        } catch (e: ProtocolException) {
            t.error = e
            t.finished = true
        } catch (e: java.io.IOException) {
            t.error = ProtocolException(ErrorCode.TRANSPORT_UNAVAILABLE, "the connection dropped while the file was moving; ask for a new link")
            t.finished = true
        } catch (e: Exception) {
            t.error = ProtocolException(ErrorCode.INTERNAL, "the file could not be moved")
            t.finished = true
        } finally {
            publish()
        }
    }

    private fun publish() {
        _progress.value = transfers.values.filter { !it.finished }.map { Progress(it.name, it.download, it.done.get(), it.total) }
    }

    // ---- Download: storage → decrypt → check → file ----

    private suspend fun download(t: Transfer, command: Command.FetchFile) {
        val request = Request.Builder().url(command.link.url).get().apply {
            command.link.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 403) {
                fail(ErrorCode.TARGET_NOT_FOUND, "the link answered HTTP ${response.code}; nothing was uploaded to it, or it expired")
            }
            if (!response.isSuccessful) fail(ErrorCode.TRANSPORT_UNAVAILABLE, "the link answered HTTP ${response.code}")
            val body = response.body
            val length = body.contentLength().takeIf { it >= 0 }
            if (length != null) {
                if (command.size != null && length != command.size) fail(ErrorCode.INVALID_REQUEST, "the stored file has the wrong size")
                checkSpace(length)
                t.total = length
            }
            val pending = files.begin(command.location, command.folder, command.subfolder, command.fileName, command.mime, command.overwrite)
            var kept = false
            try {
                val cipher = cipher(command.link)
                val digest = MessageDigest.getInstance("SHA-256")
                files.output(pending).use { out ->
                    body.byteStream().use { input -> pump(t, input, cipher, digest, inputIsPlain = false) { out.write(it) } }
                    out.write(cipher.doFinal().also(digest::update))
                }
                if (hex(digest.digest()) != command.sha256) {
                    fail(ErrorCode.INVALID_REQUEST, "the file did not match its sha256, so it was not saved")
                }
                t.item = files.commit(pending, t.done.get())
                t.name = t.item?.name ?: t.name
                kept = true
            } finally {
                if (!kept) files.abort(pending)
            }
        }
    }

    // ---- Upload: file → encrypt → storage ----

    private fun upload(t: Transfer, command: Command.PushFile, input: InputStream, size: Long?) {
        val cipher = cipher(command.link)
        val digest = MessageDigest.getInstance("SHA-256")
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            // CTR keeps the length, so storage that needs a length gets it.
            override fun contentLength() = size ?: -1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                input.use { stream ->
                    pump(t, stream, cipher, digest, inputIsPlain = true) { sink.write(it) }
                    sink.write(cipher.doFinal())
                }
                if (size != null && t.done.get() != size) throw ProtocolException(ErrorCode.INVALID_REQUEST, "the file changed while it was being sent")
            }
        }
        val request = Request.Builder().url(command.link.url).put(body).apply {
            command.link.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) fail(ErrorCode.TRANSPORT_UNAVAILABLE, "the upload link answered HTTP ${response.code}")
        }
        t.sha256 = hex(digest.digest())
    }

    /**
     * Copies [input] through [cipher] in large blocks, hashing the plain side
     * (the input for uploads, the output for downloads) and counting progress.
     */
    private inline fun pump(
        t: Transfer,
        input: InputStream,
        cipher: Cipher,
        digest: MessageDigest,
        inputIsPlain: Boolean,
        write: (ByteArray) -> Unit,
    ) {
        val buffer = ByteArray(BLOCK_BYTES)
        var lastPublish = 0L
        while (true) {
            if (t.job?.isCancelled == true) throw CancellationException("stopped")
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            if (inputIsPlain) digest.update(buffer, 0, n)
            val out = cipher.update(buffer, 0, n) ?: continue
            if (!inputIsPlain) digest.update(out)
            write(out)
            val done = t.done.addAndGet(n.toLong())
            if (done - lastPublish >= PUBLISH_EVERY_BYTES) {
                lastPublish = done
                publish()
            }
        }
    }

    private fun cipher(link: FileLink): Cipher = cipherFor(link)

    private fun checkLink(link: FileLink, upload: Boolean) {
        if (!isAllowedLink(link.url, gatewayUrl(), upload)) {
            fail(ErrorCode.POLICY_REFUSED, "Latch follows file links only to its own gateway and to Vercel Blob storage")
        }
    }

    private fun checkSpace(bytes: Long) {
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull() ?: return
        // Leave room for the phone itself.
        if (bytes > free - FREE_SPACE_MARGIN_BYTES) {
            fail(ErrorCode.INVALID_REQUEST, "the phone has ${free / (1024 * 1024)} MB free, not enough for this file")
        }
    }

    private fun newId() = "t_" + ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** `file.fetch` and `file.push` answer at once when a file moves this fast. */
        const val QUICK_MS = 1_500L
        /** Large blocks keep a gigabyte moving at the connection's speed. */
        private const val BLOCK_BYTES = 256 * 1024
        private const val PUBLISH_EVERY_BYTES = 2L * 1024 * 1024
        private const val FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** AES-256-CTR with a 128-bit big-endian counter: the same stream as `openssl enc -aes-256-ctr`. */
        fun cipherFor(link: FileLink): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(unhex(link.keyHex), "AES"), IvParameterSpec(unhex(link.ivHex)))
        }

        /**
         * Only the owner's own gateway (same scheme, host, and port) and Vercel
         * Blob's private storage: a link can never make the phone talk to anything else.
         */
        fun isAllowedLink(link: String, gateway: String?, upload: Boolean): Boolean {
            val url = link.toHttpUrlOrNull() ?: return false
            val own = gateway?.toHttpUrlOrNull()
            val sameGateway = own != null && url.scheme == own.scheme && url.host == own.host && url.port == own.port
            val blobStore = url.isHttps && url.host.endsWith(".blob.vercel-storage.com")
            val blobApi = upload && url.isHttps && url.host == "vercel.com" && url.encodedPath.startsWith("/api/blob")
            return sameGateway || blobStore || blobApi
        }

        fun unhex(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
