
package com.example.bikelogger.tracking

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LocationBus {
    private val _locations = MutableSharedFlow<TrackPoint>(replay = 0, extraBufferCapacity = 64)
    val locations = _locations.asSharedFlow()
    suspend fun emit(p: TrackPoint) = _locations.emit(p)
}
