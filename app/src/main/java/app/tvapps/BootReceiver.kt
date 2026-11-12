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
        // после обновления программы и после загрузки: вернуть себе роль главного экрана по умолчанию (Android при обновлении её сбрасывает)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Thread {
                runCatching {
                    runBlocking {
                        HomeControl.restoreDefault(app, 3000)
                        // ещё раз позже: система в первые секунды после установки может быть занята
                        if (HomeControl.currentHome(app) != app.packageName) { delay(15_000); HomeControl.restoreDefault(app) }
                        // после обновления вернуть человека на «Дом», если он был на экране (иначе Android оставит предыдущую задачу, например настройки)
                        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) HomeControl.reopenAfterUpdate(app)
                    }
                }
            }.start()
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) { UpdSched.apply(app); return }
        }
        // фоновая проверка обновлений: расписание заново после загрузки (не зависит от «Готовить кэш при включении»)
        UpdSched.apply(app)
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
