package com.rasel.RasFocus.p2p

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

class AudioRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var currentOutputFile: File? = null
        private set

    fun startRecording(): Boolean {
        try {
            val audioDir = File(context.cacheDir, "voice_notes")
            if (!audioDir.exists()) audioDir.mkdirs()

            currentOutputFile = File(audioDir, "audio_${System.currentTimeMillis()}.m4a")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentOutputFile?.absolutePath)
                prepare()
                start()
            }
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            return false
        }
    }

    fun stopRecording(): File? {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: RuntimeException) {
            // Can happen if stop() is called immediately after start()
            e.printStackTrace()
            currentOutputFile?.delete()
            currentOutputFile = null
        }
        recorder = null
        return currentOutputFile
    }

    fun cancelRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        recorder = null
        currentOutputFile?.delete()
        currentOutputFile = null
    }
}
