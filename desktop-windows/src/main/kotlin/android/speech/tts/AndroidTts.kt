package android.speech.tts

import android.content.Context
import android.os.Bundle
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors

abstract class UtteranceProgressListener {
    abstract fun onStart(utteranceId: String)
    abstract fun onDone(utteranceId: String)
    @Deprecated("Deprecated in Java")
    abstract fun onError(utteranceId: String)
    open fun onError(utteranceId: String, errorCode: Int) {
        @Suppress("DEPRECATION")
        onError(utteranceId)
    }
}

class Voice(
    val name: String,
    val locale: Locale,
    val quality: Int = 300,
    val latency: Int = 300,
    val isNetworkConnectionRequired: Boolean = false,
    val features: Set<String> = emptySet(),
)

class TextToSpeech(context: Context, listener: OnInitListener?) {
    fun interface OnInitListener {
        fun onInit(status: Int)
    }

    object Engine {
        const val KEY_FEATURE_NOT_INSTALLED = "notInstalled"
    }

    private var progressListener: UtteranceProgressListener? = null
    var voice: Voice? = null
    var language: Locale = Locale.getDefault()

    val voices: Set<Voice> by lazy { queryWindowsVoices() }

    init {
        listener?.onInit(SUCCESS)
    }

    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int {
        progressListener = listener
        return SUCCESS
    }

    fun synthesizeToFile(text: CharSequence, params: Bundle?, file: File, utteranceId: String): Int {
        val selectedVoice = voice?.name
        val str = text.toString()
        ttsExecutor.submit {
            progressListener?.onStart(utteranceId)
            val ok = synthesizeWithWindowsSapi(str, selectedVoice, file)
            if (ok && file.exists() && file.length() > 0) {
                progressListener?.onDone(utteranceId)
            } else {
                progressListener?.onError(utteranceId, ERROR)
            }
        }
        return SUCCESS
    }

    fun shutdown() {}

    companion object {
        const val SUCCESS = 0
        const val ERROR = -1

        private val ttsExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "Voxtitle-WindowsSAPI").apply { isDaemon = true }
        }

        @Volatile
        private var cachedVoices: Set<Voice>? = null

        private fun queryWindowsVoices(): Set<Voice> {
            cachedVoices?.let { return it }
            val result = LinkedHashSet<Voice>()
            runCatching {
                val script = """
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    Add-Type -AssemblyName System.Speech
                    ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    foreach (${'$'}v in ${'$'}s.GetInstalledVoices()) {
                        if (${'$'}v.Enabled) {
                            ${'$'}i = ${'$'}v.VoiceInfo
                            Write-Output ("{0}|{1}|{2}" -f ${'$'}i.Name, ${'$'}i.Culture.Name, ${'$'}i.Gender)
                        }
                    }
                """.trimIndent()
                val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                proc.waitFor()
                for (line in out.lineSequence()) {
                    val parts = line.trim().split('|')
                    if (parts.size >= 3) {
                        val vName = parts[0].trim()
                        val culture = parts[1].trim()
                        val gender = parts[2].trim().lowercase()
                        val displayName = if (gender == "female" || gender == "male") "$vName ($gender)" else vName
                        result.add(Voice(name = displayName, locale = Locale.forLanguageTag(culture)))
                    }
                }
            }
            if (result.isEmpty()) {
                result.add(Voice("Microsoft Huihui Desktop (female)", Locale.SIMPLIFIED_CHINESE))
                result.add(Voice("Microsoft Zira Desktop (female)", Locale.US))
            }
            cachedVoices = result
            return result
        }

        private fun synthesizeWithWindowsSapi(text: String, voiceName: String?, outFile: File): Boolean {
            return runCatching {
                outFile.parentFile?.mkdirs()
                val cleanVoice = voiceName?.substringBefore(" (")?.trim().orEmpty()
                val textB64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                val pathB64 = Base64.getEncoder().encodeToString(outFile.absolutePath.toByteArray(Charsets.UTF_8))
                val voiceB64 = Base64.getEncoder().encodeToString(cleanVoice.toByteArray(Charsets.UTF_8))
                val script = """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}text = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('$textB64'))
                    ${'$'}path = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('$pathB64'))
                    ${'$'}voice = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('$voiceB64'))
                    ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    if (${'$'}voice.Length -gt 0) {
                        try { ${'$'}s.SelectVoice(${'$'}voice) } catch {}
                    }
                    ${'$'}s.SetOutputToWaveFile(${'$'}path)
                    ${'$'}s.Speak(${'$'}text)
                    ${'$'}s.Dispose()
                """.trimIndent()
                val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true)
                    .start()
                proc.inputStream.readBytes()
                proc.waitFor() == 0
            }.getOrDefault(false)
        }
    }
}
