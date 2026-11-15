package com.skymusic

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class OneStarAudioEngine(private val context: Context) {
    private val TAG = "OneStarAudioEngine"
    private val SAMPLE_RATE = 22050

    fun processAudio(uri: Uri, onResult: (String) -> Unit) {
        thread {
            try {
                val floatSamples = decodeToFloatArray(context, uri)
                if (floatSamples.isEmpty()) {
                    onResult("[]")
                    return@thread
                }

                // 🌟 纯数学自相关算法（提取频率）
                val notesList = mutableListOf<JSONObject>()
                val windowSize = 2048
                var lastTime = -1L
                
                for (i in 0 until floatSamples.size - windowSize step windowSize / 2) {
                    val window = floatSamples.copyOfRange(i, i + windowSize)
                    val freq = detectPitch(window)
                    
                    if (freq > 50 && freq < 2000) {
                        val timeMs = (i.toFloat() / SAMPLE_RATE * 1000).toLong()
                        if (timeMs - lastTime > 100) { // 防止连击过快
                            val midi = 69 + 12 * ln(freq / 440.0) / ln(2.0)
                            val midiInt = midi.roundToInt()
                            
                            val note = JSONObject().apply {
                                put("time", timeMs / 1000.0)
                                put("midi", midiInt)
                                put("velocity", 0.8)
                            }
                            notesList.add(note)
                            lastTime = timeMs
                        }
                    }
                }

                onResult(JSONArray(notesList).toString())

            } catch (e: Exception) {
                Log.e(TAG, "处理失败: ${e.message}")
                onResult("[]")
            }
        }
    }

    // 基于自相关的基频检测 (Autocorrelation)
    private fun detectPitch(audioBuffer: FloatArray): Double {
        val n = audioBuffer.size
        var sum = 0.0
        for (v in audioBuffer) sum += v * v
        if (sum < 0.01) return 0.0 // 静音

        var bestOffset = -1
        var bestCorrelation = 0.0
        val minOffset = SAMPLE_RATE / 2000 // 最高频率 2000Hz
        val maxOffset = SAMPLE_RATE / 50   // 最低频率 50Hz

        for (offset in minOffset until maxOffset) {
            var correlation = 0.0
            for (i in 0 until n - offset) {
                correlation += audioBuffer[i] * audioBuffer[i + offset]
            }
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestOffset = offset
            }
        }

        if (bestOffset > 0) return SAMPLE_RATE.toDouble() / bestOffset
        return 0.0
    }

    // 解码 MP3 到浮点数组
    private fun decodeToFloatArray(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrack = -1
        var inputFormat: MediaFormat? = null
        
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrack = i
                inputFormat = format
                break
            }
        }

        if (audioTrack < 0 || inputFormat == null) {
            extractor.release()
            return FloatArray(0)
        }

        extractor.selectTrack(audioTrack)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val pcmData = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(inBuf, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)!!
                outBuf.order(ByteOrder.nativeOrder())
                val shortBuf = outBuf.asShortBuffer()
                while (shortBuf.hasRemaining()) {
                    pcmData.add(shortBuf.get())
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isEOS = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return resample(pcmData.toShortArray(), sampleRate)
    }

    private fun resample(input: ShortArray, inputRate: Int): FloatArray {
        if (inputRate == SAMPLE_RATE) {
            return FloatArray(input.size) { input[it] / 32768.0f }
        }
        val ratio = SAMPLE_RATE.toFloat() / inputRate
        val outputLength = (input.size * ratio).roundToInt()
        val output = FloatArray(outputLength)
        for (i in 0 until outputLength) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            output[i] = if (srcIdx < input.size) (input[srcIdx] / 32768.0f) else 0f
        }
        return output
    }
}