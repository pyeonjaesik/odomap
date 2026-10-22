package com.odomap.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Voice output. Phase 1: Android TextToSpeech; swap in the Pleos TTS SDK when it ships. */
interface Speaker {
    fun speak(text: String)

    fun shutdown()
}

class AndroidTtsSpeaker(context: Context) : Speaker {
    private var ready = false
    private val pending = ArrayList<String>()
    private val tts: TextToSpeech =
        TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                if (!ready) Log.w(TAG, "no TextToSpeech engine (status $status) — arrival speech is skipped")
                if (ready) {
                    val r = tts.setLanguage(Locale.KOREAN)
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) Log.w(TAG, "Korean voice not available ($r)")
                    pending.forEach { say(it) }
                }
                pending.clear()
            }
        }

    override fun speak(text: String) {
        synchronized(this) {
            if (ready) say(text) else pending.add(text)
        }
    }

    private fun say(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "odomap-${text.hashCode()}")
    }

    override fun shutdown() {
        runCatching { tts.shutdown() }
    }

    companion object {
        private const val TAG = "Speaker"
    }
}
