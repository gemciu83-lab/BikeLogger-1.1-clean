package com.example.bikelogger.util

import android.content.Context
import android.os.Environment
import com.example.bikelogger.tracking.TrackPoint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object GpxWriter {
    fun saveGpx(
        ctx: Context,
        points: List<TrackPoint>,
        startMs: Long,
        endMs: Long
    ): File? {
        if (points.isEmpty()) return null

        val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "ride_${nameFmt.format(Date(startMs))}.gpx"

        // Formatter ISO 8601 w UTC (GPX oczekuje czasu w ISO)
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
        val file = File(dir, name)

        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<gpx version="1.1" creator="BikeLogger" xmlns="http://www.topografix.com/GPX/1/1">""")
            append("<trk><name>$name</name><trkseg>")
            points.forEach { p ->
                val t = isoFmt.format(Date(p.timestampMs))
                append("""<trkpt lat="${p.latitude}" lon="${p.longitude}">""")
                append("""<ele>${"%.1f".format(p.altitude)}</ele>""")
                append("<time>$t</time>")
                append("</trkpt>")
            }
            append("</trkseg></trk></gpx>")
        }

        FileOutputStream(file).use { it.write(xml.toByteArray()) }
        return file
    }
}
