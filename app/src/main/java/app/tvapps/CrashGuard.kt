package app.tvapps

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Защита от падения Compose «Expected BringIntoViewRequester to not be used before parents are placed» (приставка Евгения, Android 7.1, 8.0).
 * Причина: сразу после смены списка (удаление и установка приложения, остановка программы очисткой памяти) фокус попадает на плитку,
 * которая ещё не размещена или уже снята, и встроенная прокрутка Compose «показать сфокусированное» бросает исключение в корутине без обработчика;
 * оно отменяет задание всей композиции и роняло процесс. Своих вызовов bringIntoView без защиты в программе нет.
 * Здесь именно это исключение главного потока не роняет процесс: остаётся запись в журнале, а окно пересоздаётся (новая композиция), чтобы не осталось замёрзшего экрана.
 * Все остальные исключения идут прежним путём. Не более 3 раз за минуту: при частом повторе падение остаётся обычным.
 */
object CrashGuard {
    private const val TAG = "TVApps"
    private val recent = ArrayDeque<Long>()
    private val live = WeakHashMap<Activity, Boolean>()   // true: окно надо пересоздать, когда оно вернётся на экран
    private var resumed: WeakReference<Activity>? = null
    @Volatile var swallowed = 0
        private set

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val handled = try { isBringRace(e) && t === Looper.getMainLooper().thread && inLooperLoop() && allow() && onRace(e) } catch (_: Throwable) { false }
            if (!handled) prev?.uncaughtException(t, e)
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) { synchronized(live) { live[a] = false } }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                resumed = WeakReference(a)
                val need = synchronized(live) { live[a] == true }
                if (need) { synchronized(live) { live[a] = false }; Handler(Looper.getMainLooper()).post { runCatching { if (!a.isFinishing && !a.isDestroyed) a.recreate() } } }
            }
            override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { synchronized(live) { live.remove(a) } }
        })
    }

    /** Это то самое исключение (ищется и в причинах): сообщение Compose про BringIntoViewRequester. */
    fun isBringRace(e: Throwable): Boolean {
        var x: Throwable? = e
        var n = 0
        while (x != null && n < 8) {
            if (x is IllegalStateException && x.message?.contains("BringIntoViewRequester") == true) return true
            x = x.cause; n++
        }
        return false
    }

    /** Обработчик вызван из корутины внутри сообщения главного потока (стек ещё содержит Looper.loop): поток цел и продолжит работу. Иначе поток уже разрушен исключением, гасить нельзя. */
    private fun inLooperLoop(): Boolean = Thread.currentThread().stackTrace.any { it.className == "android.os.Looper" && it.methodName == "loop" }

    private fun allow(): Boolean = synchronized(recent) {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first() > 60_000L) recent.removeFirst()
        if (recent.size >= 3) false else { recent.addLast(now); true }
    }

    /** Записать в журнал и пересоздать окна (то, что на экране, сразу; остальные при возврате). true: падение погашено. */
    private fun onRace(e: Throwable): Boolean {
        swallowed++
        android.util.Log.w(TAG, "Compose: запрос прокрутки к плитке до размещения родителей (погашено, окно пересоздаётся): " + e.message)
        val top = resumed?.get()
        android.util.Log.i(TAG, "CrashGuard: окно на экране: " + (top?.javaClass?.simpleName ?: "нет"))
        synchronized(live) { for (k in live.keys.toList()) if (k !== top) live[k] = true }
        if (top != null) Handler(Looper.getMainLooper()).post {
            runCatching { if (!top.isFinishing && !top.isDestroyed) { android.util.Log.i(TAG, "CrashGuard: пересоздаю окно " + top.javaClass.simpleName); top.recreate() } }
                .onFailure { android.util.Log.w(TAG, "CrashGuard: не удалось пересоздать окно: " + it) }
        }
        return true
    }

    /** Только отладочная сборка (для проверки на устройстве): `am broadcast -a app.tvapps.DBG_RACE -p app.tvapps` кидает это же исключение из корутины без обработчика. */
    fun debugHook(app: Application) {
        if (!BuildConfig.DEBUG) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                kotlinx.coroutines.MainScope().launch { throw IllegalStateException("Expected BringIntoViewRequester to not be used before parents are placed.") }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(app, r, android.content.IntentFilter("app.tvapps.DBG_RACE"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
}
