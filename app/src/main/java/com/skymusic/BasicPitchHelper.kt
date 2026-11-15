package com.skymusic

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class BasicPitchHelper(private val context: Context) {
    private val TAG = "BasicPitchHelper"

    fun processAudio(uri: Uri, onResult: (String) -> Unit) {
        thread {
            try {
                // 模拟 2 秒的延迟，假装在跑 AI
                Thread.sleep(2000)

                // 🌟 模拟生成的音符数据 (时间: 秒, MIDI 音高)
                val mockNotes = listOf(
                    Pair(0.5, 60),  // C4
                    Pair(1.0, 62),  // D4
                    Pair(1.5, 64),  // E4
                    Pair(2.0, 65),  // F4
                    Pair(2.5, 67),  // G4
                )

                val notesList = mutableListOf<JSONObject>()
                mockNotes.forEach { (time, midi) ->
                    val note = JSONObject().apply {
                        put("time", time)
                        put("midi", midi)
                        put("velocity", 0.8)
                    }
                    notesList.add(note)
                }

                val resultJson = JSONArray(notesList).toString()
                onResult(resultJson)

            } catch (e: Exception) {
                Log.e(TAG, "处理失败: ${e.message}")
                onResult("[]")
            }
        }
    }
}