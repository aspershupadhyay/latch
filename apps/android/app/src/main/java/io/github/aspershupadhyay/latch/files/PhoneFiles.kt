package io.github.aspershupadhyay.latch.files

import android.Manifest
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.FileChunk
import io.github.aspershupadhyay.latch.protocol.FileItem
import io.github.aspershupadhyay.latch.protocol.FileList
import io.github.aspershupadhyay.latch.protocol.FileLocation
import io.github.aspershupadhyay.latch.protocol.FilePreview
import io.github.aspershupadhyay.latch.protocol.Limits
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.Screenshot
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * The phone side of the file tools (protocol 1.6, ADR-026). Three places,
 * nothing else:
 *
 * - photos: pictures and videos the owner allowed with Android's photo
 *   permission (all, or only selected ones on Android 14+). New files go to
 *   `Pictures/<subfolder>` and appear in the gallery; no permission needed.
 * - downloads: `Download/<subfolder>`. Without extra permissions Android
 *   shows Latch only the files Latch saved there.
 * - folder: the one folder the owner picked (Storage Access Framework), with
 *   full create, read, update, delete.
 *
 * Files are named by opaque ids issued here and valid until the session ends;
 * paths and content URIs never leave the phone. File contents are never
 * logged. In photos and Downloads only files Latch saved may be changed.
 */
class PhoneFiles(private val context: Context, private val folderUri: () -> Uri?) {
    private class Entry(
        val uri: Uri,
        val location: FileLocation,
        val folder: Boolean,
        val name: String,
        val mime: String?,
        /** For photos and Downloads: the subfolder it was saved to, for appends. */
        val subfolder: String? = null,
    )

    private val resolver: ContentResolver get() = context.contentResolver
    private val entries = HashMap<String, Entry>()
    private val idsByUri = HashMap<String, String>()
    /** Files Latch created or replaced in this session: they may be appended to. */
    private val written = HashSet<String>()
    private val random = SecureRandom()

    /** Forgets every id (session end). */
    @Synchronized
    fun reset() {
        entries.clear()
        idsByUri.clear()
        written.clear()
    }

    private fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(code, message)

