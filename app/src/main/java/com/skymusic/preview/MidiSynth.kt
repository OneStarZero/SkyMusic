package com.skymusic.preview

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * MIDI 音符 → PCM 合成器
 * 支持按采样区间合成（流式），无 Android 依赖
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
     * 合成指定采样区间 [fromSample, toSample)
     * @param notes 音符列表（需按 startMs 排序）
     * @param fromSample 起始采样（含）
     * @param toSample 结束采样（不含）
     * @param volume 总音量 0-1
     * @return 该区间的 PCM 数据
     */
    fun synthesizeRange(
        notes: List<Note>,
        fromSample: Int,
        toSample: Int,
        volume: Float = 0.6f
    ): ShortArray {
        val len = (toSample - fromSample).coerceAtLeast(0)
        if (len == 0) return ShortArray(0)

        val buffer = FloatArray(len)
        val attackSamples = (SAMPLE_RATE * 0.008).toInt()
        val releaseSamples = (SAMPLE_RATE * 0.12).toInt()

        for (note in notes) {
            val noteStart = ((note.startMs / 1000.0) * SAMPLE_RATE).toInt()
            val noteDur = ((note.durationMs / 1000.0) * SAMPLE_RATE).toInt()
            if (noteDur <= 0) continue

            val noteEnd = noteStart + noteDur

            // 跳过不重叠的音符
            if (noteEnd <= fromSample) continue
            if (noteStart >= toSample) break  // notes 已排序，后面都不重叠

            val freq = midiToFreq(note.midi)
            val amp = note.velocity * volume
            val relStart = noteDur - releaseSamples

            // 计算重叠区间
            val overlapStart = maxOf(noteStart, fromSample)
            val overlapEnd = minOf(noteEnd, toSample)

            for (i in overlapStart until overlapEnd) {
                val local = i - noteStart
                val t = local.toDouble() / SAMPLE_RATE

                // 音色：基频 + 谐波（更柔和，接近钢琴）
                val wave = sin(2 * PI * freq * t) * 0.62 +
                           sin(2 * PI * freq * 2 * t) * 0.22 +
                           sin(2 * PI * freq * 3 * t) * 0.10 +
                           sin(2 * PI * freq * 4 * t) * 0.04 +
                           sin(2 * PI * freq * 5 * t) * 0.02

                // ADSR 包络
                val env = when {
                    local < attackSamples -> local.toFloat() / attackSamples
                    local > relStart -> ((noteDur - local).toFloat() / releaseSamples).coerceIn(0f, 1f)
                    else -> 1f
                }

                // 指数衰减（更慢，更自然）
                val decay = exp(-t * 1.2).toFloat()

                buffer[i - fromSample] += (wave * env * decay * amp).toFloat()
            }
        }

        // 转 ShortArray，软限幅
        val out = ShortArray(len)
        for (i in 0 until len) {
            val v = buffer[i].coerceIn(-1f, 1f)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    fun midiToFreq(midi: Int): Double {
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }
}