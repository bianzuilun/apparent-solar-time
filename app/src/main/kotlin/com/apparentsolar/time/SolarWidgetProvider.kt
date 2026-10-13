package com.apparentsolar.time

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

open class SolarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SolarWidget.updateAll(context)
    }

    override fun onEnabled(context: Context) {
        SolarWidget.updateAll(context)
    }

    override fun onDisabled(context: Context) {
        if (!SolarWidget.hasAnyWidgets(context)) {
            SolarWidget.cancelAlarms(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == SolarWidget.ACTION_REBASE) {
            SolarWidget.updateAll(context)
        }
    }
}

class VivoSolarWidgetProvider : SolarWidgetProvider()

class XiaomiSolarWidgetProvider : SolarWidgetProvider()
