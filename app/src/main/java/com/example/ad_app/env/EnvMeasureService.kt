package com.example.ad_app.env

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ad_app.DataLogger
import com.example.ad_app.R
import kotlin.math.log10
import kotlin.math.sqrt

class EnvMeasureService : Service(), SensorEventListener {

    companion object {
        const val TAG = "ADAppService"

        const val ACTION_START = "com.example.ad_app.action.START"
        const val ACTION_STOP = "com.example.ad_app.action.STOP"
        const val ACTION_UPDATE = "com.example.ad_app.action.UPDATE"

        const val EXTRA_LUX = "lux"
        const val EXTRA_DBFS = "dbfs"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PLACE = "place"

        @Volatile var isRunning: Boolean = false
            private set
    }

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    @Volatile private var lastLux: Float = -1f

    @Volatile private var lastSsid: String? = null
    @Volatile private var lastPlace: String = "unknown"

    // 장기 수집용: 소음 10초마다 1초만 샘플링
    private val NOISE_EVERY_MS = 10_000L
    private val NOISE_WINDOW_MS = 1_000L
    private val WIFI_REFRESH_MS = 60_000L

    @Volatile private var isRecording = false
    private var audioThread: Thread? = null

    private val CHANNEL_ID = "env_measure_channel"
    private val NOTI_ID = 1001

    private var logRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate()")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMeasuring()
            ACTION_STOP -> stopMeasuringAndSelf()
            else -> startMeasuring()
        }
        return START_STICKY
    }

    private fun startMeasuring() {
        if (isRunning) {
            Log.i(TAG, "startMeasuring(): already running")
            return
        }

        if (!logRegistered) {
            DataLogger.register(this)
            logRegistered = true
        }

        startForeground(NOTI_ID, buildNotification())

        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        refreshWifiContext()

        val micGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (micGranted) startAudioDutyCycle()
        else Log.w(TAG, "RECORD_AUDIO not granted. noise skipped.")

        isRunning = true
        Log.i(TAG, "startMeasuring(): started")
    }

    private fun stopMeasuringAndSelf() {
        if (!isRunning) {
            if (logRegistered) {
                DataLogger.unregister()
                logRegistered = false
            }
            stopSelf()
            return
        }

        stopAudioDutyCycle()
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}

        if (logRegistered) {
            DataLogger.unregister()
            logRegistered = false
        }

        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopMeasuringAndSelf()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            lastLux = event.values[0]
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun refreshWifiContext() {
        val ssid = getCurrentWifiSsid(this)
        val place = resolvePlaceLabelFromWifi(ssid)
        lastSsid = ssid
        lastPlace = place
        Log.d(TAG, "wifi ssid=$ssid place=$place")
    }

    // ---------- Audio (duty cycle) ----------
    private fun startAudioDutyCycle() {
        if (isRecording) return
        isRecording = true

        audioThread = Thread {
            var lastWifiAt = 0L

            while (isRecording) {
                val now = System.currentTimeMillis()

                // Wi-Fi는 60초마다만
                if (now - lastWifiAt > WIFI_REFRESH_MS) {
                    refreshWifiContext()
                    lastWifiAt = now
                }

                // 1초만 샘플링해서 dbfs 계산
                val dbfs = captureDbfsWindow(NOISE_WINDOW_MS) ?: -120f

                // 최신값 갱신(파일 write는 DataLogger가 10초마다)
                DataLogger.updateEnv(
                    lux = lastLux,
                    dbfs = dbfs,
                    ssid = lastSsid,
                    place = lastPlace
                )

                // UI 업데이트(10초에 1번)
                broadcastUpdate(lux = lastLux, dbfs = dbfs, ssid = lastSsid, place = lastPlace)

                // 남은 시간 sleep (총 10초 주기)
                val elapsed = System.currentTimeMillis() - now
                val sleepMs = (NOISE_EVERY_MS - elapsed).coerceAtLeast(0L)
                try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
            }
        }.also { it.start() }
    }

    private fun stopAudioDutyCycle() {
        isRecording = false
        try { audioThread?.join(500) } catch (_: Exception) {}
        audioThread = null
    }

    @SuppressLint("MissingPermission")
    private fun captureDbfsWindow(windowMs: Long): Float? {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) return null

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuffer * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }

        val buffer = ShortArray(minBuffer / 2)
        var sumSq = 0.0
        var count = 0

        rec.startRecording()
        val endAt = System.currentTimeMillis() + windowMs

        while (System.currentTimeMillis() < endAt) {
            val read = rec.read(buffer, 0, buffer.size)
            if (read > 0) {
                for (i in 0 until read) {
                    val v = buffer[i].toDouble()
                    sumSq += v * v
                }
                count += read
            }
        }

        try { rec.stop() } catch (_: Exception) {}
        rec.release()

        if (count <= 0) return null
        val rms = sqrt(sumSq / count)
        val db = if (rms > 0) 20.0 * log10(rms / 32768.0) else -120.0
        return db.toFloat()
    }

    private fun broadcastUpdate(lux: Float, dbfs: Float, ssid: String?, place: String) {
        val i = Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LUX, lux)
            putExtra(EXTRA_DBFS, dbfs)
            putExtra(EXTRA_SSID, ssid ?: "")
            putExtra(EXTRA_PLACE, place)
        }
        sendBroadcast(i)
    }

    // ---------- Notification ----------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Environment Measurement",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("환경 측정 중")
            .setContentText("조도/소음/Wi-Fi 맥락 수집 및 로컬 저장")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}