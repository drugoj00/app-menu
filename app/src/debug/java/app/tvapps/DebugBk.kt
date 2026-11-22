package app.tvapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Только для debug-сборки: проверка копий в сеть и синхронизации без экрана.
 * am broadcast -n app.tvapps/.DebugBk -a app.tvapps.DEBUG_BK --es cmd test|save|auto|list|restore|push|pull|sync|decide --es kind smb|dav|local --es url ... --es user ... --es pass ... --es name ...
 * Итог в журнале (метка TVAppsBk). Пароль берётся только из команды (тестовый сервер на ПК), в журнал не пишется.
 */
class DebugBk : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Cfg.init(ctx)
                val kind = i.getStringExtra("kind") ?: "local"
                val t = BkTarget("dbg", kind, "dbg", i.getStringExtra("url").orEmpty(), i.getStringExtra("user").orEmpty(), auto = true, sync = true)
                BkVault.put(ctx, t.id, i.getStringExtra("pass").orEmpty())
                val cmd = i.getStringExtra("cmd").orEmpty()
                val r: String = try {
                    when (cmd) {
                        "test" -> BkEngine.test(ctx, t) ?: "OK"
                        "save" -> BkEngine.saveNow(ctx, t)
                        "auto" -> BkEngine.saveAuto(ctx, t)
                        "list" -> BkEngine.copies(ctx, t).joinToString(";") { it.name + ":" + it.size + ":" + it.modified }
                        "restore" -> BkEngine.restore(ctx, t, i.getStringExtra("name").orEmpty()).toString()
                        "push" -> BkEngine.push(ctx, t).toString()
                        "pull" -> BkEngine.pull(ctx, t).toString()
                        "sync" -> BkEngine.sync(ctx, t).toString()
                        "hash" -> BkEngine.settingsHash(ctx)
                        "decide" -> BkEngine.decide(i.getStringExtra("lh").orEmpty(), i.getStringExtra("ll"), i.getLongExtra("lc", 0), if (i.hasExtra("rt")) i.getLongExtra("rt", 0) else null, i.getLongExtra("lr", 0)).toString()
                        "setpref" -> { val e = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit(); val v = i.getStringExtra("v"); if (v == null) e.remove(i.getStringExtra("k")) else e.putString(i.getStringExtra("k"), v); e.commit(); "set" }
                        "lastchange" -> BkTargets.localChangedAt(ctx).toString()
                        "clean" -> { BkVault.remove(ctx, "dbg"); BkVault.remove(ctx, "vt"); ctx.getSharedPreferences("tvapps_bk", Context.MODE_PRIVATE).edit().clear().apply(); "cleaned" }
                        "dump" -> ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).all.keys.sorted().joinToString(",")
                        "vault" -> { val ok = BkVault.put(ctx, "vt", "секрет-пароль"); "put=$ok get=${BkVault.get(ctx, "vt") == "секрет-пароль"}" }
                        else -> "?"
                    }
                } catch (e: Throwable) { "ERR " + e.javaClass.simpleName + " " + ((e as? BkError)?.kind ?: "") }
                Log.i("TVAppsBk", "cmd=$cmd kind=$kind result=$r")
            } finally { pending.finish() }
        }.start()
    }
}
