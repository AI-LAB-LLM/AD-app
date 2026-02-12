package com.example.ad_app.bio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

//Samsung Health Tracking SDK 기반 IBI -> RMSSD 계산 트래커
class HrvTracker(
    context: Context,
    private val windowSeconds: Int = 60,
    private val onUpdate: (rmssdMs: Float, hrBpm: Float, sampleCount: Int) -> Unit
) {
    companion object {
        private const val TAG = "HrvTracker"
    }

    private val appContext = context.applicationContext

    private var service: HealthTrackingService? = null
    private var tracker: HealthTracker? = null

    // 최근 windowSeconds 동안의 IBI 샘플 (timestampMs, ibiMs)
    private val ibiQueue = ConcurrentLinkedQueue<Pair<Long, Int>>()

    @Volatile private var lastHrBpm: Float = -1f
    @Volatile private var lastRmssdMs: Float = -1f

    // 너무 자주 콜백/브로드캐스트되는 것 방지(1초에 1번 정도)
    private var lastEmitAt: Long = 0L

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "HealthTrackingService connected")

            val svc = service ?: return
            val supported = svc.trackingCapability.supportHealthTrackerTypes
            if (!supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
                Log.w(TAG, "HEART_RATE_CONTINUOUS not supported")
                return
            }

            try {
                tracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS).apply {
                    setEventListener(eventListener)
                }
                Log.i(TAG, "HR tracker listener set")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get/set HR tracker", e)
            }
        }

        override fun onConnectionEnded() {
            Log.i(TAG, "HealthTrackingService connection ended")
        }

        override fun onConnectionFailed(exception: HealthTrackerException) {
            Log.e(TAG, "HealthTrackingService connection failed: $exception")
        }
    }

    private val eventListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(data: MutableList<DataPoint>) {
            var updated = false

            for (dp in data) {
                val ts = dp.timestamp

                // HR (Int로 들어오므로 Float로 변환)
                runCatching {
                    val hrInt = dp.getValue(ValueKey.HeartRateSet.HEART_RATE) // Int
                    if (hrInt in 30..220) {
                        lastHrBpm = hrInt.toFloat()
                    }
                }

                // IBI 리스트 + 상태 리스트(정상=0)
                val ibiList = runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_LIST) }.getOrNull()
                val statusList = runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) }.getOrNull()

                if (!ibiList.isNullOrEmpty()) {
                    updated = true
                    addIbiSamples(ts, ibiList, statusList)
                }
            }

            // IBI가 갱신된 경우에만 RMSSD 계산/콜백
            if (updated) {
                lastRmssdMs = computeRmssdMs()
                emitIfNeeded()
            } else {
                // HR만 갱신된 경우에도 가끔 업데이트하고 싶으면 아래 주석 해제
                // emitIfNeeded()
            }
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "Tracker error: $error")
        }

        override fun onFlushCompleted() {
            Log.i(TAG, "Flush completed")
        }
    }

    fun start() {
        // BODY_SENSORS 권한이 없으면 삼성 트래킹 자체가 안 뜨는 경우가 많음
        val granted = appContext.checkSelfPermission(Manifest.permission.BODY_SENSORS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "BODY_SENSORS not granted -> HRV disabled")
            onUpdate(-1f, -1f, 0)
            return
        }

        try {
            service = HealthTrackingService(connectionListener, appContext).also {
                it.connectService()
            }
            Log.i(TAG, "connectService() called")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start HealthTrackingService", t)
            onUpdate(-1f, -1f, 0)
        }
    }

    fun stop() {
        try { tracker?.unsetEventListener() } catch (_: Exception) {}
        tracker = null

        try { service?.disconnectService() } catch (_: Exception) {}
        service = null

        ibiQueue.clear()
        lastRmssdMs = -1f
        lastHrBpm = -1f
    }

    fun getLastRmssdMs(): Float = lastRmssdMs
    fun getLastHrBpm(): Float = lastHrBpm


    // 내부 로직
    private fun addIbiSamples(timestampMs: Long, ibiList: List<Int>, statusList: List<Int>?) {
        for (i in ibiList.indices) {
            val ibi = ibiList[i]               // ms
            val status = statusList?.getOrNull(i) ?: 0

            // 정상(status==0) + 현실 범위만
            if (status != 0) continue
            if (ibi !in 300..2000) continue

            ibiQueue.add(timestampMs to ibi)
        }
        trimOldIbi(timestampMs)
    }

    private fun trimOldIbi(now: Long) {
        val cutoff = now - windowSeconds * 1000L
        while (true) {
            val head = ibiQueue.peek() ?: break
            if (head.first < cutoff) ibiQueue.poll() else break
        }
    }

    private fun computeRmssdMs(): Float {
        val samples = ibiQueue.toList()
            .sortedBy { it.first }
            .map { it.second }

        if (samples.size < 3) return -1f

        var sumSq = 0.0
        var cnt = 0
        for (i in 1 until samples.size) {
            val diff = (samples[i] - samples[i - 1]).toDouble()
            sumSq += diff * diff
            cnt++
        }
        return if (cnt > 0) sqrt(sumSq / cnt).toFloat() else -1f
    }

    private fun emitIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastEmitAt < 1000L) return // 1초에 1번 정도만
        lastEmitAt = now

        val count = ibiQueue.size
        onUpdate(lastRmssdMs, lastHrBpm, count)
    }
}