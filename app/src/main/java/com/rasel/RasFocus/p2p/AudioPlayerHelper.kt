package com.rasel.RasFocus.p2p

import android.media.MediaPlayer
import java.io.IOException

class AudioPlayerHelper {
    private var mediaPlayer: MediaPlayer? = null
    var isPlaying = false
        private set

    fun play(filePath: String, onCompletion: () -> Unit) {
        stop() // Stop any current playback
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(filePath)
                prepare()
                start()
                this@AudioPlayerHelper.isPlaying = true
                setOnCompletionListener {
                    this@AudioPlayerHelper.isPlaying = false
                    onCompletion()
                    it.release()
                    mediaPlayer = null
                }
            } catch (e: IOException) {
                e.printStackTrace()
                this@AudioPlayerHelper.isPlaying = false
                release()
                mediaPlayer = null
            }
        }
    }

    fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
    }
}
