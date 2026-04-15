package com.trustedtime.trusted_time

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.TimeUnit

/** Main entry point for the TrustedTime Android plugin. */
class TrustedTimePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var methodChannel: MethodChannel
    private lateinit var backgroundChannel: MethodChannel
    private lateinit var integrityChannel: EventChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        // Channel for synchronous hardware-clock queries.
        methodChannel = MethodChannel(binding.binaryMessenger, "trusted_time/monotonic")
        methodChannel.setMethodCallHandler(this)

        // Channel for scheduling background work.
        backgroundChannel = MethodChannel(binding.binaryMessenger, "trusted_time/background")
        backgroundChannel.setMethodCallHandler(this)

        // Stream for broadcasting temporal integrity violations.
        integrityChannel = EventChannel(binding.binaryMessenger, "trusted_time/integrity")
        integrityChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink) =
                IntegrityWatcher.attach(context, sink)
            override fun onCancel(args: Any?) = IntegrityWatcher.detach(context)
        })
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getUptimeMs" -> result.success(SystemClock.elapsedRealtime()) // Returns kernel uptime.
            "enableBackgroundSync" -> {
                val hours = call.argument<Int>("intervalHours") ?: 24
                scheduleBackgroundSync(hours.toLong())
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    /** Dispatches a recurring WorkManager job for anchor refreshing. */
    private fun scheduleBackgroundSync(intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("trusted_time_sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        backgroundChannel.setMethodCallHandler(null)
        IntegrityWatcher.detach(context)
    }
}

/**
 * Background worker that performs a lightweight HTTPS Date-header check to
 * pre-warm the clock offset for the next Dart-side sync. Stores the result
 * in SharedPreferences so TrustedTime can detect large drifts on cold start.
 */
class BackgroundSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val url = java.net.URL("https://www.google.com")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            val serverDate = conn.date
            conn.disconnect()
            if (serverDate > 0) {
                val driftMs = System.currentTimeMillis() - serverDate
                applicationContext.getSharedPreferences("trusted_time", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("bg_drift_ms", driftMs)
                    .putLong("bg_check_at", System.currentTimeMillis())
                    .apply()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
