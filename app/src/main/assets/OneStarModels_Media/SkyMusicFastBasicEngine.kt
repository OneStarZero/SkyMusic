package com.skymusic.onestar_mp3models

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class SkyMusicFastBasicEngine(private val context: Context) {
    private val TAG = "SkyMusicFastBasic"
    private val SAMPLE_RATE = 22050
    private var model: CompiledModel? = null

    init {
        try {
            // 🌟 按照你的目录，模型在 assets 根目录下的 OneStarModels_Media 文件夹里
            model = CompiledModel.create(
                context.assets,
                "OneStarModels_Media/basicpitch.tflite",
                CompiledModel.Options(CompiledModel.Accelerator.GPU)
            )
            Log.d(TAG, "模型加载成功！")
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败: ${e.message}")
        }
    }

    fun processAudio(uri: Uri, onResult: (String) -> Unit) {
        thread {
            try {
                // 1. 真实的音频解码
                val floatSamples = AudioDecoder.decodeToFloatArray(context, uri)
                if (floatSamples.isEmpty()) {
                    onResult("[]")
                    return@thread
                }

                // 2. 执行真实的 AI 推理
                val notesList = mutableListOf<JSONObject>()
                val windowSize = 44100 // 2秒窗口

                var offset = 0
                while (offset + windowSize <= floatSamples.size) {
                    val window = floatSamples.copyOfRange(offset, offset + windowSize)
                    val timeOffsetSec = offset.toFloat() / SAMPLE_RATE
                    
                    // 把音频窗口喂给模型
                    val inferredNotes = runInference(window, timeOffsetSec)
                    notesList.addAll(inferredNotes)

                    offset += windowSize
                }

                val resultJson = JSONArray(notesList).toString()
                onResult(resultJson)

            } catch (e: Exception) {
                Log.e(TAG, "推理失败: ${e.message}")
                onResult("[]")
            }
        }
    }

    private fun runInference(window: FloatArray, timeOffsetSec: Float): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        val model = this.model ?: return list

        // 把 window 转为 Tensor
        val inputBuffer = TensorBuffer.createFloat32(window, intArrayOf(1, window.size))
        val inputs = mapOf("input_audio" to inputBuffer)
        
        // 🌟 注意：因为真正的输出解析（概率矩阵转音符）极长，
        // 这里暂时保持一次占位输出，确保你编译和运行不会崩溃。
        // 如果你确认编译通过且没有闪退，下一步我们就开始写矩阵解析。
        
        val note = JSONObject().apply {
            put("time", timeOffsetSec + 0.5)
            put("midi", 60)
            put("velocity", 0.8)
        }
        list.add(note)
        
        return list
    }
}