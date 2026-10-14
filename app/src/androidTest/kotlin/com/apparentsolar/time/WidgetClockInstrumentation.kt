package com.apparentsolar.time

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Chronometer
import android.widget.RemoteViews
import android.widget.TextView
import com.apparentsolar.solar.SolarTime
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exercises the actual launcher-compatible views without changing the device clock or app data. */
class WidgetClockInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            var checked = 0
            val cases = listOf(0, 5, 545, 3021, 3599, 3600, 3605, 35999, 36000, 86399)
            for (layout in listOf(R.layout.widget_solar_time, R.layout.widget_solar_time_xiaomi)) {
                for (seconds in cases) {
                    runOnMainSync {
                        val fixture = makeFixture(layout, seconds)
                        val expected = String.format(Locale.US, "%02d:%02d:%02d",
                            seconds / 3600, seconds / 60 % 60, seconds % 60)
                        check(presentedClock(fixture) == expected) {
                            "Expected $expected, got ${presentedClock(fixture)} (layout=$layout)"
                        }
                        if (seconds == 3021 || seconds == 3600) {
                            saveRendering(fixture, "widget-clock-$layout-$seconds.png")
                        }
                    }
                    checked++
                }
            }
            output.putString("summary", "PASS: $checked native RemoteViews layout checks, including 00:50:21, 00:59:59 and 01:00:00")
            output.putString("screenshots", targetContext.getExternalFilesDir(null)?.absolutePath)
        } catch (error: Throwable) {
            output.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
            return
        }
        finish(Activity.RESULT_OK, output)
    }

    private fun makeFixture(layout: Int, seconds: Int): View {
        val views = RemoteViews(targetContext.packageName, layout)
        views.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() - seconds * 1000L, null, false)
        views.setTextViewText(R.id.tvShichen, SolarTime.shichenLabel(seconds * 1000L))
        views.setTextViewText(R.id.tvOffset, "时钟格式验证")
        views.setTextViewText(R.id.tvDetail, "样例显示，不改变系统时间")
        val fixture = views.apply(targetContext, null)
        val density = targetContext.resources.displayMetrics.density
        fixture.measure(View.MeasureSpec.makeMeasureSpec((300 * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((160 * density).roundToInt(), View.MeasureSpec.EXACTLY))
        fixture.layout(0, 0, fixture.measuredWidth, fixture.measuredHeight)
        return fixture
    }

    private fun presentedClock(fixture: View): String {
        val template = fixture.findViewById<TextView>(R.id.clockZeroPad)
        val counter = fixture.findViewById<Chronometer>(R.id.chrono)
        val glyphWidth = template.paint.measureText("0")
        val delta = (counter.left - template.left).toFloat()
        val leading = (delta / glyphWidth).roundToInt()
        check(leading in 0..4 && abs(delta - leading * glyphWidth) <= 1.1f) {
            "Misaligned zero padding: delta=$delta, glyph=$glyphWidth"
        }
        check(counter.width >= counter.paint.measureText(counter.text.toString()) - 1.1f)
        return template.text.take(leading).toString() + counter.text
    }

    private fun saveRendering(fixture: View, name: String) {
        val bitmap = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        fixture.draw(Canvas(bitmap))
        File(targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
