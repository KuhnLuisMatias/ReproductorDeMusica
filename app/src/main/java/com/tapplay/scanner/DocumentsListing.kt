package com.tapplay.scanner

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** MIME type SAF providers use for directories. */
internal const val DIRECTORY_MIME = DocumentsContract.Document.MIME_TYPE_DIR

/** One row of a folder listing. [name] is "" when the provider omits it; 0 means unknown. */
data class ChildEntry(
    val documentId: String,
    val name: String,
    val mime: String?,
    val lastModified: Long,
    val size: Long,
)

/** Lists the children of a document with a single provider query. May throw. */
fun interface ChildLister {
    fun list(parentDocId: String): List<ChildEntry>
}

/** Null-safe row mapping; a row without a document id is unusable and dropped. */
internal fun childEntryOf(
    documentId: String?,
    name: String?,
    mime: String?,
    lastModified: Long?,
    size: Long?,
): ChildEntry? =
    documentId?.let {
        ChildEntry(it, name.orEmpty(), mime, lastModified ?: 0L, size ?: 0L)
    }

/**
 * Root document id, mirroring DocumentFile.fromTreeUri: when the URI already
 * carries a /document/ segment ([docId]) that folder is the root, else [treeId].
 */
internal fun rootDocumentIdFor(
    treeId: String,
    docId: String?,
): String = if (docId.isNullOrEmpty()) treeId else docId

/**
 * [TreeFolder] over an already-read [ChildEntry]: name/isFile/uri are plain
 * values (zero provider queries). Children come from ONE [lister] query; if it
 * fails, [fallbackChildren] (the legacy DocumentFile listing) is used.
 * A null mime is resolved lazily through [isFileFallback].
 */
internal class ListedTreeFolder(
    private val entry: ChildEntry,
    override val uri: String,
    private val lister: ChildLister,
    private val childFactory: (ChildEntry) -> TreeFolder,
    private val fallbackChildren: () -> List<TreeFolder>,
    private val isFileFallback: () -> Boolean,
) : TreeFolder {
    override val name: String = entry.name

    override val isFile: Boolean by lazy {
        // Same rule as DocumentFile.isFile: directory -> false, empty mime is not a file by itself.
        val mime = entry.mime
        when {
            mime.isNullOrBlank() -> isFileFallback()
            mime == DIRECTORY_MIME -> false
            else -> true
        }
    }

    override val children: List<TreeFolder> by lazy {
        try {
            lister.list(entry.documentId).map(childFactory)
        } catch (e: Exception) {
            Log.w("DocumentsListing", "child query failed: ${e.javaClass.simpleName}")
            fallbackChildren()
        }
    }
}

/** Real lister: one ContentResolver query per folder over the picked tree. */
internal class ContentResolverChildLister(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ChildLister {
    override fun list(parentDocId: String): List<ChildEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor =
            resolver.query(childrenUri, PROJECTION, null, null, null)
                ?: error("null cursor")
        return cursor.use { c ->
            val id = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val display = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modified = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val size = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            if (id < 0) error("no id column")
            val out = ArrayList<ChildEntry>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val entry =
                    childEntryOf(
                        documentId = c.getStringOrNull(id),
                        name = c.getStringOrNull(display),
                        mime = c.getStringOrNull(mime),
                        lastModified = c.getLongOrNull(modified),
                        size = c.getLongOrNull(size),
                    )
                if (entry != null) out += entry
            }
            out
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else runCatching { getLong(index) }.getOrNull()

    private companion object {
        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
    }
}
