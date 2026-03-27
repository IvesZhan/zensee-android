package com.zensee.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

object ZenAudioManager {
    private lateinit var appContext: Context
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun setSoundEnabled(enabled: Boolean) {
        SettingsManager.setSoundEnabled(enabled)
        if (!enabled) {
            stopPlayback()
        }
    }

    fun playBowl() {
        if (!::appContext.isInitialized || !SettingsManager.isSoundEnabled()) return
        stopPlayback()

        val audioAttributes = AudioAttributes.Builder()
            // Match iOS playback semantics so the bowl sound still uses the media channel.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val systemAudioManager = appContext.getSystemService(AudioManager::class.java)
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        val focusResult = systemAudioManager?.requestAudioFocus(focusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        val descriptor = appContext.resources.openRawResourceFd(R.raw.bowl) ?: return
        audioFocusRequest = focusRequest
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            descriptor.close()
            isLooping = false
            setVolume(1f, 1f)
            setOnCompletionListener { stopPlayback() }
            setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            prepare()
            start()
        }
    }

    fun stopPlayback() {
        mediaPlayer?.runCatching {
            stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        val systemAudioManager = if (::appContext.isInitialized) {
            appContext.getSystemService(AudioManager::class.java)
        } else {
            null
        }
        audioFocusRequest?.let { request ->
            systemAudioManager?.abandonAudioFocusRequest(request)
        }
        audioFocusRequest = null
    }
}
