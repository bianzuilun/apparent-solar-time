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
 * 整点使用允许空闲期间执行的唤醒闹钟，避免睡眠跨日后计时器累计到 24 小时以上。
 * 系统周期更新和点击组件提供独立恢复入口，不依赖 App 常驻。
 */
object SolarWidget {

    const val ACTION_REBASE = "com.apparentsolar.time.action.REBASE"

    private val providerLayouts = listOf(
        SolarWidgetProvider::class.java to R.layout.widget_solar_time,
        VivoSolarWidgetProvider::class.java to R.layout.widget_solar_time,
        XiaomiSolarWidgetProvider::class.java to R.layout.widget_solar_time_xiaomi
    )

    fun hasAnyWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return providerLayouts.any { (provider, _) ->
            manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
        }
    }

    fun updateAll(context: Context) {
        val app = context.applicationContext
        val widgetManager = AppWidgetManager.getInstance(app)
        val active = providerLayouts.mapNotNull { (provider, layout) ->
            val ids = widgetManager.getAppWidgetIds(ComponentName(app, provider))
            if (ids.isEmpty()) null else ids to layout
        }
        if (active.isEmpty()) {
            cancelAlarms(app)
            return
        }
        val now = System.currentTimeMillis()
        val zoneOffset = TimeZone.getDefault().getOffset(now) / 1000
        val saved = LocationStore.load(app)
        val longitude = saved?.longitude ?: SolarTime.zoneMeridianDegrees(zoneOffset)
        val snap = SolarTime.snapshot(now, longitude, zoneOffset)
        scheduleAlarms(app, now, longitude)

        val base = SystemClock.elapsedRealtime() - snap.apparentSolarMillisOfDay
        for ((ids, layout) in active) {
            val rv = RemoteViews(app.packageName, layout)
            // 补零由共享时钟布局负责，权限和整点更新延迟都不会改变显示格式。
            rv.setChronometer(R.id.chrono, base, null, true)
            rv.setTextViewText(R.id.tvShichen, SolarTime.shichenLabel(snap.apparentSolarMillisOfDay))
            rv.setTextViewText(R.id.tvOffset, offsetLine(snap))
            rv.setTextViewText(R.id.tvDetail, detailLine(snap, saved))
            rv.setOnClickPendingIntent(android.R.id.background, rebasePendingIntent(app, 2))
            widgetManager.updateAppWidget(ids, rv)
        }
    }

    fun cancelAlarms(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(rebasePendingIntent(app))
        alarmManager.cancel(rebasePendingIntent(app, 1))
    }

    fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun scheduleAlarms(context: Context, now: Long, longitude: Double) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactPi = rebasePendingIntent(context)
        val fallbackPi = rebasePendingIntent(context, 1)
        val trigger = SolarTime.nextSolarHourUtcMillis(now, longitude) + 250L

        // 始终保留一条无需特殊权限的唤醒闹钟。Android 在用户撤销精确闹钟权限时
        // 会取消现有精确闹钟并终止应用，届时代码没有机会现场降级；独立备用闹钟
        // 不会随精确闹钟一起消失，可在下个整点重新校准时间和时辰。
        alarmManager.cancel(fallbackPi)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, fallbackPi)

        // 清掉上一轮精确闹钟。若仍有权限，再增加一条准点触发的主闹钟；主闹钟
        // 触发 updateAll 后会把尚未触发的备用闹钟一起推进到下一个整点。
        alarmManager.cancel(exactPi)
        if (canScheduleExact(alarmManager)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, exactPi)
                return
            } catch (_: SecurityException) {
                // 授权检查与安排之间权限可能被撤销；上面的备用闹钟仍然有效。
            }
        }
    }

    private fun rebasePendingIntent(context: Context, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, SolarWidgetProvider::class.java)
            .setAction(ACTION_REBASE)
            // 这是很短的组件文字刷新。前台广播标记可减少厂商系统把到点广播
            // 当作普通后台自启动而冻结的概率，不会启动界面或常驻服务。
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
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
