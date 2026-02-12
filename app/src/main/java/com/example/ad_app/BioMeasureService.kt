package com.example.ad_app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import java.util.concurrent.ConcurrentLinkedQueue

class BioMeasureService : Service() {

    companion object {
        const val TAG = "BioMeasureService"

        const val ACTION_START = "com.example.ad_app.bio.action.START"
        const val ACTION_STOP  = "com.example.ad_app.bio.action.STOP"
        const val ACTION_UPDATE = "com.example.ad_app.bio.action.UPDATE"

        const val EXTRA_HR_BPM = "hr_bpm"
        const val EXTRA_STEPS_DAILY = "steps_daily"
        const val EXTRA_STEPS_DELTA = "steps_delta"
        const val EXTRA_STEPS_PER_MIN = "steps_per_min"
        const val EXTRA_HRV_RMSSD = "hrv_rmssd"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var passiveClient: PassiveMonitoringClient

    @Volatile private var lastHr: Float = -1f
    @Volatile private var lastStepsDaily: Long = -1L
    @Volatile private var lastStepsDelta: Long = 0L
    @Volatile private var lastStepsPerMin: Float = 0f
    @Volatile private var lastHrvRmssd: Float = -1f

    // 최근 60초 동안 steps_delta를 모아서 steps/min 추정
    private val stepEvents = ConcurrentLinkedQueue<Pair<Long, Long>>() // (timestampMs, delta)

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {

            // HR
            dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { dp ->
                lastHr = dp.value.toFloat()
            }

            // 오늘 누적 steps
            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { dp ->
                lastStepsDaily = dp.value
            }

            // 델타 steps (여러 개 들어오면 합산)
            val deltas = dataPoints.getData(DataType.STEPS)
            if (deltas.isNotEmpty()) {
                val sumDelta = deltas.sumOf { it.value }
                lastStepsDelta = sumDelta

                val now = System.currentTimeMillis()
                stepEvents.add(now to sumDelta)
                trimOldSteps(now)
                lastStepsPerMin = computeStepsPerMin()
            }

            broadcastBioUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        passiveClient = HealthServices.getClient(this).passiveMonitoringClient
        Log.i(TAG, "onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBio()
            ACTION_STOP  -> stopBioAndSelf()
            else -> startBio()
        }
        return START_STICKY
    }

    private fun startBio() {
        Log.i(TAG, "startBio()")

        val config = PassiveListenerConfig.builder()
            .setDataTypes(
                setOf(
                    DataType.HEART_RATE_BPM,
                    DataType.STEPS_DAILY,
                    DataType.STEPS
                )
            )
            .build()

        try {
            passiveClient.setPassiveListenerCallback(config, callback)
            Log.i(TAG, "Passive listener callback set")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set passive callback", e)
        }
    }

    private fun stopBioAndSelf() {
        Log.i(TAG, "stopBioAndSelf()")

        try {
            passiveClient.clearPassiveListenerCallbackAsync()
        } catch (e: Exception) {
            Log.w(TAG, "clearPassiveListenerCallbackAsync failed: ${e.message}")
        }

        stopSelf()
    }

    override fun onDestroy() {
        stopBioAndSelf()
        super.onDestroy()
    }

    private fun trimOldSteps(now: Long) {
        val cutoff = now - 60_000L
        while (true) {
            val head = stepEvents.peek() ?: break
            if (head.first < cutoff) stepEvents.poll() else break
        }
    }

    private fun computeStepsPerMin(): Float {
        // 최근 60초 누적 delta = steps/min 근사
        val sum = stepEvents.sumOf { it.second }
        return sum.toFloat()
    }

    private fun broadcastBioUpdate() {
        val i = Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_HR_BPM, lastHr)
            putExtra(EXTRA_STEPS_DAILY, lastStepsDaily)
            putExtra(EXTRA_STEPS_DELTA, lastStepsDelta)
            putExtra(EXTRA_STEPS_PER_MIN, lastStepsPerMin)
            putExtra(EXTRA_HRV_RMSSD, lastHrvRmssd)
        }
        sendBroadcast(i)
    }
}
