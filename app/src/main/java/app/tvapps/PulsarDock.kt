package app.tvapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

/**
 * 17.3 (просьба владелицы 24.11.2026): если Pulsar рисует свою нижнюю панель (док), док «Дома» поднимается выше неё сам.
 * Pulsar шлёт свой широковещательный Intent app.click.action.DOCK_STATE (on, h в пикселях от нижнего края) при показе и скрытии панели
 * и раз в 3 с, пока она на экране; значение старше 9 с считается устаревшим (Pulsar остановили), тогда сдвиг 0.
 */
object PulsarDock {
    private var h = 0
    private var at = 0L

    fun set(on: Boolean, hPx: Int) {
        h = if (on) hPx.coerceIn(0, 1200) else 0
        at = SystemClock.elapsedRealtime()
        HomeDock.rev.intValue++
    }

    /** Сообщает Pulsar, что на экране док Меню: панель Pulsar тогда не забирает фокус пульта себе (раз в 3 с и при закрытии). */
    fun announce(ctx: Context, on: Boolean) {
        runCatching { ctx.sendBroadcast(Intent("app.click.action.MENU_DOCK").setPackage("app.click").putExtra("on", on)) }
    }

    /** «Вниз» из дока Меню при панели Pulsar под ним: передать фокус пульта панели Pulsar. */
    fun focusDock(ctx: Context) {
        runCatching { ctx.sendBroadcast(Intent("app.click.action.FOCUS_DOCK").setPackage("app.click")) }
    }

    /** Если данные устарели, сбрасывает сдвиг и просит перерисовать док. */
    fun refresh() { if (h > 0 && SystemClock.elapsedRealtime() - at > 9000) { h = 0; HomeDock.rev.intValue++ } }

    fun heightDp(c: Context): Int {
        if (h <= 0 || SystemClock.elapsedRealtime() - at > 9000) return 0
        return (h / c.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun register(ctx: Context): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { set(i.getBooleanExtra("on", false), i.getIntExtra("h", 0)) }
        }
        val f = IntentFilter("app.click.action.DOCK_STATE")
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
        return r
    }
}
