package com.hashira.launcher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Approximate location, only if the user grants ACCESS_COARSE_LOCATION.
 * Does not depend on Google: uses LocationManager. On GrapheneOS there may be no network provider,
 * in which case it returns null and the app asks to use a city.
 */
class DeviceLocation(private val context: Context) {
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun coarse(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null

        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) return last.latitude to last.longitude

        val enabled = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        if (!enabled) return null

        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal, context.mainExecutor) { loc ->
                        cont.resume(loc?.let { it.latitude to it.longitude })
                    }
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
            }
        }
    }
}
