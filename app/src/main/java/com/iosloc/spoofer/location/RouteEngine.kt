package com.iosloc.spoofer.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Plays a polyline at a constant speed by interpolating between waypoints.
 *
 * [sink] is the go-ios DVT session; it is called on every tick with the
 * interpolated position (not only the waypoints), so the iPhone moves smoothly.
 */
class RouteEngine(
    private val sink: (GeoPoint) -> Unit,
    private val onTick: (GeoPoint, Double) -> Unit = { _, _ -> },
    private val onFinish: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) {
    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun start(
        scope: CoroutineScope,
        points: List<GeoPoint>,
        speedKmh: Double,
        tickMs: Long,
        loop: Boolean
    ) {
        require(points.size >= 2) { "路线至少需要 2 个点" }
        stop()
        val snapshot = points.toList()
        job = scope.launch(Dispatchers.Default) {
            try {
                play(snapshot, max(speedKmh, 0.5), tickMs.coerceIn(200L, 5000L), loop)
            } catch (t: Throwable) {
                if (isActive) onError(t)
            }
        }
    }

    private suspend fun play(
        points: List<GeoPoint>,
        speedKmh: Double,
        tickMs: Long,
        loop: Boolean
    ) {
        val segments = (0 until points.size - 1).map {
            GeoPoint.distanceMeters(points[it], points[it + 1])
        }
        val total = segments.sum()
        if (total < 1.0) {
            sink(points.first())
            onTick(points.first(), 1.0)
            onFinish()
            return
        }

        val speedMps = speedKmh / 3.6
        var traveled = 0.0
        var last = System.currentTimeMillis()
        sink(points.first())
        onTick(points.first(), 0.0)

        while (currentCoroutineContext().isActive) {
            delay(tickMs)
            val now = System.currentTimeMillis()
            val dt = ((now - last) / 1000.0).coerceIn(0.0, 5.0)
            last = now
            traveled += speedMps * dt

            if (traveled >= total) {
                if (!loop) {
                    val end = points.last()
                    sink(end)
                    onTick(end, 1.0)
                    onFinish()
                    return
                }
                traveled %= total
            }

            var index = 0
            var remain = traveled
            while (index < segments.size - 1 && remain > segments[index]) {
                remain -= segments[index]
                index++
            }
            val segLen = segments[index]
            val fraction = if (segLen <= 0.0) 0.0 else (remain / segLen).coerceIn(0.0, 1.0)
            val p = points[index].interpolate(points[index + 1], fraction)
            sink(p)
            onTick(p, (traveled / total).coerceIn(0.0, 1.0))
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
