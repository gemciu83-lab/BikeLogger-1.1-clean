
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
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "ride_${sdf.format(Date(startMs))}.gpx"
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
        val file = File(dir, name)
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<gpx version=\"1.1\" creator=\"BikeLogger\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            append("<trk><name>$name</name><trkseg>")
            points.forEach {
                val t = Date(it.timestampMs).toInstant().toString()
                append("<trkpt lat=\"${it.latitude}\" lon=\"${it.longitude}\">")
                append("<ele>${"%.1f".format(it.altitude)}</ele>")
                append("<time>$t</time>")
                append("</trkpt>")
            }
            append("</trkseg></trk></gpx>")
        }
        FileOutputStream(file).use { it.write(xml.toByteArray()) }
        return file
    }
}
