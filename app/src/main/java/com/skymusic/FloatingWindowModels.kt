package com.skymusic

import java.io.File

internal data class LoadedSheetEntry(
    val cacheKey: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val songName: String,
    val author: String,
    val duration: String,
    val notesCount: Int,
    val bpm: String,
    val model: String,
    val notes: List<Pair<Long, Int>>
)

internal data class FileMetaCacheEntry(
    val sizeBytes: Long,
    val lastModified: Long,
    val info: List<String>
)

internal data class ParsedLocalSheet(
    val songName: String,
    val author: String,
    val duration: String,
    val bpm: String,
    val model: String,
    val notes: List<Pair<Long, Int>>
)

internal data class ScannedSongItem(
    val file: File,
    val info: List<String>,
    val tag: String = ""
)

internal object FloatingWindowConstants {
    const val CHANNEL_ID = "skymusic_float_channel"
    const val NOTIF_ID = 10086
    const val BASE_PATH = "/storage/emulated/0/SkyMusic-OneStar"
    const val APP_SHEETS_PATH = "$BASE_PATH/AppSheets"
    const val LOCAL_SHEETS_PATH = "$BASE_PATH/LocalSheets"
    const val MAX_CACHED_SHEETS = 8
    const val PREFS_NAME = "SkyMusicPrefs"
}
