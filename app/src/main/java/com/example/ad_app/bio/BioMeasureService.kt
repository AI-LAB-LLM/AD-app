package com.example.ad_app.bio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.example.ad_app.DataLogger
import com.example.ad_app.R
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

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var passiveClient: PassiveMonitoringClient

    @Volatile private var lastHr: Float = -1f
    @Volatile private var lastStepsDaily: Long = -1L
    @Volatile private var lastStepsDelta: Long = 0L
    @Volatile private var lastStepsPerMin: Float = 0f
    @Volatile private var lastStepEventAt: Long = 0L

    // UI 표시용 최근 60초 step queue
    private val stepEvents = ConcurrentLinkedQueue<Pair<Long, Long>>() // (tsMs, delta)

    private var hrvTracker: HrvTracker? = null
    @Volatile private var lastHrvRmssd: Float = -1f
    @Volatile private var lastHrvN: Int = -1

    private var lastBroadcastAt: Long = 0L
    private val UI_BROADCAST_INTERVAL_MS = 2000L

    private var logRegistered = false

    private val CHANNEL_ID = "bio_measure_channel"
    private val NOTI_ID = 1002

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            var changed = false
            val now = System.currentTimeMillis()

            // HR
            dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { dp ->
                lastHr = dp.value.toFloat()
                DataLogger.updateBioHr(lastHr)
                changed = true
            }

            // Steps daily
            var gotStepsDaily = false
            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { dp ->
                lastStepsDaily = dp.value
                DataLogger.updateBioStepsDaily(lastStepsDaily)
                gotStepsDaily = true
            }

            // Steps delta events
            val deltas = dataPoints.getData(DataType.STEPS)
            if (deltas.isNotEmpty()) {
                val sumDelta = deltas.sumOf { it.value }
                lastStepsDelta = sumDelta
                lastStepEventAt = now

                // UI용 queue
                stepEvents.add(now to sumDelta)
                trimOldSteps(now)
                lastStepsPerMin = computeStepsPerMin(now)

                // CSV 로거용 raw step event 추가
                DataLogger.appendStepDelta(sumDelta, now)

                changed = true
            } else {
                // step 이벤트가 없으면 이번 callback 기준 delta는 0
                lastStepsDelta = 0L
                trimOldSteps(now)
                lastStepsPerMin = computeStepsPerMin(now)
            }

            if (gotStepsDaily || deltas.isNotEmpty()) {
                Log.d(
                    TAG,
                    "steps update daily=$lastStepsDaily delta=$lastStepsDelta spm=$lastStepsPerMin"
                )
            }

            if (changed) {
                broadcastBioUpdateThrottled()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        passiveClient = HealthServices.getClient(this).passiveMonitoringClient
        createNotificationChannel()

        hrvTracker = HrvTracker(this) { rmssdMs, hrBpm, sampleCount ->
            Log.d(
                TAG,
                "HRV CALLBACK start rmssd=$rmssdMs hr=$hrBpm n=$sampleCount at=${System.currentTimeMillis()}"
            )

            lastHrvRmssd = rmssdMs
            lastHrvN = sampleCount

            if (hrBpm >= 0) {
                lastHr = hrBpm
                DataLogger.updateBioHr(lastHr)
            }

            Log.d(TAG, "HRV CALLBACK before DataLogger.updateBioHrv rmssd=$lastHrvRmssd n=$lastHrvN")

            DataLogger.updateBioHrv(
                hrvRmssd = lastHrvRmssd,
                hrvSampleCount = lastHrvN
            )

            Log.d(TAG, "HRV CALLBACK end rmssd=$rmssdMs ms, hr=$hrBpm bpm, n=$sampleCount")
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

        startForeground(NOTI_ID, buildNotification())

        if (!logRegistered) {
            DataLogger.register(this)
            logRegistered = true
        }

        startPassiveMonitoring()
        hrvTracker?.start()

        isRunning = true
    }

    private fun stopBioAndSelf() {
        if (!isRunning) {
            if (logRegistered) {
                DataLogger.unregister()
                logRegistered = false
            }
            stopSelf()
            return
        }

        Log.i(TAG, "stopBioAndSelf()")

        hrvTracker?.stop()
        stopPassiveMonitoring()

        if (logRegistered) {
            DataLogger.unregister()
            logRegistered = false
        }

        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopBioAndSelf()
        super.onDestroy()
    }

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

    private fun trimOldSteps(now: Long) {
        val cutoff = now - 60_000L
        while (true) {
            val head = stepEvents.peek() ?: break
            if (head.first < cutoff) {
                stepEvents.poll()
            } else {
                break
            }
        }
    }

    private fun computeStepsPerMin(now: Long): Float {
        trimOldSteps(now)
        val cutoff = now - 60_000L
        val sum = stepEvents
            .asSequence()
            .filter { it.first >= cutoff }
            .sumOf { it.second }
        return sum.toFloat()
    }

    private fun currentUiStepsDelta(now: Long): Long {
        // 최근 10초 안에 step 이벤트가 있었을 때만 delta 유지
        return if (lastStepEventAt > 0 && now - lastStepEventAt <= 10_000L) {
            lastStepsDelta
        } else {
            0L
        }
    }

    private fun broadcastBioUpdateThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastBroadcastAt < UI_BROADCAST_INTERVAL_MS) return
        lastBroadcastAt = now
        broadcastBioUpdate()
    }

    private fun broadcastBioUpdate() {
        val now = System.currentTimeMillis()
        val currentSpm = computeStepsPerMin(now)
        val currentDelta = currentUiStepsDelta(now)

        val i = Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_HR_BPM, lastHr)
            putExtra(EXTRA_STEPS_DAILY, lastStepsDaily)
            putExtra(EXTRA_STEPS_DELTA, currentDelta)
            putExtra(EXTRA_STEPS_PER_MIN, currentSpm)
            putExtra(EXTRA_HRV_RMSSD, lastHrvRmssd)
        }
        sendBroadcast(i)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Bio Measurement",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("생체 측정 중")
            .setContentText("HR/HRV/걸음 수 수집 및 로컬 저장")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}