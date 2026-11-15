package com.skymusic

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class MidiPreviewPlayer(private val context: Context) {
    private var player: ExoPlayer? = null

    fun play(filePath: String) {
        stop()
        val file = File(filePath)
        if (!file.exists()) return

        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            prepare()
            playWhenReady = true
        }
    }

    fun stop() {
        player?.release()
        player = null
    }
}