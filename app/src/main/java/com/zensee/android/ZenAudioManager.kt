package com.zensee.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

object ZenAudioManager {
    enum class PreviewToggleResult {
        PLAYING,
        STOPPED,
        SOUND_DISABLED,
        UNAVAILABLE
    }

    private enum class PlaybackMode {
        PREVIEW,
        MEDITATION
    }

    private lateinit var appContext: Context
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var playbackMode: PlaybackMode? = null
    private var previewingSoundKey: String? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    private val previewListeners = linkedSetOf<(String?) -> Unit>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun setSoundEnabled(enabled: Boolean) {
        SettingsManager.setSoundEnabled(enabled)
        if (!enabled) {
            stopPlayback()
        }
    }

    fun isSoundEnabled(): Boolean = SettingsManager.isSoundEnabled()

    fun selectedMeditationSound(): MeditationSound {
        return MeditationSoundCatalog.findByStorageKey(SettingsManager.selectedMeditationSoundKey())
    }

    fun setSelectedMeditationSound(storageKey: String) {
        val sound = MeditationSoundCatalog.findByStorageKey(storageKey)
        SettingsManager.setSelectedMeditationSoundKey(sound.storageKey)
    }

    fun previewingSoundKey(): String? = previewingSoundKey

    fun addPreviewStateListener(listener: (String?) -> Unit) {
        previewListeners += listener
        listener(previewingSoundKey)
    }

    fun removePreviewStateListener(listener: (String?) -> Unit) {
        previewListeners -= listener
    }

    fun togglePreview(storageKey: String): PreviewToggleResult {
        if (!::appContext.isInitialized) return PreviewToggleResult.UNAVAILABLE
        if (!SettingsManager.isSoundEnabled()) return PreviewToggleResult.SOUND_DISABLED

        val sound = MeditationSoundCatalog.findByStorageKey(storageKey)
        val isSameSoundPreviewing = playbackMode == PlaybackMode.PREVIEW &&
            previewingSoundKey == sound.storageKey &&
            mediaPlayer?.isPlaying == true

        if (isSameSoundPreviewing) {
            stopPreview()
            return PreviewToggleResult.STOPPED
        }

        return if (playSound(sound, PlaybackMode.PREVIEW)) {
            PreviewToggleResult.PLAYING
        } else {
            PreviewToggleResult.UNAVAILABLE
        }
    }

    fun stopPreview() {
        if (playbackMode == PlaybackMode.PREVIEW) {
            stopPlayback()
        }
    }

    fun playSelectedMeditationSound() {
        if (!::appContext.isInitialized || !SettingsManager.isSoundEnabled()) return
        playSound(selectedMeditationSound(), PlaybackMode.MEDITATION)
    }

    private fun playSound(sound: MeditationSound, mode: PlaybackMode): Boolean {
        stopPlayback()

        val audioAttributes = AudioAttributes.Builder()
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
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false

        val soundResId = appContext.resources.getIdentifier(sound.rawResName, "raw", appContext.packageName)
        if (soundResId == 0) {
            systemAudioManager.abandonAudioFocusRequest(focusRequest)
            return false
        }

        val descriptor = appContext.resources.openRawResourceFd(soundResId) ?: run {
            systemAudioManager.abandonAudioFocusRequest(focusRequest)
            return false
        }
        audioFocusRequest = focusRequest
        playbackMode = mode
        previewingSoundKey = if (mode == PlaybackMode.PREVIEW) sound.storageKey else null
        dispatchPreviewState()
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
        return true
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
        playbackMode = null
        if (previewingSoundKey != null) {
            previewingSoundKey = null
            dispatchPreviewState()
        }
    }

    private fun dispatchPreviewState() {
        val currentState = previewingSoundKey
        previewListeners.toList().forEach { listener ->
            listener(currentState)
        }
    }
}
