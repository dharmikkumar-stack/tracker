package com.kidsafe.beacon

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import java.io.File

/**
 * Records a short audio clip to an .m4a file for a fixed duration.
 * Caller must already hold RECORD_AUDIO permission.
 */
class AudioRecorder(private val context: Context) {

    suspend fun record(durationMs: Long): File? {
        val file = File.createTempFile("audio_", ".m4a", context.cacheDir)
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            delay(durationMs)
            recorder.stop()
            recorder.release()
            file
        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            null
        }
    }
}
