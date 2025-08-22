
package com.example.bikelogger.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WeatherClient {
    data class Wind(val speedKmh: Double, val dirDegFrom: Int, val gustKmh: Double?)

    @Throws(Exception::class)
    fun fetchWind(lat: Double, lon: Double): Wind {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${lat}&longitude=${lon}" +
            "&current=wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
            "&windspeed_unit=kmh&timezone=auto"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        conn.inputStream.use { ins ->
            val txt = ins.bufferedReader().readText()
            val cur = JSONObject(txt).getJSONObject("current")
            val spd = cur.optDouble("wind_speed_10m", Double.NaN)
            val dir = cur.optInt("wind_direction_10m", -1)
            val gust = if (cur.has("wind_gusts_10m")) cur.optDouble("wind_gusts_10m", Double.NaN) else null
            return Wind(spd, dir, if (gust?.isNaN() == true) null else gust)
        }
    }
}
