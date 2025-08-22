
package com.example.bikelogger.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object RideIndex {

    data class Summary(
        val title: String,
        val filePath: String,
        val distanceKm: Double,
        val durationText: String,
        val avgMovingKmh: Double,
        val vMaxKmh: Double,
        val elevGainM: Int,
        val dateText: String
    ) {
        companion object {
            fun fromCurrent(
                filePath: String,
                startMs: Long,
                distanceKm: Double,
                movingSeconds: Long,
                avgMovingKmh: Double,
                vMaxKmh: Double,
                elevGainM: Int
            ): Summary {
                val sdfName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val title = "Przejazd ${sdfName.format(Date(startMs))}"
                val durationText = formatHHMMSS(movingSeconds)
                val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(startMs))
                return Summary(
                    title = title,
                    filePath = filePath,
                    distanceKm = distanceKm,
                    durationText = durationText,
                    avgMovingKmh = avgMovingKmh,
                    vMaxKmh = vMaxKmh,
                    elevGainM = elevGainM,
                    dateText = dateText
                )
            }

            private fun formatHHMMSS(seconds: Long): String {
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                val s = seconds % 60
                return "%02d:%02d:%02d".format(h, m, s)
            }
        }
    }

    private fun dir(ctx: Context): File =
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir

    fun append(ctx: Context, summary: Summary) {
        val f = File(dir(ctx), summaryFileName(summary.filePath))
        f.writeText(serialize(summary))
    }

    fun loadAll(ctx: Context): List<Summary> {
        val d = dir(ctx)
        val items = d.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        return items.mapNotNull { runCatching { deserialize(it.readText()) }.getOrNull() }
            .sortedByDescending { it.dateText }
    }

    private fun summaryFileName(gpxPath: String): String {
        val base = File(gpxPath).nameWithoutExtension
        return "$base.json"
    }

    private fun serialize(s: Summary): String = buildString {
        appendLine("title=${s.title}")
        appendLine("filePath=${s.filePath}")
        appendLine("distanceKm=${"%.3f".format(s.distanceKm)}")
        appendLine("durationText=${s.durationText}")
        appendLine("avgMovingKmh=${"%.2f".format(s.avgMovingKmh)}")
        appendLine("vMaxKmh=${"%.2f".format(s.vMaxKmh)}")
        appendLine("elevGainM=${s.elevGainM}")
        appendLine("dateText=${s.dateText}")
    }

    private fun deserialize(t: String): Summary {
        val map = t.lines().filter { it.contains("=") }.associate {
            val i = it.indexOf("=")
            it.substring(0, i) to it.substring(i + 1)
        }
        return Summary(
            title = map["title"] ?: "Przejazd",
            filePath = map["filePath"] ?: "",
            distanceKm = (map["distanceKm"] ?: "0").toDouble(),
            durationText = map["durationText"] ?: "00:00:00",
            avgMovingKmh = (map["avgMovingKmh"] ?: "0").toDouble(),
            vMaxKmh = (map["vMaxKmh"] ?: "0").toDouble(),
            elevGainM = (map["elevGainM"] ?: "0").toInt(),
            dateText = map["dateText"] ?: ""
        )
    }
}
