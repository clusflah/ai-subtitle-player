package android.provider

import android.app.PendingIntent
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object OpenableColumns {
    const val DISPLAY_NAME = "_display_name"
    const val SIZE = "_size"
}

object DocumentsContract {
    object Document {
        const val COLUMN_DOCUMENT_ID = "document_id"
        const val COLUMN_DISPLAY_NAME = "_display_name"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val MIME_TYPE_DIR = "vnd.android.document/directory"
    }

    @JvmStatic
    fun getTreeDocumentId(documentUri: Uri): String {
        return documentUri.path ?: documentUri.toString()
    }

    @JvmStatic
    fun buildChildDocumentsUriUsingTree(treeUri: Uri, parentDocumentId: String): Uri {
        val dir = File(parentDocumentId)
        return if (dir.exists()) Uri.fromFile(dir) else treeUri
    }

    @JvmStatic
    fun buildDocumentUriUsingTree(treeUri: Uri, documentId: String): Uri {
        return Uri.fromFile(File(documentId))
    }
}

object MediaStore {
    const val VOLUME_EXTERNAL_PRIMARY = "external_primary"

    object MediaColumns {
        const val _ID = "_id"
        const val DISPLAY_NAME = "_display_name"
        const val SIZE = "_size"
        const val MIME_TYPE = "mime_type"
        const val DATE_ADDED = "date_added"
        const val DATE_MODIFIED = "date_modified"
        const val DATA = "_data"
        const val RELATIVE_PATH = "relative_path"
        const val IS_PENDING = "is_pending"
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val DURATION = "duration"
    }

    object Video {
        object VideoColumns {
            const val DURATION = "duration"
        }

        object Media {
            val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/video/media")
            const val _ID = MediaColumns._ID
            const val DISPLAY_NAME = MediaColumns.DISPLAY_NAME
            const val SIZE = MediaColumns.SIZE
            const val MIME_TYPE = MediaColumns.MIME_TYPE
            const val DATE_ADDED = MediaColumns.DATE_ADDED
            const val DATA = MediaColumns.DATA
            const val RELATIVE_PATH = MediaColumns.RELATIVE_PATH
            const val DURATION = VideoColumns.DURATION
        }

        object Thumbnails {
            const val MINI_KIND = 1
            @JvmStatic
            fun getThumbnail(
                cr: ContentResolver,
                origId: Long,
                kind: Int,
                options: BitmapFactory.Options?,
            ): Bitmap? = null
        }
    }

    object Audio {
        object Media {
            val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/audio/media")
            const val _ID = MediaColumns._ID
            const val DISPLAY_NAME = MediaColumns.DISPLAY_NAME
            const val SIZE = MediaColumns.SIZE
            const val MIME_TYPE = MediaColumns.MIME_TYPE
            const val DATE_ADDED = MediaColumns.DATE_ADDED
            const val DATA = MediaColumns.DATA
            const val RELATIVE_PATH = MediaColumns.RELATIVE_PATH
            const val DURATION = MediaColumns.DURATION
        }
    }

    object Images {
        object Media {
            val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/images/media")
            const val _ID = MediaColumns._ID
            const val DISPLAY_NAME = MediaColumns.DISPLAY_NAME
            const val SIZE = MediaColumns.SIZE
            const val MIME_TYPE = MediaColumns.MIME_TYPE
            const val DATE_ADDED = MediaColumns.DATE_ADDED
            const val DATA = MediaColumns.DATA
            const val RELATIVE_PATH = MediaColumns.RELATIVE_PATH
        }
    }

    object Files {
        @JvmStatic
        fun getContentUri(volumeName: String): Uri = Uri.parse("content://media/$volumeName/file")
    }

    @JvmStatic
    fun getVolumeName(uri: Uri): String = VOLUME_EXTERNAL_PRIMARY

    @JvmStatic
    fun createDeleteRequest(resolver: ContentResolver, uris: Collection<Uri>): PendingIntent = PendingIntent()

    @JvmStatic
    fun createWriteRequest(resolver: ContentResolver, uris: Collection<Uri>): PendingIntent = PendingIntent()
}

object Settings {
    const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
    const val ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION = "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"
}
