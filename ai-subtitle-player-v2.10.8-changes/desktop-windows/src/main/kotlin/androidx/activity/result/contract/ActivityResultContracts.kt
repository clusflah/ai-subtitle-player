package androidx.activity.result.contract

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

abstract class ActivityResultContract<I, O> {
    abstract fun execute(input: I, onResult: (O) -> Unit)
}

object ActivityResultContracts {
    @Volatile
    var defaultInitialDirectory: String? = null

    @Volatile
    private var lastSubtitleDirectory: String? = null

    private fun activeParentFrame(): Frame? =
        Frame.getFrames().firstOrNull { it.isDisplayable && it.isVisible && it.isActive }
            ?: Frame.getFrames().firstOrNull { it.isDisplayable && it.isVisible }

    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun execute(input: Array<String>, onResult: (Uri?) -> Unit) {
            val isSubtitlePicker = input.any {
                it.contains("subrip", ignoreCase = true) ||
                    it.contains("subtitle", ignoreCase = true) ||
                    it.contains("vtt", ignoreCase = true) ||
                    it.contains("ass", ignoreCase = true)
            }
            SwingUtilities.invokeLater {
                val dlgTitle = if (isSubtitlePicker) "选择字幕文件" else "选择文件"
                val dlg = FileDialog(activeParentFrame(), dlgTitle, FileDialog.LOAD).apply {
                    val initDir = (if (isSubtitlePicker) lastSubtitleDirectory ?: defaultInitialDirectory else defaultInitialDirectory)
                        ?.takeIf { File(it).isDirectory }
                    if (initDir != null) {
                        directory = initDir
                    }
                    if (isSubtitlePicker) {
                        file = "*.srt;*.ass;*.ssa;*.vtt;*.smi;*.sami;*.sub;*.lrc;*.zip"
                    }
                }
                dlg.isVisible = true
                val dir = dlg.directory
                val picked = dlg.file
                val targetFile = if (dir != null && picked != null && !picked.contains('*') && !picked.contains(';')) {
                    File(dir, picked).takeIf { it.exists() && it.isFile }
                } else {
                    null
                }
                if (targetFile != null && isSubtitlePicker) {
                    lastSubtitleDirectory = targetFile.parentFile?.absolutePath
                }
                val uri = targetFile?.let { Uri.fromFile(it) }
                Handler(Looper.getMainLooper()).post { onResult(uri) }
            }
        }
    }

    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun execute(input: Array<String>, onResult: (List<Uri>) -> Unit) {
            SwingUtilities.invokeLater {
                val dlg = FileDialog(activeParentFrame(), "选择文件", FileDialog.LOAD).apply {
                    isMultipleMode = true
                    defaultInitialDirectory?.takeIf { File(it).isDirectory }?.let { directory = it }
                }
                dlg.isVisible = true
                val uris = dlg.files?.filter { it.exists() && it.isFile }?.map { Uri.fromFile(it) }.orEmpty()
                Handler(Looper.getMainLooper()).post { onResult(uris) }
            }
        }
    }

    class CreateDocument(private val mimeType: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun execute(input: String, onResult: (Uri?) -> Unit) {
            SwingUtilities.invokeLater {
                val dlg = FileDialog(activeParentFrame(), "保存文件", FileDialog.SAVE).apply {
                    defaultInitialDirectory?.takeIf { File(it).isDirectory }?.let { directory = it }
                    file = input
                }
                dlg.isVisible = true
                val dir = dlg.directory
                val f = dlg.file
                val uri = if (dir != null && f != null) {
                    val target = File(dir, f)
                    target.parentFile?.mkdirs()
                    runCatching { if (!target.exists()) target.createNewFile() }
                    Uri.fromFile(target)
                } else null
                Handler(Looper.getMainLooper()).post { onResult(uri) }
            }
        }
    }

    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun execute(input: Uri?, onResult: (Uri?) -> Unit) {
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    dialogTitle = "选择文件夹"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                    input?.let { ContentResolver.resolveFile(it) }?.takeIf { it.isDirectory }?.let { currentDirectory = it }
                }
                val res = chooser.showOpenDialog(activeParentFrame())
                val uri = if (res == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
                    Uri.fromFile(chooser.selectedFile)
                } else null
                Handler(Looper.getMainLooper()).post { onResult(uri) }
            }
        }
    }

    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun execute(input: String, onResult: (Boolean) -> Unit) {
            Handler(Looper.getMainLooper()).post { onResult(true) }
        }
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun execute(input: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
            val map = input.associateWith { true }
            Handler(Looper.getMainLooper()).post { onResult(map) }
        }
    }

    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun execute(input: Intent, onResult: (ActivityResult) -> Unit) {
            Handler(Looper.getMainLooper()).post {
                onResult(ActivityResult(Activity.RESULT_OK, input))
            }
        }
    }

    class StartIntentSenderForResult : ActivityResultContract<IntentSenderRequest, ActivityResult>() {
        override fun execute(input: IntentSenderRequest, onResult: (ActivityResult) -> Unit) {
            Handler(Looper.getMainLooper()).post {
                onResult(ActivityResult(Activity.RESULT_OK, Intent()))
            }
        }
    }
}
