package com.skymusic.preview

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * MIDI 音符 → PCM 合成器
 * 纯正弦波 + 谐波 + ADSR 包络，无 Android 依赖
 */
object MidiSynth {

    const val SAMPLE_RATE = 44100

    data class Note(
        val midi: Int,
        val startMs: Long,
        val durationMs: Long,
        val velocity: Float = 0.8f
    )

    /**
     * 合成 PCM
     * @param notes 音符列表
     * @param totalMs 总时长
     * @param volume 总音量 0-1
     */
    fun synthesize(notes: List<Note>, totalMs: Long, volume: Float = 0.6f): ShortArray {
        val totalSamples = ((totalMs / 1000.0) * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val buffer = FloatArray(totalSamples)

        val attackSamples = (SAMPLE_RATE * 0.008).toInt()
        val releaseSamples = (SAMPLE_RATE * 0.12).toInt()

        for (note in notes) {
            val freq = midiToFreq(note.midi)
            val startSample = ((note.startMs / 1000.0) * SAMPLE_RATE).toInt()
            val durSamples = ((note.durationMs / 1000.0) * SAMPLE_RATE).toInt()
            if (durSamples <= 0) continue
            if (startSample >= totalSamples) continue

            val endSample = (startSample + durSamples).coerceAtMost(totalSamples)
            val amp = note.velocity * volume
            val relStart = durSamples - releaseSamples

            for (i in startSample until endSample) {
                val local = i - startSample
                val t = local.toDouble() / SAMPLE_RATE

                val wave = sin(2 * PI * freq * t) * 0.75 +
                           sin(2 * PI * freq * 2 * t) * 0.18 +
                           sin(2 * PI * freq * 3 * t) * 0.07

                val env = when {
                    local < attackSamples -> local.toFloat() / attackSamples
                    local > relStart -> ((durSamples - local).toFloat() / releaseSamples).coerceIn(0f, 1f)
                    else -> 1f
                }

                val decay = exp(-t * 1.8).toFloat()
                buffer[i] += (wave * env * decay * amp).toFloat()
            }
        }

        val out = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val v = buffer[i].coerceIn(-1f, 1f)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    fun midiToFreq(midi: Int): Double {
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }
}
