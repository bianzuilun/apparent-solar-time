package com.apparentsolar.time

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.apparentsolar.solar.SolarTime
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val engine by lazy { LocationEngine(applicationContext) }

    private lateinit var tvClock: TextView
    private lateinit var tvShichen: TextView
    private lateinit var tvOffset: TextView
    private lateinit var tvEq: TextView
    private lateinit var tvLon: TextView
    private lateinit var tvZone: TextView
    private lateinit var tvLoc: TextView
    private lateinit var btnExact: Button

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OemWidgetProviders.configure(this)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(R.layout.activity_main)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            findViewById<View>(R.id.mainScroll).setOnApplyWindowInsetsListener { view, insets ->
                val safeArea = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
                insets
            }
        }
        tvClock = findViewById(R.id.tvClock)
        tvShichen = findViewById(R.id.tvShichen)
        tvOffset = findViewById(R.id.tvOffset)
        tvEq = findViewById(R.id.tvEq)
        tvLon = findViewById(R.id.tvLon)
        tvZone = findViewById(R.id.tvZone)
        tvLoc = findViewById(R.id.tvLoc)
        btnExact = findViewById(R.id.btnExact)

        findViewById<Button>(R.id.btnLocate).setOnClickListener { ensurePermissionThenLocate() }
        findViewById<Button>(R.id.btnManual).setOnClickListener { showManualDialog() }
        findViewById<Button>(R.id.btnAbout).setOnClickListener { showAboutDialog() }
        btnExact.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        SolarWidget.updateAll(applicationContext)
        engine.startContinuous(object : LocationEngine.Callback {
            override fun onLocation(location: LocationStore.SavedLocation) {
                SolarWidget.updateAll(applicationContext)
            }
        })
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        engine.stop()
        SolarWidget.updateAll(applicationContext)
        super.onPause()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val zoneOffset = TimeZone.getDefault().getOffset(now) / 1000
        val saved = LocationStore.load(this)
        val longitude = saved?.longitude ?: SolarTime.zoneMeridianDegrees(zoneOffset)
        val snap = SolarTime.snapshot(now, longitude, zoneOffset)

        tvClock.text = hms(snap.apparentSolarMillisOfDay)
        tvShichen.text = SolarTime.shichenLabel(snap.apparentSolarMillisOfDay)
        tvOffset.text = "真太阳时较设备时间 " + SolarWidget.signedHms(snap.offsetFromStandardSeconds)
        tvEq.text = SolarWidget.hm(snap.equationOfTimeSeconds)
        tvLon.text = SolarWidget.hm(snap.longitudeCorrectionSeconds)
        tvZone.text = zoneLine(zoneOffset) +
            String.format(Locale.US, " ｜ 中央经线 %.1f°", SolarTime.zoneMeridianDegrees(zoneOffset))
        tvLoc.text = when (saved) {
            null -> "未定位，暂用时区中央经线（点「获取定位」或手动输入经纬度）"
            else -> String.format(
                Locale.US, "%.4f°%s, %.4f°%s ｜ %s ±%.0fm",
                abs(saved.latitude), if (saved.latitude >= 0) "N" else "S",
                abs(saved.longitude), if (saved.longitude >= 0) "E" else "W",
                saved.provider, saved.accuracyMeters
            )
        }

        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        btnExact.visibility =
            if (SolarWidget.canScheduleExact(am)) View.GONE else View.VISIBLE
    }

    private fun zoneLine(zoneOffsetSeconds: Int): String {
        val sign = if (zoneOffsetSeconds < 0) "-" else "+"
        val a = abs(zoneOffsetSeconds)
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, a / 3600, a % 3600 / 60)
    }

    private fun ensurePermissionThenLocate() {
        if (engine.hasPermission()) {
            startLocating()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_LOCATION,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startLocating()
            } else {
                Toast.makeText(this, "未获得定位权限，可手动输入经纬度", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLocating() {
        Toast.makeText(this, "正在定位，GPS 首次定位可能需要十几秒…", Toast.LENGTH_SHORT).show()
        engine.startContinuous(object : LocationEngine.Callback {
            override fun onLocation(location: LocationStore.SavedLocation) {
                SolarWidget.updateAll(applicationContext)
            }
        })
    }

    private fun showManualDialog() {
        val saved = LocationStore.load(this)
        val numberType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val latInput = EditText(this).apply {
            hint = "纬度（-90 ~ 90，北纬为正）"
            inputType = numberType
            setText(saved?.latitude?.toString() ?: "")
        }
        val lonInput = EditText(this).apply {
            hint = "经度（-180 ~ 180，东经为正）"
            inputType = numberType
            setText(saved?.longitude?.toString() ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(latInput)
            addView(lonInput)
        }
        AlertDialog.Builder(this)
            .setTitle("手动设置经纬度")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val lat = latInput.text.toString().toDoubleOrNull()
                val lon = lonInput.text.toString().toDoubleOrNull()
                if (lat == null || lon == null || abs(lat) > 90 || abs(lon) > 180) {
                    Toast.makeText(this, "经纬度无效", Toast.LENGTH_LONG).show()
                } else {
                    LocationStore.save(
                        this,
                        LocationStore.SavedLocation(lat, lon, 0f, "手动输入", System.currentTimeMillis())
                    )
                    SolarWidget.updateAll(applicationContext)
                    Toast.makeText(this, "已保存，小组件已同步", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAboutDialog() {
        val github = getString(R.string.about_github)
        val douyin = getString(R.string.about_douyin)
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setItems(arrayOf(
                getString(R.string.about_github_row, github),
                getString(R.string.about_douyin_row, douyin),
            )) { _, which ->
                val label = if (which == 0) "GitHub" else "抖音号"
                val value = if (which == 0) github else douyin
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                // Android 13+ displays its own clipboard confirmation.
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(this, getString(R.string.about_copied, label), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun hms(millis: Long): String {
        val t = millis / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60)
    }

    private companion object {
        const val REQUEST_LOCATION = 1
    }
}
