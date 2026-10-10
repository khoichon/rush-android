package com.khoichon.rush

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * One sound (assets/sounds/<name>.ogg): play / playLooping / pause / setVolume. The player is
 * created lazily and prepared asynchronously; whatever was asked for before it is ready is applied
 * once it is. Main thread only.
 */
class Sfx(private val context: Context, private val name: String) {
    private var mp: MediaPlayer? = null
    private var ready = false
    private var want = 0        // 0 = silent, 1 = play once, 2 = loop
    private var vol = 1f

    /** Create and start preparing the player without playing anything. */
    fun preload() = ensure()

    private fun ensure() {
        if (mp != null) return
        val player = MediaPlayer()
        try {
            context.assets.openFd("sounds/$name.ogg").use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setOnPreparedListener { ready = true; apply() }
            player.setOnErrorListener { _, _, _ -> ready = false; true }
            player.prepareAsync()
            mp = player
        } catch (e: Exception) {
            runCatching { player.release() }
        }
    }

    private fun apply() {
        val m = mp ?: return
        if (!ready) return
        runCatching {
            m.setVolume(vol, vol)
            when (want) {
                0 -> if (m.isPlaying) m.pause()
                1 -> { m.isLooping = false; m.seekTo(0); m.start() }
                else -> { m.isLooping = true; if (!m.isPlaying) m.start() }
            }
        }
    }

    fun setVolume(v: Float) {
        vol = v.coerceIn(0f, 1f)
        val m = mp ?: return
        if (ready) runCatching { m.setVolume(vol, vol) }
    }

    fun play() { want = 1; ensure(); apply() }

    fun playLooping() {
        if (want == 2) return
        want = 2; ensure(); apply()
    }

    fun pause() {
        if (want == 0) return
        want = 0
        apply()
    }

    fun release() {
        want = 0
        ready = false
        runCatching { mp?.release() }
        mp = null
    }
}
