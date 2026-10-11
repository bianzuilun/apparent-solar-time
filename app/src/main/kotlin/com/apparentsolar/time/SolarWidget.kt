package com.apparentsolar.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import com.apparentsolar.solar.SolarTime
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * 小组件刷新机制：
 *
 * RemoteViews 里的 Chronometer 以「真太阳午夜」为锚点自行按秒走字，
 * 不需要（也不允许）每秒推送一次小组件更新。
 *
 * 锚点漂移来源是真太阳日长度的缓变（均时差一天最多变化约 20 秒），
 * 因此每个真太阳整点用闹钟重锚一次即可把误差控制在 1 秒内。
 * 非唤醒闹钟：息屏时不触发（此时没人看表），亮屏后补发。
 */
object SolarWidget {

    const val ACTION_REBASE = "com.apparentsolar.time.action.REBASE"

    private const val HOUR_MS = 3_600_000L

    fun updateAll(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = canScheduleExact(alarmManager)

        val now = System.currentTimeMillis()
        val zoneOffset = TimeZone.getDefault().getOffset(now) / 1000
        val saved = LocationStore.load(app)
        val longitude = saved?.longitude ?: SolarTime.zoneMeridianDegrees(zoneOffset)
        val snap = SolarTime.snapshot(now, longitude, zoneOffset)

        val rv = RemoteViews(app.packageName, R.layout.widget_solar_time)
        val base = SystemClock.elapsedRealtime() - snap.apparentSolarMillisOfDay
        // 有精确闹钟时每个整点都会重锚，可以把 0 点后一小时显示成 0:MM:SS 保持 HH:MM:SS 观感；
        // 没有精确闹钟时不设格式，Chronometer 在 0~1 点显示为 M:SS（数值仍正确）
        val format = if (exact && snap.apparentSolarMillisOfDay < HOUR_MS) "0:%s" else null
        rv.setChronometer(R.id.chrono, base, format, true)
        rv.setTextViewText(R.id.tvOffset, offsetLine(snap))
        rv.setTextViewText(R.id.tvDetail, detailLine(snap, saved))
        AppWidgetManager.getInstance(app).updateAppWidget(componentName(app), rv)

        scheduleAlarms(app, snap, exact)
    }

    fun componentName(context: Context): ComponentName =
        ComponentName(context, SolarWidgetProvider::class.java)

    fun cancelAlarms(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(rebasePendingIntent(app))
    }

    fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()

    private fun scheduleAlarms(context: Context, snap: SolarTime.Snapshot, exact: Boolean) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = rebasePendingIntent(context)
        alarmManager.cancel(pi)
        if (exact) {
            // 精确路径：链式单发，对齐下一个真太阳整点（顺带覆盖真太阳午夜翻日）
            val delay = HOUR_MS - snap.apparentSolarMillisOfDay % HOUR_MS
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC, System.currentTimeMillis() + delay + 50, pi
            )
        }
        // 兜底路径（含精确闹钟被系统/OEM 推迟的情形）：每小时不精确重锚一次
        alarmManager.setInexactRepeating(
            AlarmManager.RTC, System.currentTimeMillis() + HOUR_MS, HOUR_MS, pi
        )
    }

    private fun rebasePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SolarWidgetProvider::class.java).setAction(ACTION_REBASE)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun offsetLine(snap: SolarTime.Snapshot): String =
        "较设备时间 ${signedHms(snap.offsetFromStandardSeconds)}"

    private fun detailLine(snap: SolarTime.Snapshot, saved: LocationStore.SavedLocation?): String {
        val loc = saved?.let {
            String.format(Locale.US, "%.2f°%s", abs(it.longitude), if (it.longitude >= 0) "E" else "W")
        } ?: "未定位"
        return "均时差 ${hm(snap.equationOfTimeSeconds)} · $loc"
    }

    internal fun hm(seconds: Double): String {
        val sign = if (seconds < -0.5) "-" else "+"
        val a = abs(seconds).toLong()
        return String.format(Locale.US, "%s%02d:%02d", sign, a / 60, a % 60)
    }

    internal fun signedHms(seconds: Double): String {
        val sign = if (seconds < -0.5) "-" else "+"
        val a = abs(seconds).toLong()
        return String.format(Locale.US, "%s%02d:%02d:%02d", sign, a / 3600, a % 3600 / 60, a % 60)
    }
}
