package com.example.ad_app

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object DataLogger {
    private const val TAG = "DataLogger"

    // 장기 수집 기본값
    private const val LOG_INTERVAL_MS = 10_000L      // 10초마다 1줄 저장
    private const val FLUSH_INTERVAL_MS = 60_000L    // 60초마다 flush
    private const val DIR_NAME = "ad_logs"

    // stale 기준
    private const val STALE_ENV_MS = 30_000L
    private const val STALE_HR_MS = 15_000L
    private const val STALE_HRV_MS = 15_000L
    private const val STALE_STEPS_MS = 30_000L

    // HRV 유효성 기준
    private const val HRV_MIN_N = 10
    private const val HRV_MIN_MS = 5f
    private const val HRV_MAX_MS = 150f

    private val refCount = AtomicInteger(0)
    private val lock = Any()

    private var scheduler: ScheduledExecutorService? = null
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var currentDayKey: String? = null
    private var wroteHeader = false
    private var lastFlushAt: Long = 0L

    // KST 저장
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }

    // ---------- 최신값(Env) ----------
    @Volatile private var envLux: Float? = null
    @Volatile private var envDbfs: Float? = null
    @Volatile private var envSsid: String? = null
    @Volatile private var envPlace: String? = null
    @Volatile private var envUpdatedAt: Long = 0L

    // ---------- 최신값(Bio) ----------
    @Volatile private var bioHr: Float? = null
    @Volatile private var bioHrUpdatedAt: Long = 0L

    @Volatile private var bioHrv: Float? = null
    @Volatile private var bioHrvN: Int? = null
    @Volatile private var bioHrvUpdatedAt: Long = 0L

    @Volatile private var bioStepsDaily: Long? = null
    @Volatile private var bioStepsDelta: Long? = null
    @Volatile private var bioStepsPerMin: Float? = null
    @Volatile private var bioStepsUpdatedAt: Long = 0L

    fun register(context: Context) {
        val n = refCount.incrementAndGet()
        synchronized(lock) {
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor()
                ensureOpenOrRotate(context, System.currentTimeMillis())
                startSchedule(context)
                Log.i(TAG, "scheduler started")
            }
        }
        Log.i(TAG, "register(): refCount=$n file=${currentFile?.absolutePath}")
    }

    fun unregister() {
        val n = (refCount.decrementAndGet()).coerceAtLeast(0)
        Log.i(TAG, "unregister(): refCount=$n")
        if (n == 0) {
            synchronized(lock) {
                try { scheduler?.shutdownNow() } catch (_: Exception) {}
                scheduler = null
                closeLocked()
            }
        }
    }

    fun getCurrentFilePath(): String? = currentFile?.absolutePath

    // ---------- Env ----------
    fun updateEnv(lux: Float, dbfs: Float, ssid: String?, place: String) {
        envLux = if (lux >= 0) lux else null
        envDbfs = dbfs
        envSsid = ssid?.takeIf { it.isNotBlank() }
        envPlace = place.takeIf { it.isNotBlank() }
        envUpdatedAt = System.currentTimeMillis()
    }

    // ---------- Bio ----------
    fun updateBioHr(hrBpm: Float) {
        if (hrBpm < 0) return
        bioHr = hrBpm
        bioHrUpdatedAt = System.currentTimeMillis()
    }

    fun updateBioHrv(hrvRmssd: Float, hrvSampleCount: Int) {
        if (hrvRmssd < 0) return
        val now = System.currentTimeMillis()
        bioHrv = hrvRmssd
        bioHrvN = hrvSampleCount.takeIf { it >= 0 }
        bioHrvUpdatedAt = now
    }

    fun updateBioSteps(
        stepsDaily: Long,
        stepsDelta: Long,
        stepsPerMin: Float
    ) {
        val now = System.currentTimeMillis()

        if (stepsDaily >= 0) bioStepsDaily = stepsDaily
        bioStepsDelta = stepsDelta
        bioStepsPerMin = stepsPerMin
        bioStepsUpdatedAt = now
    }

    // ---------- 내부 스케줄 ----------
    private fun startSchedule(context: Context) {
        val sch = scheduler ?: return

        sch.scheduleAtFixedRate({
            try { writeTick(context) } catch (t: Throwable) { Log.e(TAG, "writeTick failed", t) }
        }, 0L, LOG_INTERVAL_MS, TimeUnit.MILLISECONDS)

        sch.scheduleAtFixedRate({
            synchronized(lock) {
                try {
                    writer?.flush()
                    lastFlushAt = System.currentTimeMillis()
                } catch (_: Exception) {}
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun writeTick(context: Context) {
        val now = System.currentTimeMillis()

        synchronized(lock) {
            ensureOpenOrRotate(context, now)
            writeHeaderIfNeededLocked()

            // age 계산
            val envAge = envUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val hrAge = bioHrUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val hrvAge = bioHrvUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val stepsAge = bioStepsUpdatedAt.takeIf { it > 0 }?.let { now - it }

            // Env는 stale이면 비움
            val luxOut = if (envAge != null && envAge <= STALE_ENV_MS) envLux else null
            val dbfsOut = if (envAge != null && envAge <= STALE_ENV_MS) envDbfs else null
            val ssidOut = if (envAge != null && envAge <= STALE_ENV_MS) envSsid else null
            val placeOut = if (envAge != null && envAge <= STALE_ENV_MS) envPlace else null

            // HR / Steps / HRV는 마지막 값 유지
            val hrOut = bioHr
            val hrvOut = bioHrv
            val hrvNOut = bioHrvN
            val stepsDailyOut = bioStepsDaily
            val stepsDeltaOut = bioStepsDelta
            val stepsPerMinOut = bioStepsPerMin

            val hrvValidInt = run {
                val rmssd = hrvOut
                val n = hrvNOut
                val basicOk =
                    rmssd != null &&
                            n != null &&
                            n >= HRV_MIN_N &&
                            rmssd in HRV_MIN_MS..HRV_MAX_MS

                if (basicOk) 1 else 0
            }

            val line = buildLine(
                tsMs = now,
                source = "tick",
                lux = luxOut,
                dbfs = dbfsOut,
                ssid = ssidOut,
                place = placeOut,
                hrBpm = hrOut,
                hrvRmssd = hrvOut,
                stepsDaily = stepsDailyOut,
                stepsDelta = stepsDeltaOut,
                stepsPerMin = stepsPerMinOut,
                envAgeMs = envAge,
                hrAgeMs = hrAge,
                hrvAgeMs = hrvAge,
                hrvN = hrvNOut,
                hrvValid = hrvValidInt,
                stepsAgeMs = stepsAge
            )

            writer?.write(line)
            writer?.newLine()

            if (now - lastFlushAt > FLUSH_INTERVAL_MS * 2) {
                try {
                    writer?.flush()
                    lastFlushAt = now
                } catch (_: Exception) {}
            }
        }
    }

    private fun ensureOpenOrRotate(context: Context, now: Long) {
        val dayKey = dayFmt.format(Date(now))

        if (writer == null || currentDayKey != dayKey || currentFile == null) {
            closeLocked()

            val dir = File(context.getExternalFilesDir(null), DIR_NAME)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "ad_log_${dayKey}.csv")
            currentFile = file
            currentDayKey = dayKey
            writer = BufferedWriter(FileWriter(file, true))

            wroteHeader = file.exists() && file.length() > 0
            lastFlushAt = 0L

            Log.i(TAG, "opened file=${file.absolutePath} wroteHeader=$wroteHeader")
        }
    }

    private fun writeHeaderIfNeededLocked() {
        if (wroteHeader) return

        val header = listOf(
            "ts_ms",
            "ts_iso",
            "source",
            "lux",
            "dbfs",
            "ssid",
            "place",
            "hr_bpm",
            "hrv_rmssd_ms",
            "steps_daily",
            "steps_delta",
            "steps_per_min",
            "env_age_ms",
            "hr_age_ms",
            "hrv_age_ms",
            "hrv_n",
            "hrv_valid",
            "steps_age_ms"
        ).joinToString(",")

        writer?.write(header)
        writer?.newLine()
        writer?.flush()
        lastFlushAt = System.currentTimeMillis()
        wroteHeader = true
    }

    private fun buildLine(
        tsMs: Long,
        source: String,
        lux: Float?,
        dbfs: Float?,
        ssid: String?,
        place: String?,
        hrBpm: Float?,
        hrvRmssd: Float?,
        stepsDaily: Long?,
        stepsDelta: Long?,
        stepsPerMin: Float?,
        envAgeMs: Long?,
        hrAgeMs: Long?,
        hrvAgeMs: Long?,
        hrvN: Int?,
        hrvValid: Int,
        stepsAgeMs: Long?
    ): String {
        val tsIso = isoFmt.format(Date(tsMs))

        fun f(v: Float?): String = v?.let { "%.3f".format(Locale.US, it) } ?: ""
        fun l(v: Long?): String = v?.toString() ?: ""
        fun i(v: Int?): String = v?.toString() ?: ""
        fun s(v: String?): String = v?.let { csvEscape(it) } ?: ""

        return listOf(
            tsMs.toString(),
            tsIso,
            source,
            f(lux),
            f(dbfs),
            s(ssid),
            s(place),
            f(hrBpm),
            f(hrvRmssd),
            l(stepsDaily),
            l(stepsDelta),
            f(stepsPerMin),
            l(envAgeMs),
            l(hrAgeMs),
            l(hrvAgeMs),
            i(hrvN),
            hrvValid.toString(),
            l(stepsAgeMs)
        ).joinToString(",")
    }

    private fun csvEscape(x: String): String {
        val needQuote = x.contains(",") || x.contains("\"") || x.contains("\n") || x.contains("\r")
        val y = x.replace("\"", "\"\"")
        return if (needQuote) "\"$y\"" else y
    }

    private fun closeLocked() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        wroteHeader = false
    }
}