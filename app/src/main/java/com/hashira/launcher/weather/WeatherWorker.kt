package com.hashira.launcher.weather

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hashira.launcher.data.SettingsRepository
import com.hashira.launcher.data.WeatherSource
import java.util.concurrent.TimeUnit

/**
 * Background weather update (WorkManager, no Google services).
 * With "device location" it does not request location from the background: it reuses the last coordinates.
 */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).state.value
        if (!settings.weather.show) return Result.success()

        val repo = WeatherRepository(applicationContext)
        val lat = settings.weatherLat
        val lon = settings.weatherLon
        val coords: Pair<Double, Double> = when (settings.weatherSource) {
            WeatherSource.CITY -> if (lat != null && lon != null) lat to lon else return Result.success()
            WeatherSource.DEVICE -> repo.cached()?.let { it.lat to it.lon } ?: return Result.success()
        }
        return try {
            repo.fetch(coords.first, coords.second)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object WeatherScheduler {
    private const val NAME = "hashira_weather"

    /** Schedules (or cancels) the periodic update. WorkManager requires a minimum of 15 minutes. */
    fun apply(context: Context, enabled: Boolean, intervalMin: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeatherWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
