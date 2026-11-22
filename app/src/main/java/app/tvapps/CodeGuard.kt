package app.tvapps

import android.app.Application
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Защита от цикла падений при замене программы (Android 7.1, приставка Евгения, 17.11.2026): пока система заменяет пакет, файла base.apk
 * нет, а наш процесс (или перезапуск «главного экрана») стартует и падает в Application.onCreate (ресурсов нет) снова и снова.
 * Здесь: если APK или ресурсы недоступны, процесс тихо завершается без исключения и без инициализации; если это повторяется
 * (2 раза за 30 секунд), главный экран отдаётся стандартному лаунчеру, чтобы устройство не осталось без экрана.
 */
object CodeGuard {
    private const val TAG = "TVApps"

    /** APK на месте и ресурсы читаются. */
    fun ready(app: Application): Boolean = runCatching {
        // только отладочная сборка: файл files/dbg_noapk имитирует отсутствие APK
        if (BuildConfig.DEBUG && File(app.filesDir, "dbg_noapk").exists()) return@runCatching false
        val r = app.resources
        File(app.packageCodePath).isFile && r != null && r.displayMetrics != null && r.assets != null
    }.getOrDefault(false)

    /** Тихий выход: запись в журнал, при повторе передача «Дома» стандартному лаунчеру, завершение процесса. */
    fun bail(app: Application) {
        android.util.Log.w(TAG, "APK или ресурсы недоступны (идёт замена пакета?): процесс завершается без запуска")
        runCatching {
            val sp = app.getSharedPreferences("tvapps_guard", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val times = (sp.getString("bails", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 30_000L } + now
            sp.edit().putString("bails", times.takeLast(8).joinToString(",")).commit()
            if (times.size >= 2) handHome(app)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(0)
    }

    /** Запускает любой другой главный экран (не наш) из системного списка. */
    private fun handHome(app: Application) {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val other = app.packageManager.queryIntentActivities(home, 0).firstOrNull { it.activityInfo?.packageName != app.packageName }?.activityInfo
            if (other != null) {
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(other.packageName, other.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.util.Log.w(TAG, "главный экран передан: " + other.packageName)
            }
        }
    }
}
