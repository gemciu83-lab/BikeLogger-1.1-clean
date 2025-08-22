
package com.example.bikelogger.tracking

import org.osmdroid.util.GeoPoint
import kotlin.math.*

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestampMs: Long,
    val speedKmh: Double
) {
    val geoPoint: GeoPoint get() = GeoPoint(latitude, longitude)

    fun distanceTo(other: TrackPoint): Double {
        val R = 6371e3
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat/2)*sin(dLat/2) +
                cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
                sin(dLon/2)*sin(dLon/2)
        val c = 2 * atan2(sqrt(a), sqrt(1-a))
        return (R * c) / 1000.0
    }
}
