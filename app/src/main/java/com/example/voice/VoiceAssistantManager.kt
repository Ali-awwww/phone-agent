package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale

private const val TAG = "VoiceAssistantManager"

class VoiceAssistantManager(
    private val context: Context
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordedFile: File? = null
    private var isRecordingAudioFile = false

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val arabicLocale = Locale.forLanguageTag("ar")
            val result = tts?.setLanguage(arabicLocale)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) {
                tts?.setLanguage(Locale.getDefault())
                ttsReady = true
            }
        }
    }

    fun speakArabic(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wakeel_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalTranscript: (String) -> Unit,
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopSpeaking()
        if (isSpeechRecognitionAvailable()) {
            startSpeechRecognizer(onPartialResult, onFinalTranscript, onError)
        } else {
            startMediaRecorderFallback(onError)
        }
    }

    fun stopListening(
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecordingAudioFile) {
            stopMediaRecorderFallback(onAudioRecordedFallback, onError)
        } else {
            speechRecognizer?.stopListening()
        }
    }

    private fun startSpeechRecognizer(
        onPartialResult: (String) -> Unit,
        onFinalTranscript: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "لم أتمكن من سماع كلمات واضحة، حاول التحدث مرة أخرى."
                        SpeechRecognizer.ERROR_AUDIO -> "مشكلة في التقاط الصوت من الميكروفون."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "يرجى تفعيل إذن الميكروفون أولاً."
                        SpeechRecognizer.ERROR_NETWORK -> "تعذر التعرف على الصوت بسبب الشبكة."
                        else -> "تعذر التعرف على الصوت (رمز $error)."
                    }
                    onError(message)
                }

                override fun onResults(results: Bundle?) {
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
                        onPartialResult(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer error", e)
            onError("تعذر تشغيل التعرف الصوتي: ${e.localizedMessage}")
        }
    }

    private fun startMediaRecorderFallback(onError: (String) -> Unit) {
        try {
            val outFile = File(context.cacheDir, "voice_cmd_${System.currentTimeMillis()}.m4a")
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
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecordingAudioFile = true
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder start failed", e)
            isRecordingAudioFile = false
            onError("تعذر بدء تسجيل الصوت: ${e.localizedMessage}")
        }
    }

    private fun stopMediaRecorderFallback(
        onAudioRecordedFallback: (ByteArray, String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecordingAudioFile = false
            val file = recordedFile
            if (file != null && file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                file.delete()
                onAudioRecordedFallback(bytes, "audio/mp4")
            } else {
                onError("التسجيل الصوتي فارغ.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder stop failed", e)
            isRecordingAudioFile = false
            mediaRecorder?.release()
            mediaRecorder = null
            onError("تعذر إنهاء التسجيل الصوتي: ${e.localizedMessage}")
        }
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        mediaRecorder?.release()
        mediaRecorder = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
