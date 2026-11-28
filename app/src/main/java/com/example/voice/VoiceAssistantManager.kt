package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.security.SensitiveDataScrubber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private const val TAG = "VoiceAssistantManager"

class VoiceAssistantManager(
    private val context: Context,
    private val recognitionTimeoutMs: Long = SPEECH_RECOGNITION_TIMEOUT_MS
) : TextToSpeech.OnInitListener {

    companion object {
        const val MAX_RECORDING_DURATION_MS = 60_000
        const val MAX_RECORDING_FILE_BYTES = 1_048_576L // 1 MB حد أقصى لحماية الذاكرة
        const val SPEECH_RECOGNITION_TIMEOUT_MS = 18_000L
        const val DEFAULT_SPEECH_MAX_CHARS = 380
        const val WEAK_NETWORK_SPEECH_MAX_CHARS = 150

        fun truncateForSpeech(
            text: String,
            maxChars: Int = DEFAULT_SPEECH_MAX_CHARS
        ): String {
            val cleaned = text.replace(Regex("\\s+"), " ").trim()
            if (cleaned.length <= maxChars) return cleaned
            val limit = maxChars.coerceAtLeast(32)
            val slice = cleaned.take(limit)
            val lastBreak = slice.lastIndexOfAny(charArrayOf(' ', '،', '.', '؟', '!'))
            val trimmed = if (lastBreak >= (limit * 0.6).toInt()) {
                slice.substring(0, lastBreak).trimEnd()
            } else {
                slice.trimEnd()
            }
            return "$trimmed…"
        }

        internal fun readBoundedAndDeleteFile(
            file: File?,
            maxBytes: Long = MAX_RECORDING_FILE_BYTES
        ): ByteArray? {
            if (file == null) return null
            return try {
                if (!file.exists() || file.length() <= 0L) {
                    null
                } else {
                    val cap = maxBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    val out = ByteArrayOutputStream(minOf(file.length().toInt(), cap))
                    val buffer = ByteArray(8192)
                    FileInputStream(file).use { input ->
                        var totalRead = 0
                        while (totalRead < cap) {
                            val toRead = minOf(buffer.size, cap - totalRead)
                            val read = input.read(buffer, 0, toRead)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            totalRead += read
                        }
                    }
                    out.toByteArray().takeIf { it.isNotEmpty() }
                }
            } finally {
                runCatching { file.delete() }
            }
        }

        internal fun wrapPcmAsWav(pcmBytes: ByteArray, sampleRate: Int = 16000): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val totalDataLen = pcmBytes.size + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmBytes.size)
            }.array()
            return header + pcmBytes
        }
    }

    private var tts: TextToSpeech? = null
    internal var ttsReady: Boolean = false
        private set
    internal var lastTtsLanguageStatus: Int = TextToSpeech.LANG_NOT_SUPPORTED
        private set

    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    internal var recordedFile: File? = null
        private set
    internal var isRecordingAudioFile: Boolean = false
        private set
    private var isSpeechRecognizerActive: Boolean = false

    private val recognizerAudioBuffer = ByteArrayOutputStream()
    private var activeOnAudioFallback: ((ByteArray, String) -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null
    private var activeOnPartial: ((String) -> Unit)? = null
    private var lastPartialTranscript: String = ""

    private val voiceTimeoutHandler = Handler(Looper.getMainLooper())
    private val voiceTimeoutRunnable = Runnable {
        onRecognitionTimeoutReached()
    }

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext, this)
        }
        purgeOrphanedVoiceFiles()
    }

    fun isCurrentlyListening(): Boolean = isRecordingAudioFile || isSpeechRecognizerActive

    fun purgeOrphanedVoiceFiles() {
        runCatching {
            context.cacheDir.listFiles { f ->
                f.isFile && f.name.startsWith("voice_cmd_") && f.name.endsWith(".m4a")
            }?.forEach { it.delete() }
        }
    }

    override fun onInit(status: Int) {
        applyTtsLanguageCheck(status, tts)
    }

    internal fun applyTtsLanguageCheck(status: Int, targetTts: TextToSpeech?) {
        if (status != TextToSpeech.SUCCESS || targetTts == null) {
            ttsReady = false
            lastTtsLanguageStatus = TextToSpeech.ERROR
            return
        }
        val arabicLocale = Locale.forLanguageTag("ar")
        val arResult = targetTts.setLanguage(arabicLocale)
        if (arResult >= TextToSpeech.LANG_AVAILABLE) {
            lastTtsLanguageStatus = arResult
            ttsReady = true
            return
        }
        val defaultResult = targetTts.setLanguage(Locale.getDefault())
        lastTtsLanguageStatus = defaultResult
        ttsReady = defaultResult >= TextToSpeech.LANG_AVAILABLE
        if (!ttsReady) {
            Log.w(TAG, "TTS language not supported: ar=$arResult, default=$defaultResult")
        }
    }

    fun speakArabic(
        text: String,
        maxChars: Int = DEFAULT_SPEECH_MAX_CHARS
    ) {
        if (!ttsReady || text.isBlank()) return
        val spoken = truncateForSpeech(text, maxChars)
        if (spoken.isBlank()) return
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "wakeel_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    internal var speechRecognitionAvailableOverride: Boolean? = null

    fun isSpeechRecognitionAvailable(): Boolean {
        return speechRecognitionAvailableOverride ?: SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalTranscript: (String) -> Unit,
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        activeOnPartial = onPartialResult
        activeOnAudioFallback = onAudioRecordedFallback
        activeOnError = onError
        lastPartialTranscript = ""
        recognizerAudioBuffer.reset()
        stopSpeaking()
        if (isSpeechRecognitionAvailable()) {
            startSpeechRecognizer(
                onPartialResult = onPartialResult,
                onFinalTranscript = onFinalTranscript,
                onAudioRecordedFallback = onAudioRecordedFallback,
                onError = onError
            )
        } else {
            startMediaRecorderFallback(onAudioRecordedFallback, onError)
        }
    }

    fun stopListening(
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
        activeOnAudioFallback = onAudioRecordedFallback
        activeOnError = onError
        if (isRecordingAudioFile) {
            stopMediaRecorderFallback(onAudioRecordedFallback, onError)
        } else {
            isSpeechRecognizerActive = false
            speechRecognizer?.stopListening()
        }
    }

    internal fun onRecognitionTimeoutReached() {
        val fallback = activeOnAudioFallback ?: { _, _ -> }
        val errorCb = activeOnError ?: { _ -> }
        if (isRecordingAudioFile) {
            stopMediaRecorderFallback(fallback, errorCb)
            return
        }
        isSpeechRecognizerActive = false
        runCatching {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null

        val buffered = drainBufferedPcmAsWav()
        if (buffered != null) {
            fallback(buffered, "audio/wav")
            return
        }
        if (lastPartialTranscript.isNotBlank()) {
            // إذا كان هناك نص جزئي مقتضب قبل المهلة ولم تتوفر بايتات، نبلغ عبر الخطأ الواضح
            errorCb("انتهت مهلة التعرّف الصوتي. آخر ما سُمع: «$lastPartialTranscript»")
        } else {
            errorCb("انتهت مهلة الاستماع الصوتي — لم أسمع شيئاً.")
        }
    }

    private fun drainBufferedPcmAsWav(): ByteArray? {
        val pcm = synchronized(recognizerAudioBuffer) {
            val raw = recognizerAudioBuffer.toByteArray()
            recognizerAudioBuffer.reset()
            raw
        }
        if (pcm.isEmpty()) return null
        return wrapPcmAsWav(pcm)
    }

    internal fun buildRecognitionListener(
        onPartialResult: (String) -> Unit,
        onFinalTranscript: (String) -> Unit,
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer == null || buffer.isEmpty()) return
            synchronized(recognizerAudioBuffer) {
                val remaining = MAX_RECORDING_FILE_BYTES.toInt() - recognizerAudioBuffer.size()
                if (remaining > 0) {
                    recognizerAudioBuffer.write(buffer, 0, minOf(buffer.size, remaining))
                }
            }
        }

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
            isSpeechRecognizerActive = false
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null

            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "لم أتمكن من سماع كلمات واضحة، حاول التحدث مرة أخرى."
                SpeechRecognizer.ERROR_AUDIO -> "مشكلة في التقاط الصوت من الميكروفون."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "يرجى تفعيل إذن الميكروفون أولاً."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "تعذر التعرف على الصوت بسبب الشبكة."
                else -> "تعذر التعرف على الصوت (رمز $error)."
            }

            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                recognizerAudioBuffer.reset()
                onError(message)
                return
            }

            // F20: تمرير البديل إلى مسار التعرّف واستدعاؤه عند onError بدل الصمت
            val bufferedWav = drainBufferedPcmAsWav()
            if (bufferedWav != null) {
                onAudioRecordedFallback(bufferedWav, "audio/wav")
                return
            }

            if (error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT
            ) {
                startMediaRecorderFallback(onAudioRecordedFallback, onError)
                if (isRecordingAudioFile) {
                    onPartialResult("تم التحويل للتسجيل الصوتي الاحتياطي — تحدّث ثم اضغط إيقاف")
                    return
                }
            }

            onError(message)
        }

        override fun onResults(results: Bundle?) {
            voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
            isSpeechRecognizerActive = false
            recognizerAudioBuffer.reset()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                onFinalTranscript(text)
            } else {
                onError("لم يتم التقاط نص صوتي.")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                lastPartialTranscript = text
                onPartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startSpeechRecognizer(
        onPartialResult: (String) -> Unit,
        onFinalTranscript: (String) -> Unit,
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
            voiceTimeoutHandler.postDelayed(voiceTimeoutRunnable, recognitionTimeoutMs)

            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer
            isSpeechRecognizerActive = true

            recognizer.setRecognitionListener(
                buildRecognitionListener(
                    onPartialResult = onPartialResult,
                    onFinalTranscript = onFinalTranscript,
                    onAudioRecordedFallback = onAudioRecordedFallback,
                    onError = onError
                )
            )

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
            isSpeechRecognizerActive = false
            Log.e(TAG, "SpeechRecognizer error", e)
            startMediaRecorderFallback(onAudioRecordedFallback, onError)
            if (isRecordingAudioFile) {
                onPartialResult("تم التحويل للتسجيل الصوتي الاحتياطي — تحدّث ثم اضغط إيقاف")
            } else {
                onError("تعذر تشغيل التعرف الصوتي: ${SensitiveDataScrubber.scrubLogMessage(e.localizedMessage)}")
            }
        }
    }

    private fun startMediaRecorderFallback(
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        activeOnAudioFallback = onAudioRecordedFallback
        activeOnError = onError
        var outFile: File? = null
        try {
            purgeOrphanedVoiceFiles()
            outFile = File(context.cacheDir, "voice_cmd_${System.currentTimeMillis()}.m4a")
            recordedFile = outFile
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)
                setMaxDuration(MAX_RECORDING_DURATION_MS)
                setMaxFileSize(MAX_RECORDING_FILE_BYTES)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                    ) {
                        stopMediaRecorderFallback(
                            activeOnAudioFallback ?: onAudioRecordedFallback,
                            activeOnError ?: onError
                        )
                    }
                }
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecordingAudioFile = true
            voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
            voiceTimeoutHandler.postDelayed(voiceTimeoutRunnable, MAX_RECORDING_DURATION_MS.toLong())
        } catch (e: Exception) {
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            runCatching { outFile?.delete() }
            runCatching { recordedFile?.delete() }
            recordedFile = null
            isRecordingAudioFile = false
            purgeOrphanedVoiceFiles()
            Log.e(TAG, "MediaRecorder start failed", e)
            val scrubbedErr = SensitiveDataScrubber.scrubLogMessage(e.localizedMessage)
            onError("تعذر بدء تسجيل الصوت: $scrubbedErr")
        }
    }

    private fun stopMediaRecorderFallback(
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
        val file = recordedFile
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecordingAudioFile = false
            val bytes = readBoundedAndDeleteFile(file, MAX_RECORDING_FILE_BYTES)
            if (bytes != null && bytes.isNotEmpty()) {
                onAudioRecordedFallback(bytes, "audio/mp4")
            } else {
                onError("التسجيل الصوتي فارغ.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder stop failed", e)
            isRecordingAudioFile = false
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            val scrubbedErr = SensitiveDataScrubber.scrubLogMessage(e.localizedMessage)
            onError("تعذر إنهاء التسجيل الصوتي: $scrubbedErr")
        } finally {
            runCatching { file?.delete() }
            recordedFile = null
            purgeOrphanedVoiceFiles()
        }
    }

    fun release() {
        voiceTimeoutHandler.removeCallbacks(voiceTimeoutRunnable)
        isSpeechRecognizerActive = false
        isRecordingAudioFile = false
        recognizerAudioBuffer.reset()
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        runCatching { recordedFile?.delete() }
        recordedFile = null
        purgeOrphanedVoiceFiles()
    }
}
