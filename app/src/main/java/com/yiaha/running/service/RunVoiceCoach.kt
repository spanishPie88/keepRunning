package com.yiaha.running.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 轻量 TTS 语音教练。TTS 尚未初始化完成时会暂存播报，避免吞掉“开始跑步”。 */
class RunVoiceCoach(context: Context) {
    private val tag = "RunVoiceCoach"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceId = AtomicLong(0L)
    private val pending = mutableListOf<PendingSpeech>()
    private var ready = false
    private var released = false
    private var textToSpeech: TextToSpeech? = null

    init {
        mainHandler.post {
            if (released) return@post
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS || released) {
                    Log.w(tag, "TTS initialization failed: $status")
                    return@TextToSpeech
                }

                textToSpeech?.apply {
                    val languageResult = setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                        languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.w(tag, "Simplified Chinese TTS is unavailable; using engine default")
                    }
                    setSpeechRate(1.0f)
                }
                ready = true
                val queued = pending.toList()
                pending.clear()
                queued.forEach { speakOnMain(it.text, it.flushQueue) }
            }
        }
    }

    fun announceStart() = speak("跑步开始", flushQueue = true)

    fun announcePause() = speak("跑步已暂停", flushQueue = true)

    fun announceResume() = speak("继续跑步", flushQueue = true)

    fun announceKilometer(kilometer: Int, splitElapsedMillis: Long) {
        speak("已完成第${kilometer}公里，用时${formatChineseDuration(splitElapsedMillis)}")
    }

    fun announceFinish(distanceMeters: Double) {
        val distance = String.format(Locale.CHINA, "%.2f", distanceMeters / 1_000.0)
        speak("跑步结束，本次共$distance 公里", flushQueue = true)
    }

    fun shutdown() {
        released = true
        pending.clear()
        mainHandler.post {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            ready = false
        }
    }

    private fun speak(text: String, flushQueue: Boolean = false) {
        mainHandler.post {
            if (released) return@post
            if (!ready) {
                pending += PendingSpeech(text, flushQueue)
            } else {
                speakOnMain(text, flushQueue)
            }
        }
    }

    private fun speakOnMain(text: String, flushQueue: Boolean) {
        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        textToSpeech?.speak(text, queueMode, null, "run-${utteranceId.incrementAndGet()}")
        Log.d(tag, "speak: $text")
    }

    private fun formatChineseDuration(durationMillis: Long): String {
        val totalSeconds = (durationMillis / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    }

    private data class PendingSpeech(val text: String, val flushQueue: Boolean)
}