    private fun idFor(entry: Entry): String {
        val key = entry.uri.toString()
        val id = idsByUri[key] ?: ("f_" + ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) })
        idsByUri[key] = id
        entries[id] = entry
        return id
    }

    private fun entry(id: String): Entry =
        entries[id] ?: fail(ErrorCode.TARGET_NOT_FOUND, "no file with that id in this session; list files again")

    private fun kindOf(folder: Boolean, mime: String?) = when {
        folder -> "folder"
        mime?.startsWith("image/") == true -> "image"
        mime?.startsWith("video/") == true -> "video"
        else -> "file"
    }

    private fun item(entry: Entry, size: Long?, modified: Long?) = FileItem(
        id = idFor(entry),
        name = entry.name,
        kind = kindOf(entry.folder, entry.mime),
        location = entry.location.wire,
        mime = entry.mime,
        size = if (entry.folder) null else size,
        modifiedMs = modified,
    )

    // ---- Permissions ----

    fun photosAllowed(): Boolean {
        fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> has(Manifest.permission.READ_MEDIA_IMAGES)
            else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun tree(): Uri = folderUri()
        ?: fail(ErrorCode.PERMISSION_MISSING, "the owner has not picked a folder for the AI; ask them to pick one in Latch → Access → Files")

    // ---- List ----

    @Synchronized
    fun list(location: FileLocation, folderId: String?, query: String?, limit: Int, offset: Int): FileList = when (location) {
        FileLocation.FOLDER -> listFolder(folderId, query, limit, offset)
        FileLocation.PHOTOS -> {
            if (!photosAllowed()) fail(ErrorCode.PERMISSION_MISSING, "the owner has not allowed photos; ask them to allow photos in Latch → Access → Files")
            listMedia(location, query, limit, offset)
        }
        FileLocation.DOWNLOADS -> listMedia(location, query, limit, offset)
    }

    private fun mediaCollection(location: FileLocation): Uri = when (location) {
        FileLocation.DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    private fun mediaSelection(location: FileLocation, query: String?): Pair<String, Array<String>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (location == FileLocation.PHOTOS) {
            parts += "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        }
        if (!query.isNullOrBlank()) {
            parts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            args += "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        return (if (parts.isEmpty()) "1" else parts.joinToString(" AND ")) to args.toTypedArray()
    }

    private fun contentUriFor(location: FileLocation, id: Long, mediaType: Int): Uri = when {
        location == FileLocation.DOWNLOADS -> ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        else -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private fun listMedia(location: FileLocation, query: String?, limit: Int, offset: Int): FileList {
        val collection = mediaCollection(location)
        val (selection, args) = mediaSelection(location, query)
        val total = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { it.count } ?: 0
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val items = mutableListOf<FileItem>()
        resolver.query(collection, projection, queryArgs, null)?.use { c ->
            while (c.moveToNext() && items.size < limit) {
                val type = if (location == FileLocation.PHOTOS) c.getInt(5) else MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
                val entry = Entry(contentUriFor(location, c.getLong(0), type), location, false, c.getString(1) ?: "file", c.getString(2))
                items += item(entry, c.getLong(3), c.getLong(4) * 1000)
            }
        }
        val end = offset + items.size
        return FileList(location.wire, null, items, total, if (end < total) end else null)
    }

    private fun folderDoc(folderId: String?): Pair<Uri, String> {
        val tree = tree()
        if (folderId == null) return tree to DocumentsContract.getTreeDocumentId(tree)
        val entry = entry(folderId)
        if (!entry.folder || entry.location != FileLocation.FOLDER) fail(ErrorCode.INVALID_REQUEST, "that id is not a folder in the picked folder")
        return tree to DocumentsContract.getDocumentId(entry.uri)
    }

    private class Child(val docId: String, val name: String, val mime: String, val size: Long?, val modified: Long?)

    private fun children(tree: Uri, parentDoc: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDoc)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<Child>()
        try {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FOLDER_SCAN) {
                    out += Child(
                        c.getString(0), c.getString(1) ?: "file", c.getString(2) ?: "application/octet-stream",
                        if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4),
                    )
                }
            }
        } catch (_: SecurityException) {
            fail(ErrorCode.PERMISSION_MISSING, "Latch lost access to the picked folder; ask the owner to pick it again in Latch → Access → Files")
        }
        return out
    }

    private fun folderEntry(tree: Uri, child: Child) = Entry(
        DocumentsContract.buildDocumentUriUsingTree(tree, child.docId),
        FileLocation.FOLDER,
        child.mime == DocumentsContract.Document.MIME_TYPE_DIR,
        child.name,
        child.mime.takeIf { it != DocumentsContract.Document.MIME_TYPE_DIR },
    )

    private fun listFolder(folderId: String?, query: String?, limit: Int, offset: Int): FileList {
        val (tree, parent) = folderDoc(folderId)
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val matching = children(tree, parent)
            .filter { q == null || it.name.lowercase().contains(q) }
            .sortedByDescending { it.modified ?: 0 }
        val page = matching.drop(offset).take(limit).map { child -> item(folderEntry(tree, child), child.size, child.modified) }
        val folderName = if (folderId == null) folderName(tree) else entry(folderId).name
        val end = offset + page.size
        return FileList(FileLocation.FOLDER.wire, folderName, page, matching.size, if (end < matching.size) end else null)
    }

    private fun folderName(tree: Uri): String? = try {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: SecurityException) {
        null
    }

    // ---- Read ----

    private fun sizeOf(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun fileEntry(id: String): Entry {
        val entry = entry(id)
        if (entry.folder) fail(ErrorCode.INVALID_REQUEST, "that is a folder; list it instead")
        return entry
    }

    private fun isText(mime: String?) = mime != null && (
        mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")
        )

    @Synchronized
    fun preview(id: String): FilePreview {
        val entry = fileEntry(id)
        val size = sizeOf(entry.uri)
        val item = item(entry, size, null)
        if (isText(entry.mime)) {
            val bytes = readBytes(entry.uri, 0, Limits.MAX_PREVIEW_TEXT_BYTES + 1)
            val truncated = bytes.size > Limits.MAX_PREVIEW_TEXT_BYTES
            val kept = if (truncated) bytes.copyOf(Limits.MAX_PREVIEW_TEXT_BYTES) else bytes
            return FilePreview(item, text = String(kept, Charsets.UTF_8).trimEnd('�'), textTruncated = truncated)
        }
        if (entry.mime?.startsWith("image/") == true || entry.mime?.startsWith("video/") == true) {
            return FilePreview(item, image = thumbnail(entry.uri))
        }
        return FilePreview(item)
    }

    private fun thumbnail(uri: Uri): Screenshot? {
        val bitmap = try {
            resolver.loadThumbnail(uri, Size(PREVIEW_PX, PREVIEW_PX), null)
        } catch (_: Exception) {
            decodeScaled(uri)
        } ?: return null
        var quality = 85
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
            quality -= 15
        } while (out.size > PREVIEW_MAX_BYTES && quality > 20)
        return Screenshot("image/jpeg", bitmap.width, bitmap.height, Base64.getEncoder().encodeToString(out))
    }

    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > PREVIEW_PX * 2 || bounds.outHeight / sample > PREVIEW_PX * 2) sample *= 2
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (_: Exception) {
        null
    }

    private fun readBytes(uri: Uri, offset: Long, length: Int): ByteArray = try {
        resolver.openInputStream(uri)?.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        } ?: fail(ErrorCode.TARGET_NOT_FOUND, "the file could not be opened")
    } catch (_: SecurityException) {
        fail(ErrorCode.PERMISSION_MISSING, "Latch may no longer read that file")
    } catch (_: java.io.FileNotFoundException) {
        fail(ErrorCode.TARGET_NOT_FOUND, "the file is gone; list files again")
    }

    @Synchronized
    fun read(id: String, offset: Long, length: Int): FileChunk {
        val entry = fileEntry(id)
        val size = sizeOf(entry.uri)
        val bytes = readBytes(entry.uri, offset, length)
        val eof = bytes.size < length || (size != null && offset + bytes.size >= size)
        return FileChunk(item(entry, size, null), offset, Base64.getEncoder().encodeToString(bytes), eof)
    }

    // ---- Write ----

    private fun decode(data: String): ByteArray = try {
        Base64.getDecoder().decode(data)
    } catch (_: IllegalArgumentException) {
        fail(ErrorCode.INVALID_REQUEST, "data_base64 is not valid base64")
    }

    private fun writeTo(uri: Uri, mode: String, bytes: ByteArray) {
        try {
            resolver.openOutputStream(uri, mode)?.use { it.write(bytes) } ?: fail(ErrorCode.INTERNAL, "the phone could not open the file")
        } catch (_: SecurityException) {
            fail(ErrorCode.POLICY_REFUSED, "Latch replaces only files it saved there")
        }
    }

    /** The existing file a write would replace, if any. */
    @Synchronized
    fun existing(location: FileLocation, folderId: String?, subfolder: String?, name: String): String? = when (location) {
        FileLocation.FOLDER -> {
            val (tree, parent) = folderDoc(folderId)
            children(tree, parent).firstOrNull { it.name == name && it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
                ?.let { idFor(folderEntry(tree, it)) }
        }
        else -> findMedia(location, subfolder, name)?.let(::idFor)
    }

    private fun relativePath(location: FileLocation, subfolder: String?): String =
        (if (location == FileLocation.DOWNLOADS) "Download/" else "Pictures/") + (subfolder ?: DEFAULT_SUBFOLDER) + "/"

    private fun findMedia(location: FileLocation, subfolder: String?, name: String): Entry? {
        val collection = if (location == FileLocation.DOWNLOADS) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE)
        return resolver.query(collection, projection, selection, arrayOf(name, relativePath(location, subfolder)), null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val type = if (location == FileLocation.PHOTOS) c.getInt(2) else MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
            Entry(contentUriFor(location, c.getLong(0), type), location, false, name, c.getString(1), subfolder)
        }
    }

    @Synchronized
    fun write(
        location: FileLocation,
        folderId: String?,
        subfolder: String?,
        name: String,
        mime: String?,
        dataBase64: String,
        append: Boolean,
        overwrite: Boolean,
    ): FileItem {
        val bytes = decode(dataBase64)
        val type = mime ?: "application/octet-stream"
        if (location == FileLocation.PHOTOS && !(type.startsWith("image/") || type.startsWith("video/"))) {
            fail(ErrorCode.INVALID_REQUEST, "photos take images and videos only")
        }
        val current = existing(location, folderId, subfolder, name)?.let(::entry)
        if (append) {
            val target = current?.takeIf { it.uri.toString() in written }
                ?: fail(ErrorCode.TARGET_NOT_FOUND, "append only to a file Latch saved in this session")
            writeTo(target.uri, "wa", bytes)
            return item(target, sizeOf(target.uri), System.currentTimeMillis())
        }
        if (current != null && overwrite) {
            writeTo(current.uri, "wt", bytes)
            written += current.uri.toString()
            return item(current, bytes.size.toLong(), System.currentTimeMillis())
        }
        val created = when (location) {
            FileLocation.FOLDER -> {
                val (tree, parent) = folderDoc(folderId)
                val uri = try {
                    DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), type, name)
                } catch (_: Exception) {
                    null
                } ?: fail(ErrorCode.INTERNAL, "the picked folder did not accept a new file")
                Entry(uri, location, false, displayName(uri) ?: name, type)
            }
            else -> {
                val collection = if (location == FileLocation.DOWNLOADS) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else if (type.startsWith("video/")) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, type)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(location, subfolder))
                }
                val uri = resolver.insert(collection, values) ?: fail(ErrorCode.INTERNAL, "the phone did not accept a new file")
                Entry(uri, location, false, displayName(uri) ?: name, type, subfolder)
            }
        }
        writeTo(created.uri, "w", bytes)
        written += created.uri.toString()
        return item(created, bytes.size.toLong(), System.currentTimeMillis())
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun mkdir(folderId: String?, name: String): FileItem {
        val (tree, parent) = folderDoc(folderId)
        val uri = try {
            DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), DocumentsContract.Document.MIME_TYPE_DIR, name)
        } catch (_: Exception) {
            null
        } ?: fail(ErrorCode.INTERNAL, "the picked folder did not accept a new folder")
        return item(Entry(uri, FileLocation.FOLDER, true, displayName(uri) ?: name, null), null, System.currentTimeMillis())
    }

    private fun changeable(id: String): Entry {
        val entry = entry(id)
        if (entry.location != FileLocation.FOLDER && entry.uri.toString() !in written && !ownedByLatch(entry.uri)) {
            fail(ErrorCode.POLICY_REFUSED, "in photos and Downloads Latch changes only files it saved")
        }
        return entry
    }

    private fun ownedByLatch(uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == context.packageName
        } == true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun rename(id: String, newName: String): FileItem {
        val entry = changeable(id)
        val renamed = if (entry.location == FileLocation.FOLDER) {
            val uri = try {
                DocumentsContract.renameDocument(resolver, entry.uri, newName)
            } catch (_: Exception) {
                null
            } ?: fail(ErrorCode.INTERNAL, "the picked folder did not rename it")
            Entry(uri, entry.location, entry.folder, displayName(uri) ?: newName, entry.mime)
        } else {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            try {
                resolver.update(entry.uri, values, null, null)
            } catch (_: SecurityException) {
                fail(ErrorCode.POLICY_REFUSED, "in photos and Downloads Latch changes only files it saved")
            }
            Entry(entry.uri, entry.location, false, displayName(entry.uri) ?: newName, entry.mime, entry.subfolder)
        }
        entries.remove(id)
        idsByUri.remove(entry.uri.toString())
        if (entry.uri.toString() in written) written += renamed.uri.toString()
        return item(renamed, sizeOf(renamed.uri), System.currentTimeMillis())
    }

    @Synchronized
    fun delete(id: String) {
        val entry = changeable(id)
        val ok = try {
            if (entry.location == FileLocation.FOLDER) DocumentsContract.deleteDocument(resolver, entry.uri) else resolver.delete(entry.uri, null, null) > 0
        } catch (_: SecurityException) {
            fail(ErrorCode.POLICY_REFUSED, "in photos and Downloads Latch changes only files it saved")
        } catch (_: Exception) {
            false
        }
        if (!ok) fail(ErrorCode.TARGET_NOT_FOUND, "the file could not be deleted; list files again")
        entries.remove(id)
        idsByUri.remove(entry.uri.toString())
        written -= entry.uri.toString()
    }

    /** Name of a file or folder for the owner's approval card. */
    @Synchronized
    fun nameOf(id: String): String? = entries[id]?.name

    /** Content URIs and types for Android's share screen. */
    @Synchronized
    fun forSharing(ids: List<String>): List<Pair<Uri, String>> = ids.map { id ->
        val entry = fileEntry(id)
        entry.uri to (entry.mime ?: "application/octet-stream")
    }

    /**
     * Opens [packageName]'s share screen with these files (and [text]), as the
     * owner would from the gallery's Share button. The app only prepares the
     * post or message; sending it is a tap under the owner's app rules.
     */
    @Synchronized
    fun share(from: Context, packageName: String, ids: List<String>, text: String?) {
        val items = forSharing(ids)
        val types = items.map { it.second }.distinct()
        val type = when {
            types.size == 1 -> types.single()
            types.all { it.startsWith("image/") } -> "image/*"
            types.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val intent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items.single().first)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.first }))
        }.apply {
            setPackage(packageName)
            setType(type)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            clipData = ClipData.newRawUri(null, items.first().first).also { clip ->
                items.drop(1).forEach { clip.addItem(ClipData.Item(it.first)) }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(from.packageManager) == null) {
            fail(ErrorCode.TARGET_NOT_FOUND, "$packageName cannot receive shared files of this type, or is not installed")
        }
        try {
            from.startActivity(intent)
        } catch (_: Exception) {
            fail(ErrorCode.TARGET_NOT_FOUND, "$packageName did not open its share screen")
        }
    }

    companion object {
        const val DEFAULT_SUBFOLDER = "Latch"
        private const val MAX_FOLDER_SCAN = 5_000
        private const val PREVIEW_PX = 1024
        private const val PREVIEW_MAX_BYTES = 512 * 1024
    }
}
