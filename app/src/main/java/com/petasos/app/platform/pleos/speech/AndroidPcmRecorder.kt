package com.petasos.app.platform.pleos.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

/** Standard Android microphone capture; never asks the Pleos SDK to open a second microphone. */
internal class AndroidPcmRecorder(context: Context) : PcmRecorder {
    private val recorder: AudioRecord

    init {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO_REQUIRED")
        }
        val minimum = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0)
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minimum, 6_400))
        if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(true)
        val candidate = builder.build()
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            error("PCM_RECORDER_NOT_INITIALIZED")
        }
        recorder = candidate
    }

    override fun start() = recorder.startRecording()
    override val recording: Boolean get() = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    override fun read(samples: ShortArray): Int = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
    override fun stop() = recorder.stop()
    override val stopped: Boolean get() = recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED
    override fun release() = recorder.release()
}
