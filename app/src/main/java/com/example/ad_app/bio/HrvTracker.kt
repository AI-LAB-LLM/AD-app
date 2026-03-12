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
import kotlin.math.abs
import kotlin.math.sqrt

//Samsung Health Tracking SDK 기반 IBI -> RMSSD 계산 트래커
class HrvTracker(
    context: Context,
    private val windowSeconds: Int = 60,
    private val onUpdate: (rmssdMs: Float, hrBpm: Float, sampleCount: Int) -> Unit
) {
    companion object {
        private const val TAG = "HrvTracker"
        private const val PERM_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val TARGET_SDK_36 = 36

        // outlier 필터 파라미터
        private const val IBI_MEDIAN_LO = 0.70
        private const val IBI_MEDIAN_HI = 1.30
        private const val MAX_DIFF_MS = 200 // 너무 큰 diff는 아티팩트로 제외
    }

    private val appContext = context.applicationContext

    private var service: HealthTrackingService? = null
    private var tracker: HealthTracker? = null

    private val ibiQueue = ConcurrentLinkedQueue<Pair<Long, Int>>() // (tsMs, ibiMs)

    @Volatile private var lastHrBpm: Float = -1f
    @Volatile private var lastRmssdMs: Float = -1f
    @Volatile private var lastHrvN: Int = 0
    private var lastEmitAt: Long = 0L

    private data class IbiUpdateResult(
        val added: Int,
        val trimmed: Int,
        val rejectedByStatus: Int,
        val rejectedByRange: Int,
        val queueSize: Int
    ) {
        val queueChanged: Boolean
            get() = added > 0 || trimmed > 0
    }

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
            Log.e(TAG, "HealthTrackingService connection failed: $exception", exception)
        }
    }

    private val eventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            var queueChanged = false

            Log.d(TAG, "onDataReceived size=${data.size} at=${System.currentTimeMillis()}")

            for (dp in data) {
                val rawTs = dp.timestamp
                val tsMs = normalizeToEpochMs(rawTs)

                val hrStatus =
                    runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) }.getOrNull()
                val hrInt =
                    runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE) }.getOrNull()

                if (hrInt != null && hrInt in 30..220) {
                    lastHrBpm = hrInt.toFloat()
                }

                val ibiList =
                    runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_LIST) }.getOrNull()
                val statusList =
                    runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) }.getOrNull()

                Log.d(
                    TAG,
                    "HR dp: status=$hrStatus hr=$hrInt ibiSize=${ibiList?.size ?: 0} tsMs=$tsMs"
                )

                if (hrStatus == 1 && !ibiList.isNullOrEmpty()) {
                    val result = addIbiSamples(tsMs, ibiList, statusList)
                    if (result.queueChanged) {
                        queueChanged = true
                    }
                } else if (hrStatus == 1 && ibiList.isNullOrEmpty()) {
                    val trimmed = trimOldIbi(tsMs)
                    if (trimmed > 0) {
                        queueChanged = true
                        Log.d(TAG, "trimOnly: trimmed=$trimmed queueSize=${ibiQueue.size}")
                    }
                }
            }

            Log.d(TAG, "onDataReceived queueChanged=$queueChanged queueSize=${ibiQueue.size}")

            if (queueChanged) {
                val (rmssd, n) = computeRmssdMsAndN()
                lastRmssdMs = rmssd
                lastHrvN = n

                Log.d(TAG, "compute result rmssd=$rmssd n=$n queueSize=${ibiQueue.size}")
                emitIfNeeded()
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
        val hasBodySensors =
            appContext.checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

        val targetSdk = appContext.applicationInfo.targetSdkVersion
        val hasReadHeartRate =
            (targetSdk >= TARGET_SDK_36) &&
                    (appContext.checkSelfPermission(PERM_READ_HEART_RATE) == PackageManager.PERMISSION_GRANTED)

        Log.i(
            TAG,
            "start(): targetSdk=$targetSdk bodySensors=$hasBodySensors readHeartRate=$hasReadHeartRate"
        )

        if (!hasBodySensors && !hasReadHeartRate) {
            Log.w(TAG, "No HR permission granted -> HRV disabled")
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
        try {
            tracker?.unsetEventListener()
        } catch (_: Exception) {
        }
        tracker = null

        try {
            service?.disconnectService()
        } catch (_: Exception) {
        }
        service = null

        ibiQueue.clear()
        lastRmssdMs = -1f
        lastHrBpm = -1f
        lastHrvN = 0
    }

    fun getLastRmssdMs(): Float = lastRmssdMs
    fun getLastHrBpm(): Float = lastHrBpm
    fun getLastHrvN(): Int = lastHrvN

    private fun normalizeToEpochMs(raw: Long): Long {
        val nowMs = System.currentTimeMillis()
        data class Cand(val ms: Long)

        val cands = listOf(
            Cand(raw),
            Cand(raw / 1_000L),
            Cand(raw / 1_000_000L),
            Cand(raw * 1_000L)
        ).filter { it.ms > 0 }

        return cands.minBy { abs(it.ms - nowMs) }.ms
    }

    private fun addIbiSamples(
        timestampMs: Long,
        ibiList: List<Int>,
        statusList: List<Int>?
    ): IbiUpdateResult {
        val totalIbiMs = ibiList.sum().toLong()
        var t = timestampMs - totalIbiMs
        if (t < 0L) t = 0L

        var added = 0
        var rejectedByStatus = 0
        var rejectedByRange = 0

        for (i in ibiList.indices) {
            val ibi = ibiList[i]
            val status = statusList?.getOrNull(i) ?: 0

            if (status != 0) {
                rejectedByStatus++
                continue
            }

            if (ibi !in 300..2000) {
                rejectedByRange++
                continue
            }

            t += ibi.toLong()
            ibiQueue.add(t to ibi)
            added++
        }

        val trimmed = trimOldIbi(timestampMs)

        Log.d(
            TAG,
            "addIbiSamples: ibiList=$ibiList statusList=$statusList " +
                    "added=$added trimmed=$trimmed rejectedByStatus=$rejectedByStatus " +
                    "rejectedByRange=$rejectedByRange queueSize=${ibiQueue.size}"
        )

        return IbiUpdateResult(
            added = added,
            trimmed = trimmed,
            rejectedByStatus = rejectedByStatus,
            rejectedByRange = rejectedByRange,
            queueSize = ibiQueue.size
        )
    }

    private fun trimOldIbi(nowMs: Long): Int {
        val cutoff = nowMs - windowSeconds * 1000L
        var trimmed = 0

        while (true) {
            val head = ibiQueue.peek() ?: break
            if (head.first < cutoff) {
                ibiQueue.poll()
                trimmed++
            } else {
                break
            }
        }
        return trimmed
    }

    private fun computeRmssdMsAndN(): Pair<Float, Int> {
        val ibis = ibiQueue.toList().sortedBy { it.first }.map { it.second }

        if (ibis.size < 2) {
            Log.d(TAG, "computeRmssdMsAndN fail: ibis.size=${ibis.size}")
            return -1f to 0
        }

        val sorted = ibis.sorted()
        val med = sorted[sorted.size / 2]
        val lo = (med * IBI_MEDIAN_LO).toInt().coerceAtLeast(300)
        val hi = (med * IBI_MEDIAN_HI).toInt().coerceAtMost(2000)
        val filtered = ibis.filter { it in lo..hi }

        if (filtered.size < 2) {
            Log.d(TAG, "computeRmssdMsAndN fail: filtered.size=${filtered.size} med=$med lo=$lo hi=$hi")
            return -1f to filtered.size
        }

        val diffs = filtered.zipWithNext { a, b -> abs(b - a) }.filter { it <= MAX_DIFF_MS }

        if (diffs.isEmpty()) {
            Log.d(TAG, "computeRmssdMsAndN fail: diffs empty filtered.size=${filtered.size}")
            return -1f to filtered.size
        }

        var sumSq = 0.0
        for (d in diffs) {
            sumSq += d.toDouble() * d.toDouble()
        }
        val rmssd = sqrt(sumSq / diffs.size).toFloat()

        Log.d(TAG, "computeRmssdMsAndN ok: rmssd=$rmssd ibis=${ibis.size} filtered=${filtered.size} diffs=${diffs.size}")
        return rmssd to filtered.size
    }

    private fun emitIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastEmitAt < 1000L) return
        lastEmitAt = now
        onUpdate(lastRmssdMs, lastHrBpm, lastHrvN)
    }
}