package app.tvapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * При включении устройства без окон готовит кэш списка и значков, чтобы меню открывалось сразу,
 * и заново включает службы спецвозможностей, которые прошивка при загрузке не подняла.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        Cfg.init(app)
        if (!Cfg.bootWarm) return
        val pending = goAsync()
        Thread {
            try {
                AppCache.save(app, loadApps(app), force = true)
            } finally {
                pending.finish()
            }
            // службы проверяем сразу и ещё раз через минуту: часть прошивок сбрасывает их позже
            runBlocking {
                AccessSync.restore(app)
                delay(60_000)
                AccessSync.restore(app)
            }
        }.start()
    }
}
