package com.skymusic.preview

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * App 端原生 MIDI 试听播放器
 * 流式合成：边合成边写入 AudioTrack，内存占用固定
 * 支持：播放 / 暂停 / 恢复 / 停止 / seek / 进度回调
 */
object MidiPreviewPlayer {

    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null

    @Volatile private var isPlaying = false
    @Volatile private var isPaused = false
    @Volatile private var seekRequestSample = -1

    private var notes: List<MidiSynth.Note> = emptyList()
    private var currentTotalMs = 0L
    private var totalSamples = 0
    private var currentSample = 0

    private var startTimeMs = 0L
    private var pausedElapsedMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 状态回调：(state, progressMs, totalMs) */
    var onStateChange: ((state: String, progressMs: Long, totalMs: Long) -> Unit)? = null

    /**
     * 播放
     */
    fun play(noteList: List<MidiSynth.Note>, totalMs: Long): Boolean {
        stop()
        if (noteList.isEmpty() || totalMs <= 0) return false

        // 按开始时间排序（合成时依赖有序）
        notes = noteList.sortedBy { it.startMs }
        currentTotalMs = totalMs
        totalSamples = ((totalMs / 1000.0) * MidiSynth.SAMPLE_RATE).toInt().coerceAtLeast(1)
        currentSample = 0
        seekRequestSample = -1

        isPlaying = true
        isPaused = false
        pausedElapsedMs = 0L
        startTimeMs = System.currentTimeMillis()

        playThread = thread(name = "MidiPreviewPlayer") {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    MidiSynth.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, 16384)

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

                while (isPlaying && currentSample < totalSamples) {
                    // 处理 seek 请求
                    val seekTo = seekRequestSample
                    if (seekTo >= 0) {
                        seekRequestSample = -1
                        currentSample = seekTo.coerceIn(0, totalSamples)
                        // 清空 AudioTrack 缓冲
                        try {
                            track.pause()
                            track.flush()
                            track.play()
                        } catch (_: Throwable) {}
                        // 重置计时
                        startTimeMs = System.currentTimeMillis() - (currentSample * 1000L / MidiSynth.SAMPLE_RATE)
                        pausedElapsedMs = 0L
                    }

                    // 暂停时等待
                    if (isPaused) {
                        Thread.sleep(30)
                        continue
                    }

                    // 流式合成一块
                    val to = minOf(currentSample + chunk, totalSamples)
                    val pcm = MidiSynth.synthesizeRange(notes, currentSample, to)

                    if (pcm.isNotEmpty()) {
                        var written = 0
                        while (written < pcm.size && isPlaying) {
                            if (isPaused) {
                                Thread.sleep(20)
                                continue
                            }
                            val n = track.write(pcm, written, pcm.size - written)
                            if (n < 0) break
                            written += n
                        }
                    }

                    currentSample = to
                }

                // 等待缓冲播放完
                if (isPlaying && !isPaused) {
                    Thread.sleep(200)
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

    /** 跳转到指定毫秒 */
    fun seek(ms: Long) {
        if (!isPlaying) return
        val sample = ((ms.coerceIn(0, currentTotalMs) / 1000.0) * MidiSynth.SAMPLE_RATE).toInt()
        seekRequestSample = sample
        pausedElapsedMs = ms.coerceIn(0, currentTotalMs)
        startTimeMs = System.currentTimeMillis() - pausedElapsedMs
        notifyState(if (isPaused) "paused" else "playing", pausedElapsedMs, currentTotalMs)
    }

    /** 停止 */
    fun stop() {
        isPlaying = false
        isPaused = false
        seekRequestSample = -1
        playThread?.interrupt()
        playThread = null
        releaseTrack()
        notes = emptyList()
        currentSample = 0
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
