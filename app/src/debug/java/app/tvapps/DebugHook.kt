package app.tvapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Только для debug-сборки: запускает режим «Дом» без экрана, чтобы проверять настоящий код (adb shell am broadcast). */
class DebugHook : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    Cfg.init(ctx)
                    Adb.awaitReady()
                    if (!Adb.isConnected) autoConnect(ctx)
                    val r = when (i.getStringExtra("cmd")) {
                        "on" -> HomeControl.hardOn(ctx)
                        "off" -> HomeControl.hardOff(ctx).also { if (it.isEmpty()) HomeControl.setAlias(ctx, false) }
                        else -> "state"
                    }
                    Log.i("TVAppsDbg", "cmd=${i.getStringExtra("cmd")} result='$r' hardOff=${Cfg.hardOff} home=${HomeControl.currentHome(ctx)} alias=${HomeControl.aliasEnabled(ctx)} adb=${Adb.isConnected} others=${HomeControl.otherHomePackages(ctx)}")
                }
            } catch (e: Throwable) {
                Log.e("TVAppsDbg", "ошибка", e)
            } finally { pending.finish() }
        }.start()
    }
}
