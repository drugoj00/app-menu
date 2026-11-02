package app.tvapps

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

/** Виджеты Android на экране «Дом»: хозяин виджетов программы и список доступных. */
object AppWidgets {
    private var h: AppWidgetHost? = null

    fun host(ctx: Context): AppWidgetHost = h ?: AppWidgetHost(ctx.applicationContext, 1024).also { h = it }

    fun release(ctx: Context, ref: String) {
        ref.toIntOrNull()?.let { runCatching { host(ctx).deleteAppWidgetId(it) } }
    }

    fun providers(ctx: Context): List<AppWidgetProviderInfo> =
        runCatching { AppWidgetManager.getInstance(ctx).installedProviders }.getOrDefault(emptyList())
}

/** Незавершённое добавление виджета Android: разрешение на привязку или окно настройки самого виджета. */
internal data class WidgetFlow(val rowId: String, val slot: Int, val id: Int, val info: AppWidgetProviderInfo)
