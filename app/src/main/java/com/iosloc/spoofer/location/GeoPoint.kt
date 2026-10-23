package com.iosloc.spoofer.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val name: String = ""
) {
    companion object {
        private const val EARTH_R = 6371000.0

        /** Great-circle distance in meters between two points. */
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val la1 = Math.toRadians(a.lat)
            val la2 = Math.toRadians(b.lat)
            val h = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLon / 2).pow(2)
            return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
        }
    }

    /** Linear interpolation (accurate enough over route segments). */
    fun interpolate(other: GeoPoint, fraction: Double): GeoPoint {
        val f = fraction.coerceIn(0.0, 1.0)
        return GeoPoint(
            lat = lat + (other.lat - lat) * f,
            lon = lon + (other.lon - lon) * f,
            name = ""
        )
    }
}
