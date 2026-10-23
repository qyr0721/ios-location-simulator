package com.iosloc.spoofer.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Plays a route of [GeoPoint]s against [LocationController].
 *
 * Speed mode: walks the polyline at [speedKmh] using great-circle segment
 * distances, emitting a position every [tickMs]. The device therefore sees a
 * smoothly moving position instead of teleporting waypoint to waypoint.
 *
 * Waypoint mode: jumps to the next waypoint every [tickMs] (GPX-style).
 */
class RoutePlayer(
    private val scope: CoroutineScope,
    private val controller: LocationController,
    private val onPoint: (GeoPoint) -> Unit,
    private val onFinish: () -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private var job: Job? = null

    fun playSpeed(
        points: List<GeoPoint>,
        speedKmh: Double,
        tickMs: Long = 1000,
        loop: Boolean = false
    ) = start(points, tickMs, loop) {
        val speedMps = speedKmh / 3.6
        var segment = 0
        var traveled = 0.0
        var last = System.currentTimeMillis()
        var pos = points[0]

        while (true) {
            if (segment >= points.size - 1) {
                if (loop) {
                    segment = 0; traveled = 0.0; pos = points[0]
                } else {
                    controller.set(points.last().lat, points.last().lon)
                    onPoint(points.last())
                    break
                }
            }
            val a = points[segment]
            val b = points[segment + 1]
            val segLen = GeoPoint.distanceMeters(a, b)
            if (segLen < 1.0) {
                segment++
                traveled = 0.0
                continue
            }
            val now = System.currentTimeMillis()
            val dt = (now - last) / 1000.0
            last = now
            traveled += speedMps * dt
            if (traveled >= segLen) {
                traveled -= segLen
                segment++
                pos = b
            } else {
                pos = a.interpolate(b, traveled / segLen)
            }
            controller.set(pos.lat, pos.lon)
            onPoint(pos)
            delay(tickMs)
        }
    }

    fun playWaypoints(points: List<GeoPoint>, intervalMs: Long, loop: Boolean = false) =
        start(points, intervalMs, loop) {
            var i = 0
            while (true) {
                val p = points[i]
                controller.set(p.lat, p.lon)
                onPoint(p)
                i++
                if (i >= points.size) {
                    if (loop) i = 0 else break
                }
                delay(intervalMs)
            }
        }

    private fun start(points: List<GeoPoint>, tickMs: Long, loop: Boolean, body: suspend () -> Unit) {
        require(points.isNotEmpty()) { "路线至少需要一个点" }
        stop()
        job = scope.launch(Dispatchers.IO) {
            try {
                if (points.size == 1) {
                    val p = points[0]
                    controller.set(p.lat, p.lon)
                    onPoint(p)
                } else {
                    body()
                }
                if (isActive && !loop) onFinish()
            } catch (t: Throwable) {
                if (isActive) onError(t)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
