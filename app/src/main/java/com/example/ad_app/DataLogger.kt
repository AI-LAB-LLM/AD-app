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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object DataLogger {
    private const val TAG = "DataLogger"

    private const val LOG_INTERVAL_MS   = 10_000L
    private const val FLUSH_INTERVAL_MS = 60_000L
    private const val DIR_NAME          = "ad_logs"

    // ── stale 기준 ──────────────────────────────────
    // ENV  : 30초  (10초 주기로 측정하므로 여유 있게)
    // HR   : 180초 (Galaxy Watch는 정지 시 2분 이상 간격으로 수신될 수 있음)
    // HRV  : 120초 (IBI 계산 주기가 느림)
    private const val STALE_ENV_MS  = 30_000L
    private const val STALE_HR_MS   = 180_000L  // 120s → 180s (2분 공백 방지)
    private const val STALE_HRV_MS  = 120_000L

    // ── HRV 유효성 기준 ─────────────────────────────
    private const val HRV_MIN_N  = 10
    private const val HRV_MIN_MS = 5f
    private const val HRV_MAX_MS = 150f

    private val refCount = AtomicInteger(0)
    private val lock     = Any()

    private var scheduler     : ScheduledExecutorService? = null
    private var writer        : BufferedWriter?            = null
    private var currentFile   : File?                     = null
    private var currentDayKey : String?                   = null
    private var wroteHeader   = false
    private var lastFlushAt   : Long = 0L

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }

    // ── Env ─────────────────────────────────────────
    @Volatile private var envLux       : Float?  = null
    @Volatile private var envDbfs      : Float?  = null
    @Volatile private var envSsid      : String? = null
    @Volatile private var envPlace     : String? = null
    @Volatile private var envUpdatedAt : Long    = 0L

    // ── HR ──────────────────────────────────────────
    @Volatile private var bioHr          : Float? = null
    @Volatile private var bioHrUpdatedAt : Long   = 0L

    // ── HRV ─────────────────────────────────────────
    @Volatile private var bioHrv          : Float? = null
    @Volatile private var bioHrvN         : Int?   = null
    @Volatile private var bioHrvUpdatedAt : Long   = 0L

    // stale 구간에도 마지막 유효값을 hrv_valid=0 으로 채우기 위해 별도 보존
    @Volatile private var bioHrvLastValid  : Float? = null
    @Volatile private var bioHrvNLastValid : Int?   = null

    // ── Steps ────────────────────────────────────────
    // raw delta 이벤트를 queue에 쌓고, writeTick() 시점에 집계한다.
    // → steps_delta  = 지난 10초 동안 실제 걸은 걸음 수
    // → steps_per_min = 지난 60초 동안 실제 걸은 걸음 수
    private val stepEvents               = ConcurrentLinkedQueue<Pair<Long, Long>>()
    @Volatile private var bioStepsDaily          : Long? = null
    @Volatile private var bioStepsDailyUpdatedAt : Long  = 0L
    @Volatile private var bioStepEventUpdatedAt  : Long  = 0L

    // ════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════

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
                stepEvents.clear()
            }
        }
    }

    fun getCurrentFilePath(): String? = currentFile?.absolutePath

    // ── Env ─────────────────────────────────────────

    fun updateEnv(lux: Float, dbfs: Float, ssid: String?, place: String) {
        envLux       = if (lux >= 0) lux else null
        envDbfs      = dbfs
        envSsid      = ssid?.takeIf { it.isNotBlank() }
        envPlace     = place.takeIf { it.isNotBlank() }
        envUpdatedAt = System.currentTimeMillis()
    }

    // ── HR ──────────────────────────────────────────

    fun updateBioHr(hrBpm: Float) {
        if (hrBpm < 0) return
        bioHr          = hrBpm
        bioHrUpdatedAt = System.currentTimeMillis()
    }

    // ── HRV ─────────────────────────────────────────

    fun updateBioHrv(hrvRmssd: Float, hrvSampleCount: Int) {
        if (hrvRmssd < 0) return

        val now = System.currentTimeMillis()

        // 값이 같든 다르든 호출될 때마다 updatedAt 갱신
        // → HrvTracker가 emit했다는 것 자체가 "새로 계산됨"을 의미
        bioHrv          = hrvRmssd
        bioHrvN         = hrvSampleCount.takeIf { it >= 0 }
        bioHrvUpdatedAt = now

        // lastValid는 유효 기준 통과할 때만 갱신
        val valid = hrvSampleCount >= HRV_MIN_N && hrvRmssd in HRV_MIN_MS..HRV_MAX_MS
        if (valid) {
            bioHrvLastValid  = hrvRmssd
            bioHrvNLastValid = hrvSampleCount
        }
    }

    // ── Steps ────────────────────────────────────────

    fun updateBioStepsDaily(stepsDaily: Long) {
        if (stepsDaily < 0) return
        bioStepsDaily          = stepsDaily
        bioStepsDailyUpdatedAt = System.currentTimeMillis()
    }

    // BioMeasureService에서 delta 이벤트 발생 시 호출.
    // 집계(steps_delta, steps_per_min)는 writeTick()에서 수행한다.
    fun appendStepDelta(stepsDelta: Long, eventTimeMs: Long = System.currentTimeMillis()) {
        if (stepsDelta <= 0) return
        synchronized(lock) {
            stepEvents.add(eventTimeMs to stepsDelta)
            trimOldStepEventsLocked(eventTimeMs)
            bioStepEventUpdatedAt = eventTimeMs
        }
        Log.d(TAG, "appendStepDelta delta=$stepsDelta at=$eventTimeMs")
    }

    // ════════════════════════════════════════════════
    // 내부 스케줄
    // ════════════════════════════════════════════════

    private fun startSchedule(context: Context) {
        val sch = scheduler ?: return

        sch.scheduleAtFixedRate({
            try { writeTick(context) } catch (t: Throwable) { Log.e(TAG, "writeTick failed", t) }
        }, 0L, LOG_INTERVAL_MS, TimeUnit.MILLISECONDS)

        sch.scheduleAtFixedRate({
            synchronized(lock) {
                try { writer?.flush(); lastFlushAt = System.currentTimeMillis() } catch (_: Exception) {}
            }
        }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun writeTick(context: Context) {
        val now = System.currentTimeMillis()

        synchronized(lock) {
            ensureOpenOrRotate(context, now)
            writeHeaderIfNeededLocked()

            // ── age 계산 ─────────────────────────────
            val envAge        = envUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val hrAge         = bioHrUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val hrvAge        = bioHrvUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val stepsDailyAge = bioStepsDailyUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val stepEventAge  = bioStepEventUpdatedAt.takeIf { it > 0 }?.let { now - it }
            val stepsAge      = stepEventAge ?: stepsDailyAge

            // ── Env: stale이면 비움 ──────────────────
            val luxOut   = if (envAge != null && envAge <= STALE_ENV_MS) envLux   else null
            val dbfsOut  = if (envAge != null && envAge <= STALE_ENV_MS) envDbfs  else null
            val ssidOut  = if (envAge != null && envAge <= STALE_ENV_MS) envSsid  else null
            val placeOut = if (envAge != null && envAge <= STALE_ENV_MS) envPlace else null

            // ── HR: stale이면 비움 (180초 기준) ──────
            val hrFresh = hrAge != null && hrAge <= STALE_HR_MS
            val hrOut   = if (hrFresh) bioHr else null

            // ── HRV ──────────────────────────────────
            // fresh  → 현재 계산된 값 사용
            // stale  → lastValid 값으로 채우되 hrv_valid=0 표시 (컬럼 공백 방지)
            val hrvFresh = hrvAge != null && hrvAge <= STALE_HRV_MS
            val hrvOut: Float?
            val hrvNOut: Int?
            if (hrvFresh) {
                hrvOut  = bioHrv
                hrvNOut = bioHrvN
            } else {
                hrvOut  = bioHrvLastValid
                hrvNOut = bioHrvNLastValid
            }
            val hrvValidInt = if (
                hrvFresh &&
                hrvOut != null && hrvNOut != null &&
                hrvNOut >= HRV_MIN_N && hrvOut in HRV_MIN_MS..HRV_MAX_MS
            ) 1 else 0

            // ── Steps: writeTick 시점에 직접 집계 ───
            trimOldStepEventsLocked(now)
            val stepsDeltaOut  = sumStepEventsSinceLocked(now, LOG_INTERVAL_MS)
            val stepsPerMinOut = sumStepEventsSinceLocked(now, 60_000L).toFloat()
            val stepsDailyOut  = bioStepsDaily

            Log.d(TAG,
                "writeTick ts=$now " +
                        "hr=$hrOut(age=${hrAge}ms fresh=$hrFresh) " +
                        "hrv=$hrvOut n=$hrvNOut(age=${hrvAge}ms fresh=$hrvFresh valid=$hrvValidInt) " +
                        "stepsDelta=$stepsDeltaOut spm=$stepsPerMinOut"
            )

            val line = buildLine(
                tsMs        = now,
                source      = "tick",
                lux         = luxOut,
                dbfs        = dbfsOut,
                ssid        = ssidOut,
                place       = placeOut,
                hrBpm       = hrOut,
                hrvRmssd    = hrvOut,
                stepsDaily  = stepsDailyOut,
                stepsDelta  = stepsDeltaOut,
                stepsPerMin = stepsPerMinOut,
                envAgeMs    = envAge,
                hrAgeMs     = hrAge,
                hrvAgeMs    = hrvAge,
                hrvN        = hrvNOut,
                hrvValid    = hrvValidInt,
                stepsAgeMs  = stepsAge
            )
            writer?.write(line)
            writer?.newLine()

            if (now - lastFlushAt > FLUSH_INTERVAL_MS * 2) {
                try { writer?.flush(); lastFlushAt = now } catch (_: Exception) {}
            }
        }
    }

    // ════════════════════════════════════════════════
    // Steps queue 헬퍼 (synchronized(lock) 내부에서만 호출)
    // ════════════════════════════════════════════════

    private fun trimOldStepEventsLocked(now: Long) {
        val cutoff = now - 60_000L
        while (true) {
            val head = stepEvents.peek() ?: break
            if (head.first < cutoff) stepEvents.poll() else break
        }
    }

    private fun sumStepEventsSinceLocked(now: Long, windowMs: Long): Long {
        val cutoff = now - windowMs
        return stepEvents.asSequence().filter { it.first >= cutoff }.sumOf { it.second }
    }

    // ════════════════════════════════════════════════
    // 파일 관리
    // ════════════════════════════════════════════════

    private fun ensureOpenOrRotate(context: Context, now: Long) {
        val dayKey = dayFmt.format(Date(now))
        if (writer == null || currentDayKey != dayKey || currentFile == null) {
            closeLocked()
            val dir = File(context.getExternalFilesDir(null), DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "ad_log_${dayKey}.csv")
            currentFile   = file
            currentDayKey = dayKey
            writer        = BufferedWriter(FileWriter(file, true))
            wroteHeader   = file.exists() && file.length() > 0
            lastFlushAt   = 0L
            Log.i(TAG, "opened file=${file.absolutePath} wroteHeader=$wroteHeader")
        }
    }

    private fun writeHeaderIfNeededLocked() {
        if (wroteHeader) return
        val header = listOf(
            "ts_ms", "ts_iso", "source",
            "lux", "dbfs", "ssid", "place",
            "hr_bpm", "hrv_rmssd_ms",
            "steps_daily", "steps_delta", "steps_per_min",
            "env_age_ms", "hr_age_ms", "hrv_age_ms", "hrv_n", "hrv_valid", "steps_age_ms"
        ).joinToString(",")
        writer?.write(header); writer?.newLine(); writer?.flush()
        lastFlushAt = System.currentTimeMillis()
        wroteHeader = true
    }

    private fun buildLine(
        tsMs: Long, source: String,
        lux: Float?, dbfs: Float?, ssid: String?, place: String?,
        hrBpm: Float?, hrvRmssd: Float?,
        stepsDaily: Long?, stepsDelta: Long?, stepsPerMin: Float?,
        envAgeMs: Long?, hrAgeMs: Long?, hrvAgeMs: Long?,
        hrvN: Int?, hrvValid: Int, stepsAgeMs: Long?
    ): String {
        val tsIso = isoFmt.format(Date(tsMs))
        fun f(v: Float?): String  = v?.let { "%.3f".format(Locale.US, it) } ?: ""
        fun l(v: Long?): String   = v?.toString() ?: ""
        fun i(v: Int?): String    = v?.toString() ?: ""
        fun s(v: String?): String = v?.let { csvEscape(it) } ?: ""
        return listOf(
            tsMs.toString(), tsIso, source,
            f(lux), f(dbfs), s(ssid), s(place),
            f(hrBpm), f(hrvRmssd),
            l(stepsDaily), l(stepsDelta), f(stepsPerMin),
            l(envAgeMs), l(hrAgeMs), l(hrvAgeMs),
            i(hrvN), hrvValid.toString(), l(stepsAgeMs)
        ).joinToString(",")
    }

    private fun csvEscape(x: String): String {
        val needQuote = x.contains(",") || x.contains("\"") || x.contains("\n") || x.contains("\r")
        return if (needQuote) "\"${x.replace("\"", "\"\"")}\"" else x
    }

    private fun closeLocked() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer      = null
        wroteHeader = false
    }
}
