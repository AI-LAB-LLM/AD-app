package com.example.ad_app.bio

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
        const val EXTRA_HRV_RMSSD = "hrv_rmssd" // ms

        @Volatile var isRunning: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Health Services (HR / Steps)
    private lateinit var passiveClient: PassiveMonitoringClient

    @Volatile private var lastHr: Float = -1f
    @Volatile private var lastStepsDaily: Long = -1L
    @Volatile private var lastStepsDelta: Long = 0L
    @Volatile private var lastStepsPerMin: Float = 0f

    // 최근 60초 steps delta 모아서 steps/min 추정
    private val stepEvents = ConcurrentLinkedQueue<Pair<Long, Long>>() // (timestampMs, delta)


    // Samsung HRV (IBI -> RMSSD): HrvTracker 사용
    private var hrvTracker: HrvTracker? = null
    @Volatile private var lastHrvRmssd: Float = -1f

    // 브로드캐스트 과다 방지(원하면 조절)
    private var lastBroadcastAt: Long = 0L

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {

            // HR
            dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { dp ->
                lastHr = dp.value.toFloat()
            }

            // 오늘 누적 걸음수
            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { dp ->
                lastStepsDaily = dp.value
            }

            // 델타 걸음수
            val deltas = dataPoints.getData(DataType.STEPS)
            if (deltas.isNotEmpty()) {
                val sumDelta = deltas.sumOf { it.value } // Long
                lastStepsDelta = sumDelta

                val now = System.currentTimeMillis()
                stepEvents.add(now to sumDelta)
                trimOldSteps(now)
                lastStepsPerMin = computeStepsPerMin()
            }

            broadcastBioUpdateThrottled()
        }
    }


    override fun onCreate() {
        super.onCreate()
        passiveClient = HealthServices.getClient(this).passiveMonitoringClient

        // HRV 트래커 연결
        hrvTracker = HrvTracker(this) { rmssdMs, hrBpm, sampleCount ->
            lastHrvRmssd = rmssdMs
            // 삼성 HR이 더 빨리/안정적으로 올 때 HR 보정(옵션)
            if (hrBpm >= 0) lastHr = hrBpm

            Log.d(TAG, "HRV rmssd=$rmssdMs ms, hr=$hrBpm bpm, n=$sampleCount")

            broadcastBioUpdateThrottled()
        }

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
        if (isRunning) {
            Log.i(TAG, "startBio(): already running")
            return
        }
        Log.i(TAG, "startBio()")

        startPassiveMonitoring()
        hrvTracker?.start()

        isRunning = true
    }

    private fun stopBioAndSelf() {
        Log.i(TAG, "stopBioAndSelf()")

        // HRV 중단
        hrvTracker?.stop()

        // Passive 중단
        stopPassiveMonitoring()

        isRunning = false
        stopSelf()
    }

    override fun onDestroy() {
        stopBioAndSelf()
        super.onDestroy()
    }


    // Passive monitoring
    private fun startPassiveMonitoring() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(
                setOf(
                    DataType.HEART_RATE_BPM,
                    DataType.STEPS_DAILY,
                    DataType.STEPS
                )
            )
            .build()

        runCatching {
            passiveClient.setPassiveListenerCallback(config, passiveCallback)
            Log.i(TAG, "Passive listener callback set")
        }.onFailure {
            Log.e(TAG, "Failed to set passive callback", it)
        }
    }

    private fun stopPassiveMonitoring() {
        runCatching {
            passiveClient.clearPassiveListenerCallbackAsync()
            Log.i(TAG, "Passive listener callback cleared")
        }.onFailure {
            Log.w(TAG, "clearPassiveListenerCallbackAsync failed", it)
        }
    }


    // Steps helpers
    private fun trimOldSteps(now: Long) {
        val cutoff = now - 60_000L
        while (true) {
            val head = stepEvents.peek() ?: break
            if (head.first < cutoff) stepEvents.poll() else break
        }
    }

    private fun computeStepsPerMin(): Float {
        val sum = stepEvents.sumOf { it.second } // Long
        return sum.toFloat()
    }


    // Broadcast
    private fun broadcastBioUpdateThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastBroadcastAt < 500L) return // 0.5초에 1번 정도만
        lastBroadcastAt = now
        broadcastBioUpdate()
    }

    private fun broadcastBioUpdate() {
        val i = Intent(ACTION_UPDATE).apply {
            setPackage(packageName) // 앱 내부로만
            putExtra(EXTRA_HR_BPM, lastHr)
            putExtra(EXTRA_STEPS_DAILY, lastStepsDaily)
            putExtra(EXTRA_STEPS_DELTA, lastStepsDelta)
            putExtra(EXTRA_STEPS_PER_MIN, lastStepsPerMin)
            putExtra(EXTRA_HRV_RMSSD, lastHrvRmssd)
        }
        sendBroadcast(i)
    }
}