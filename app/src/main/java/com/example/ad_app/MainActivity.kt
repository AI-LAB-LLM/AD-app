package com.example.ad_app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ad_app.bio.BioMeasureService
import com.example.ad_app.env.EnvMeasureService

class MainActivity : AppCompatActivity() {

    private val TAG = "ADApp"

    private lateinit var tvLux: TextView
    private lateinit var tvNoise: TextView
    private lateinit var tvWifi: TextView

    private lateinit var tvHr: TextView
    private lateinit var tvSteps: TextView
    private lateinit var tvHrv: TextView

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var receiverRegistered = false

    private val PERM_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    private val SDK_36 = 36

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                // 환경 데이터
                EnvMeasureService.ACTION_UPDATE -> {
                    val lux = intent.getFloatExtra(EnvMeasureService.EXTRA_LUX, -1f)
                    val db = intent.getFloatExtra(EnvMeasureService.EXTRA_DBFS, -120f)
                    val ssid = intent.getStringExtra(EnvMeasureService.EXTRA_SSID).orEmpty()
                    val place = intent.getStringExtra(EnvMeasureService.EXTRA_PLACE).orEmpty()

                    tvLux.text = if (lux >= 0) "조도: %.1f lux".format(lux) else "조도: -"
                    tvNoise.text = "소음(상대): %.1f dBFS".format(db)

                    val ssidText = if (ssid.isBlank()) "-" else ssid
                    val placeText = if (place.isBlank()) "unknown" else place
                    tvWifi.text = "WiFi: $ssidText  ($placeText)"
                }

                // 생체 데이터
                BioMeasureService.ACTION_UPDATE -> {
                    val hr = intent.getFloatExtra(BioMeasureService.EXTRA_HR_BPM, -1f)
                    val stepsDaily = intent.getLongExtra(BioMeasureService.EXTRA_STEPS_DAILY, -1L)
                    val stepsPerMin = intent.getFloatExtra(BioMeasureService.EXTRA_STEPS_PER_MIN, 0f)
                    val hrvRmssd = intent.getFloatExtra(BioMeasureService.EXTRA_HRV_RMSSD, -1f)

                    tvHr.text = if (hr >= 0) "심박: %.0f bpm".format(hr) else "심박: - bpm"

                    tvSteps.text = if (stepsDaily >= 0) {
                        "걸음 수(오늘): $stepsDaily  (≈%.0f spm)".format(stepsPerMin)
                    } else {
                        "걸음 수(오늘): -"
                    }

                    tvHrv.text = if (hrvRmssd >= 0) "HRV(RMSSD): %.1f ms".format(hrvRmssd) else "HRV(RMSSD): - ms"
                }
            }
        }
    }

    private val requestAllPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->

        val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val nearbyOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else true

        val activityOk =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        val hrOk = if (Build.VERSION.SDK_INT >= SDK_36) {
            ContextCompat.checkSelfPermission(this, PERM_READ_HEART_RATE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        }

        Log.i(TAG, "perm mic=$micOk fine=$fineOk coarse=$coarseOk nearby=$nearbyOk activity=$activityOk hr=$hrOk")

        // 기존 정책 유지: 마이크 권한이 있어야 “측정 시작”
        if (micOk) {
            startServiceMeasuring()
        } else {
            tvNoise.text = "소음(상대): 마이크 권한 필요"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLux = findViewById(R.id.tvLux)
        tvNoise = findViewById(R.id.tvNoise)
        tvWifi = findViewById(R.id.tvWifi)

        tvHr = findViewById(R.id.tvHr)
        tvSteps = findViewById(R.id.tvSteps)
        tvHrv = findViewById(R.id.tvHrv)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        refreshButtons()

        btnStart.setOnClickListener { ensurePermissionsThenStart() }
        btnStop.setOnClickListener {
            stopServiceMeasuring()
            finish() // “측정 중단 눌러야 앱 꺼지게” 유지
        }
    }

    override fun onStart() {
        super.onStart()
        registerUpdateReceiver()
        refreshButtons()
    }

    override fun onStop() {
        super.onStop()
        unregisterUpdateReceiver()
    }

    private fun ensurePermissionsThenStart() {
        val need = mutableListOf<String>()

        // Env
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        // Bio
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (Build.VERSION.SDK_INT >= SDK_36) {
            if (ContextCompat.checkSelfPermission(this, PERM_READ_HEART_RATE) != PackageManager.PERMISSION_GRANTED) {
                need += PERM_READ_HEART_RATE
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                need += Manifest.permission.BODY_SENSORS
            }
        }

        if (need.isEmpty()) {
            startServiceMeasuring()
        } else {
            Log.i(TAG, "Request permissions: $need")
            requestAllPermissions.launch(need.toTypedArray())
        }
    }

    private fun refreshButtons() {
        // 지금은 EnvMeasureService 실행 여부로 버튼 상태를 잡음
        val running = EnvMeasureService.isRunning
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun startServiceMeasuring() {
        Log.i(TAG, "startServiceMeasuring()")

        // 1) 환경 측정
        val env = Intent(this, EnvMeasureService::class.java).apply {
            action = EnvMeasureService.ACTION_START
        }
        ContextCompat.startForegroundService(this, env)

        // 2) 생체 측정
        val bio = Intent(this, BioMeasureService::class.java).apply {
            action = BioMeasureService.ACTION_START
        }
        startService(bio)

        refreshButtons()
    }

    private fun stopServiceMeasuring() {
        Log.i(TAG, "stopServiceMeasuring()")

        val env = Intent(this, EnvMeasureService::class.java).apply {
            action = EnvMeasureService.ACTION_STOP
        }
        startService(env)

        val bio = Intent(this, BioMeasureService::class.java).apply {
            action = BioMeasureService.ACTION_STOP
        }
        startService(bio)

        refreshButtons()
    }

    private fun registerUpdateReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(EnvMeasureService.ACTION_UPDATE)
            addAction(BioMeasureService.ACTION_UPDATE)
        }

        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered = true
        Log.i(TAG, "updateReceiver registered")
    }

    private fun unregisterUpdateReceiver() {
        if (!receiverRegistered) return
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        receiverRegistered = false
        Log.i(TAG, "updateReceiver unregistered")
    }
}
