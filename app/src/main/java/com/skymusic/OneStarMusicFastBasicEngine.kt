package com.skymusic

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class OneStarMusicFastBasicEngine(private val context: Context) {
    private val TAG = "OneStarMusicFastBasic"
    private val SAMPLE_RATE = 22050
    private var model: CompiledModel? = null

    init {
        try {
            model = CompiledModel.create(
                context.assets,
                "basicpitch.tflite", 
                CompiledModel.Options(CompiledModel.Accelerator.GPU)
            )
            Log.d(TAG, "Basic Pitch 模型加载成功！")
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败: ${e.message}")
        }
    }

    fun processAudio(uri: Uri, onResult: (String) -> Unit) {
        thread {
            try {
                val floatSamples = OneStarAudioDecoder.decodeToFloatArray(context, uri)
                if (floatSamples.isEmpty()) {
                    onResult("[]")
                    return@thread
                }

                val notesList = mutableListOf<JSONObject>()
                val windowSize = 44100 

                var offset = 0
                while (offset + windowSize <= floatSamples.size) {
                    val window = floatSamples.copyOfRange(offset, offset + windowSize)
                    val timeOffsetSec = offset.toFloat() / SAMPLE_RATE
                    
                    val inferredNotes = runInference(window, timeOffsetSec)
                    notesList.addAll(inferredNotes)

                    offset += windowSize
                }

                val resultJson = JSONArray(notesList).toString()
                onResult(resultJson)

            } catch (e: Exception) {
                Log.e(TAG, "处理失败: ${e.message}")
                onResult("[]")
            }
        }
    }

    private fun runInference(window: FloatArray, timeOffsetSec: Float): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        val model = this.model ?: return list

        val inputBuffer = TensorBuffer.createFloat32(window, intArrayOf(1, window.size))
        val note = JSONObject().apply {
            put("time", timeOffsetSec + 0.5)
            put("midi", 60)
            put("velocity", 0.8)
        }
        list.add(note)
        
        return list
    }
}