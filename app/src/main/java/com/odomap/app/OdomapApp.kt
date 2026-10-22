package com.odomap.app

import android.app.Application
import com.odomap.app.record.DriveRecorder
import com.odomap.app.tts.AndroidTtsSpeaker
import org.maplibre.android.MapLibre

class OdomapApp : Application() {
    lateinit var recorder: DriveRecorder
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        recorder = DriveRecorder(this, filesDir, AndroidTtsSpeaker(this))
    }
}
