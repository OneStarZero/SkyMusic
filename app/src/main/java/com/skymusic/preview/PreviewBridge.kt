package com.skymusic.preview

import org.json.JSONObject

/**
 * 试听桥接层：JSON 解析 + 转发给 MidiPreviewPlayer
 */
object PreviewBridge {

    fun play(payloadJson: String): Boolean {
        return try {
            val root = JSONObject(payloadJson)
            val arr = root.optJSONArray("notes") ?: return false
            val totalMs = root.optLong("totalMs", 0L)
            if (totalMs <= 0) return false

            val list = ArrayList<MidiSynth.Note>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    MidiSynth.Note(
                        midi = o.optInt("midi", 60),
                        startMs = o.optLong("startMs", 0L),
                        durationMs = o.optLong("durationMs", 200L),
                        velocity = o.optDouble("velocity", 0.8).toFloat()
                    )
                )
            }
            if (list.isEmpty()) return false
            MidiPreviewPlayer.play(list, totalMs)
        } catch (t: Throwable) {
            false
        }
    }

    fun pause() = MidiPreviewPlayer.pause()
    fun resume() = MidiPreviewPlayer.resume()
    fun stop() = MidiPreviewPlayer.stop()
    fun seek(ms: Long) = MidiPreviewPlayer.seek(ms)
    fun isPlaying(): Boolean = MidiPreviewPlayer.isPlaying()
    fun isPaused(): Boolean = MidiPreviewPlayer.isPaused()
    fun isActive(): Boolean = MidiPreviewPlayer.isActive()

    fun setOnStateChange(cb: ((String, Long, Long) -> Unit)?) {
        MidiPreviewPlayer.onStateChange = cb
    }
}
