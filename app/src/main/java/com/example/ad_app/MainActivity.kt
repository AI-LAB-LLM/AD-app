package com.example.ad_app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ad_app.bio.BioMeasureService
import com.example.ad_app.env.EnvMeasureService
import java.io.File

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

    // Wear OS/Android 16 대비(Health permission)
    private val PERM_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    private val TARGET_SDK_36 = 36

    private fun shouldUseHealthPermission(): Boolean {
        return applicationInfo.targetSdkVersion >= TARGET_SDK_36
    }

    private fun logDirPath(): String {
        val dir = File(getExternalFilesDir(null), "ad_logs")
        return dir.absolutePath
    }

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

                    tvHrv.text = if (hrvRmssd >= 0) {
                        "HRV(RMSSD): %.1f ms".format(hrvRmssd)
                    } else {
                        "HRV(RMSSD): - ms"
                    }
                }
            }
        }
    }

    private val requestAllPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val nearbyOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else true

        val activityOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        val bodySensorsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

        val readHrOk = if (shouldUseHealthPermission()) {
            ContextCompat.checkSelfPermission(this, PERM_READ_HEART_RATE) == PackageManager.PERMISSION_GRANTED
        } else true

        Log.i(TAG, "perm mic=$micOk fine=$fineOk coarse=$coarseOk nearby=$nearbyOk activity=$activityOk bodySensors=$bodySensorsOk readHr=$readHrOk targetSdk=${applicationInfo.targetSdkVersion}")

        // 기존 정책 유지: 마이크 권한 있어야 시작
        if (micOk) {
            startServiceMeasuring()
        } else {
            tvNoise.text = "소음(상대): 마이크 권한 필요"
            Toast.makeText(this, "마이크 권한이 필요해요", Toast.LENGTH_SHORT).show()
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
            finish()
        }

        Log.i(TAG, "CSV dir: ${logDirPath()}")
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
        val need = LinkedHashSet<String>() // 중복 방지

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.BODY_SENSORS
        }

        if (shouldUseHealthPermission() &&
            ContextCompat.checkSelfPermission(this, PERM_READ_HEART_RATE) != PackageManager.PERMISSION_GRANTED
        ) {
            need += PERM_READ_HEART_RATE
        }

        if (need.isEmpty()) {
            startServiceMeasuring()
        } else {
            Log.i(TAG, "Request permissions: $need (targetSdk=${applicationInfo.targetSdkVersion})")
            requestAllPermissions.launch(need.toTypedArray())
        }
    }

    private fun refreshButtons() {
        val running = EnvMeasureService.isRunning || BioMeasureService.isRunning
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun startServiceMeasuring() {
        Log.i(TAG, "startServiceMeasuring()")
        Toast.makeText(this, "CSV 저장 위치: ${logDirPath()}", Toast.LENGTH_LONG).show()

        val env = Intent(this, EnvMeasureService::class.java).apply {
            action = EnvMeasureService.ACTION_START
        }
        ContextCompat.startForegroundService(this, env)

        val bio = Intent(this, BioMeasureService::class.java).apply {
            action = BioMeasureService.ACTION_START
        }
        // BioMeasureService가 FGS면 여기서도 startForegroundService 사용 권장
        ContextCompat.startForegroundService(this, bio)

        refreshButtons()
    }

    private fun stopServiceMeasuring() {
        Log.i(TAG, "stopServiceMeasuring()")

        startService(Intent(this, EnvMeasureService::class.java).apply { action = EnvMeasureService.ACTION_STOP })
        startService(Intent(this, BioMeasureService::class.java).apply { action = BioMeasureService.ACTION_STOP })

        refreshButtons()
        Toast.makeText(this, "저장된 CSV는 Device Explorer/adb로 추출 가능해요", Toast.LENGTH_SHORT).show()
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
