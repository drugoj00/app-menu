package app.tvapps

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

/**
 * Обёртка вокруг чужого виджета: долгое нажатие (палец или мышь) в ЛЮБОМ месте виджета открывает окно его настроек.
 * Сам виджет забирает касания себе, поэтому обычное долгое нажатие работало только в пустых местах: здесь оно перехватывается до виджета.
 * Короткое нажатие и прокрутка внутри виджета работают как прежде; правая кнопка мыши открывает окно сразу.
 */
class LongPressFrame(ctx: Context, private val onLong: () -> Unit) : android.widget.FrameLayout(ctx) {
    private var fired = false
    private var dx = 0f
    private var dy = 0f
    private var down: android.view.MotionEvent? = null
    private val fire = Runnable {
        val d = down ?: return@Runnable
        fired = true
        // виджету сообщаем об отмене касания: он не должен считать это своим нажатием
        val c = android.view.MotionEvent.obtain(d.downTime, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_CANCEL, dx, dy, 0)
        super.dispatchTouchEvent(c); c.recycle()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLong()
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                fired = false; dx = ev.x; dy = ev.y
                down?.recycle(); down = android.view.MotionEvent.obtain(ev)
                removeCallbacks(fire)
                if (ev.buttonState and android.view.MotionEvent.BUTTON_SECONDARY != 0) { fire.run(); return true }
                postDelayed(fire, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val s = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (!fired && (Math.abs(ev.x - dx) > s || Math.abs(ev.y - dy) > s)) removeCallbacks(fire)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { removeCallbacks(fire) }
        }
        return fired
    }

    // после долгого нажатия остаток жеста (движение, отпускание) гасим здесь
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) removeCallbacks(fire)
        return fired
    }

    override fun onDetachedFromWindow() { removeCallbacks(fire); down?.recycle(); down = null; super.onDetachedFromWindow() }
}

/** Виджеты Android на экране «Дом»: хозяин виджетов программы и список доступных. */
object AppWidgets {
    private var h: AppWidgetHost? = null

    fun host(ctx: Context): AppWidgetHost = h ?: AppWidgetHost(ctx.applicationContext, 1024).also { h = it }

    fun release(ctx: Context, ref: String) {
        ref.toIntOrNull()?.let { runCatching { host(ctx).deleteAppWidgetId(it) } }
    }

    /** Команды обновления идут по очереди в одном потоке: на каждый виджет своя, но не одновременно. */
    private val kickQueue by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "widget-kick").also { it.isDaemon = true } } }

    /**
     * Просит поставщика виджета перерисоваться: чужой виджет (часы, погода) обновляется только своим сервисом, а на приставке он нередко остановлен,
     * и виджет застывает на времени последнего обновления. Обычной программе сигнал APPWIDGET_UPDATE поставщику запрещён системой, поэтому:
     * 1) при root или подключённом ADB сигнал отправляется командой shell (виджет обновляется сразу и точно);
     * 2) всегда: размер меняется на единицу и возвращается, система сообщает поставщику «параметры изменились» (часть виджетов на это перерисовывается).
     */
    fun kick(view: android.appwidget.AppWidgetHostView, id: Int, info: AppWidgetProviderInfo, wDp: Int, hDp: Int) {
        runCatching {
            view.updateAppWidgetSize(null, wDp + 1, hDp + 1, wDp + 1, hDp + 1)
            view.postDelayed({ runCatching { view.updateAppWidgetSize(null, wDp, hDp, wDp, hDp) } }, 400)
        }
        val cn = info.provider ?: return
        if (!Adb.isConnected) return
        kickQueue.execute {
            try {
                val cmd = "am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n " + cn.flattenToShortString() + " --eia appWidgetIds " + id
                if (Adb.isRoot) {
                    val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().readText(); p.waitFor()
                } else kotlinx.coroutines.runBlocking { Adb.shell(cmd, 8_000) }
            } catch (_: Throwable) {
            }
        }
    }

    fun providers(ctx: Context): List<AppWidgetProviderInfo> =
        runCatching { AppWidgetManager.getInstance(ctx).installedProviders }.getOrDefault(emptyList())
}

/** Незавершённое добавление виджета Android: разрешение на привязку или окно настройки самого виджета. */
internal data class WidgetFlow(val rowId: String, val slot: Int, val id: Int, val info: AppWidgetProviderInfo)
