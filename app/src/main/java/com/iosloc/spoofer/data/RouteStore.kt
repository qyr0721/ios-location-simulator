package com.iosloc.spoofer.data

import android.content.Context
import com.iosloc.spoofer.location.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class SavedRoute(
    val id: String,
    val name: String,
    val points: List<GeoPoint>,
    val createdAt: Long
)

data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val createdAt: Long
)

data class MapConfig(
    val provider: String,
    val urlTemplate: String,
    val apiKey: String,
    val attribution: String
) {
    fun effectiveUrl(): String {
        if (provider == PROVIDER_OSM || urlTemplate.isBlank()) return OSM_URL
        val key = URLEncoder.encode(apiKey, "UTF-8")
        return urlTemplate.replace("{key}", key)
    }

    fun effectiveAttribution(): String =
        if (provider == PROVIDER_OSM || attribution.isBlank()) OSM_ATTRIBUTION else attribution

    companion object {
        const val PROVIDER_OSM = "osm"
        const val PROVIDER_CUSTOM = "custom"
        const val OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val OSM_ATTRIBUTION = "&copy; OpenStreetMap contributors"

        val DEFAULT = MapConfig(PROVIDER_OSM, OSM_URL, "", OSM_ATTRIBUTION)
    }
}

object RouteStore {

    private const val PREFS = "iosloc_store"
    private const val KEY_ROUTES = "routes"
    private const val KEY_PLACES = "places"
    private const val KEY_MAP = "map_config"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- routes -----------------------------------------------------------

    fun routes(context: Context): List<SavedRoute> {
        val raw = prefs(context).getString(KEY_ROUTES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val pts = o.optJSONArray("points") ?: JSONArray()
                val points = (0 until pts.length()).mapNotNull { j ->
                    val p = pts.optJSONObject(j) ?: return@mapNotNull null
                    GeoPoint(p.optDouble("lat"), p.optDouble("lon"))
                }
                SavedRoute(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    points = points,
                    createdAt = o.optLong("createdAt")
                )
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun saveRoute(context: Context, name: String, points: List<GeoPoint>): SavedRoute {
        val route = SavedRoute(
            id = UUID.randomUUID().toString(),
            name = name,
            points = points.toList(),
            createdAt = System.currentTimeMillis()
        )
        val array = JSONArray()
        routes(context).forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("createdAt", r.createdAt)
                put("points", JSONArray().apply {
                    r.points.forEach { p ->
                        put(JSONObject().apply {
                            put("lat", p.lat)
                            put("lon", p.lon)
                        })
                    }
                })
            })
        }
        array.put(JSONObject().apply {
            put("id", route.id)
            put("name", route.name)
            put("createdAt", route.createdAt)
            put("points", JSONArray().apply {
                route.points.forEach { p ->
                    put(JSONObject().apply {
                        put("lat", p.lat)
                        put("lon", p.lon)
                    })
                }
            })
        })
        prefs(context).edit().putString(KEY_ROUTES, array.toString()).apply()
        return route
    }

    fun deleteRoute(context: Context, id: String) {
        val array = JSONArray()
        routes(context).filter { it.id != id }.forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("createdAt", r.createdAt)
                put("points", JSONArray().apply {
                    r.points.forEach { p ->
                        put(JSONObject().apply {
                            put("lat", p.lat)
                            put("lon", p.lon)
                        })
                    }
                })
            })
        }
        prefs(context).edit().putString(KEY_ROUTES, array.toString()).apply()
    }

    // ---- favorite places --------------------------------------------------

    fun places(context: Context): List<SavedPlace> {
        val raw = prefs(context).getString(KEY_PLACES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                SavedPlace(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    lat = o.optDouble("lat"),
                    lon = o.optDouble("lon"),
                    createdAt = o.optLong("createdAt")
                )
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun savePlace(context: Context, name: String, lat: Double, lon: Double): SavedPlace {
        val place = SavedPlace(
            id = UUID.randomUUID().toString(),
            name = name,
            lat = lat,
            lon = lon,
            createdAt = System.currentTimeMillis()
        )
        val array = JSONArray()
        places(context).forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("lat", p.lat)
                put("lon", p.lon)
                put("createdAt", p.createdAt)
            })
        }
        array.put(JSONObject().apply {
            put("id", place.id)
            put("name", place.name)
            put("lat", place.lat)
            put("lon", place.lon)
            put("createdAt", place.createdAt)
        })
        prefs(context).edit().putString(KEY_PLACES, array.toString()).apply()
        return place
    }

    fun deletePlace(context: Context, id: String) {
        val array = JSONArray()
        places(context).filter { it.id != id }.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("lat", p.lat)
                put("lon", p.lon)
                put("createdAt", p.createdAt)
            })
        }
        prefs(context).edit().putString(KEY_PLACES, array.toString()).apply()
    }

    // ---- map provider -----------------------------------------------------

    fun mapConfig(context: Context): MapConfig {
        val raw = prefs(context).getString(KEY_MAP, null) ?: return MapConfig.DEFAULT
        return runCatching {
            val o = JSONObject(raw)
            MapConfig(
                provider = o.optString("provider", MapConfig.PROVIDER_OSM),
                urlTemplate = o.optString("urlTemplate", MapConfig.OSM_URL),
                apiKey = o.optString("apiKey", ""),
                attribution = o.optString("attribution", MapConfig.OSM_ATTRIBUTION)
            )
        }.getOrDefault(MapConfig.DEFAULT)
    }

    fun saveMapConfig(context: Context, config: MapConfig) {
        val o = JSONObject().apply {
            put("provider", config.provider)
            put("urlTemplate", config.urlTemplate)
            put("apiKey", config.apiKey)
            put("attribution", config.attribution)
        }
        prefs(context).edit().putString(KEY_MAP, o.toString()).apply()
    }
}
