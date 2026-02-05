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

class MainActivity : AppCompatActivity() {

    private val TAG = "ADApp"

    private lateinit var tvLux: TextView
    private lateinit var tvNoise: TextView
    private lateinit var tvWifi: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var receiverRegistered = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != EnvMeasureService.ACTION_UPDATE) return

            val lux = intent.getFloatExtra(EnvMeasureService.EXTRA_LUX, -1f)
            val db = intent.getFloatExtra(EnvMeasureService.EXTRA_DBFS, -120f)
            val ssid = intent.getStringExtra(EnvMeasureService.EXTRA_SSID).orEmpty()
            val place = intent.getStringExtra(EnvMeasureService.EXTRA_PLACE).orEmpty()

            tvLux.text = if (lux >= 0) "조도: %.1f lux".format(lux) else "조도: -"
            tvNoise.text = "소음(상대): %.1f dBFS".format(db)

            // ssid가 ""면 읽기 실패(권한/설정/비WiFi 등)
            val ssidText = if (ssid.isBlank()) "-" else ssid
            val placeText = if (place.isBlank()) "unknown" else place
            tvWifi.text = "WiFi: $ssidText  ($placeText)"
        }
    }

    private val requestAllPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // Wi-Fi SSID는 기기/OS별로 위치권한 or nearby-wifi 권한이 필요할 수 있음
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearbyOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else true

        Log.i(TAG, "perm mic=$micOk fine=$fineOk coarse=$coarseOk nearby=$nearbyOk")

        if (micOk) {
            // 마이크만 OK면 측정은 가능. Wi-Fi는 허용 안 해도 unknown으로 처리됨.
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
        unregisterUpdateReceiver() // 앱 나가도 서비스는 계속, 리시버만 해제
    }

    private fun ensurePermissionsThenStart() {
        val need = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }

        // SSID 읽기용: 보통 위치권한이 필요
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }

        // Android 13+ 기기에서 Wi-Fi 접근이 막히는 경우 대비
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        if (need.isEmpty()) {
            startServiceMeasuring()
        } else {
            Log.i(TAG, "Request permissions: $need")
            requestAllPermissions.launch(need.toTypedArray())
        }
    }

    private fun refreshButtons() {
        val running = EnvMeasureService.isRunning
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun startServiceMeasuring() {
        Log.i(TAG, "startServiceMeasuring()")
        val i = Intent(this, EnvMeasureService::class.java).apply {
            action = EnvMeasureService.ACTION_START
        }
        ContextCompat.startForegroundService(this, i)
        refreshButtons()
    }

    private fun stopServiceMeasuring() {
        Log.i(TAG, "stopServiceMeasuring()")
        val i = Intent(this, EnvMeasureService::class.java).apply {
            action = EnvMeasureService.ACTION_STOP
        }
        startService(i)
        refreshButtons()
    }

    private fun registerUpdateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(EnvMeasureService.ACTION_UPDATE)

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
