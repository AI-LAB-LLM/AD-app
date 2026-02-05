package com.example.ad_app

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

    // ---- Light sensor ----
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    @Volatile private var lastLux: Float = -1f

    // ---- Wi-Fi ----
    @Volatile private var lastSsid: String? = null
    @Volatile private var lastPlace: String = "unknown"

    // ---- Audio (noise) ----
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var audioThread: Thread? = null

    private val CHANNEL_ID = "env_measure_channel"
    private val NOTI_ID = 1001

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

        val micGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "startMeasuring(): micGranted=$micGranted")

        startForeground(NOTI_ID, buildNotification())

        // Light sensor
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Light sensor listener registered")
        } ?: run {
            Log.w(TAG, "Light sensor not available")
        }

        // Audio
        if (micGranted) {
            startAudioMeter()
        } else {
            Log.w(TAG, "RECORD_AUDIO not granted. Audio meter skipped.")
        }

        // Wi-Fi는 시작 시 1번 찍어두기
        refreshWifiContext()

        isRunning = true
        Log.i(TAG, "startMeasuring(): started")
    }

    private fun stopMeasuringAndSelf() {
        Log.i(TAG, "stopMeasuringAndSelf()")
        stopAudioMeter()
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {}

        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy()")
        // 중복 호출돼도 안전하게 동작하도록 stopMeasuringAndSelf 내부가 방어됨
        stopMeasuringAndSelf()
        super.onDestroy()
    }

    // -------- Light sensor --------
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            lastLux = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // -------- Wi-Fi context --------
    private fun refreshWifiContext() {
        // WifiPlace.kt의 함수 사용 (같은 패키지에 있어야 함)
        val ssid = getCurrentWifiSsid(this)
        val place = resolvePlaceLabelFromWifi(ssid)

        lastSsid = ssid
        lastPlace = place

        Log.d(TAG, "wifi ssid=$ssid place=$place")
    }

    // -------- Audio meter --------
    @SuppressLint("MissingPermission")
    private fun startAudioMeter() {
        if (isRecording) {
            Log.i(TAG, "startAudioMeter(): already recording")
            return
        }

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        Log.i(TAG, "Audio config: sampleRate=$sampleRate, channel=MONO, format=PCM_16BIT")
        Log.i(TAG, "minBufferSize(bytes)=$minBuffer")

        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize failed: $minBuffer")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuffer * 2
        )

        Log.i(TAG, "AudioRecord state=${audioRecord?.state} (1=initialized)")

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed. state=${audioRecord?.state}")
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        audioRecord?.startRecording()
        Log.i(TAG, "AudioRecord started")

        audioThread = Thread {
            val buffer = ShortArray(minBuffer / 2) // minBuffer는 bytes 기준이라 /2
            var lastSendAt = 0L
            var lastWifiCheckAt = 0L

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                val now = System.currentTimeMillis()

                // 3초에 1번 정도만 Wi-Fi 확인 (너무 자주 확인할 필요 없음)
                if (now - lastWifiCheckAt > 3000) {
                    refreshWifiContext()
                    lastWifiCheckAt = now
                }

                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val v = buffer[i].toDouble()
                        sum += v * v
                    }
                    val rms = sqrt(sum / read)

                    // dBFS (절대 SPL 아님)
                    val db = if (rms > 0) 20.0 * log10(rms / 32768.0) else -120.0

                    // 0.5초에 1번만 업데이트 브로드캐스트
                    if (now - lastSendAt > 500) {
                        broadcastUpdate(
                            lux = lastLux,
                            dbfs = db.toFloat(),
                            ssid = lastSsid,
                            place = lastPlace
                        )
                        lastSendAt = now
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error: $read")
                }

                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {}
            }

            Log.i(TAG, "Audio thread ended")
        }.also { it.start() }

        Log.i(TAG, "Audio meter started")
    }

    private fun stopAudioMeter() {
        if (!isRecording && audioRecord == null) return

        isRecording = false
        try { audioThread?.join(500) } catch (_: Exception) {}
        audioThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        Log.i(TAG, "Audio meter stopped")
    }

    private fun broadcastUpdate(lux: Float, dbfs: Float, ssid: String?, place: String) {
        val i = Intent(ACTION_UPDATE).apply {
            setPackage(packageName) // 앱 내부로만
            putExtra(EXTRA_LUX, lux)
            putExtra(EXTRA_DBFS, dbfs)
            putExtra(EXTRA_SSID, ssid ?: "")
            putExtra(EXTRA_PLACE, place)
        }
        sendBroadcast(i)
    }

    // -------- Notification --------
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
            .setContentText("조도/소음/Wi-Fi 맥락 수집 중")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
