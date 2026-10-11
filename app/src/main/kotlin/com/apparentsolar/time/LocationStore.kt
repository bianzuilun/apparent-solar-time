package com.apparentsolar.time

import android.content.Context

/** 保存最后一次可用定位。真太阳时对经度极不敏感（10 米 ≈ 0.002 秒），无需频繁刷新。 */
object LocationStore {

    data class SavedLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val provider: String,
        val timeMs: Long,
    )

    private const val PREFS = "solar_prefs"

    fun save(context: Context, location: SavedLocation) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("lat", location.latitude.toRawBits())
            .putLong("lon", location.longitude.toRawBits())
            .putFloat("acc", location.accuracyMeters)
            .putString("provider", location.provider)
            .putLong("time", location.timeMs)
            .apply()
    }

    fun load(context: Context): SavedLocation? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getLong("lat", Long.MIN_VALUE)
        val lon = p.getLong("lon", Long.MIN_VALUE)
        if (lat == Long.MIN_VALUE || lon == Long.MIN_VALUE) return null
        return SavedLocation(
            latitude = Double.fromBits(lat),
            longitude = Double.fromBits(lon),
            accuracyMeters = p.getFloat("acc", 0f),
            provider = p.getString("provider", "?") ?: "?",
            timeMs = p.getLong("time", 0L),
        )
    }
}
