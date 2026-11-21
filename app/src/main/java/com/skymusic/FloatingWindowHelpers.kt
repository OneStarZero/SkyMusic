package com.skymusic

import java.io.File
import java.util.Locale

internal object FloatingWindowHelpers {

    fun formatDurationMs(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000L
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60L, totalSec % 60L)
    }

    fun calculateDuration(notes: List<Pair<Long, Int>>): String {
        if (notes.isEmpty()) return "0:00"
        return formatDurationMs(notes.last().first)
    }

    fun buildSheetCacheKey(path: String, keyMode: Int): String = "$path#$keyMode"

    fun isFileMetaUnchanged(cached: FileMetaCacheEntry?, file: File): Boolean {
        if (cached == null || !file.exists()) return false
        return cached.sizeBytes == file.length() && cached.lastModified == file.lastModified()
    }

    fun roundSpeed(speed: Float): Float =
        (Math.round(speed.coerceIn(0.5f, 5f) * 10f) / 10f)

    fun formatSpeed(speed: Float): String =
        String.format(Locale.getDefault(), "%.1fx", speed)

    fun readCleanFileText(file: File): String {
        var text = file.readText(Charsets.UTF_8)
        if (text.contains("\u0000")) {
            text = file.readText(Charsets.UTF_16LE)
        }
        return text.replace("\u0000", "").replace("\uFEFF", "")
    }

    fun readLocalFileInfo(file: File): List<String> {
        return try {
            val content = readCleanFileText(file)
            var name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringBefore("-")
            var author = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
            val bpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
            var model = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: ""
            if (model.isBlank() && name.contains(" || ")) {
                model = name.substringAfter(" || ")
            }
            if (author.isBlank()) author = "未知作者"
            if (name.isBlank()) name = file.nameWithoutExtension

            val timeMatches = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content).toList()
            val notesCount = timeMatches.size
            val duration = if (notesCount > 0) {
                val lastMs = timeMatches.last().groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
                formatDurationMs(lastMs)
            } else {
                "0:00"
            }
            listOf(name, author, duration, notesCount.toString(), "", bpm, model)
        } catch (_: Exception) {
            listOf(file.nameWithoutExtension, "未知作者", "0:00", "0", "", "0", "")
        }
    }

    fun parseLocalSheet(file: File): ParsedLocalSheet? {
        return try {
            val content = readCleanFileText(file)
            val times = Regex("\"time\"\\s*:\\s*(\\d+)").findAll(content)
                .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
                .toList()
            val keys = Regex("\"key\"\\s*:\\s*\"1Key(\\d+)\"").findAll(content)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()
            if (times.isEmpty() || keys.isEmpty()) return null

            var name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringBefore("-")
            var author = Regex("\"author\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1)
                ?: file.nameWithoutExtension.substringAfter("-", "未知作者")
            val bpm = Regex("\"bpm\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: "0"
            var model = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"").find(content)?.groupValues?.getOrNull(1) ?: ""
            if (model.isBlank() && name.contains(" || ")) {
                model = name.substringAfter(" || ")
            }
            if (author.isBlank()) author = "未知作者"
            if (name.isBlank()) name = file.nameWithoutExtension

            val notes = times.indices
                .mapNotNull { i -> if (i < keys.size) Pair(times[i], keys[i]) else null }
                .sortedBy { it.first }
            if (notes.isEmpty()) return null

            ParsedLocalSheet(
                songName = name,
                author = author,
                duration = calculateDuration(notes),
                bpm = bpm,
                model = model,
                notes = notes
            )
        } catch (_: Exception) {
            null
        }
    }
}
