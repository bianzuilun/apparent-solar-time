package com.apparentsolar.time

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/** Only expose each vendor's catalogue entry on its own devices. */
object OemWidgetProviders {
    fun configure(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val brand = Build.BRAND.lowercase(Locale.ROOT)
        val vivo = listOf(manufacturer, brand).any { it.contains("vivo") || it.contains("iqoo") }
        val xiaomi = listOf(manufacturer, brand).any {
            it.contains("xiaomi") || it.contains("redmi") || it.contains("poco")
        }
        setEnabled(context, VivoSolarWidgetProvider::class.java, vivo)
        setEnabled(context, XiaomiSolarWidgetProvider::class.java, xiaomi)
    }

    private fun setEnabled(context: Context, provider: Class<*>, enabled: Boolean) {
        val manager = context.packageManager
        val component = ComponentName(context, provider)
        val current = manager.getComponentEnabledSetting(component)
        val next = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        // Both receivers are disabled in the manifest, so DEFAULT already means disabled.
        if (current != next && (enabled || current != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)) {
            manager.setComponentEnabledSetting(component, next, PackageManager.DONT_KILL_APP)
        }
    }
}
