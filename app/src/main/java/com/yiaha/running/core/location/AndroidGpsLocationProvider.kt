package com.yiaha.running.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.yiaha.running.core.model.LocationSource
import com.yiaha.running.core.model.RunPoint

class AndroidGpsLocationProvider(
    private val context: Context,
    private val minTimeMillis: Long = 1_000L,
    private val minDistanceMeters: Float = 1f
) : RunLocationProvider {

    private val tag = "RunLocationProvider"

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var listener: LocationListener? = null
    private val activeProviders = mutableSetOf<String>()

    override fun start(onPoint: (RunPoint) -> Unit, onError: (Throwable) -> Unit) {
        if (!hasFineLocationPermission()) {
            onError(SecurityException("ACCESS_FINE_LOCATION is required for GPS tracking"))
            return
        }

        if (!isSystemLocationEnabled()) {
            onError(IllegalStateException("System location is disabled"))
            return
        }

        stop()

        val nextListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(
                    tag,
                    "gps point lat=${location.latitude}, lon=${location.longitude}, " +
                        "acc=${location.accuracy}, speed=${location.speed}"
                )
                onPoint(location.toRunPoint())
            }

            @Deprecated("Deprecated by Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                Log.w(tag, "$provider disabled")
                activeProviders.remove(provider)
                if (activeProviders.isEmpty()) {
                    onError(IllegalStateException("All location providers are disabled"))
                }
            }
        }

        listener = nextListener

        try {
            val providers = chooseEnabledProviders()
            providers.forEach { provider ->
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        minTimeMillis,
                        minDistanceMeters,
                        nextListener,
                        Looper.getMainLooper()
                    )
                    activeProviders += provider
                }.onFailure {
                    Log.w(tag, "Failed to subscribe provider=$provider", it)
                }
            }
            if (activeProviders.isEmpty()) {
                throw IllegalStateException("No available location provider")
            }
            Log.i(tag, "Location updates started, providers=$activeProviders")
        } catch (throwable: Throwable) {
            listener = null
            activeProviders.clear()
            Log.e(tag, "Failed to start GPS updates", throwable)
            onError(throwable)
        }
    }

    override fun stop() {
        listener?.let(locationManager::removeUpdates)
        listener = null
        activeProviders.clear()
        Log.i(tag, "Location updates stopped")
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSystemLocationEnabled(): Boolean {
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val masterEnabled = if (
            android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P
        ) {
            locationManager.isLocationEnabled
        } else {
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }
        return masterEnabled && gpsEnabled
    }

    private fun chooseEnabledProviders(): List<String> {
        val availableProviders = locationManager.allProviders.toSet()
        Log.i(tag, "available providers=$availableProviders")
        return LOCATION_PROVIDER_PRIORITY.filter { provider ->
            provider in availableProviders && runCatching {
                locationManager.isProviderEnabled(provider)
            }.getOrDefault(false)
        }
    }

    private fun Location.toRunPoint(): RunPoint {
        return RunPoint(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude()) altitude else null,
            accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
            speedMetersPerSecond = if (hasSpeed()) speed else null,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            wallClockMillis = time,
            source = provider.toLocationSource()
        )
    }

    private fun String?.toLocationSource(): LocationSource {
        return when (this) {
            LocationManager.GPS_PROVIDER -> LocationSource.Gps
            LocationManager.NETWORK_PROVIDER -> LocationSource.Network
            "fused" -> LocationSource.Fused
            else -> LocationSource.Fused
        }
    }

    companion object {
        private val LOCATION_PROVIDER_PRIORITY = listOf(
            LocationManager.GPS_PROVIDER,
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
}
