package androidx.activity.result.contract

import android.app.Activity
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
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun execute(input: Array<String>, onResult: (Uri?) -> Unit) {
            SwingUtilities.invokeLater {
                val dlg = FileDialog(null as Frame?, "选择文件", FileDialog.LOAD)
                dlg.isVisible = true
                val dir = dlg.directory
                val file = dlg.file
                val uri = if (dir != null && file != null) Uri.fromFile(File(dir, file)) else null
                Handler(Looper.getMainLooper()).post { onResult(uri) }
            }
        }
    }

    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun execute(input: Array<String>, onResult: (List<Uri>) -> Unit) {
            SwingUtilities.invokeLater {
                val dlg = FileDialog(null as Frame?, "选择文件", FileDialog.LOAD).apply {
                    isMultipleMode = true
                }
                dlg.isVisible = true
                val uris = dlg.files?.map { Uri.fromFile(it) }.orEmpty()
                Handler(Looper.getMainLooper()).post { onResult(uris) }
            }
        }
    }

    class CreateDocument(private val mimeType: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun execute(input: String, onResult: (Uri?) -> Unit) {
            SwingUtilities.invokeLater {
                val dlg = FileDialog(null as Frame?, "保存文件", FileDialog.SAVE).apply {
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
                }
                val res = chooser.showOpenDialog(null)
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
