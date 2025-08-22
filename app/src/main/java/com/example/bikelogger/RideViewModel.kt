
package com.example.bikelogger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bikelogger.tracking.LocationBus
import com.example.bikelogger.tracking.TrackPoint
import com.example.bikelogger.util.RideIndex
import com.example.bikelogger.util.WeatherClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class WindInfo(
    val speedKmh: Double = Double.NaN,
    val dirDegFrom: Int = -1, // SKĄD wieje (0=N, 90=E, ...)
    val gustKmh: Double = Double.NaN
)

data class UiState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val path: List<TrackPoint> = emptyList(),
    val speedKmh: Double = 0.0,
    val avgMovingSpeedKmh: Double = 0.0,
    val distanceKm: Double = 0.0,
    val elapsedText: String = "00:00:00",
    val elevGainM: Int = 0,
    val vMaxKmh: Double = 0.0,
    val startTimestampMs: Long = 0L,
    val lastSavedFileName: String = "",
    val wind: WindInfo = WindInfo()
)

class RideViewModel : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private val _rides = MutableStateFlow<List<RideIndex.Summary>>(emptyList())
    val rides = _rides.asStateFlow()

    private var ticker: Job? = null
    private var startMs: Long = 0L
    private var movingSeconds: Long = 0L
    private var lastTickMs: Long = 0L

    // auto-pauza
    private var belowLowSpeedSeconds = 0
    private var aboveResumeSeconds = 0
    private val pauseThresholdKmh = 1.0
    private val resumeThresholdKmh = 2.0
    private val pauseAfterS = 10
    private val resumeAfterS = 3

    // weather throttling
    private var lastWxFetchMs = 0L
    private var lastWxLat = 0.0
    private var lastWxLon = 0.0

    fun setLocationPermission(granted: Boolean) {
        _ui.value = _ui.value.copy(locationPermissionGranted = granted)
    }

    fun start() {
        if (_ui.value.isRecording) return
        startMs = System.currentTimeMillis()
        movingSeconds = 0
        lastTickMs = startMs
        belowLowSpeedSeconds = 0
        aboveResumeSeconds = 0

        _ui.value = UiState(
            isRecording = true,
            startTimestampMs = startMs
        )

        viewModelScope.launch {
            LocationBus.locations.collect { point -> accumulate(point) }
        }
        ticker = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val totalS = (now - startMs) / 1000
                val prev = _ui.value

                val deltaS = ((now - lastTickMs) / 1000).toInt().coerceAtLeast(0)
                lastTickMs = now
                if (prev.isRecording && !prev.isPaused) {
                    movingSeconds += deltaS
                }
                val avg = if (movingSeconds > 0) prev.distanceKm / (movingSeconds / 3600.0) else 0.0

                _ui.value = prev.copy(
                    elapsedText = formatHHMMSS(totalS),
                    avgMovingSpeedKmh = avg
                )
                delay(1000)
            }
        }
    }

    fun stop() {
        if (!_ui.value.isRecording) return
        ticker?.cancel()
        _ui.value = _ui.value.copy(isRecording = false, isPaused = false)
    }

    fun markLap() { /* opcjonalnie */ }

    fun onSavedAndIndex(ctx: Context, file: java.io.File?) {
        file ?: return
        _ui.value = _ui.value.copy(lastSavedFileName = file.name)
        val s = RideIndex.Summary.fromCurrent(
            filePath = file.absolutePath,
            startMs = _ui.value.startTimestampMs,
            distanceKm = _ui.value.distanceKm,
            movingSeconds = movingSeconds,
            avgMovingKmh = _ui.value.avgMovingSpeedKmh,
            vMaxKmh = _ui.value.vMaxKmh,
            elevGainM = _ui.value.elevGainM
        )
        RideIndex.append(ctx, s)
        loadRides(ctx)
    }

    fun loadRides(ctx: Context) {
        _rides.value = RideIndex.loadAll(ctx)
    }

    private fun accumulate(p: TrackPoint) {
        val prev = _ui.value

        // AUTO-PAUZA
        val spd = p.speedKmh
        var paused = prev.isPaused
        if (!paused) {
            if (spd < pauseThresholdKmh) {
                belowLowSpeedSeconds++
                if (belowLowSpeedSeconds >= pauseAfterS) {
                    paused = true
                    belowLowSpeedSeconds = 0
                    aboveResumeSeconds = 0
                }
            } else belowLowSpeedSeconds = 0
        } else {
            if (spd > resumeThresholdKmh) {
                aboveResumeSeconds++
                if (aboveResumeSeconds >= resumeAfterS) {
                    paused = false
                    aboveResumeSeconds = 0
                    belowLowSpeedSeconds = 0
                }
            } else aboveResumeSeconds = 0
        }

        val addedDist = if (prev.path.isNotEmpty()) prev.path.last().distanceTo(p) else 0.0
        val totalDist = prev.distanceKm + (if (!paused) addedDist else 0.0)
        val vMax = max(prev.vMaxKmh, spd)

        val elevGain = prev.elevGainM + run {
            if (prev.path.isNotEmpty()) {
                val dz = p.altitude - prev.path.last().altitude
                if (dz > 0 && dz <= 5.0) dz.toInt() else 0
            } else 0
        }

        _ui.value = prev.copy(
            path = prev.path + p,
            distanceKm = totalDist,
            speedKmh = spd,
            vMaxKmh = vMax,
            elevGainM = elevGain,
            isPaused = paused
        )

        // Fetch weather (throttled)
        viewModelScope.launch {
            maybeFetchWind(p.latitude, p.longitude)
        }
    }

    private suspend fun maybeFetchWind(lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val timeOk = now - lastWxFetchMs > 5 * 60_000 // 5 min
        val distOk = distanceKm(lastWxLat, lastWxLon, lat, lon) > 0.5 // > 500 m
        if (lastWxFetchMs == 0L || timeOk || distOk) {
            runCatching {
                val w = WeatherClient.fetchWind(lat, lon)
                val uiPrev = _ui.value
                _ui.value = uiPrev.copy(
                    wind = WindInfo(
                        speedKmh = w.speedKmh,
                        dirDegFrom = w.dirDegFrom,
                        gustKmh = w.gustKmh ?: Double.NaN
                    )
                )
                lastWxFetchMs = now
                lastWxLat = lat; lastWxLon = lon
            }
        }
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lastWxFetchMs == 0L) return Double.MAX_VALUE
        val R = 6371e3
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2)*sin(dLat/2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon/2)*sin(dLon/2)
        val c = 2 * atan2(sqrt(a), sqrt(1-a))
        return (R * c) / 1000.0
    }

    private fun formatHHMMSS(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
