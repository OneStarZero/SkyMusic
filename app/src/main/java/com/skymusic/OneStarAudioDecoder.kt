package com.skymusic

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.roundToInt

object OneStarAudioDecoder {
    private const val TARGET_SAMPLE_RATE = 22050

    fun decodeToFloatArray(context: Context, uri: Uri): FloatArray {
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
        if (inputRate == TARGET_SAMPLE_RATE) {
            return FloatArray(input.size) { input[it] / 32768.0f }
        }
        val ratio = TARGET_SAMPLE_RATE.toFloat() / inputRate
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