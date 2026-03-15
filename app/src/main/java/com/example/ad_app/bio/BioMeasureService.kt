package com.example.ad_app.bio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        const val ACTION_START  = "com.example.ad_app.bio.action.START"
        const val ACTION_STOP   = "com.example.ad_app.bio.action.STOP"
        const val ACTION_UPDATE = "com.example.ad_app.bio.action.UPDATE"

        const val EXTRA_HR_BPM        = "hr_bpm"
        const val EXTRA_STEPS_DAILY   = "steps_daily"
        const val EXTRA_STEPS_PER_MIN = "steps_per_min"
        const val EXTRA_HRV_RMSSD     = "hrv_rmssd"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var passiveClient: PassiveMonitoringClient

    @Volatile private var lastHr         : Float = -1f
    @Volatile private var lastStepsDaily : Long  = -1L
    @Volatile private var lastStepsPerMin: Float = 0f

    // UI 표시용 최근 60초 step queue
    private val uiStepEvents = ConcurrentLinkedQueue<Pair<Long, Long>>()

    // STEPS_DAILY 변화량으로 steps_delta 보완하기 위한 이전값 추적
    // Galaxy Watch에서 DataType.STEPS (interval delta)가 Passive로 거의 안 오는 경우 대비
    @Volatile private var prevStepsDaily: Long = -1L

    private var hrvTracker   : HrvTracker? = null
    @Volatile private var lastHrvRmssd : Float = -1f
    @Volatile private var lastHrvN     : Int   = -1

    // HrvTracker watchdog
    // Samsung Health SDK는 장시간 실행 중 onConnectionEnded()가 발생하여
    // HRV 수신이 중단되는 경우가 있다. 10분 이상 업데이트 없으면 자동 재시작.
    private val mainHandler              = Handler(Looper.getMainLooper())
    private var hrvWatchdogRunnable      : Runnable? = null
    private val HRV_WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L   // 5분마다 체크
    private val HRV_STALE_RESTART_MS     = 10 * 60 * 1000L  // 10분 업데이트 없으면 재시작
    @Volatile private var lastHrvUpdateAt: Long = 0L

    private var lastBroadcastAt         : Long = 0L
    private val UI_BROADCAST_INTERVAL_MS       = 2000L

    private var logRegistered = false

    private val CHANNEL_ID = "bio_measure_channel"
    private val NOTI_ID    = 1002

    // ════════════════════════════════════════════════
    // Passive callback
    // ════════════════════════════════════════════════

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val now     = System.currentTimeMillis()
            var changed = false

            // ── HR ───────────────────────────────────
            dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { dp ->
                lastHr = dp.value.toFloat()
                DataLogger.updateBioHr(lastHr)
                changed = true
                Log.d(TAG, "HR received: $lastHr bpm")
            }

            // ── STEPS interval (DataType.STEPS) ──────
            // 먼저 처리해서 STEPS_DAILY 보완 여부 결정에 활용
            val deltaList = dataPoints.getData(DataType.STEPS)
            Log.d(TAG, "STEPS interval count=${deltaList.size}")

            if (deltaList.isNotEmpty()) {
                val sumDelta = deltaList.sumOf { it.value }
                // DataLogger queue에 추가 (writeTick에서 집계)
                DataLogger.appendStepDelta(sumDelta, now)
                // UI queue에도 추가
                uiStepEvents.add(now to sumDelta)
                trimUiSteps(now)
                lastStepsPerMin = computeUiStepsPerMin(now)
                Log.d(TAG, "STEPS interval sumDelta=$sumDelta spm=$lastStepsPerMin")
                changed = true
            } else {
                trimUiSteps(now)
                lastStepsPerMin = computeUiStepsPerMin(now)
            }

            // ── STEPS_DAILY ───────────────────────────
            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { dp ->
                val newDaily = dp.value

                // DataType.STEPS가 없었을 때만 STEPS_DAILY 변화량으로 보완
                // → 둘 다 있으면 중복 카운팅이 발생하므로 반드시 분기 처리
                if (prevStepsDaily >= 0 && newDaily > prevStepsDaily && deltaList.isEmpty()) {
                    val inferred = newDaily - prevStepsDaily
                    DataLogger.appendStepDelta(inferred, now)
                    uiStepEvents.add(now to inferred)
                    trimUiSteps(now)
                    lastStepsPerMin = computeUiStepsPerMin(now)
                    Log.d(TAG, "STEPS_DAILY inferred delta=$inferred (${prevStepsDaily}→$newDaily)")
                }

                prevStepsDaily = newDaily
                lastStepsDaily = newDaily
                DataLogger.updateBioStepsDaily(lastStepsDaily)
                Log.d(TAG, "STEPS_DAILY updated: $lastStepsDaily")
            }

            if (changed) broadcastBioUpdateThrottled()
        }
    }

    // ════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        passiveClient = HealthServices.getClient(this).passiveMonitoringClient
        createNotificationChannel()
        buildHrvTracker()
        Log.i(TAG, "onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBio()
            ACTION_STOP  -> stopBioAndSelf()
            else         -> startBio()
        }
        return START_STICKY
    }

    private fun startBio() {
        if (isRunning) { Log.i(TAG, "startBio(): already running"); return }
        Log.i(TAG, "startBio()")

        startForeground(NOTI_ID, buildNotification())
        if (!logRegistered) { DataLogger.register(this); logRegistered = true }

        startPassiveMonitoring()
        hrvTracker?.start()
        scheduleHrvWatchdog()

        isRunning = true
    }

    private fun stopBioAndSelf() {
        if (!isRunning) {
            if (logRegistered) { DataLogger.unregister(); logRegistered = false }
            stopSelf()
            return
        }
        Log.i(TAG, "stopBioAndSelf()")

        cancelHrvWatchdog()
        hrvTracker?.stop()
        stopPassiveMonitoring()

        if (logRegistered) { DataLogger.unregister(); logRegistered = false }

        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopBioAndSelf()
        super.onDestroy()
    }

    // ════════════════════════════════════════════════
    // HrvTracker & watchdog
    // ════════════════════════════════════════════════

    private fun buildHrvTracker() {
        hrvTracker = HrvTracker(this) { rmssdMs, hrBpm, sampleCount ->
            Log.d(TAG, "HRV callback rmssd=$rmssdMs hr=$hrBpm n=$sampleCount")
            lastHrvRmssd    = rmssdMs
            lastHrvN        = sampleCount
            lastHrvUpdateAt = System.currentTimeMillis()

            if (hrBpm >= 0) {
                lastHr = hrBpm
                DataLogger.updateBioHr(lastHr)
            }
            DataLogger.updateBioHrv(hrvRmssd = lastHrvRmssd, hrvSampleCount = lastHrvN)
            broadcastBioUpdateThrottled()
        }
    }

    private fun scheduleHrvWatchdog() {
        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                val now     = System.currentTimeMillis()
                val elapsed = if (lastHrvUpdateAt > 0) now - lastHrvUpdateAt else Long.MAX_VALUE

                if (elapsed > HRV_STALE_RESTART_MS) {
                    Log.w(TAG, "HRV watchdog: no update for ${elapsed / 1000}s → restart HrvTracker")
                    try { hrvTracker?.stop() } catch (_: Exception) {}
                    hrvTracker = null
                    buildHrvTracker()
                    try { hrvTracker?.start() } catch (t: Throwable) {
                        Log.e(TAG, "HrvTracker restart failed", t)
                    }
                } else {
                    Log.d(TAG, "HRV watchdog ok: last update ${elapsed / 1000}s ago")
                }

                mainHandler.postDelayed(this, HRV_WATCHDOG_INTERVAL_MS)
            }
        }
        hrvWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, HRV_WATCHDOG_INTERVAL_MS)
    }

    private fun cancelHrvWatchdog() {
        hrvWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        hrvWatchdogRunnable = null
    }

    // ════════════════════════════════════════════════
    // Passive Monitoring
    // ════════════════════════════════════════════════

    private fun startPassiveMonitoring() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(
                DataType.HEART_RATE_BPM,
                DataType.STEPS_DAILY,
                DataType.STEPS
            ))
            .build()
        runCatching {
            passiveClient.setPassiveListenerCallback(config, passiveCallback)
            Log.i(TAG, "Passive listener callback set")
        }.onFailure { Log.e(TAG, "Failed to set passive callback", it) }
    }

    private fun stopPassiveMonitoring() {
        runCatching {
            passiveClient.clearPassiveListenerCallbackAsync()
            Log.i(TAG, "Passive listener callback cleared")
        }.onFailure { Log.w(TAG, "clearPassiveListenerCallbackAsync failed", it) }
    }

    // ════════════════════════════════════════════════
    // UI Steps helpers
    // ════════════════════════════════════════════════

    private fun trimUiSteps(now: Long) {
        val cutoff = now - 60_000L
        while (true) {
            val head = uiStepEvents.peek() ?: break
            if (head.first < cutoff) uiStepEvents.poll() else break
        }
    }

    private fun computeUiStepsPerMin(now: Long): Float {
        val cutoff = now - 60_000L
        return uiStepEvents.asSequence().filter { it.first >= cutoff }.sumOf { it.second }.toFloat()
    }

    // ════════════════════════════════════════════════
    // Broadcast
    // ════════════════════════════════════════════════

    private fun broadcastBioUpdateThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastBroadcastAt < UI_BROADCAST_INTERVAL_MS) return
        lastBroadcastAt = now
        broadcastBioUpdate()
    }

    private fun broadcastBioUpdate() {
        val now = System.currentTimeMillis()
        val i = Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_HR_BPM,        lastHr)
            putExtra(EXTRA_STEPS_DAILY,   lastStepsDaily)
            putExtra(EXTRA_STEPS_PER_MIN, computeUiStepsPerMin(now))
            putExtra(EXTRA_HRV_RMSSD,     lastHrvRmssd)
        }
        sendBroadcast(i)
    }

    // ════════════════════════════════════════════════
    // Notification
    // ════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Bio Measurement", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("생체 측정 중")
            .setContentText("HR/HRV/걸음 수 수집 및 로컬 저장")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
}