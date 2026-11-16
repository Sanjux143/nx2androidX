package com.nx.android.app.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.nx.android.R
import com.nx.android.data.PrefsStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * The app's private folders as a document provider.
 *
 * The runtime rootfs, the tools, the hosts, the saves, the drivers and the logs all live in the app's
 * own data directory, where the player cannot reach them with a file manager. This provider hands that
 * directory to Android's document framework: it shows up as one root (the app's name) in the system
 * picker and in every file manager that speaks SAF, and the player can read, create, rename, move and
 * delete there without a computer.
 *
 * The shape is the one AOSP's own file system provider uses: one root (the data directory), the
 * document id is the absolute path of a file, and the operations are plain file operations.
 *
 * Two things are deliberate:
 *
 *  - The provider is created by the system, not by the app: it is not part of the object graph, so it
 *    reads the setting from the prefs file itself (the same file the settings page writes) and it
 *    reads it on every call, so the switch takes effect at once.
 *  - Every document id is resolved and checked to be inside the root, so a crafted id cannot walk out
 *    of the app's own directory.
 */
class NxFilesProvider : DocumentsProvider() {

    /** The root: the app's own data directory (the runtime, the saves, the logs — everything). */
    private var baseDir: File = File("/")

    /** The root's canonical path: the yardstick every document id is measured against. */
    private var basePath: String = "/"

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        baseDir = context.dataDir
        basePath = canonical(baseDir)
    }

    override fun onCreate(): Boolean = true

    // ---- the roots ----

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        // The switch is off: the app's folders are nobody's business right now.
        if (!enabled()) return result
        val row = result.newRow()
        row.add(Root.COLUMN_ROOT_ID, basePath)
        row.add(Root.COLUMN_DOCUMENT_ID, basePath)
        row.add(Root.COLUMN_SUMMARY, basePath)
        row.add(
            Root.COLUMN_FLAGS,
            (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD).toLong(),
        )
        row.add(Root.COLUMN_TITLE, context?.getString(R.string.app_name) ?: "nx2androidX")
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
        row.add(Root.COLUMN_AVAILABLE_BYTES, baseDir.usableSpace)
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        return result
    }

    // ---- the documents ----

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        requireEnabled()
        val file = resolve(documentId)
        if (!file.exists()) throw FileNotFoundException("$documentId not found")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(result, file)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireEnabled()
        val parent = resolve(parentDocumentId)
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val children = parent.listFiles() ?: return result
        for (child in children) {
            // A link out of the app's directory is not ours to show.
            if (!insideRoot(child)) continue
            addRow(result, child)
        }
        return result
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        requireEnabled()
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val needle = query.lowercase(Locale.ROOT)
        if (needle.isEmpty()) return result

        val pending = ArrayDeque<File>()
        pending.addLast(resolve(rootId))
        while (pending.isNotEmpty() && result.count < MAX_SEARCH_RESULTS) {
            val file = pending.removeFirst()
            if (file.isDirectory) {
                val children = file.listFiles() ?: continue
                for (child in children) if (insideRoot(child)) pending.addLast(child)
            } else if (file.name.lowercase(Locale.ROOT).contains(needle)) {
                addRow(result, file)
            }
        }
        return result
    }

    override fun getDocumentType(documentId: String): String {
        requireEnabled()
        return mimeType(resolve(documentId))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = canonical(resolve(parentDocumentId))
        val child = canonical(resolve(documentId))
        child != parent && child.startsWith(parent + File.separator)
    } catch (e: FileNotFoundException) {
        false
    }

    // ---- reading and writing ----

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        requireEnabled()
        val file = resolve(documentId)
        if (!file.exists()) throw FileNotFoundException("$documentId not found")
        if (file.isDirectory) throw FileNotFoundException("$documentId is a directory")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        requireEnabled()
        val file = resolve(documentId)
        if (!file.exists() || file.isDirectory) throw FileNotFoundException("$documentId not found")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        requireEnabled()
        val parent = resolve(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("$parentDocumentId is not a directory")
        val target = uniqueName(parent, displayName)
        val created = if (Document.MIME_TYPE_DIR == mimeType) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("Failed to create ${target.path}")
        return canonical(target)
    }

    override fun deleteDocument(documentId: String) {
        requireEnabled()
        val file = resolve(documentId)
        if (!deleteRecursively(file)) throw FileNotFoundException("Failed to delete $documentId")
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        requireEnabled()
        val parent = resolve(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("$parentDocumentId is not a directory")
        val file = resolve(documentId)
        if (canonical(file.parentFile ?: file) != canonical(parent)) {
            throw FileNotFoundException("$documentId is not in $parentDocumentId")
        }
        if (!deleteRecursively(file)) throw FileNotFoundException("Failed to delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireEnabled()
        val file = resolve(documentId)
        if (!file.exists()) throw FileNotFoundException("$documentId not found")
        val parent = file.parentFile ?: throw FileNotFoundException("$documentId has no parent")
        val target = uniqueName(parent, displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("Failed to rename $documentId")
        return canonical(target)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        requireEnabled()
        val source = resolve(sourceDocumentId)
        val sourceParent = resolve(sourceParentDocumentId)
        val targetParent = resolve(targetParentDocumentId)
        if (!sourceParent.exists()) throw FileNotFoundException("$sourceParentDocumentId not found")
        if (!source.exists()) throw FileNotFoundException("$sourceDocumentId not found")
        // The document has to sit in the parent it claims to sit in.
        if (canonical(source.parentFile ?: source) != canonical(sourceParent)) {
            throw FileNotFoundException("$sourceDocumentId is not in $sourceParentDocumentId")
        }
        if (!targetParent.exists() || !targetParent.isDirectory) {
            throw FileNotFoundException("$targetParentDocumentId is not a directory")
        }
        val target = uniqueName(targetParent, source.name)
        if (!source.renameTo(target)) throw FileNotFoundException("Failed to move $sourceDocumentId")
        return canonical(target)
    }

    // ---- the helpers ----

    /**
     * The setting: the file managers may look at the app's folders only while it is on (on by default).
     * It is read on every call, so the switch in the settings page takes effect at once.
     */
    private fun enabled(): Boolean {
        val ctx = context ?: return false
        return ctx.getSharedPreferences(PrefsStore.NAME, Context.MODE_PRIVATE)
            .getBoolean(PrefsStore.FILE_PROVIDER, true)
    }

    private fun requireEnabled() {
        if (!enabled()) throw FileNotFoundException("The file provider is off")
    }

    /** The file behind a document id, checked to be inside the app's own directory. */
    private fun resolve(docId: String): File {
        val file = File(docId)
        val canonical = canonical(file)
        if (canonical != basePath && !canonical.startsWith(basePath + File.separator)) {
            throw FileNotFoundException("$docId is outside the app's directory")
        }
        return File(canonical)
    }

    private fun insideRoot(file: File): Boolean {
        val path = canonical(file)
        return path == basePath || path.startsWith(basePath + File.separator)
    }

    /** The canonical path of a file: the symlinks and the ".." resolved, so the check cannot be fooled. */
    private fun canonical(file: File): String = try {
        file.canonicalPath
    } catch (e: IOException) {
        file.absolutePath
    }

    /** A name that is free in the folder: "name (2)", "name (3)", ... */
    private fun uniqueName(parent: File, displayName: String): File {
        var target = File(parent, displayName)
        var n = 2
        while (target.exists()) target = File(parent, "$displayName ($n++)")
        return target
    }

    /** A folder goes with its content: the file managers delete folders, not empty stubs. */
    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            for (child in children) if (!deleteRecursively(child)) return false
        }
        return file.delete()
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val name = file.name
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(lastDot + 1).lowercase(Locale.ROOT))
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }

    /** One file as one row of the document list. */
    private fun addRow(cursor: MatrixCursor, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        val parent = file.parentFile
        if (parent != null && parent.canWrite()) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_RENAME
        val mime = mimeType(file)
        if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL

        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, canonical(file))
        row.add(Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(Document.COLUMN_SIZE, file.length())
        row.add(Document.COLUMN_MIME_TYPE, mime)
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(Document.COLUMN_FLAGS, flags.toLong())
        row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
    }

    companion object {
        private const val ALL_MIME_TYPES = "*/*"
        private const val MAX_SEARCH_RESULTS = 50

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }
}
