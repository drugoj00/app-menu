package app.tvapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Только для debug-сборки: прогоняет настоящий `Updates.install` по заданному адресу (проверка загрузки, сверки суммы и повтора без сети GitHub).
 * adb shell am broadcast -n app.tvapps/.DebugUpdHook -a app.tvapps.DEBUG_UPD --es url http://10.0.2.2:8099/x.apk --es sha <64 hex или пусто> --el size <байт или 0>
 */
class DebugUpdHook : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    Cfg.init(ctx)
                    val url = i.getStringExtra("url").orEmpty()
                    val sha = i.getStringExtra("sha").orEmpty()
                    val size = i.getLongExtra("size", 0L)
                    val app = AppUpd(ctx.packageName, "t", "0", 1L, emptyList(), emptyList())
                    val cand = UpdCand("github", "99.0", 99L, listOf(url), size, sha256 = sha)
                    NetErr.clear()
                    val r = Updates.install(ctx, app, cand, quiet = true) { }
                    Log.i("TVAppsDbg", "upd result='$r' text='${updResultText(ctx, r).replace("\n", " | ")}'")
                }
            } catch (e: Throwable) {
                Log.e("TVAppsDbg", "ошибка", e)
            } finally { pending.finish() }
        }.start()
    }
}
