package com.apparentsolar.time

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** 定位来源：GPS / 网络 / 融合（系统提供，无 Google Play 依赖）。 */
class LocationEngine(context: Context) {

    interface Callback {
        fun onLocation(location: LocationStore.SavedLocation)
    }

    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val listener = LocationListener { location -> onFix(location) }

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(): LocationStore.SavedLocation? {
        if (!hasPermission()) return null
        var best: Location? = null
        for (provider in PROVIDERS) {
            try {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || l.accuracy < best!!.accuracy) best = l
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return best?.toSaved()
    }

    fun startContinuous(callback: Callback) {
        this.callback = callback
        if (!hasPermission()) return
        lastKnown()?.let { callback.onLocation(it) }
        for (provider in PROVIDERS) {
            try {
                lm.requestLocationUpdates(provider, 5_000L, 10f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun stop() {
        callback = null
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }

    private var callback: Callback? = null

    private fun onFix(location: Location) {
        val saved = location.toSaved()
        val current = LocationStore.load(app)
        // 只在精度更好或旧数据过旧时落盘；手动输入（accuracy=0）不会被更差的 GPS 覆盖
        val stale = System.currentTimeMillis() - (current?.timeMs ?: 0) > 10 * 60_000L
        val better = current == null || saved.accuracyMeters <= current.accuracyMeters + 5f
        if (stale || better) {
            LocationStore.save(app, saved)
            callback?.onLocation(saved)
        }
    }

    private fun Location.toSaved() = LocationStore.SavedLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        provider = provider ?: "?",
        timeMs = time,
    )

    private companion object {
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
