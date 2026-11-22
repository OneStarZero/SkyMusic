package com.skymusic.preview

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * App 端原生 MIDI 试听播放器
 * 支持：播放 / 暂停 / 恢复 / 停止 / 进度回调
 */
object MidiPreviewPlayer {

    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    private var pcmData: ShortArray? = null

    @Volatile private var isPlaying = false
    @Volatile private var isPaused = false
    @Volatile private var writeOffset = 0

    private var currentTotalMs = 0L
    private var startTimeMs = 0L
    private var pausedElapsedMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 状态回调：(state, progressMs, totalMs) */
    var onStateChange: ((state: String, progressMs: Long, totalMs: Long) -> Unit)? = null

    /**
     * 播放
     * @param notes 音符列表
     * @param totalMs 总时长
     */
    fun play(notes: List<MidiSynth.Note>, totalMs: Long): Boolean {
        stop()
        if (notes.isEmpty() || totalMs <= 0) return false

        currentTotalMs = totalMs
        isPlaying = true
        isPaused = false
        writeOffset = 0
        pausedElapsedMs = 0L
        startTimeMs = System.currentTimeMillis()

        playThread = thread(name = "MidiPreviewPlayer") {
            try {
                val pcm = MidiSynth.synthesize(notes, totalMs)
                pcmData = pcm

                val minBuf = AudioTrack.getMinBufferSize(
                    MidiSynth.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, 8192)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(MidiSynth.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()

                val chunk = 4096
                while (writeOffset < pcm.size && isPlaying) {
                    if (isPaused) {
                        Thread.sleep(30)
                        continue
                    }
                    val len = minOf(chunk, pcm.size - writeOffset)
                    track.write(pcm, writeOffset, len)
                    writeOffset += len
                }

                // 等待播放完成
                while (isPlaying && !isPaused && track.playbackHeadPosition < pcm.size) {
                    Thread.sleep(50)
                }

                if (isPlaying && !isPaused) {
                    notifyState("ended", currentTotalMs, currentTotalMs)
                }
            } catch (t: Throwable) {
                notifyState("error", 0, currentTotalMs)
            } finally {
                releaseTrack()
                isPlaying = false
                isPaused = false
            }
        }

        notifyState("playing", 0, totalMs)
        startProgressLoop()
        return true
    }

    /** 暂停 */
    fun pause() {
        if (!isPlaying || isPaused) return
        isPaused = true
        pausedElapsedMs = System.currentTimeMillis() - startTimeMs
        try {
            audioTrack?.pause()
        } catch (_: Throwable) {}
        notifyState("paused", pausedElapsedMs.coerceAtMost(currentTotalMs), currentTotalMs)
    }

    /** 恢复 */
    fun resume() {
        if (!isPlaying || !isPaused) return
        isPaused = false
        startTimeMs = System.currentTimeMillis() - pausedElapsedMs
        try {
            audioTrack?.play()
        } catch (_: Throwable) {}
        notifyState("playing", pausedElapsedMs.coerceAtMost(currentTotalMs), currentTotalMs)
    }

    /** 停止 */
    fun stop() {
        isPlaying = false
        isPaused = false
        playThread?.interrupt()
        playThread = null
        releaseTrack()
        pcmData = null
        writeOffset = 0
        pausedElapsedMs = 0L
        notifyState("stopped", 0, currentTotalMs)
    }

    fun isPlaying(): Boolean = isPlaying && !isPaused
    fun isPaused(): Boolean = isPaused
    fun isActive(): Boolean = isPlaying

    private fun releaseTrack() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release()
            }
        } catch (_: Throwable) {}
        audioTrack = null
    }

    private fun startProgressLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!isPlaying) return
                if (!isPaused) {
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    notifyState("playing", elapsed.coerceAtMost(currentTotalMs), currentTotalMs)
                }
                mainHandler.postDelayed(this, 200)
            }
        })
    }

    private fun notifyState(state: String, progressMs: Long, totalMs: Long) {
        mainHandler.post {
            onStateChange?.invoke(state, progressMs, totalMs)
        }
    }
}
